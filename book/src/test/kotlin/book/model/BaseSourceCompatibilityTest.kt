package book.model

import book.app.App
import book.util.help.RuleBigDataHelp
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BaseSourceCompatibilityTest {

    @AfterTest
    fun clearSharedScope() {
        SharedJsScope.clearCache()
    }

    @Test
    fun storesVariablesRefreshesExploreAndUpdatesConcurrentRate() {
        val source = source()
        source.putVariable("stage34-variable")
        assertEquals("stage34-variable", source.getVariable())

        RuleBigDataHelp.putSourceVariable(source.getKey(), source.userid.orEmpty(), "exploreKinds", "cached")
        synchronized(App) {
            val original = App.refreshExplore
            var callback = ""
            App.refreshExplore = { sourceUrl, _ -> callback = sourceUrl }
            try {
                source.refreshExplore()
            } finally {
                App.refreshExplore = original
            }
            assertEquals(source.getKey(), callback)
        }
        assertNull(
            RuleBigDataHelp.getSourceVariable(source.getKey(), source.userid.orEmpty(), "exploreKinds")
        )

        source.putConcurrent("3/1000")
        assertEquals("3/1000", source.concurrentRate)
    }

    @Test
    fun refreshesJsLibraryAndKeepsQreadBindings() {
        val source = source().apply { jsLib = "var stage34Shared = 1;" }
        val first = source.getShareScope()
        source.refreshJSLib()
        val second = source.getShareScope()

        assertNotSame(first, second)
        assertTrue(
            source.evalJS(
                "result != null && book == null && chapter == null && Base64 != null && source === java"
            ) as Boolean
        )
    }

    private fun source() = BookSource(
        bookSourceName = "stage34-source",
        bookSourceUrl = "https://stage34.example",
        userid = "stage34-user",
        usertocken = "stage34-token"
    )
}
