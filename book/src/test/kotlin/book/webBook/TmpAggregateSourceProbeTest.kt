package book.webBook

import book.model.Book
import book.model.BookChapter
import book.model.BookSource
import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * 临时探针：验证「番茄🍅（沐凡）」这类重 JS 聚合源。
 * 覆盖 发现(exploreUrl JS + 多分类) → 搜索 → 六模式切换 → 详情 → 目录 → 正文。
 * 用法：QREAD_PROBE_SOURCE=<书源json路径> 后跑该测试。
 */
class TmpAggregateSourceProbeTest {

    private fun step(name: String, block: suspend () -> Unit) = runBlocking {
        println("\n===== $name =====")
        try {
            block()
        } catch (e: Throwable) {
            println("!! 失败: ${e.javaClass.simpleName}: ${e.message}")
            e.stackTrace.take(6).forEach { println("     at $it") }
        }
    }

    private fun setState(source: BookSource, mutate: (com.google.gson.JsonObject) -> Unit) {
        val state = runCatching { JsonParser.parseString(source.getVariable()).asJsonObject }
            .getOrNull() ?: com.google.gson.JsonObject()
        mutate(state)
        source.setVariable(state.toString())
    }

    @Test
    fun probe() = runBlocking {
        val path = System.getenv("QREAD_PROBE_SOURCE")?.takeIf { it.isNotBlank() }
        if (path == null) {
            println("跳过：未设置 QREAD_PROBE_SOURCE")
            return@runBlocking
        }
        val source = BookSource.fromJsonArray(File(path).readText()).getOrThrow().first()
        source.userid = "probe"
        println("== 书源：${source.bookSourceName}  url=${source.bookSourceUrl}")
        println("   jsLib=${source.jsLib?.length ?: 0} 字符  eventListener=${source.eventListener}  customButton=${source.customButton}")

        // 1) 发现分类
        var kindsJson = ""
        step("1. 发现分类（exploreUrl @js）") {
            kindsJson = source.exploreKinds(true)
            val arr = runCatching { JsonParser.parseString(kindsJson).asJsonArray }.getOrNull()
            println("   分类总数=${arr?.size() ?: 0}")
            arr?.take(6)?.forEach { e ->
                val o = e.asJsonObject
                println("   - ${o.get("title")?.asString} type=${o.get("type")?.asString ?: "-"} url=${o.get("url")?.asString?.take(60)}")
            }
        }

        // 2) 发现列表：多个分类各取一页
        step("2. 发现列表（多个分类）") {
            val arr = runCatching { JsonParser.parseString(kindsJson).asJsonArray }.getOrNull() ?: return@step
            val withUrl = arr.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
                .filter { (it.get("url")?.takeIf { e -> e.isJsonPrimitive }?.asString ?: "").isNotBlank() }
            println("   带数据 url 的分类数=${withUrl.size}")
            withUrl.take(5).forEach { o ->
                val title = o.get("title")?.asString
                val url = o.get("url")!!.asString
                val result = runCatching {
                    WBook(source, debugLog = false, userid = "probe").exploreBook(url, 1)
                }
                result.onSuccess { list ->
                    println("   [${title}] ${list.size} 条  首条=${list.firstOrNull()?.name}")
                }.onFailure { e ->
                    println("   [${title}] !! ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

        // 3) 搜索（默认模式）
        var firstBook: Book? = null
        step("3. 搜索（默认模式）") {
            val list = WBook(source, debugLog = false, userid = "probe").searchBook("斗破苍穹", 1)
            println("   结果=${list.size} 条")
            list.take(3).forEach { println("   - ${it.name} / ${it.author}") }
            list.firstOrNull()?.let {
                firstBook = Book(
                    bookUrl = it.bookUrl, origin = source.bookSourceUrl, originName = source.bookSourceName,
                    name = it.name, author = it.author, kind = it.kind, coverUrl = it.coverUrl,
                    intro = it.intro, latestChapterTitle = it.latestChapterTitle, tocUrl = it.tocUrl,
                    type = source.bookSourceType, userid = "probe"
                )
            }
        }

        // 4) 六模式矩阵：+N 前缀（书源内置写法）与 source 变量两种切换方式
        step("4. 模式切换矩阵（+N 前缀 / module 变量）") {
            listOf(1, 2, 3, 4, 8, 11, 13, 19).forEach { m ->
                val byPrefix = runCatching {
                    WBook(source, debugLog = false, userid = "probe").searchBook("+$m 斗破苍穹", 1)
                }
                setState(source) { it.addProperty("module", m) }
                val byVar = runCatching {
                    WBook(source, debugLog = false, userid = "probe").searchBook("斗破苍穹", 1)
                }
                val p = byPrefix.map { "${it.size}条" }.getOrElse { "ERR:${it.javaClass.simpleName}" }
                val v = byVar.map { "${it.size}条" }.getOrElse { "ERR:${it.javaClass.simpleName}" }
                val first = byVar.getOrNull()?.firstOrNull()?.name ?: byPrefix.getOrNull()?.firstOrNull()?.name ?: "-"
                println("   module=$m  +前缀=$p  变量=$v  首条=$first")
            }
            setState(source) { it.addProperty("module", 3) }
        }

        // 5) 详情 / 目录 / 正文（默认开关，段评全开）
        var chapterRef: BookChapter? = null
        var bookRef: Book? = null
        step("5. 详情/目录/正文（开关全开）") {
            val book = firstBook ?: run { println("   无搜索结果，跳过"); return@step }
            bookRef = book
            val web = WBook(source, debugLog = false, userid = "probe")
            val info = web.getBookInfo(book, canReName = false)
            println("   详情：书名=${info.name} 作者=${info.author}")
            val chapters = web.getChapterList(info)
            println("   目录=${chapters.size} 章，首章=${chapters.firstOrNull()?.title}")
            val chapter = chapters.firstOrNull() ?: return@step
            chapterRef = chapter
            val content = web.getBookContent(info, chapter)
            println("   正文=${content.length} 字（段评/神评开）")
            println("   结尾：${content.takeLast(100).replace("\n", " ")}")
        }

        // 6) 关闭段评/神评/横幅后再取正文，验证正文本体
        step("6. 正文（关闭段评/神评/横幅）") {
            val book = bookRef
            val chapter = chapterRef
            if (book == null || chapter == null) {
                println("   缺少上下文，跳过")
                return@step
            }
            setState(source) { st ->
                listOf("showPara", "showGod", "showChap", "showAuthor", "showBook", "writePara").forEach {
                    st.addProperty(it, false)
                }
            }
            val web = WBook(source, debugLog = false, userid = "probe")
            val info = web.getBookInfo(book, canReName = false)
            val content = web.getBookContent(info, chapter)
            println("   正文=${content.length} 字（全关）")
            println("   开头：${content.take(80).replace("\n", " ")}")
            println("   结尾：${content.takeLast(80).replace("\n", " ")}")
        }

        println("\n===== 探针结束 =====")
    }
}
