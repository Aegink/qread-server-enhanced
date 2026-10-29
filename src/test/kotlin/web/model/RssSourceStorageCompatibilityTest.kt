package web.model

import book.model.RssSource as EngineRssSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RssSourceStorageCompatibilityTest {

    @Test
    fun storesNewFieldsInsideExistingJsonWithoutAddingTableColumns() {
        val source = EngineRssSource(
            sourceUrl = "https://stage20.example",
            sourceName = "stage20",
            type = 2,
            searchUrl = "/search",
            startHtml = "<main>start</main>",
            startJs = "start()",
            startStyle = "main{}",
            preload = true,
            preloadJs = "preload()",
            cacheFirst = true,
            showWebLog = true
        )
        val global = RssSource().jsontomodel(source)
        val user = UserRssSource().jsontomodel(source, "stage20-user")

        listOf(global.json, user.json).forEach { json ->
            val restored = EngineRssSource.fromJson(requireNotNull(json))
            assertEquals(2, restored.type)
            assertEquals("/search", restored.searchUrl)
            assertEquals("<main>start</main>", restored.startHtml)
            assertEquals("start()", restored.startJs)
            assertEquals("main{}", restored.startStyle)
            assertTrue(restored.preload)
            assertEquals("preload()", restored.preloadJs)
            assertTrue(restored.cacheFirst)
            assertTrue(restored.showWebLog)
        }

        val jsonOnlyFields = setOf(
            "type", "searchUrl", "startHtml", "startJs", "startStyle",
            "preload", "preloadJs", "cacheFirst", "showWebLog"
        )
        assertFalse(RssSource::class.java.declaredFields.map { it.name }.any(jsonOnlyFields::contains))
        assertFalse(UserRssSource::class.java.declaredFields.map { it.name }.any(jsonOnlyFields::contains))
    }
}
