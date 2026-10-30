package book.webBook.analyzeRule

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 索引选择器语法（legado 规则里非常常见）。
 *
 * 真源抽测里两个「目录为空」的书源都用了它：
 * - 精华书阁WAP：`chapterList = class.chapter.1@li@a`（取第 2 个匹配项）
 * - 大书包小说：`chapterList = .book_list!0@li`（排除第 1 个匹配项）
 */
class AnalyzeRuleIndexSelectorTest {

    private fun rule(content: String) = AnalyzeRule(debugLog = null).setContent(content)

    @Test
    fun numericIndexSelectsSingleElement() {
        val html = """
            <div class="item">a</div>
            <div class="item">b</div>
            <div class="item">c</div>
        """.trimIndent()

        val elements = rule(html).getElements(".item.1")

        assertEquals(1, elements.size, "`.item.1` 应只取第 2 个元素")
        assertEquals("b", rule(html).getString(".item.1@text"))
    }

    @Test
    fun excludeIndexWorksOnChildSelection() {
        val html = """
            <ul class="book_list">
              <li class="head">目录</li>
              <li class="c"><a>第一章</a></li>
              <li class="c"><a>第二章</a></li>
            </ul>
        """.trimIndent()

        // 索引按"每一步自己的结果集"生效：排除第一个 li 要写在 li 这一步之后
        assertEquals(
            listOf("第一章", "第二章"),
            rule(html).getStringList(".book_list@li[!0]@a@text")
        )
        assertEquals(
            listOf("第一章", "第二章"),
            rule(html).getStringList(".book_list@li!0@a@text")
        )
    }

    @Test
    fun excludeIndexOnParentStepExcludesTheParentItself() {
        // `.book_list!0` 排除的是 ul 本身（结果集只有 1 个元素），所以结果为空——
        // 这是与 legado 一致的分步语义，真源里 `.book_list!0@li` 这种写法属于书源笔误
        val html = """
            <ul class="book_list">
              <li class="head">目录</li>
              <li class="c"><a>第一章</a></li>
            </ul>
        """.trimIndent()

        assertEquals(0, rule(html).getElements(".book_list!0@li").size)
    }
}
