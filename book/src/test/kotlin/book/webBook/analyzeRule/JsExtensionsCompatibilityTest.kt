package book.webBook.analyzeRule

import book.app.App
import book.model.BookSource
import book.util.http.StrResponse
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JsExtensionsCompatibilityTest {

    @Test
    fun exposesSourceTagToKotlinAndJavaScript() {
        val analyzeRule = AnalyzeRule(source = source())

        assertEquals("stage10-source", analyzeRule.getTag())
        assertEquals("stage10-source", analyzeRule.evalJS("java.getTag()"))
    }

    @Test
    fun ajaxTestAllPreservesOrderMeasuresTimeAndReturnsTimeoutCode() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/first") { exchange ->
            val bytes = "first".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/second") { exchange ->
            val bytes = "second".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/slow") { exchange ->
            Thread.sleep(300)
            val bytes = "slow".toByteArray()
            runCatching {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val analyzeRule = AnalyzeRule(source = source(concurrentRate = "1/10000"))
            val responses = analyzeRule.ajaxTestAll(
                arrayOf("$baseUrl/first", "$baseUrl/second"),
                timeout = 1_000,
                skipRateLimit = true
            )

            assertEquals(listOf("first", "second"), responses.map { it.body() })
            assertTrue(responses.all { it.callTime() >= 0 })

            val timedOut = analyzeRule.ajaxTestAll(
                arrayOf("$baseUrl/slow"),
                timeout = 50,
                skipRateLimit = true
            ).single()
            assertTrue(timedOut.callTime() == -1 || timedOut.callTime() == -2)
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun supportsTimeoutAndWebViewOverloads() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/echo") { exchange ->
            val requestBody = exchange.requestBody.bufferedReader().readText()
            val bytes = "${exchange.requestMethod}:$requestBody".toByteArray()
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.createContext("/slow") { exchange ->
            Thread.sleep(300)
            runCatching {
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()

        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val rule = AnalyzeRule(source = source())
            assertEquals("GET:", rule.ajax(listOf("$base/echo"), 1_000L))
            assertEquals("GET:", rule.connect("$base/echo", null, 1_000L).body)
            assertEquals("GET:", rule.get("$base/echo", emptyMap(), 1_000).body())
            assertEquals(200, rule.head("$base/echo", emptyMap(), 1_000).statusCode())
            assertEquals("POST:payload", rule.post("$base/echo", "payload", emptyMap(), 1_000).body())
            val start = System.nanoTime()
            rule.connect("$base/slow", null, 50L)
            assertTrue((System.nanoTime() - start) / 1_000_000 < 250)

            synchronized(App) {
                val original = App.webview
                var calls = 0
                App.webview = { _, url, _, _, _, _, _, _ ->
                    calls++
                    StrResponse(url.orEmpty(), "web-result")
                }
                try {
                    assertEquals("web-result", rule.webView(null, "$base/echo", null, true))
                    assertEquals(1, calls)
                } finally {
                    App.webview = original
                }
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun supportsByteArrayFontCacheAndCodePointSafeReplacement() {
        val rule = AnalyzeRule(source = source())
        val bytes = File("../src/main/resources/static/static/layui/font/iconfont.ttf").readBytes()

        val cachedOne = rule.queryTTF(bytes, true)
        val cachedTwo = rule.queryTTF(bytes, true)
        val uncached = rule.queryTTF(bytes, false)

        assertSame(cachedOne, cachedTwo)
        assertNotSame(cachedOne, uncached)
        val unicode = cachedOne!!.unicodeToGlyph.keys.first()
        val text = String(intArrayOf(unicode), 0, 1)
        assertEquals(text, rule.replaceFont(text, cachedOne, cachedOne, false))
        assertEquals("", rule.replaceFont("\uD83D\uDE00", cachedOne, cachedOne, true))
    }

    private fun source(concurrentRate: String? = null): BookSource {
        return BookSource(
            bookSourceName = "stage10-source",
            bookSourceUrl = "https://stage10.example",
            concurrentRate = concurrentRate,
            userid = "stage10-user"
        )
    }
}
