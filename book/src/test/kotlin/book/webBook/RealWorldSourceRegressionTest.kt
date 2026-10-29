package book.webBook

import book.model.BookSource
import book.webBook.analyzeRule.AnalyzeUrl
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真实书源与编码回归：
 * - 仓库根目录那 5 份真实书源 JSON 必须仍能完整反序列化（改模型/规则字段时最先炸的地方）
 * - GBK 等非 UTF-8 站点必须按响应头 charset 正确解码
 */
class RealWorldSourceRegressionTest {

    private val projectRoot: File = File(System.getProperty("user.dir")).let {
        if (it.name == "book") it.parentFile ?: it else it
    }

    @Test
    fun shippedBookSourcesStillParse() {
        val files = listOf(
            "69主站.json",
            "天天看小说.json",
            "霹雳书屋.json",
            "本地书仓.json",
            "本地书仓支持分页版.json"
        )
        files.forEach { name ->
            val file = File(projectRoot, name)
            assertTrue(file.exists(), "缺少真实书源文件: ${file.absolutePath}")
            val sources = BookSource.fromJsonArray(file.readText()).getOrThrow()
            assertTrue(sources.isNotEmpty(), "$name 解析出 0 个书源")
            sources.forEach { source ->
                assertTrue(source.bookSourceName.isNotBlank(), "$name 里有书源缺少名称")
                assertTrue(source.bookSourceUrl.isNotBlank(), "$name 里有书源缺少地址")
                val rules = listOfNotNull(
                    source.ruleSearch,
                    source.ruleBookInfo,
                    source.ruleToc,
                    source.ruleContent
                )
                assertTrue(rules.isNotEmpty(), "$name 里书源「${source.bookSourceName}」四类规则全为空")
            }
        }
    }

    @Test
    fun gbkEncodedPageIsDecodedByCharsetHeader() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val gbk = Charset.forName("GBK")
        server.createContext("/gbk") { exchange ->
            val bytes = "中文标题：第一章".toByteArray(gbk)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=gbk")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val body = AnalyzeUrl("$base/gbk", debugLog = null).getStrResponseAwait().body()
            assertEquals("中文标题：第一章", body?.trim())
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }
}
