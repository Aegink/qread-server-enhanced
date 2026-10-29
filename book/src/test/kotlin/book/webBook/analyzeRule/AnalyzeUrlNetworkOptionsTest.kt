package book.webBook.analyzeRule

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * URL 选项 `{"timeout":ms}` 与 `{"followRedirects":false}`（上游 ruleHelp.md 已文档化）。
 *
 * 这两个选项是书源最常用的网络参数：慢站要单独放宽/收紧超时，靠 302 下发 Cookie 或做风控的站点
 * 需要能关掉自动跟随。之前我们的 UrlOption 没有这两个字段，写了也静默失效。
 */
class AnalyzeUrlNetworkOptionsTest {

    @Test
    fun perRequestTimeoutIsApplied() = runBlocking {
        withServer { register ->
            register("/slow") { exchange ->
                Thread.sleep(400)
                runCatching {
                    val bytes = "slow".toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            register("/fast") { exchange ->
                val bytes = "fast".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            // 100ms 超时打 400ms 的接口：必须失败，而不是等默认超时
            assertFails {
                AnalyzeUrl("${base()}/slow,{\"timeout\":100}", debugLog = null).getStrResponseAwait()
            }

            // 同一个慢接口，放宽到 3s 就能拿到内容
            assertEquals("slow", AnalyzeUrl("${base()}/slow,{\"timeout\":3000}", debugLog = null).getStrResponseAwait().body())

            // 快接口不受影响
            assertEquals("fast", AnalyzeUrl("${base()}/fast,{\"timeout\":3000}", debugLog = null).getStrResponseAwait().body())
        }
    }

    @Test
    fun followRedirectsCanBeDisabled() = runBlocking {
        withServer { register ->
            register("/start") { exchange ->
                exchange.responseHeaders.add("Location", "${base()}/final")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            register("/final") { exchange ->
                val bytes = "final".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            // 默认跟随：拿到最终页内容
            assertEquals("final", AnalyzeUrl("${base()}/start", debugLog = null).getStrResponseAwait().body())

            // 关闭跟随：停在 302，且不请求 /final
            val response = AnalyzeUrl("${base()}/start,{\"followRedirects\":false}", debugLog = null).getStrResponseAwait()
            assertEquals(302, response.code())
            assertTrue(response.body().isNullOrBlank(), "关闭跟随后不应拿到正文: ${response.body()}")
        }
    }

    // ---- 脚手架 ----

    private var server: HttpServer? = null

    private fun base(): String = "http://127.0.0.1:${server!!.address.port}"

    private fun register(path: String, handler: (com.sun.net.httpserver.HttpExchange) -> Unit) {
        server!!.createContext(path) { exchange ->
            runCatching { handler(exchange) }.onFailure {
                runCatching {
                    exchange.sendResponseHeaders(500, -1)
                    exchange.close()
                }
            }
        }
    }

    private suspend fun withServer(block: suspend (register: (String, (com.sun.net.httpserver.HttpExchange) -> Unit) -> Unit) -> Unit) {
        val created = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        created.executor = Executors.newCachedThreadPool()
        created.start()
        server = created
        try {
            block { path, handler -> register(path, handler) }
        } finally {
            created.stop(0)
            (created.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
            server = null
        }
    }
}
