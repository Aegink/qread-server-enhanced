package book.webBook.analyzeRule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * JSONPath 列表规则的回归：书源里 `bookList` / `chapterList` 大量使用 `$.xxx` 与 `$..` 形态。
 * 上游 LegadoTeam/legado 修复过"list-backed JSONPath 结果"被硬转 ArrayList 后抛异常、
 * 进而静默返回空列表的问题（#648）。这里把几种真实用法都钉住。
 */
class AnalyzeByJSonPathListTest {

    private val json = """
        {
          "data": {
            "list": [
              {"title": "甲", "tags": ["玄幻", "连载"]},
              {"title": "乙", "tags": ["都市"]}
            ]
          },
          "next": "p2"
        }
    """.trimIndent()

    @Test
    fun readsPlainArrayPath() {
        val result = AnalyzeByJSonPath(json).getList("\$.data.list")

        assertEquals(2, result?.size ?: 0)
    }

    @Test
    fun readsNestedFieldOfArray() {
        val result = AnalyzeByJSonPath(json).getList("\$.data.list[*].title")

        assertEquals(listOf("甲", "乙"), result?.map { it.toString() })
    }

    @Test
    fun readsDeepScanResult() {
        val result = AnalyzeByJSonPath(json).getList("\$..tags")

        val flattened = result?.map { item -> (item as List<*>).map { it.toString() } }
        assertEquals(listOf(listOf("玄幻", "连载"), listOf("都市")), flattened)
    }

    @Test
    fun mergesListPathsWithPercentPercent() {
        // `%%` 是"按索引交错合并"，只对列表型结果生效，且长度以第一个列表为准（第三个标签因此被截断）
        val merged = AnalyzeByJSonPath(json).getList("\$.data.list[*].title%%\$.data.list[*].tags[*]")

        assertEquals(listOf("甲", "玄幻", "乙", "连载"), merged?.map { it.toString() })
    }

    @Test
    fun scalarPathDoesNotTakePartInPercentMerge() {
        val merged = AnalyzeByJSonPath(json).getList("\$.data.list[*].title%%\$.next")

        assertEquals(listOf("甲", "乙"), merged?.map { it.toString() })
    }

    @Test
    fun missingPathReturnsEmptyList() {
        val result = AnalyzeByJSonPath(json).getList("\$.nope")
        assertTrue(result.isNullOrEmpty(), "缺失路径应返回空列表，实际: $result")
    }

    @Test
    fun missingPathForSingleObjectReturnsNull() {
        // 少年梦（知秋）真源：searchUrl 返回 {"code":0,...}，规则写 $['data']，
        // 曾经抛 PathNotFoundException 打断整次搜索；这里钉住"取不到就是 null"
        assertNull(AnalyzeByJSonPath(json).getObject("\$.nope"), "缺失路径应返回 null 而不是抛异常")
    }

    @Test
    fun existingPathForSingleObjectStillReads() {
        assertEquals("p2", AnalyzeByJSonPath(json).getObject("\$.next"))
    }
}
