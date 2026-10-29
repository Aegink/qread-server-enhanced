package book.webBook

import book.model.Book
import book.model.BookSource
import book.model.rule.BookInfoRule
import book.model.rule.SearchRule
import book.model.rule.TocRule
import book.webBook.analyzeRule.AnalyzeRule
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PreUpdateJsCompatibilityTest {

    @Test
    fun rejectsPreUpdateOnlyApisInOrdinaryScripts() {
        val source = BookSource(bookSourceName = "pre-update-guard", bookSourceUrl = "https://source.example")
        val rule = AnalyzeRule(Book(name = "Book", author = "Author"), source = source)

        listOf("java.reGetBook()", "java.refreshBookUrl()", "java.refreshTocUrl()").forEach { script ->
            val error = assertFailsWith<Exception> { rule.evalJS(script) }
            assertTrue(error.message.orEmpty().contains("只能在 preUpdateJs 中调用"))
        }
    }

    @Test
    fun refreshesExpiringTocUrlBeforeLoadingChapters() = runBlocking {
        withServer { server, hits ->
            val base = "http://127.0.0.1:${server.address.port}"
            register(server, hits, "/detail") {
                "<html><a id='toc' href='$base/toc-fresh'>toc</a></html>"
            }
            register(server, hits, "/toc-fresh") {
                "<a class='chapter' href='/content/1'>Fresh chapter</a>"
            }
            register(server, hits, "/toc-expired") {
                error("expired toc URL must not be requested")
            }

            val source = source(base, "java.refreshTocUrl()")
            val book = Book(
                bookUrl = "$base/detail",
                tocUrl = "$base/toc-expired",
                origin = source.bookSourceUrl,
                name = "Book",
                author = "Author"
            )

            val chapters = WBook(source, debugLog = false).getChapterList(book)

            assertEquals("$base/toc-fresh", book.tocUrl)
            assertEquals(listOf("Fresh chapter"), chapters.map { it.title })
            assertEquals(1, hits["/detail"]?.get())
            assertEquals(1, hits["/toc-fresh"]?.get())
            assertEquals(null, hits["/toc-expired"]?.get())
        }
    }

    @Test
    fun relocatesInvalidBookUrlByExactSearchBeforeLoadingChapters() = runBlocking {
        withServer { server, hits ->
            val base = "http://127.0.0.1:${server.address.port}"
            register(server, hits, "/search") {
                """
                    <div class='book'>
                      <span class='name'>Target Book</span>
                      <span class='author'>Target Author</span>
                      <a class='url' href='$base/detail-new'>open</a>
                    </div>
                """.trimIndent()
            }
            register(server, hits, "/detail-new") {
                "<html><a id='toc' href='$base/toc-new'>toc</a></html>"
            }
            register(server, hits, "/toc-new") {
                "<a class='chapter' href='/content/2'>Relocated chapter</a>"
            }

            val source = source(base, "java.reGetBook()").apply {
                searchUrl = "$base/search"
                ruleSearch = SearchRule(
                    bookList = ".book",
                    name = ".name@text",
                    author = ".author@text",
                    bookUrl = ".url@href"
                )
            }
            val book = Book(
                bookUrl = "$base/detail-expired",
                tocUrl = "$base/toc-expired",
                origin = source.bookSourceUrl,
                name = "Target Book",
                author = "Target Author"
            )

            val chapters = WBook(source, debugLog = false).getChapterList(book)

            assertEquals("$base/detail-new", book.bookUrl)
            assertEquals("$base/toc-new", book.tocUrl)
            assertEquals(listOf("Relocated chapter"), chapters.map { it.title })
            assertEquals(1, hits["/search"]?.get())
            assertEquals(1, hits["/detail-new"]?.get())
            assertEquals(1, hits["/toc-new"]?.get())
        }
    }

    @Test
    fun blankPreUpdateScriptLeavesExistingTocFlowUnchanged() = runBlocking {
        withServer { server, hits ->
            val base = "http://127.0.0.1:${server.address.port}"
            register(server, hits, "/toc") {
                "<a class='chapter' href='/content/3'>Existing chapter</a>"
            }
            val source = source(base, "   ")
            val book = Book(
                bookUrl = "$base/detail",
                tocUrl = "$base/toc",
                origin = source.bookSourceUrl
            )

            val chapters = WBook(source, debugLog = false).getChapterList(book)

            assertEquals(listOf("Existing chapter"), chapters.map { it.title })
            assertEquals(1, hits["/toc"]?.get())
            assertEquals(null, hits["/detail"]?.get())
        }
    }

    private fun source(base: String, preUpdateJs: String) = BookSource(
        bookSourceName = "pre-update-test",
        bookSourceUrl = base,
        ruleBookInfo = BookInfoRule(tocUrl = "#toc@href"),
        ruleToc = TocRule(
            preUpdateJs = preUpdateJs,
            chapterList = ".chapter",
            chapterName = "text",
            chapterUrl = "href"
        )
    )

    private suspend fun withServer(block: suspend (HttpServer, MutableMap<String, AtomicInteger>) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hits = mutableMapOf<String, AtomicInteger>()
        server.executor = Executors.newCachedThreadPool()
        server.start()
        try {
            block(server, hits)
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    private fun register(
        server: HttpServer,
        hits: MutableMap<String, AtomicInteger>,
        path: String,
        body: () -> String
    ) {
        server.createContext(path) { exchange ->
            hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
            val text = body()
            val bytes = text.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
