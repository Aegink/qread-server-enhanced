package book.webBook.analyzeRule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `getElements` 对不同规则结果类型的容错（对齐 LegadoTeam/legado）。
 *
 * 真源实测（番茄ai-deepseek）暴露过：`bookList` 是 `@js:` 且返回 JSON 字符串时，
 * 我们直接 `as List<Any>` 抛 `ClassCastException`，整个搜索被打断；上游的做法是返回空列表。
 */
class AnalyzeRuleGetElementsTest {

    private fun rule(content: String) = AnalyzeRule(debugLog = null).setContent(content)

    @Test
    fun jsRuleReturningStringYieldsEmptyListInsteadOfCrashing() {
        val elements = rule("""{"a":1}""").getElements("""@js:'[{"name":"x"}]'""")

        assertTrue(elements.isEmpty(), "JS 返回字符串时应返回空列表，而不是抛 ClassCastException")
    }

    @Test
    fun jsRuleReturningArrayIsAccepted() {
        val elements = rule("""{"a":1}""").getElements("""@js:[{name:'甲'},{name:'乙'}]""")

        assertEquals(2, elements.size)
    }

    @Test
    fun jsoupRuleStillReturnsElements() {
        val elements = rule("<div class='item'>a</div><div class='item'>b</div>").getElements(".item")

        assertEquals(2, elements.size)
    }

    @Test
    fun jsonPathRuleReturnsList() {
        val elements = rule("""{"data":{"list":[{"n":1},{"n":2},{"n":3}]}}""").getElements("$.data.list[*]")

        assertEquals(3, elements.size)
    }

    @Test
    fun getElementOnMissingJsonPathReturnsNullInsteadOfThrowing() {
        val rule = rule("""{"a":1}""")

        assertNull(rule.getElement("$.data.name"), "JSON 路径缺失应返回 null，而不是抛 PathNotFoundException")
    }
}
