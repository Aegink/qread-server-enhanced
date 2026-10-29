package book.webBook.analyzeRule

import book.model.Book
import book.model.BookSource
import book.webBook.DebugLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyzeRuleContextTest {

    @Test
    fun reusesTopScopeAfterRepeatedJavaScriptCalls() {
        val rule = AnalyzeRule(Book(), source = source()).setRuleType("CONTENT")

        repeat(18) { index ->
            assertEquals(index + 1.0, rule.evalJS("result + 1", index))
        }

        assertTrue(rule.hasReusableTopScope())
        assertEquals("CONTENT", rule.evalJS("java.getActiveRuleType()"))
        assertNull(AnalyzeRule.currentRuleTypeThreadLocal.get())
    }

    @Test
    fun exposesBookInfoContextAndOnlyLogsWhenDebuggingIsEnabled() {
        val messages = mutableListOf<String>()
        val debug = object : DebugLog {
            override fun log(sourceUrl: String?, msg: String?, isHtml: Boolean) {
                messages += msg.orEmpty()
            }
        }
        val rule = AnalyzeRule(
            ruleData = Book(name = "Context Book"),
            debugLog = debug,
            source = source(),
            isFromBookInfo = true
        ).setRuleType("BOOK_INFO")

        assertEquals(true, rule.evalJS("fromBookInfo"))
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("类型=BOOK_INFO"))
        assertTrue(messages.single().contains("耗时="))

        val quietMessages = mutableListOf<String>()
        AnalyzeRule(Book(), source = source())
            .evalJS("1 + 1")
        assertTrue(quietMessages.isEmpty())
    }

    @Test
    fun warnsOnceForLenientPutJsonAndKeepsCompatibility() {
        val messages = mutableListOf<String>()
        val debug = object : DebugLog {
            override fun log(sourceUrl: String?, msg: String?, isHtml: Boolean) {
                messages += msg.orEmpty()
            }
        }
        val rule = AnalyzeRule(Book(), debug, source()).setContent("seed")

        repeat(2) {
            rule.splitSourceRule("@put:{token:'value'}@get:{token}")
        }

        assertEquals(1, messages.count { it.contains("@put 规则 JSON 格式不规范") })
    }

    private fun source() = BookSource(
        bookSourceName = "stage35-source",
        bookSourceUrl = "https://stage35.example",
        userid = "stage35-user"
    )
}
