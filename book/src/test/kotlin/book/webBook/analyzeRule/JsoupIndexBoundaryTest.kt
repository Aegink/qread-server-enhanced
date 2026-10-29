package book.webBook.analyzeRule

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals

class JsoupIndexBoundaryTest {

    private val root = Jsoup.parse("<root><item>A</item><item>B</item></root>").child(0)

    @Test
    fun skipsRangeWhenBothEndpointsAreLeftOfTheList() {
        val result = AnalyzeByJSoup.ElementsSingle().getElementsSingle(root, "tag.item[-5:-3]")

        assertEquals(0, result.size)
    }

    @Test
    fun skipsRangeWhenBothEndpointsAreRightOfTheList() {
        val result = AnalyzeByJSoup.ElementsSingle().getElementsSingle(root, "tag.item[5:8]")

        assertEquals(0, result.size)
    }

    @Test
    fun clampsARangeThatCrossesTheBoundaryButKeepsValidElements() {
        val result = AnalyzeByJSoup.ElementsSingle().getElementsSingle(root, "tag.item[-5:1]")

        assertEquals(listOf("A", "B"), result.map { it.text() })
    }

    @Test
    fun keepsValidNegativeIndex() {
        val result = AnalyzeByJSoup.ElementsSingle().getElementsSingle(root, "tag.item[-1]")

        assertEquals(listOf("B"), result.map { it.text() })
    }
}
