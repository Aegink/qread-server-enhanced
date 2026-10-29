package book.webBook.analyzeRule

import book.model.BookSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 规则级局部变量与规则名（对齐 LegadoTeam/legado 的 `setLocal` / `setRuleName`）。
 *
 * 书源里常见写法：先 `java.put('token', ...)` 存一个值，后续规则步骤或 JS 再用 `java.get('token')` 读回；
 * 过去我们的 get 只认 book/chapter/rules/source 四级，没有"本级内存"这一层，这类书源会拿到空串。
 */
class AnalyzeRuleLocalBindingsTest {

    @Test
    fun localBindingIsVisibleToBothKotlinAndJs() {
        val rule = AnalyzeRule(
            source = BookSource(bookSourceName = "local-bindings", bookSourceUrl = "http://127.0.0.1:1")
        )

        rule.setLocal("token", "abc-123")

        assertEquals("abc-123", rule.get("token"))
        assertEquals("abc-123", rule.evalJS("java.get('token')"))
    }

    @Test
    fun localBindingTakesPrecedenceOverBuiltInKeys() {
        val rule = AnalyzeRule(debugLog = null)

        rule.setLocal("bookName", "被覆盖的名字")

        assertEquals("被覆盖的名字", rule.get("bookName"))
    }

    @Test
    fun ruleNameBacksGetTagWithoutSource() {
        val rule = AnalyzeRule(debugLog = null)

        rule.setRuleName("段评列表")

        assertEquals("段评列表", rule.getTag())
    }

    @Test
    fun blankRuleNameDoesNotOverride() {
        val rule = AnalyzeRule(debugLog = null)
        rule.setRuleName("有效名字")

        rule.setRuleName("   ")

        assertEquals("有效名字", rule.getTag())
    }

    @Test
    fun javaPutThenGetRoundTripsWithoutAnySource() {
        val rule = AnalyzeRule(debugLog = null)

        rule.evalJS("java.put('stage', 'phase-m2')")

        assertEquals("phase-m2", rule.evalJS("java.get('stage')"))
        assertEquals("phase-m2", rule.get("stage"))
    }
}
