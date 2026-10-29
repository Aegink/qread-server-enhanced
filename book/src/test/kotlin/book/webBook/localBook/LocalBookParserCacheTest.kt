package book.webBook.localBook

import book.model.Book
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalBookParserCacheTest {

    private class TestParser(
        override var book: Book,
        val id: Int,
        private val closed: AtomicInteger? = null
    ) : CachedLocalBookParser {
        override fun close() {
            closed?.incrementAndGet()
        }
    }

    @Test
    fun isolatesSameBookUrlByUserAndReusesEachUsersParser() {
        val created = AtomicInteger()
        val cache = LocalBookParserCache(8) { TestParser(it, created.incrementAndGet()) }
        val file = createTempFile()
        try {
            val userA = localBook(file, "user-a")
            val userB = localBook(file, "user-b")
            val parserA = cache.withParser(userA) { it.id }
            val parserB = cache.withParser(userB) { it.id }

            assertNotEquals(parserA, parserB)
            assertEquals(parserA, cache.withParser(userA.copy(name = "new-a")) { it.id })
            assertEquals(parserB, cache.withParser(userB.copy(name = "new-b")) { it.id })
            assertEquals(2, cache.size())
        } finally {
            file.delete()
        }
    }

    @Test
    fun serializesContextChangesPerBookWithoutBlockingOtherUsers() {
        val cache = LocalBookParserCache(8) { TestParser(it, 1) }
        val file = createTempFile()
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val enteredUsers = CountDownLatch(2)
        val failures = ConcurrentHashMap.newKeySet<String>()
        try {
            listOf("user-a", "user-b").forEach { userid ->
                repeat(2) { request ->
                    executor.submit {
                        val book = localBook(file, userid).copy(name = "$userid-$request")
                        start.await()
                        cache.withParser(book) { parser ->
                            enteredUsers.countDown()
                            if (!enteredUsers.await(5, TimeUnit.SECONDS)) {
                                failures.add("不同用户的解析被全局串行化")
                            }
                            Thread.sleep(25)
                            if (parser.book !== book) {
                                failures.add("$userid 的请求上下文被同键并发请求覆盖")
                            }
                        }
                    }
                }
            }
            start.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            assertTrue(failures.isEmpty(), failures.joinToString())
        } finally {
            executor.shutdownNow()
            file.delete()
        }
    }

    @Test
    fun staysBoundedAndRefreshesParserWhenFileChanges() {
        val created = AtomicInteger()
        val cache = LocalBookParserCache(2) { TestParser(it, created.incrementAndGet()) }
        val files = List(3) { createTempFile() }
        try {
            files.forEachIndexed { index, file ->
                cache.withParser(localBook(file, "user-$index")) { }
            }
            assertEquals(2, cache.size())

            val book = localBook(files.last(), "user-2")
            val firstId = cache.withParser(book) { it.id }
            files.last().appendText("changed")
            val secondId = cache.withParser(book) { it.id }
            assertNotEquals(firstId, secondId)
        } finally {
            files.forEach(File::delete)
        }
    }

    @Test
    fun closesParserExactlyOnceWhenEvictedRefreshedOrRemoved() {
        val closed = AtomicInteger()
        val cache = LocalBookParserCache(1) { TestParser(it, 1, closed) }
        val firstFile = createTempFile()
        val secondFile = createTempFile()
        try {
            val firstBook = localBook(firstFile, "user-a")
            val secondBook = localBook(secondFile, "user-b")
            cache.withParser(firstBook) { }
            cache.withParser(secondBook) { }
            assertEquals(1, closed.get())

            secondFile.appendText("changed")
            cache.withParser(secondBook) { }
            assertEquals(2, closed.get())

            cache.remove(secondBook)
            assertEquals(3, closed.get())
            assertEquals(0, cache.size())
        } finally {
            firstFile.delete()
            secondFile.delete()
        }
    }

    private fun localBook(file: File, userid: String) = Book(
        bookUrl = file.absolutePath,
        originName = file.absolutePath,
        userid = userid
    )

    private fun createTempFile(): File = File.createTempFile("local-book-cache-", ".epub").apply {
        writeText("initial")
        deleteOnExit()
    }
}
