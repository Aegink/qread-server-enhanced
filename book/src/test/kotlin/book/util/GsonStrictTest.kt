package book.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GsonStrictTest {

    @Test
    fun acceptsStandardJson() {
        val result = GSONStrict.fromJsonObject<Map<String, String>>("""{"method":"HEAD"}""")

        assertTrue(result.isSuccess)
        assertEquals("HEAD", result.getOrThrow()?.get("method"))
    }

    @Test
    fun rejectsLenientJsonWithoutBreakingLegacyParser() {
        val json = "{method:'HEAD'}"

        assertTrue(GSONStrict.fromJsonObject<Map<String, String>>(json).isFailure)
        assertEquals("HEAD", GSON.fromJsonObject<Map<String, String>>(json).getOrThrow()?.get("method"))
    }
}
