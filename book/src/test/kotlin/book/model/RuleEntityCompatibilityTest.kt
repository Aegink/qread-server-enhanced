package book.model

import book.model.rule.BookInfoRule
import book.model.rule.ContentRule
import book.model.rule.ExploreKind
import book.model.rule.ExploreRule
import book.model.rule.ReviewRule
import book.model.rule.SearchRule
import book.model.rule.TocRule
import book.util.GSON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleEntityCompatibilityTest {

    @Test
    fun ruleDeserializerAcceptsObjectJsonStringAndSingleRule() {
        assertEquals("$.books", parseSingleRule<SearchRule>("$.books").bookList)
        assertEquals("$.books", parseSingleRule<ExploreRule>("$.books").bookList)
        assertEquals("$.name", parseSingleRule<BookInfoRule>("$.name").name)
        assertEquals("$.chapters", parseSingleRule<TocRule>("$.chapters").chapterList)
        assertEquals("$.content", parseSingleRule<ContentRule>("$.content").content)
        assertEquals("$.review", parseSingleRule<ReviewRule>("$.review").contentRule)

        val objectRule = GSON.fromJson("""{"bookList":"$.items"}""", SearchRule::class.java)
        val jsonStringRule = GSON.fromJson(
            GSON.toJson("""{"bookList":"$.items"}"""),
            SearchRule::class.java
        )
        assertEquals(objectRule, jsonStringRule)
    }

    @Test
    fun sourceAnalyzerPreservesNewFieldsAndMixedRuleFormats() {
        val source = BookSource.fromJson(
            """
            {
              "bookSourceUrl": "https://stage12.example",
              "bookSourceName": "stage12-source",
              "exploreScreen": "{\"kind\":\"all\"}",
              "eventListener": true,
              "customButton": true,
              "nextPageLazyLoad": true,
              "homepageModules": "[{\"title\":\"hot\"}]",
              "ruleExplore": "$.explore",
              "ruleSearch": {"bookList": "$.search"},
              "ruleBookInfo": "{\"name\":\"$.name\"}",
              "ruleToc": "$.chapters",
              "ruleContent": {"content": "$.content", "subContent": "$.lyric", "callBackJs": "result"},
              "ruleReview": "$.review"
            }
            """.trimIndent()
        ).getOrThrow()

        assertEquals("{\"kind\":\"all\"}", source.exploreScreen)
        assertTrue(source.eventListener)
        assertTrue(source.customButton)
        assertTrue(source.nextPageLazyLoad)
        assertEquals("[{\"title\":\"hot\"}]", source.homepageModules)
        assertEquals("$.explore", source.ruleExplore?.bookList)
        assertEquals("$.search", source.ruleSearch?.bookList)
        assertEquals("$.name", source.ruleBookInfo?.name)
        assertEquals("$.chapters", source.ruleToc?.chapterList)
        assertEquals("$.lyric", source.ruleContent?.subContent)
        assertEquals("result", source.ruleContent?.callBackJs)
        assertEquals("$.review", source.ruleReview?.contentRule)
    }

    @Test
    fun sourceAnalyzerStillConvertsLegacyFlatRules() {
        val source = BookSource.fromJson(
            """
            {
              "bookSourceUrl": "https://legacy.example",
              "bookSourceName": "legacy-source",
              "bookSourceType": "VIDEO",
              "ruleSearchUrl": "https://legacy.example/search?key=searchKey",
              "ruleSearchList": "div.book",
              "ruleChapterList": "div.chapter",
              "ruleBookContent": "div.content"
            }
            """.trimIndent()
        ).getOrThrow()

        assertEquals("div.book", source.ruleSearch?.bookList)
        assertEquals("div.chapter", source.ruleToc?.chapterList)
        assertEquals("div.content", source.ruleContent?.content)
        assertEquals(BookType.video, source.bookSourceType)
        assertTrue(source.searchUrl?.contains("{{key}}") == true)
    }

    @Test
    fun uiAndChapterFieldsRoundTripWithoutSerializingRuntimeMd5() {
        val kind = ExploreKind(
            title = "分类",
            type = ExploreKind.Type.select,
            chars = arrayOf("全部", "完结"),
            default = "全部",
            style = FlexChildStyle(layout_justifySelf = "center")
        )
        val row = RowUi(
            name = "筛选",
            type = RowUi.Type.toggle,
            chars = arrayOf("开", "关"),
            default = "开"
        )
        val kindCopy = GSON.fromJson(GSON.toJson(kind), ExploreKind::class.java)
        val rowCopy = GSON.fromJson(GSON.toJson(row), RowUi::class.java)
        assertEquals(kind, kindCopy)
        assertEquals("center", kindCopy.style().layout_justifySelf)
        assertEquals(row, rowCopy)
        assertEquals(listOf("开", "关"), rowCopy.chars?.toList())

        val chapter = BookChapter(title = "第一章", imgUrl = "https://img.example/1.jpg")
        chapter.titleMD5 = "runtime-only"
        val chapterJson = GSON.toJson(chapter)
        val chapterCopy = GSON.fromJson(chapterJson, BookChapter::class.java)
        assertEquals(chapter.imgUrl, chapterCopy.imgUrl)
        assertFalse(chapterJson.contains("titleMD5"))
    }

    @Test
    fun splitLongChapterUsesBookReadConfig() {
        val book = Book()
        assertFalse(book.getSplitLongChapter())
        book.setSplitLongChapter(true)
        assertTrue(book.getSplitLongChapter())
        assertTrue(GSON.toJson(book.readConfig).contains("splitLongChapter"))
    }

    private inline fun <reified T> parseSingleRule(rule: String): T {
        return GSON.fromJson(GSON.toJson(rule), T::class.java)
    }
}
