package book.util.help

import book.webBook.analyzeRule.QueryTTF
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertEquals

class CacheManagerTest {

    @AfterTest
    fun clearCache() {
        clearQueryTTFCache()
    }

    @Test
    fun queryTtfCacheIsSharedAcrossManagersForTheSameUser() {
        val font = emptyFont()
        CacheManager("user-1").put("font", font)

        assertSame(font, CacheManager("user-1").getQueryTTF("font"))
        assertNull(CacheManager("user-2").getQueryTTF("font"))
    }

    @Test
    fun queryTtfCacheIsLimitedToFourEntries() {
        val manager = CacheManager("user-1")
        repeat(5) { index ->
            manager.put("font-$index", emptyFont())
        }

        assertEquals(4, queryTTFCacheSize())
        assertNull(manager.getQueryTTF("font-0"))
    }

    @Test
    fun removesExpiredQueryTtfEntry() {
        val manager = CacheManager("user-expired")
        manager.put("expired-font", emptyFont(), -1)

        assertNull(manager.getQueryTTF("expired-font"))
        assertEquals(0, queryTTFCacheSize())
    }

    @Test
    fun parsesAndCachesARealFont() {
        val fontFile = File("../src/main/resources/static/static/layui/font/iconfont.ttf")
        val font = QueryTTF(fontFile.readBytes())
        val manager = CacheManager("user-real-font")

        manager.put("real-font", font)

        assertSame(font, CacheManager("user-real-font").getQueryTTF("real-font"))
    }

    private fun emptyFont(): QueryTTF = QueryTTF(ByteArray(12))
}
