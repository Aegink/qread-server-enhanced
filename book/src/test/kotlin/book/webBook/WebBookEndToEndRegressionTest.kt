package book.webBook

import book.model.BookSource
import book.model.rule.BookInfoRule
import book.model.rule.ContentRule
import book.model.rule.SearchRule
import book.model.rule.TocRule
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真实书源端到端回归：本地 HTTP 站点 + 完整 WBook 流程（搜索 → 详情 → 目录 → 正文）。
 *
 * 目的：现有测试多为"喂 body 给分析器"的单元/兼容测试，这条链路把 URL 构造（`{{key}}`/`{{page}}`）、
 * HTTP 抓取、相对地址补全、CSS/XPath/JSONPath 规则、`##正则##` 替换、`formatJs` 标题格式化
 * 串起来跑一遍。以后从引擎上游移植改动时，这条链路坏了会第一时间报警。
 */
class WebBookEndToEndRegressionTest {

    private var server: HttpServer? = null

    @Test
    fun htmlSourceFlowsFromSearchToContent() = runBlocking {
        withServer {
            val searchQuery = AtomicReference<String>()
            serveWith("/search") { exchange ->
                searchQuery.set(exchange.requestURI.rawQuery)
                respond(
                    exchange,
                    page(
                        """
                        <div class="book">
                          <a class="name" href="/book/1">测试之书</a>
                          <span class="author">作者甲</span>
                          <span class="kind">玄幻</span>
                          <span class="last">第3章 收束</span>
                          <p class="intro">列表页简介</p>
                          <div class="cover"><img src="/cover/1.jpg"/></div>
                        </div>
                        <div class="book">
                          <a class="name" href="/book/2">第二本</a>
                          <span class="author">作者乙</span>
                          <span class="kind">都市</span>
                          <span class="last">第9章 尾声</span>
                          <p class="intro">另一段简介</p>
                          <div class="cover"><img src="/cover/2.jpg"/></div>
                        </div>
                        """.trimIndent()
                    )
                )
            }
            serve("/book/1") {
                page(
                    """
                    <h1>测试之书</h1>
                    <span class="author">作者甲</span>
                    <span id="kind">玄幻</span>
                    <div id="intro">详情页简介：一段完整的书评。</div>
                    <img id="cover" src="/cover/1.jpg"/>
                    <a class="toc" href="/toc/1">查看目录</a>
                    """.trimIndent()
                )
            }
            serve("/toc/1") {
                page(
                    """
                    <div class="chapter"><a href="/content/1">第1章 起点</a></div>
                    <div class="chapter"><a href="/content/2">第2章 承接</a></div>
                    <div class="chapter"><a href="/content/3">第3章 收束</a></div>
                    """.trimIndent()
                )
            }
            serve("/content/1") {
                page(
                    """
                    <div id="content">
                      <p>第一段正文。</p>
                      <div class="ad">广告位：不该出现在正文里</div>
                      <p>第二段正文。</p>
                    </div>
                    """.trimIndent()
                )
            }

            val source = BookSource(
                bookSourceName = "e2e-html",
                bookSourceUrl = base(),
                searchUrl = "${base()}/search?key={{key}}&page={{page}}",
                ruleSearch = SearchRule(
                    bookList = ".book",
                    name = ".name@text",
                    author = ".author@text",
                    bookUrl = ".name@href",
                    kind = ".kind@text",
                    lastChapter = ".last@text",
                    intro = ".intro@text",
                    coverUrl = ".cover img@src"
                ),
                ruleBookInfo = BookInfoRule(
                    name = "//h1/text()",
                    author = ".author@text",
                    kind = "#kind@text",
                    intro = "#intro@text",
                    coverUrl = "#cover@src",
                    tocUrl = ".toc@href"
                ),
                ruleToc = TocRule(
                    chapterList = ".chapter a",
                    chapterName = "text",
                    chapterUrl = "href",
                    formatJs = "title + '（' + index + '）'"
                ),
                ruleContent = ContentRule(
                    content = "#content@html##<div class=\"ad\">[\\s\\S]*?</div>"
                )
            )
            val wBook = WBook(source, debugLog = false, userid = "e2e-user")

            // 1) 搜索：CSS 规则、相对地址补全、{{key}}/{{page}} 占位符
            val results = wBook.searchBook("测试", 1)
            assertEquals(2, results.size)
            assertEquals("测试之书", results[0].name)
            assertEquals("作者甲", results[0].author)
            assertEquals("玄幻", results[0].kind)
            assertEquals("第3章 收束", results[0].latestChapterTitle)
            assertEquals("列表页简介", results[0].intro)
            assertEquals("${base()}/cover/1.jpg", results[0].coverUrl)
            assertEquals("${base()}/book/1", results[0].bookUrl)
            assertEquals("key=%E6%B5%8B%E8%AF%95&page=1", searchQuery.get())

            // 2) 详情：书名走 XPath，其余走 CSS
            val book = wBook.getBookInfo("${base()}/book/1", canReName = false)
            assertEquals("测试之书", book.name)
            assertEquals("作者甲", book.author)
            assertEquals("玄幻", book.kind)
            assertEquals("详情页简介：一段完整的书评。", book.intro)
            assertEquals("${base()}/cover/1.jpg", book.coverUrl)
            assertEquals("${base()}/toc/1", book.tocUrl)

            // 3) 目录：formatJs 逐章改写标题
            val chapters = wBook.getChapterList(book)
            assertEquals(
                listOf("第1章 起点（1）", "第2章 承接（2）", "第3章 收束（3）"),
                chapters.map { it.title }
            )
            // 章节 url 保持站内原始形态（qread 3.4.6 与 LegadoTeam 都是 getString(urlRule)），
            // 绝对化由 getChapterContent 里的 bookChapter.getAbsoluteURL() 负责
            assertEquals(
                listOf("/content/1", "/content/2", "/content/3"),
                chapters.map { it.url }
            )
            assertEquals(3, book.totalChapterNum)

            // 4) 正文：`##正则##` 必须把广告块剔掉
            val content = wBook.getBookContent(book, chapters.first())
            assertTrue(content.contains("第一段正文"), "正文缺失第一段: $content")
            assertTrue(content.contains("第二段正文"), "正文缺失第二段: $content")
            assertTrue(!content.contains("广告位"), "广告块未被剔除: $content")
        }
    }

    @Test
    fun jsonSourceSearchUsesJsonPathEndToEnd() = runBlocking {
        withServer {
            serve("/api/search") {
                """
                {"code":0,"data":{"list":[
                  {"title":"JSON 之书","writer":"作者丙","url":"/book/9","cate":"科幻","latest":"第7章 终局"},
                  {"title":"JSON 二书","writer":"作者丁","url":"/book/10","cate":"历史","latest":"第2章 起笔"}
                ]}}
                """.trimIndent()
            }

            val source = BookSource(
                bookSourceName = "e2e-json",
                bookSourceUrl = base(),
                searchUrl = "${base()}/api/search?key={{key}}",
                ruleSearch = SearchRule(
                    bookList = "\$.data.list[*]",
                    name = "\$.title",
                    author = "\$.writer",
                    bookUrl = "\$.url",
                    kind = "\$.cate",
                    lastChapter = "\$.latest"
                )
            )
            val wBook = WBook(source, debugLog = false, userid = "e2e-user")

            val results = wBook.searchBook("任意", 1)

            assertEquals(2, results.size)
            assertEquals("JSON 之书", results[0].name)
            assertEquals("作者丙", results[0].author)
            assertEquals("科幻", results[0].kind)
            assertEquals("第7章 终局", results[0].latestChapterTitle)
            assertEquals("${base()}/book/9", results[0].bookUrl)
        }
    }

    // ---- 测试脚手架 ----

    private fun base(): String = "http://127.0.0.1:${server!!.address.port}"

    private fun page(body: String): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body>$body</body></html>"

    private fun serve(path: String, body: () -> String) {
        serveWith(path) { exchange -> respond(exchange, body()) }
    }

    private fun serveWith(path: String, handler: (HttpExchange) -> Unit) {
        server!!.createContext(path) { exchange ->
            runCatching { handler(exchange) }.onFailure {
                runCatching {
                    exchange.sendResponseHeaders(500, -1)
                    exchange.close()
                }
            }
        }
    }

    private fun respond(exchange: HttpExchange, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private suspend fun withServer(block: suspend () -> Unit) {
        val created = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        created.executor = Executors.newCachedThreadPool()
        created.start()
        server = created
        try {
            block()
        } finally {
            created.stop(0)
            (created.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
            server = null
        }
    }
}
