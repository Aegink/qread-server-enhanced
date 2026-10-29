package book.webBook.analyzeRule

import book.app.App
import book.model.Book
import book.model.BookChapter
import book.model.BookSource
import book.util.http.JsonpResponse
import book.util.http.StrResponse
import com.script.ScriptBindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QreadRedlineCompatibilityTest {

    @Test
    fun keepsQreadJavaScriptAndPhoneBridgeBehavior() {
        val source = source()
        val rule = AnalyzeRule(Book(), source = source)
        val bindings = ScriptBindings()
        rule.binding(bindings)

        assertEquals("1", rule.qread())
        assertNotNull(bindings["Base64"])
        assertEquals("ASCII", rule.utf8ToGbk("ASCII"))

        synchronized(App) {
            val originalGet = App.get
            val originalPost = App.post
            val originalHead = App.head
            val originalVerificationCode = App.getVerificationCodeusePhone
            val originalUa = App.getWebViewUA
            val originalWebView = App.webview
            val originalBrowserDp = App.startBrowserdp
            var requestHeaders = ""
            var browserUrl = ""
            try {
                App.get = { url, headers, _, _ -> response(url, "get").also { requestHeaders = headers } }
                App.post = { url, _, _, _, _ -> response(url, "post") }
                App.head = { url, _, _, _ -> response(url, "head") }
                App.getVerificationCodeusePhone = { _, _, _ -> " 2468 " }
                App.getWebViewUA = { "qread-phone-ua" }
                App.webview = { _, url, _, _, _, _, _, _ -> StrResponse(url.orEmpty(), "webview-body") }
                App.startBrowserdp = { url, _, _, _ -> browserUrl = url }

                assertEquals("GET", rule.getusePhone("https://redline.example/get", mapOf("X-Qread" to "1")).method().name)
                assertTrue(requestHeaders.contains("X-Qread"))
                assertEquals("POST", rule.postusePhone("https://redline.example/post", "body", emptyMap()).method().name)
                assertEquals("HEAD", rule.headusePhone("https://redline.example/head", emptyMap()).method().name)
                assertEquals("2468", rule.getVerificationCodeusePhone("https://redline.example/code.png"))
                assertEquals("qread-phone-ua", rule.getWebViewUANEW())
                assertEquals("webview-body", rule.webview(null, "https://redline.example/web", null).body)
                rule.startBrowserDp("https://redline.example/review", "review")
                assertEquals("https://redline.example/review", browserUrl)
            } finally {
                App.get = originalGet
                App.post = originalPost
                App.head = originalHead
                App.getVerificationCodeusePhone = originalVerificationCode
                App.getWebViewUA = originalUa
                App.webview = originalWebView
                App.startBrowserdp = originalBrowserDp
            }
        }
    }

    @Test
    fun enforcesFiftyToastLimitAndPreservesAnalyzeRuleContext() {
        val source = source()
        val book = Book()
        val chapter = BookChapter()
        val rule = AnalyzeRule(book, source = source).apply { this.chapter = chapter }

        assertEquals("redline-user", book.userid)
        assertEquals("redline-user", chapter.userid)
        assertTrue(rule.toString().startsWith("io.legado.app.model.analyzeRule.AnalyzeRule@"))
        assertTrue(rule.isUnicodeEscaped("value=\\u4e2d"))
        assertEquals("value=中", rule.unescapeUnicode("value=\\u4e2d"))

        synchronized(App) {
            val originalToast = App.toast
            val originalLongToast = App.longToast
            var calls = 0
            try {
                App.toast = { _, token ->
                    assertEquals("redline-token", token)
                    calls++
                }
                App.longToast = { _, token ->
                    assertEquals("redline-token", token)
                    calls++
                }
                repeat(49) { rule.toast(it) }
                rule.longToast("fiftieth")
                assertEquals(50, calls)
                assertFailsWith<Exception> { rule.toast("fifty-first") }
                assertEquals(50, calls)
            } finally {
                App.toast = originalToast
                App.longToast = originalLongToast
            }
        }
    }

    @Test
    fun keepsUrlOptionsBookSourceFieldsAndArchitectureClasses() {
        val source = source().apply {
            phonehttp = true
            ruleSearch = book.model.rule.SearchRule(bookList = ".book")
            ruleExplore = book.model.rule.ExploreRule(bookList = ".explore")
            ruleBookInfo = book.model.rule.BookInfoRule(name = "h1")
            ruleToc = book.model.rule.TocRule(chapterList = ".chapter")
            ruleContent = book.model.rule.ContentRule(content = ".content")
        }
        val clicked = AnalyzeUrl(
            "https://redline.example,{\"click\":\"result + '/clicked'\"}",
            source = source,
            debugLog = null
        )
        assertEquals("https://redline.example/clicked", clicked.url)

        val bypassed = AnalyzeUrl(
            "https://redline.example/{{key}}",
            key = "ignored",
            source = source,
            needanalyzeUrl = false,
            debugLog = null
        )
        assertEquals("https://redline.example/{{key}}", bypassed.ruleUrl)
        assertEquals("", bypassed.url)

        assertEquals("redline-user", source.userid)
        assertEquals("redline-token", source.usertocken)
        assertEquals(true, source.phonehttp)
        assertEquals(".book", source.getSearchRule().bookList)
        assertEquals(".explore", source.getExploreRule().bookList)
        assertEquals("h1", source.getBookInfoRule().name)
        assertEquals(".chapter", source.getTocRule().chapterList)
        assertEquals(".content", source.getContentRule().content)

        val sourceFields = BookSource::class.java.declaredFields.mapTo(mutableSetOf()) { it.name }
        assertTrue(
            setOf("searchRuleV", "exploreRuleV", "bookInfoRuleV", "tocRuleV", "contentRuleV", "debugLog")
                .all(sourceFields::contains)
        )
        listOf(
            "book.webBook.localBook.CbzFile",
            "book.webBook.analyzeRule.SourceLoginJsExtensions",
            "book.webBook.analyzeRule.SymmetricCryptoAndroid",
            "book.webBook.analyzeRule.InfoMap"
        ).forEach { assertNotNull(Class.forName(it)) }

        val appFields = App::class.java.declaredFields.mapTo(mutableSetOf()) { it.name }
        assertTrue(setOf("webview", "webviewbody", "showBrowser").all(appFields::contains))
        assertNotNull(source.getCookieManger())
        assertNotNull(source.getCacheManger())
    }

    private fun source() = BookSource(
        bookSourceName = "qread-redline",
        bookSourceUrl = "https://redline.example",
        header = "{\"X-Source\":\"qread\"}",
        userid = "redline-user",
        usertocken = "redline-token"
    )

    private fun response(url: String?, method: String) = JsonpResponse().apply {
        this.url = url.orEmpty()
        this.method = method
        body = method
        statusCode = 200
    }
}
