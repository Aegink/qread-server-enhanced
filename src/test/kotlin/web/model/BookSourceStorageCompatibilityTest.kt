package web.model

import book.model.BookSource as EngineBookSource
import book.model.rule.ContentRule
import book.model.rule.ReviewRule
import web.util.BigDataHelp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookSourceStorageCompatibilityTest {

    @Test
    fun globalAndUserSourceJsonPreserveCompatibilityFields() {
        val engineSource = EngineBookSource(
            bookSourceUrl = "https://stage12.example",
            bookSourceName = "stage12-source",
            exploreScreen = "screen-rule",
            eventListener = true,
            customButton = true,
            nextPageLazyLoad = true,
            homepageModules = "homepage-json",
            ruleContent = ContentRule(subContent = "lyric-rule", callBackJs = "callback"),
            ruleReview = ReviewRule(contentRule = "review-rule")
        )

        val globalStored = BookSource().jsontomodel(engineSource)
        val userStored = UserBookSource().jsontomodel(engineSource, "stage12-user")
        listOf(globalStored.json, userStored.json).forEach { json ->
            val restored = EngineBookSource.fromJson(json!!).getOrThrow()
            assertEquals("screen-rule", restored.exploreScreen)
            assertTrue(restored.eventListener)
            assertTrue(restored.customButton)
            assertTrue(restored.nextPageLazyLoad)
            assertEquals("homepage-json", restored.homepageModules)
            assertEquals("lyric-rule", restored.ruleContent?.subContent)
            assertEquals("callback", restored.ruleContent?.callBackJs)
            assertEquals("review-rule", restored.ruleReview?.contentRule)
        }
    }

    @Test
    fun newEngineFieldsDoNotBecomeAutoTableColumns() {
        val compatibilityFields = setOf(
            "exploreScreen",
            "eventListener",
            "customButton",
            "nextPageLazyLoad",
            "homepageModules",
            "ruleReview"
        )
        val globalColumns = BookSource::class.java.declaredFields.mapTo(mutableSetOf()) { it.name }
        val userColumns = UserBookSource::class.java.declaredFields.mapTo(mutableSetOf()) { it.name }

        assertFalse(globalColumns.any(compatibilityFields::contains))
        assertFalse(userColumns.any(compatibilityFields::contains))
        assertTrue("json" in globalColumns)
        assertTrue("json" in userColumns)
    }

    @Test
    fun chapterJsonMappingPreservesImageUrl() {
        val chapter = book.model.BookChapter(
            url = "/chapter/1",
            title = "第一章",
            imgUrl = "https://img.example/chapter.jpg",
            variable = "{\"lyric\":\"text\"}"
        )
        val json = web.util.BookChapter.toBookChapterJson(listOf(chapter))
        val restored = web.util.BookChapter.toBookChapterList(json).orEmpty().single()

        assertEquals(chapter.imgUrl, restored.imgUrl)
        assertEquals("text", restored.getVariable("lyric"))
    }

    @Test
    fun chapterMetadataUpdatePreservesOtherCachedChapters() {
        val bookUrl = "stage13-cache-${System.nanoTime()}"
        val userId = "stage13-user"
        val chapters = listOf(
            book.model.BookChapter(url = "/chapter/0", title = "zero", index = 0),
            book.model.BookChapter(url = "/chapter/1", title = "one", index = 1)
        )
        try {
            BigDataHelp.putChapterList(bookUrl, userId, chapters)
            val changed = chapters[1].apply {
                title = "one-updated"
                imgUrl = "https://img.example/one.jpg"
                putVariable("danmaku", "data")
            }

            BigDataHelp.updateChapterMetadata(bookUrl, userId, changed)
            val restored = BigDataHelp.getChapterList(bookUrl, userId).orEmpty()

            assertEquals("zero", restored[0].title)
            assertEquals("one-updated", restored[1].title)
            assertEquals("https://img.example/one.jpg", restored[1].imgUrl)
            assertEquals("data", restored[1].getVariable("danmaku"))
        } finally {
            BigDataHelp.putChapterList(bookUrl, userId, null)
        }
    }

    @Test
    fun booklistKeepsFileAndVideoTypes() {
        val book = Booklist()
        book.type = 3
        assertEquals(3, book.type)
        book.type = 4
        assertEquals(4, book.type)
    }
}
