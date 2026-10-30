package book.webBook

import book.model.BookSource
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 真源联网抽测（默认跳过，需显式开启）。
 *
 * 用途：升级 okhttp / 引擎之后，用真实书源验证「搜索 → 详情 → 目录 → 正文」是否仍然可用。
 * 书站普遍有 Cloudflare/地域限制，所以不进 CI，只在人工验收时跑：
 *
 * ```powershell
 * $env:QREAD_LIVE_SMOKE="1"
 * $env:QREAD_SMOKE_SOURCE_DIR="C:\path\to\sources"   # 可选：该目录下所有 *.json 都参与抽测
 * $env:QREAD_SMOKE_KEY="斗破苍穹"                     # 可选：搜索关键词
 * .\gradlew.bat :book:test --tests "book.webBook.RealSourceLiveSmokeTest"
 * ```
 *
 * 结果看 `book/build/reports/tests/test/index.html` 的 standard out（每个源一行结论）。
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
        val sources = loadSources()
        assertTrue(sources.isNotEmpty(), "没有可用的真源")

        var passed = 0
        var failed = 0
        sources.forEach { source ->
            val label = source.bookSourceName.ifBlank { source.bookSourceUrl }
            if (source.searchUrl.isNullOrBlank()) {
                println("[跳过] $label: 没有搜索地址")
                return@forEach
            }
            val outcome = runCatching {
                withTimeoutOrNull(90_000) { probe(source, key) } ?: "超时(90s)"
            }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            if (outcome.startsWith("OK ")) {
                println("[通过] $label -> $outcome")
                passed++
            } else {
                println("[失败] $label -> $outcome")
                failed++
            }
        }
        println("== 真源抽测汇总：通过 $passed / 失败 $failed / 共 ${sources.size} ==")
        assertTrue(passed > 0, "所有真源都没跑通（多为网络/风控阻挡，见上方逐条日志）")
    }

    private suspend fun probe(source: BookSource, key: String): String {
        val web = WBook(source, debugLog = false, userid = "live-smoke")
        val results = web.searchBook(key, 1)
        if (results.isEmpty()) return "搜索无结果（可能被风控或关键词不匹配）"
        val first = results.first()
        val book = web.getBookInfo(first.bookUrl, canReName = false)
        val chapters = web.getChapterList(book)
        if (chapters.isEmpty()) return "搜索结果=${results.size} 但目录为空"
        val chapter = chapters.first()
        val content = web.getBookContent(book, chapter)
        return "OK 结果=${results.size} 书名=${book.name} 目录=${chapters.size} 首章=${chapter.title} 正文=${content.length}字"
    }

    private fun loadSources(): List<BookSource> {
        val dir = System.getenv("QREAD_SMOKE_SOURCE_DIR")?.takeIf { it.isNotBlank() }?.let { File(it) }
        val files = when {
            dir != null && dir.isDirectory -> dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList().orEmpty()
            else -> listOf("69主站.json", "天天看小说.json", "霹雳书屋.json").map { File(projectRoot, it) }
        }
        val limit = System.getenv("QREAD_SMOKE_LIMIT")?.toIntOrNull() ?: 12
        return files
            .filter { it.exists() }
            .flatMap { file ->
                BookSource.fromJsonArray(file.readText()).getOrElse { error ->
                    println("[跳过] ${file.name} 解析失败: ${error.message}")
                    emptyList()
                }
            }
            .take(limit)
    }
}
