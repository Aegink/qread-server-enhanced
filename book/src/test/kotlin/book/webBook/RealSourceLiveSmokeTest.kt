package book.webBook

import book.model.BookSource
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 真源联网抽测（默认跳过，需显式开启）。
 *
 * 用途：升级 okhttp / 引擎之后，用真实书源验证「搜索 → 详情 → 目录 → 正文」是否仍然可用。
 * 书站普遍有 Cloudflare/地域限制，所以不进 CI，只在人工验收时跑：
 *
 * ```powershell
 * $env:QREAD_LIVE_SMOKE="1"                       # 打开开关
 * $env:QREAD_SMOKE_KEY="斗破苍穹"                  # 可选，搜索关键词
 * .\gradlew.bat :book:test --tests "book.webBook.RealSourceLiveSmokeTest"
 * ```
 *
 * 结果看 `book/build/reports/tests/test/index.html` 的 standard out。
 */
class RealSourceLiveSmokeTest {

    private val projectRoot: File = File(System.getProperty("user.dir")).let {
        if (it.name == "book") it.parentFile ?: it else it
    }

    @Test
    fun liveSearchInfoTocContent() = runBlocking {
        if (System.getenv("QREAD_LIVE_SMOKE") != "1") {
            println("跳过真源抽测：设置 QREAD_LIVE_SMOKE=1 再运行")
            return@runBlocking
        }
        val key = System.getenv("QREAD_SMOKE_KEY")?.takeIf { it.isNotBlank() } ?: "斗破苍穹"
        val sources = listOf("69主站.json", "天天看小说.json", "霹雳书屋.json")
            .map { File(projectRoot, it) }
            .filter { it.exists() }
            .flatMap { BookSource.fromJsonArray(it.readText()).getOrElse { emptyList() } }
            .filter { !it.searchUrl.isNullOrBlank() }

        assertTrue(sources.isNotEmpty(), "没有可用的真源")
        var verified = 0
        sources.forEach { source ->
            runCatching {
                val web = WBook(source, debugLog = false, userid = "live-smoke")
                val results = web.searchBook(key, 1)
                if (results.isEmpty()) {
                    println("[跳过] ${source.bookSourceName}: 搜索无结果（可能被站点风控）")
                    return@runCatching
                }
                val first = results.first()
                val book = web.getBookInfo(first.bookUrl, canReName = false)
                val chapters = web.getChapterList(book)
                val content = if (chapters.isNotEmpty()) web.getBookContent(book, chapters.first()) else ""
                println(
                    "[通过] ${source.bookSourceName}: 结果=${results.size} 书名=${book.name} " +
                        "目录=${chapters.size} 正文长度=${content.length}"
                )
                verified++
            }.onFailure {
                println("[失败] ${source.bookSourceName}: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        assertTrue(verified > 0, "所有真源都没跑通（多半是网络/风控阻挡，见上方日志）")
    }
}
