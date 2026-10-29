package book.webBook.analyzeRule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XmlParserDetectionTest {

    private val xml = """<?xml version="1.0" encoding="UTF-8"?><Feed><BookItem id="1"/><BookItem id="2">Title</BookItem></Feed>"""

    @Test
    fun jsoupPreservesXmlElementNamesAndSelfClosingTags() {
        val document = AnalyzeByJSoup.parse(xml)
        val items = document.getElementsByTag("BookItem")

        assertEquals(2, items.size)
        assertEquals("BookItem", items.first()!!.tagName())
        assertTrue(items.first()!!.outerHtml().contains("/>"))
    }

    @Test
    fun xpathKeepsSelfClosingXmlElementsAsSiblings() {
        val analyzer = AnalyzeByXPath("  $xml")
        val xmlNodes = analyzer.getElements("//Feed/BookItem")

        assertEquals(2, xmlNodes?.size)
        assertEquals("BookItem", xmlNodes?.first()?.asElement()?.tagName())
    }

    @Test
    fun ordinaryHtmlStillUsesHtmlParser() {
        val document = AnalyzeByJSoup.parse("<DIV><BR/></DIV>")

        assertEquals("div", document.getElementsByTag("div").first()!!.tagName())
        assertEquals("br", document.getElementsByTag("br").first()!!.tagName())
    }
}
