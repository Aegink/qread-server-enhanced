package book.webBook.analyzeRule

import book.app.App
import book.model.BookSource
import book.util.http.JsonpResponse
import book.webBook.DebugLog
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalyzeUrlOptionsTest {

    @Test
    fun preservesDuplicateParametersAndEncodesUnicode() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val query = AtomicReference<String>()
        val form = AtomicReference<String>()
        server.createContext("/query") { exchange ->
            query.set(exchange.requestURI.rawQuery)
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.createContext("/form") { exchange ->
            form.set(exchange.requestBody.bufferedReader().readText())
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()

        try {
            val base = "http://127.0.0.1:${server.address.port}"
            AnalyzeUrl(
                "$base/query?dup=一&dup=二&literal=%2F",
                debugLog = null
            ).getStrResponseAwait()
            AnalyzeUrl(
                "$base/form,{\"method\":\"POST\",\"body\":\"dup=一&dup=二&literal=%2F\"}",
                debugLog = null
            ).getStrResponseAwait()

            assertEquals("dup=%E4%B8%80&dup=%E4%BA%8C&literal=%2F", query.get())
            assertEquals("dup=%E4%B8%80&dup=%E4%BA%8C&literal=%2F", form.get())
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun exposesRequestClassificationErrorResponseTimeoutAndInfoMap() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/slow") { exchange ->
            Thread.sleep(400)
            runCatching {
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()

        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertTrue(AnalyzeUrl(base, debugLog = null).isSimpleGetRequest())
            assertFalse(
                AnalyzeUrl(
                    "$base,{\"method\":\"POST\",\"body\":\"a=1\"}",
                    debugLog = null
                ).isSimpleGetRequest()
            )

            val infoMap = mutableMapOf("token" to "mapped")
            val mapped = AnalyzeUrl(base, debugLog = null, infoMap = infoMap)
                .evalJS("infoMap.get('token')")
            assertEquals("mapped", mapped)

            val errorResponse = AnalyzeUrl(base, debugLog = null)
                .getErrStrResponse(IllegalStateException("stage33"))
            assertEquals(500, errorResponse.code())
            assertTrue(errorResponse.body.orEmpty().contains("stage33"))

            assertFails {
                AnalyzeUrl("$base/slow", debugLog = null, callTimeout = 80L)
                    .getStrResponse()
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun persistsResponseCookiesWhenCookieJarIsEnabled() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/cookie") { exchange ->
            exchange.responseHeaders.add("Set-Cookie", "stage33=value; Path=/")
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()

        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val source = BookSource(
                bookSourceName = "stage33-cookie",
                bookSourceUrl = base,
                enabledCookieJar = true,
                userid = "stage33-cookie-user"
            )
            AnalyzeUrl("$base/cookie", source = source, debugLog = null).getStrResponse()

            assertTrue(source.getCookieManger().getCookie(base).contains("stage33=value"))
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun appliesBodyJsOriginAndDnsIpOptions() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var receivedOrigin: List<String> = emptyList()
        server.createContext("/content") { exchange ->
            receivedOrigin = exchange.requestHeaders["Origin"] ?: emptyList()
            val bytes = "raw-body".toByteArray()
            exchange.responseHeaders.add("X-Stage", "8")
            exchange.sendResponseHeaders(201, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()

        try {
            val port = server.address.port
            val targetUrl = "http://stage8.invalid:$port/content"
            val source = BookSource(
                bookSourceName = "stage8-test",
                bookSourceUrl = targetUrl,
                header = "{\"origin\":\"https://old.example\"}",
                userid = "stage8-user"
            )
            val options = """{"origin":"https://origin.example","dnsIp":"127.0.0.1","bodyJs":"result + '|transformed'"}"""
            val response = AnalyzeUrl("$targetUrl,$options", source = source, debugLog = null)
                .getStrResponse()

            assertEquals(201, response.code())
            assertEquals("8", response.headers()["X-Stage"])
            assertEquals("raw-body|transformed", response.body)
            assertEquals(listOf("https://origin.example"), receivedOrigin)
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun forwardsServerIdAndWebViewDelayTime() {
        synchronized(App) {
            val original = App.webview
            var forwardedDelay = -1L
            App.webview = { _, url, _, _, _, _, _, delayTime ->
                forwardedDelay = delayTime
                book.util.http.StrResponse(url.orEmpty(), "rendered")
            }

            try {
                val targetUrl = "https://stage38.example/content"
                val options = """{"webView":true,"serverID":42,"webViewDelayTime":1750}"""
                val analyzeUrl = AnalyzeUrl("$targetUrl,$options", debugLog = null)

                assertEquals("rendered", analyzeUrl.getStrResponse().body)
                assertEquals(42L, analyzeUrl.serverID)
                assertEquals(1750L, forwardedDelay)
            } finally {
                App.webview = original
            }
        }
    }

    @Test
    fun sendsHeadMethodWithoutResponseBody() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var receivedMethod = ""
        var receivedQuery = ""
        server.createContext("/head") { exchange ->
            receivedMethod = exchange.requestMethod
            receivedQuery = exchange.requestURI.rawQuery.orEmpty()
            val bytes = "GET response body".toByteArray()
            exchange.responseHeaders.add("X-Request-Method", exchange.requestMethod)
            if (exchange.requestMethod == "HEAD") {
                exchange.responseHeaders.add("Content-Length", bytes.size.toString())
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()

        try {
            val targetUrl = "http://127.0.0.1:${server.address.port}/head?probe=stage22"
            val source = BookSource(
                bookSourceName = "stage22-head",
                bookSourceUrl = targetUrl,
                userid = "stage22-user"
            )
            val options = """{"method":"HEAD","webView":true}"""
            val response = AnalyzeUrl("$targetUrl,$options", source = source, debugLog = null)
                .getStrResponse()

            assertEquals("HEAD", receivedMethod)
            assertEquals("probe=stage22", receivedQuery)
            assertEquals(200, response.code())
            assertEquals("HEAD", response.headers()["X-Request-Method"])
            assertEquals("", response.body)
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun routesPhoneHeadThroughHeadBridge() {
        synchronized(App) {
            val originalGet = App.get
            val originalHead = App.head
            var headCalls = 0
            var getCalls = 0
            App.get = { url, _, _, _ ->
                getCalls++
                JsonpResponse().apply { this.url = url.orEmpty() }
            }
            App.head = { url, _, _, _ ->
                headCalls++
                JsonpResponse().apply {
                    this.url = url.orEmpty()
                    method = "head"
                    body = "HEAD 客户端不应返回此正文"
                }
            }

            try {
                val targetUrl = "https://stage22.example/head"
                val source = BookSource(
                    bookSourceName = "stage22-phone-head",
                    bookSourceUrl = targetUrl,
                    userid = "stage22-user"
                )
                val options = """{"method":"HEAD","usePhone":true}"""
                val response = AnalyzeUrl("$targetUrl,$options", source = source, debugLog = null)
                    .getStrResponse()

                assertEquals(1, headCalls)
                assertEquals(0, getCalls)
                assertEquals("", response.body)
            } finally {
                App.get = originalGet
                App.head = originalHead
            }
        }
    }

    @Test
    fun warnsAndKeepsCompatibilityForLenientJsonOptions() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var receivedMethod = ""
        server.createContext("/lenient") { exchange ->
            receivedMethod = exchange.requestMethod
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()

        try {
            val messages = mutableListOf<String>()
            val debugLog = object : DebugLog {
                override fun log(sourceUrl: String?, msg: String?, isHtml: Boolean) {
                    messages.add(msg.orEmpty())
                }
            }
            val targetUrl = "http://127.0.0.1:${server.address.port}/lenient"
            val source = BookSource(
                bookSourceName = "stage23-lenient-json",
                bookSourceUrl = targetUrl,
                userid = "stage23-user"
            )
            val response = AnalyzeUrl("$targetUrl,{method:'HEAD'}", source = source, debugLog = debugLog)
                .getStrResponse()

            assertEquals("HEAD", receivedMethod)
            assertEquals(204, response.code())
            assertEquals("", response.body)
            assertEquals(listOf("链接参数 JSON 格式不规范，请改为规范格式"), messages)
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }
}
