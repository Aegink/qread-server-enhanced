package book.util.help

import book.util.escapeRegex
import book.util.isHex
import book.util.normalizeFileName
import book.util.parseIpsFromString
import book.util.quoteReplacementJs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelpLayerCompatibilityTest {

    @Test
    fun parsesCurrentAndLegacyReplaceRules() {
        val current = ReplaceAnalyzer.jsonToReplaceRule(
            """{"id":1,"name":"当前","pattern":"foo","replacement":"bar"}"""
        ).getOrThrow()
        val legacy = ReplaceAnalyzer.jsonToReplaceRule(
            """{"id":2,"replaceSummary":"旧版","regex":"a+","replacement":"b","isRegex":true,"useTo":"book","enable":true,"serialNumber":7}"""
        ).getOrThrow()

        assertEquals("foo", current.pattern)
        assertEquals("旧版", legacy.name)
        assertEquals("a+", legacy.pattern)
        assertEquals("book", legacy.scope)
        assertEquals(7, legacy.order)
        assertTrue(legacy.isValid())
    }

    @Test
    fun filtersInvalidRulesFromLists() {
        val rules = ReplaceAnalyzer.jsonToReplaceRules(
            """[{"name":"有效","pattern":"ok"},{"replaceSummary":"无效","regex":"[","isRegex":true}]"""
        ).getOrThrow()

        assertEquals(listOf("有效"), rules.map { it.name })
    }

    @Test
    fun completesSimpleCssAndXpathRules() {
        assertEquals("div.name@text", RuleComplete.autoComplete("div.name"))
        assertEquals("a.book@href", RuleComplete.autoComplete("a.book", type = 2))
        assertEquals("//img//@src", RuleComplete.autoComplete("//img", type = 3))
        assertEquals("@js:result", RuleComplete.autoComplete("@js:result"))
    }

    @Test
    fun exposesPortableStringUtilities() {
        assertTrue("09aF".isHex())
        assertFalse("09xF".isHex())
        assertEquals("a\\+b\\?", "a+b?".escapeRegex())
        assertEquals("a_b_c_", "a/b:c?".normalizeFileName())
        assertEquals("a\\\\b\\\$c", "a\\b\$c".quoteReplacementJs())
        assertEquals(listOf("127.0.0.1", "0:0:0:0:0:0:0:1"),
            "127.0.0.1,::1".parseIpsFromString()?.map { it.hostAddress })
    }
}
