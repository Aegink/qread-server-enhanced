package book.webBook

import book.model.BookChapter
import book.model.Book
import book.model.BookSource
import book.model.rule.TocRule
import kotlin.test.Test
import kotlin.test.assertEquals

class BookChapterOrderTest {
    private val chapters = listOf("A", "B", "C").map { BookChapter(title = it, url = it) }

    @Test
    fun userReverseSettingActuallyReversesDefaultSourceOrder() {
        val normal = BookChapterList.normalizeChapterOrder(chapters, false, false)
        val reversed = BookChapterList.normalizeChapterOrder(chapters, false, true)

        assertEquals(listOf("A", "B", "C"), normal.map { it.title })
        assertEquals(listOf("C", "B", "A"), reversed.map { it.title })
    }

    @Test
    fun sourceReverseMarkerAndUserSettingRemainIndependent() {
        val normalized = BookChapterList.normalizeChapterOrder(chapters, true, false)
        val userReversed = BookChapterList.normalizeChapterOrder(chapters, true, true)

        assertEquals(listOf("C", "B", "A"), normalized.map { it.title })
        assertEquals(listOf("A", "B", "C"), userReversed.map { it.title })
    }

    @Test
    fun duplicateChaptersAreRemovedWithoutChangingSelectedOrder() {
        val duplicated = chapters + chapters.last()

        assertEquals(
            listOf("A", "B", "C"),
            BookChapterList.normalizeChapterOrder(duplicated, false, false).map { it.title }
        )
    }

    @Test
    fun formatJsReceivesQreadBindingsAndKeepsStateAcrossChapters() {
        val source = BookSource(bookSourceName = "format-test", bookSourceUrl = "https://example.test")
        val rule = TocRule(formatJs = "title + '-' + index + '-' + (++gInt) + '-' + java.qread()")
        val formatted = chapters.map { it.copy() }

        BookChapterList.formatChapterTitles(formatted, rule, source, Book(bookUrl = "book"))

        assertEquals(listOf("A-1-1-1", "B-2-2-1", "C-3-3-1"), formatted.map { it.title })
    }

    @Test
    fun blankFormatJsLeavesTitlesUnchanged() {
        val formatted = chapters.map { it.copy() }

        BookChapterList.formatChapterTitles(
            formatted,
            TocRule(formatJs = "  "),
            BookSource(bookSourceUrl = "https://example.test"),
            Book(bookUrl = "book")
        )

        assertEquals(listOf("A", "B", "C"), formatted.map { it.title })
    }
}
