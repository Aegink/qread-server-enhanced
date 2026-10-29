package book.webBook

import book.app.App
import book.model.Book
import book.model.BookChapter
import book.model.BookSource
import book.model.BookType
import book.model.rule.ContentRule
import book.util.http.StrResponse
import book.webBook.exception.ContentEmptyException
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BookContentCompatibilityTest {

    @Test
    fun storesRemoteLyricAndSeparatesTitleImage() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/lyric") { exchange ->
            val bytes = "remote lyric".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        try {
            val lyricUrl = "http://127.0.0.1:${server.address.port}/lyric"
            val source = source(
                ContentRule(
                    content = "#content@text",
                    subContent = "#sub@text",
                    title = "#title@text"
                )
            )
            val book = Book(origin = source.bookSourceUrl, type = BookType.audio)
            val chapter = BookChapter(title = "旧标题")
            val body = """
                <div id="content">https://audio.example/1.mp3</div>
                <div id="sub">$lyricUrl</div>
                <div id="title">新标题https://img.example/title.jpg</div>
            """.trimIndent()

            val content = analyze(body, book, chapter, source)

            assertEquals("https://audio.example/1.mp3", content)
            assertEquals("remote lyric", chapter.getVariable("lyric"))
            assertEquals("新标题", chapter.title)
            assertEquals("https://img.example/title.jpg", chapter.imgUrl)
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun storesVideoDanmaku() = runBlocking {
        val source = source(ContentRule(content = "#content@text", subContent = "#sub@text"))
        val book = Book(origin = source.bookSourceUrl, type = BookType.video)
        val chapter = BookChapter()

        analyze(
            "<div id='content'>https://video.example/1.m3u8</div><div id='sub'>danmaku-json</div>",
            book,
            chapter,
            source
        )

        assertEquals("danmaku-json", chapter.getVariable("danmaku"))
    }

    @Test
    fun passesWebJsToSequentialPaginationRequest() {
        synchronized(App) {
            runBlocking {
                val originalWebview = App.webview
                val calls = mutableListOf<Pair<String?, String?>>()
                App.webview = { _, url, js, _, _, _, _, _ ->
                    calls += url to js
                    StrResponse(url.orEmpty(), "<div id='content'>page-two-web</div>")
                }
                try {
                    val source = source(
                        ContentRule(
                            content = "#content@text",
                            nextContentUrl = "#next@href",
                            webJs = "stage13WebJs()"
                        )
                    )
                    val optionUrl = "https://page-two.example/path,{&quot;webView&quot;:true}"
                    val body = """
                        <div id="content">page-one</div>
                        <a id="next" href="$optionUrl">next</a>
                    """.trimIndent()

                    val content = analyze(body, Book(origin = source.bookSourceUrl), BookChapter(), source)

                    assertEquals("page-one\npage-two-web", content)
                    assertEquals(1, calls.size)
                    assertTrue(calls.single().second?.contains("stage13WebJs()") == true)
                } finally {
                    App.webview = originalWebview
                }
            }
        }
    }

    @Test
    fun rejectsBlankNonVolumeContentButAllowsVolume() = runBlocking {
        val source = source(ContentRule(content = "#missing@text"))

        assertFailsWith<ContentEmptyException> {
            analyze("<html></html>", Book(origin = source.bookSourceUrl), BookChapter(), source)
        }
        assertEquals(
            "",
            analyze(
                "<html></html>",
                Book(origin = source.bookSourceUrl),
                BookChapter(isVolume = true),
                source
            )
        )
    }

    @Test
    fun normalizesLinesBeforeFullContentReplacement() = runBlocking {
        val source = source(
            ContentRule(
                content = "@js:'  first  \\n second  '",
                replaceRegex = "@js:result"
            )
        )
        val book = Book(origin = source.bookSourceUrl, type = BookType.default)

        val content = analyze("<html></html>", book, BookChapter(), source)

        assertEquals("　　first\n　　second", content)
    }

    private suspend fun analyze(
        body: String,
        book: Book,
        chapter: BookChapter,
        source: BookSource
    ): String {
        return BookContent.analyzeContent(
            body = body,
            book = book,
            bookChapter = chapter,
            bookSource = source,
            baseUrl = "https://stage13.example/chapter",
            redirectUrl = "https://stage13.example/chapter"
        )
    }

    private fun source(contentRule: ContentRule): BookSource {
        return BookSource(
            bookSourceName = "stage13-source",
            bookSourceUrl = "https://stage13.example",
            ruleContent = contentRule
        )
    }
}
