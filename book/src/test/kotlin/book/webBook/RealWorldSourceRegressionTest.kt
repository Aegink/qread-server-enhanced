package book.webBook

import book.model.BookSource
import book.util.GSON
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

    @Test
    fun ruleFieldsSurviveJsonRoundTrip() {
        shippedSources().forEach { (name, source) ->
            val json = GSON.toJson(source)
            val restored = BookSource.fromJson(json).getOrThrow()
            assertEquals(source.bookSourceName, restored.bookSourceName, "$name 往返后名称变化")
            assertEquals(source.bookSourceUrl, restored.bookSourceUrl, "$name 往返后地址变化")
            assertEquals(source.searchUrl, restored.searchUrl, "$name 往返后搜索地址变化")
            assertEquals(
                source.ruleToc?.chapterList,
                restored.ruleToc?.chapterList,
                "$name 往返后目录规则变化"
            )
            assertEquals(
                source.ruleContent?.content,
                restored.ruleContent?.content,
                "$name 往返后正文规则变化"
            )
        }
    }

    @Test
    fun ruleStringsStayRecognizable() {
        // 书源规则必须能看出模式（CSS / XPath / JSONPath / JS / 正则），
        // 反序列化把规则揉坏时这里会报警
        val markers = listOf(".", "#", "//", "$.", "@", "##", "<js>", "@js:", "{{")
        // legado 允许裸属性/裸取值规则，如 chapterName="text"、chapterUrl="href"
        val bareRule = Regex("^[A-Za-z][A-Za-z0-9_:.-]{0,30}$")

        shippedSources().forEach { (name, source) ->
            val rules = buildList {
                add(source.searchUrl)
                source.ruleSearch?.let {
                    add(it.bookList); add(it.name); add(it.author); add(it.bookUrl)
                }
                source.ruleBookInfo?.let {
                    add(it.name); add(it.intro); add(it.tocUrl)
                }
                source.ruleToc?.let {
                    add(it.chapterList); add(it.chapterName); add(it.chapterUrl)
                }
                source.ruleContent?.let { add(it.content) }
            }.filterNotNull().filter { it.isNotBlank() }

            assertTrue(rules.isNotEmpty(), "$name 没有任何可识别的规则")
            rules.forEach { rule ->
                assertTrue(
                    markers.any { rule.contains(it) } || bareRule.matches(rule),
                    "$name 的规则片段看不出模式: $rule"
                )
            }
        }
    }

    private fun shippedSources(): List<Pair<String, BookSource>> =
        listOf(
            "69主站.json",
            "天天看小说.json",
            "霹雳书屋.json",
            "本地书仓.json",
            "本地书仓支持分页版.json"
        ).flatMap { name ->
            val file = File(projectRoot, name)
            BookSource.fromJsonArray(file.readText()).getOrThrow().map { name to it }
        }
}
