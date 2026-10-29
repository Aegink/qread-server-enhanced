package book.webBook.localBook

import book.model.Book
import book.model.BookChapter
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TextFileReadTest {

    @Test
    fun readsTheRequestedRangeWhenSkipAndReadReturnShortCounts() {
        val source = ByteArray(128) { it.toByte() }
        val input = ShortReadInputStream(source)

        val result = readChapterBytes(input, 37, 41)

        assertContentEquals(source.copyOfRange(37, 78), result)
    }

    @Test
    fun readsAChapterAtALargeOffsetWithoutTruncation() {
        val file = Files.createTempFile("qread-textfile-", ".txt").toFile()
        try {
            val prefix = "A".repeat(600_000)
            val title = "Chapter 2"
            val body = "B".repeat(600_000)
            val chapterBytes = "$title\n$body".toByteArray()
            file.writeBytes(prefix.toByteArray() + chapterBytes)
            val book = Book(
                bookUrl = file.absolutePath,
                originName = file.absolutePath,
                charset = "UTF-8"
            )
            val chapter = BookChapter().apply {
                this.title = title
                start = prefix.length.toLong()
                end = start!! + chapterBytes.size
            }

            val content = TextFile.getContent(book, chapter)

            assertEquals(600_002, content.length)
            assertTrue(content.startsWith("　　"))
            assertTrue(content.endsWith(body))
        } finally {
            file.delete()
        }
    }

    @Test
    fun failsInsteadOfPaddingWhenTheRangeExceedsTheFile() {
        assertFailsWith<EOFException> {
            readChapterBytes(ByteArrayInputStream(ByteArray(10)), 8, 4)
        }
    }

    private class ShortReadInputStream(source: ByteArray) : ByteArrayInputStream(source) {
        override fun skip(n: Long): Long = super.skip(n.coerceAtMost(2))

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return super.read(buffer, offset, length.coerceAtMost(3))
        }
    }
}
