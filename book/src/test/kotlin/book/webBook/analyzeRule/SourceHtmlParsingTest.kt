package book.webBook.analyzeRule

import book.model.BookSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 书源 HTML 自闭合标签解析（对齐 LegadoTeam/legado #1182 `SourceHtmlParser`）。
 *
 * 老书源按 XHTML 习惯写 `<a class="item" href="..."/>` 后接兄弟节点。标准 HTML5 解析器把 `<a/>` 当作
 * "开标签"，后面的 `<span>` 会被塞进 `<a>` 内部，列表规则取到的层级就全错；这里确认我们按 XHTML 语义
 * 把它当自闭合，`<span>` 仍是兄弟节点，且 CSS / XPath 看到同一份 DOM。
 */
class SourceHtmlParsingTest {

    private val html = """
        <div class="list">
          <a class="item" href="/one"/><span class="name">第一本</span>
          <a class="item" href="/two"/><span class="name">第二本</span>
          <a class="item" href="/three"/><span class="name">第三本</span>
        </div>
    """.trimIndent()

    private fun rule(): AnalyzeRule = AnalyzeRule(
        source = BookSource(bookSourceName = "self-close", bookSourceUrl = "http://127.0.0.1:1")
    ).setRuleType("TOC").setContent(html).setBaseUrl("http://127.0.0.1:1/")

    @Test
    fun cssRulesTreatSelfClosedTagsAsClosed() {
        val rule = rule()

        assertEquals(listOf("/one", "/two", "/three"), rule.getStringList(".item@href"))

        // 关键差异：<span> 是 <a> 的兄弟节点，而不是被吞进 <a> 内部
        assertTrue(
            rule.getElements(".item .name").isEmpty(),
            "自闭合解析失效：<span> 被当成了 <a> 的子节点"
        )
        assertEquals(listOf("第一本", "第二本", "第三本"), rule.getStringList(".name@text"))
    }

    @Test
    fun xpathRulesSeeSameDom() {
        assertEquals(
            listOf("/one", "/two", "/three"),
            AnalyzeByXPath(html).getStringList("//a[@class='item']/@href")
        )
        assertEquals(
            emptyList(),
            AnalyzeByXPath(html).getStringList("//a[@class='item']/span/text()")
        )
    }

    @Test
    fun normalNestedTagsAreUnaffected() {
        val rule = AnalyzeRule(
            source = BookSource(bookSourceName = "nested", bookSourceUrl = "http://127.0.0.1:1")
        ).setRuleType("TOC")
            .setContent("<div class='a'><span class='b'>x</span></div>")
            .setBaseUrl("http://127.0.0.1:1/")

        assertEquals("x", rule.getString(".a .b@text"))
        assertEquals(1, rule.getElements(".b").size)
    }
}
