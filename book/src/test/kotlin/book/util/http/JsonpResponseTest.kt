package book.util.http

import book.app.App
import book.model.BookSource
import book.util.Base64
import book.webBook.analyzeRule.AnalyzeUrl
import org.jsoup.Connection
import java.net.URI
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JsonpResponseTest {

    @Test
    fun implementsResponseMutationParsingBufferingAndCookies() {
        val response = JsonpResponse().apply {
            url = "https://stage21.example/base/"
            method = "get"
            body = "<html><head><title>阶段21</title></head><body>正文</body></html>"
            headers["content-type"] = listOf("text/html; charset=UTF-8")
            headers["Set-Cookie"] = listOf("session=abc; Path=/; HttpOnly", "empty=; Path=/")
        }

        assertSame(response, response.url(URI.create("https://stage21.example/changed/").toURL()))
        assertEquals("https://stage21.example/changed/", response.url().toString())
        assertSame(response, response.method(Connection.Method.PATCH))
        assertEquals(Connection.Method.PATCH, response.method())

        assertSame(response, response.header("X-Test", "one"))
        response.addHeader("x-test", "two")
        assertEquals(listOf("one", "two"), response.headers("X-TEST"))
        assertTrue(response.hasHeader("x-test"))
        assertTrue(response.hasHeaderWithValue("X-Test", "TWO"))
        response.removeHeader("X-TEST")
        assertFalse(response.hasHeader("x-test"))

        assertEquals("abc", response.cookie("session"))
        assertEquals("", response.cookie("empty"))
        response.cookie("session", "updated")
        assertEquals("updated", response.cookie("session"))
        response.removeCookie("session")
        assertFalse(response.hasCookie("session"))

        assertEquals("UTF-8", response.charset())
        response.charset("UTF-16LE")
        assertEquals("UTF-16LE", response.charset())
        assertContentEquals(response.body.toByteArray(Charsets.UTF_16LE), response.bodyAsBytes())
        assertEquals(response.body, response.bodyStream().use { String(it.readBytes(), Charsets.UTF_16LE) })
        assertSame(response, response.bufferUp())
        assertEquals("阶段21", response.parse().title())
        assertEquals("https://stage21.example/changed/", response.parse().location())
    }

    @Test
    fun decodesProxyPayloadUsingResponseCharset() {
        val text = "手机代理正文"
        val bytes = text.toByteArray(Charset.forName("GBK"))
        val response = MyResponse(
            url = "https://stage21.example/gbk",
            headers = mutableMapOf("Content-Type" to listOf("text/html; charset=gb2312")),
            body = Base64.encodeToString(bytes, Base64.NO_WRAP)
        ).tojsonresponse()

        assertEquals(text, response.body())
        assertEquals("GBK", response.charset())
        assertContentEquals(bytes, response.bodyAsBytes())
    }

    @Test
    fun usePhoneRhinoRuleCanParseReturnedDocument() {
        synchronized(App) {
            val originalGet = App.get
            App.get = { url, _, _, _ ->
                JsonpResponse().apply {
                    this.url = url.orEmpty()
                    body = "<div id='value'>phone-ok</div>"
                    headers["Content-Type"] = listOf("text/html; charset=UTF-8")
                }
            }
            try {
                val source = BookSource(
                    bookSourceName = "stage21-source",
                    bookSourceUrl = "https://stage21.example",
                    phonehttp = true,
                    userid = "stage21-user"
                )
                val analyzeUrl = AnalyzeUrl(source.bookSourceUrl, source = source, debugLog = null)
                val result = analyzeUrl.evalJS(
                    "java.get('https://stage21.example/page', new Packages.java.util.HashMap())" +
                        ".parse().select('#value').text()"
                )

                assertEquals("phone-ok", result)
            } finally {
                App.get = originalGet
            }
        }
    }
}
