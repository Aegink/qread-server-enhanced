package book.webBook

import book.model.Book
import book.model.BookSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 * $env:QREAD_SMOKE_LIMIT="100"                        # 可选：样本上限
 * $env:QREAD_SMOKE_REPORT_DIR="docs/smoke"            # 可选：CSV/JSON 存档目录
 * .\gradlew.bat :book:test --tests "book.webBook.RealSourceLiveSmokeTest"
 * ```
 *
 * 结果除了逐条打印，还会落成 **CSV + JSON** 存档（默认 `build/smoke-reports/`），
 * 文件名带时间戳，同时覆盖 `smoke-latest.csv/json`，方便跨轮 diff 通过率与失败归因。
 */
class RealSourceLiveSmokeTest {

    private val projectRoot: File = File(System.getProperty("user.dir")).let {
        if (it.name == "book") it.parentFile ?: it else it
    }

    /** 单个书源的抽测结论。status 取值 PASS / FAIL / SKIP。 */
    private data class ProbeResult(
        val status: String,
        val category: String,
        val detail: String,
        val searchCount: Int = 0,
        val tocCount: Int = 0,
        val contentLen: Int = 0
    )

    /** 落盘用的一行记录。 */
    private data class Row(
        val index: Int,
        val sourceId: String,
        val name: String,
        val url: String,
        val status: String,
        val category: String,
        val detail: String,
        val searchCount: Int,
        val tocCount: Int,
        val contentLen: Int,
        val elapsedMs: Long
    )

    @Test
    fun liveSearchInfoTocContent() = runBlocking {
        if (System.getenv("QREAD_LIVE_SMOKE") != "1") {
            println("跳过真源抽测：设置 QREAD_LIVE_SMOKE=1 再运行")
            return@runBlocking
        }
        val key = System.getenv("QREAD_SMOKE_KEY")?.takeIf { it.isNotBlank() } ?: "斗破苍穹"
        val sources = loadSources()
        assertTrue(sources.isNotEmpty(), "没有可用的真源")

        val rows = ArrayList<Row>(sources.size)
        var passed = 0
        var failed = 0
        var skipped = 0

        sources.forEachIndexed { index, (sourceId, source) ->
            val label = source.bookSourceName.ifBlank { source.bookSourceUrl }
            val started = System.currentTimeMillis()

            if (source.searchUrl.isNullOrBlank()) {
                println("[跳过] $label: 没有搜索地址")
                skipped++
                rows += Row(index + 1, sourceId, label, source.bookSourceUrl, "SKIP", "无搜索地址", "没有搜索地址", 0, 0, 0, 0)
                return@forEachIndexed
            }

            val result = runCatching {
                withTimeoutOrNull(60_000) { probe(source, key) }
                    ?: ProbeResult("FAIL", "超时", "单源超过 60s 未完成")
            }.getOrElse { error ->
                // 打印前 12 帧堆栈，便于定位是引擎还是站点问题
                error.stackTrace.take(12).forEach { frame -> println("    at $frame") }
                ProbeResult("FAIL", classifyError(error), "${error.javaClass.simpleName}: ${error.message}")
            }
            val elapsed = System.currentTimeMillis() - started

            when (result.status) {
                "PASS" -> {
                    println("[通过] $label -> ${result.detail}")
                    passed++
                }
                "SKIP" -> {
                    println("[跳过] $label -> ${result.detail}")
                    skipped++
                }
                else -> {
                    println("[失败] $label -> ${result.detail}")
                    failed++
                }
            }

            rows += Row(
                index + 1, sourceId, label, source.bookSourceUrl, result.status, result.category,
                result.detail, result.searchCount, result.tocCount, result.contentLen, elapsed
            )
        }

        val reportFiles = writeReports(rows, key)
        println("== 真源抽测汇总：通过 $passed / 失败 $failed / 跳过 $skipped / 共 ${sources.size} ==")
        println("== 失败归因：" + rows.filter { it.status == "FAIL" }
            .groupingBy { it.category }.eachCount()
            .entries.sortedByDescending { it.value }
            .joinToString("、") { "${it.key} ${it.value}" } + " ==")
        reportFiles.forEach { println("== 存档：${it.absolutePath} ==")
        }

        assertTrue(passed > 0, "所有真源都没跑通（多为网络/风控阻挡，见上方逐条日志）")
    }

    private suspend fun probe(source: BookSource, key: String): ProbeResult {
        val web = WBook(source, debugLog = false, userid = "live-smoke")
        val results = web.searchBook(key, 1)
        if (results.isEmpty()) {
            return ProbeResult("FAIL", "搜索无结果", "搜索无结果（可能被风控或关键词不匹配）")
        }
        val first = results.first()
        // 与 App 行为一致：把搜索结果的元数据带进详情页，书源没有详情规则时也不会丢书名
        val book = Book(
            bookUrl = first.bookUrl,
            origin = source.bookSourceUrl,
            originName = source.bookSourceName,
            name = first.name,
            author = first.author,
            kind = first.kind,
            coverUrl = first.coverUrl,
            intro = first.intro,
            latestChapterTitle = first.latestChapterTitle,
            tocUrl = first.tocUrl,
            type = source.bookSourceType,
            userid = "live-smoke"
        )
        val info = web.getBookInfo(book, canReName = false)
        val chapters = web.getChapterList(info)
        if (chapters.isEmpty()) {
            return ProbeResult(
                "FAIL", "TocEmpty",
                "搜索结果=${results.size} 但目录为空（书名=${info.name}）",
                results.size, 0, 0
            )
        }
        val chapter = chapters.first()
        val content = web.getBookContent(info, chapter)
        if (content.isBlank()) {
            return ProbeResult(
                "FAIL", "ContentEmpty",
                "正文为空（书名=${info.name} 首章=${chapter.title}）",
                results.size, chapters.size, 0
            )
        }
        val nameFrom = when {
            info.name.isBlank() -> "无"
            info.name == first.name -> "搜索结果"
            else -> "详情页"
        }
        return ProbeResult(
            "PASS", "OK",
            "OK 结果=${results.size} 书名=${info.name}（$nameFrom）目录=${chapters.size} " +
                "首章=${chapter.title} 正文=${content.length}字",
            results.size, chapters.size, content.length
        )
    }

    /** 把异常归到「归因分类」，便于跨轮统计同一类失败是否在增长。 */
    private fun classifyError(error: Throwable): String {
        val message = error.message.orEmpty()
        val name = error.javaClass.simpleName
        return when {
            name.contains("Timeout") || message.contains("timed out", true) -> "超时"
            name == "TocEmptyException" -> "TocEmpty"
            name == "ContentEmptyException" -> "ContentEmpty"
            name.contains("PathNotFound") -> "PathNotFound"
            name.contains("Script") -> "Script"
            message.contains("unexpected end of stream", true) -> "网络层:断流"
            name.contains("SSL") || message.contains("SSL", true) -> "网络层:SSL"
            message.contains("StreamReset", true) -> "网络层:流重置"
            message.contains("PROTOCOL_ERROR", true) -> "网络层:协议错误"
            message.contains("401") || message.contains("403") -> "网络层:HTTP拦截"
            name.contains("IOException") || name.contains("Socket") -> "网络层"
            else -> name
        }
    }

    private fun writeReports(rows: List<Row>, key: String): List<File> {
        val dir = System.getenv("QREAD_SMOKE_REPORT_DIR")?.takeIf { it.isNotBlank() }
            ?.let { File(it).let { d -> if (d.isAbsolute) d else File(projectRoot, it) } }
            ?: File(projectRoot, "build/smoke-reports")
        dir.mkdirs()

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val passed = rows.count { it.status == "PASS" }
        val failed = rows.count { it.status == "FAIL" }
        val skipped = rows.count { it.status == "SKIP" }

        val csvHeader = "index,sourceId,name,status,category,searchCount,tocCount,contentLen,elapsedMs,url,detail"
        val csvBody = rows.joinToString("\n") { row ->
            listOf(
                row.index.toString(), row.sourceId, row.name, row.status, row.category,
                row.searchCount.toString(), row.tocCount.toString(), row.contentLen.toString(),
                row.elapsedMs.toString(), row.url, row.detail
            ).joinToString(",") { csvCell(it) }
        }
        val csvText = "$csvHeader\n$csvBody\n"

        val jsonText = buildString {
            append("{\n")
            append("  \"generatedAt\": \"").append(stamp).append("\",\n")
            append("  \"keyword\": ").append(jsonCell(key)).append(",\n")
            append("  \"total\": ").append(rows.size).append(",\n")
            append("  \"passed\": ").append(passed).append(",\n")
            append("  \"failed\": ").append(failed).append(",\n")
            append("  \"skipped\": ").append(skipped).append(",\n")
            append("  \"attribution\": {")
            append(rows.filter { it.status == "FAIL" }.groupingBy { it.category }.eachCount()
                .entries.sortedByDescending { it.value }
                .joinToString(", ") { "${jsonCell(it.key)}: ${it.value}" })
            append("},\n")
            append("  \"sources\": [\n")
            append(rows.joinToString(",\n") { row ->
                "    {\"index\": ${row.index}, \"sourceId\": ${jsonCell(row.sourceId)}, " +
                    "\"name\": ${jsonCell(row.name)}, \"url\": ${jsonCell(row.url)}, " +
                    "\"status\": ${jsonCell(row.status)}, \"category\": ${jsonCell(row.category)}, " +
                    "\"searchCount\": ${row.searchCount}, \"tocCount\": ${row.tocCount}, " +
                    "\"contentLen\": ${row.contentLen}, \"elapsedMs\": ${row.elapsedMs}, " +
                    "\"detail\": ${jsonCell(row.detail)}}"
            })
            append("\n  ]\n}\n")
        }

        val csvStamp = File(dir, "smoke-$stamp.csv")
        val jsonStamp = File(dir, "smoke-$stamp.json")
        csvStamp.writeText(csvText)
        jsonStamp.writeText(jsonText)
        File(dir, "smoke-latest.csv").writeText(csvText)
        File(dir, "smoke-latest.json").writeText(jsonText)
        return listOf(csvStamp, jsonStamp)
    }

    private fun csvCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    private fun jsonCell(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private fun loadSources(): List<Pair<String, BookSource>> {
        val dir = System.getenv("QREAD_SMOKE_SOURCE_DIR")?.takeIf { it.isNotBlank() }?.let { File(it) }
        val files = when {
            dir != null && dir.isDirectory ->
                dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name }?.toList().orEmpty()
            else -> listOf("69主站.json", "天天看小说.json", "霹雳书屋.json").map { File(projectRoot, it) }
        }
        val limit = System.getenv("QREAD_SMOKE_LIMIT")?.toIntOrNull() ?: 12
        return files
            .filter { it.exists() }
            .flatMap { file ->
                val sourceId = file.name.removeSuffix(".json")
                BookSource.fromJsonArray(file.readText()).getOrElse { error ->
                    println("[跳过] ${file.name} 解析失败: ${error.message}")
                    emptyList()
                }.map { sourceId to it }
            }
            .take(limit)
    }
}
