package book.webBook.analyzeRule

import book.model.BookSource
import book.util.SynchronizedLruCache
import com.script.CompiledScript
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AnalyzeRuleCacheTest {

    @Test
    fun cachesCompiledScriptsRegexAndRulesWithinBounds() {
        val analyzeRule = analyzeRule("cache-user", "https://cache.example")
        val scriptMethod = privateMethod("compileScriptCache", String::class.java)
        val regexMethod = privateMethod("compileRegexCache", String::class.java)
        val ruleMethod = privateMethod("splitSourceRuleCacheString", String::class.java)

        val firstScript = scriptMethod.invoke(analyzeRule, "1 + 1") as CompiledScript
        repeat(200) {
            assertSame(firstScript, scriptMethod.invoke(analyzeRule, "1 + 1"))
        }
        val firstRegex = regexMethod.invoke(analyzeRule, "a+") as Regex
        repeat(200) {
            assertSame(firstRegex, regexMethod.invoke(analyzeRule, "a+"))
        }
        val firstRules = ruleMethod.invoke(analyzeRule, "body@text")
        repeat(200) {
            assertSame(firstRules, ruleMethod.invoke(analyzeRule, "body@text"))
        }

        val replacingRule = analyzeRule("replace-user", "https://cache.example")
            .setContent("<p>old</p>")
        repeat(20) {
            assertEquals("new", replacingRule.getString("p@text##old##new"))
        }

        @Suppress("UNCHECKED_CAST")
        val dynamicRules = ruleMethod.invoke(analyzeRule, "{{result}}") as List<AnalyzeRule.SourceRule>
        val dynamicRule = dynamicRules.single()
        dynamicRule.makeUpRule("value##old##new")
        assertEquals("old", dynamicRule.replaceRegex)
        dynamicRule.makeUpRule("value")
        assertEquals("", dynamicRule.replaceRegex)
        assertEquals("", dynamicRule.replacement)
        assertEquals(false, dynamicRule.replaceFirst)

        repeat(80) { index ->
            scriptMethod.invoke(analyzeRule, "$index + 1")
            regexMethod.invoke(analyzeRule, "stage9-$index")
            ruleMethod.invoke(analyzeRule, "div:nth-child(${index + 1})@text")
        }

        assertEquals(AnalyzeRuleCacheCapacity.SCRIPT, cacheSize(analyzeRule, "scriptCache"))
        assertEquals(AnalyzeRuleCacheCapacity.REGEX, cacheSize(analyzeRule, "regexCache"))
        assertEquals(AnalyzeRuleCacheCapacity.RULE, cacheSize(analyzeRule, "stringRuleCache"))
    }

    @Test
    fun isolatesSameSourceRulesByUserDuringConcurrentReads() {
        val userA = analyzeRule("user-a", "https://same-source.example")
            .setContent("<p>A</p>")
        val userB = analyzeRule("user-b", "https://same-source.example")
            .setContent("<p>B</p>")
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newFixedThreadPool(8)

        try {
            val tasks = (0 until 200).map { index ->
                Callable {
                    val analyzeRule = if (index % 2 == 0) userA else userB
                    val expected = if (index % 2 == 0) "A" else "B"
                    val actual = analyzeRule.getString("p@text")
                    if (actual != expected) failures.add("$index:$actual")
                }
            }
            executor.invokeAll(tasks).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertTrue(failures.isEmpty(), failures.joinToString())
        val scriptMethod = privateMethod("compileScriptCache", String::class.java)
        val userAScript = scriptMethod.invoke(userA, "1 + 1")
        val userBScript = scriptMethod.invoke(userB, "1 + 1")
        assertNotSame(userAScript, userBScript)
        assertEquals(setOf("user-a"), cacheKeys(userA, "scriptCache").map { it.userid }.toSet())
        assertEquals(setOf("user-b"), cacheKeys(userB, "scriptCache").map { it.userid }.toSet())
        assertEquals(
            setOf("https://same-source.example"),
            cacheKeys(userA, "scriptCache").map { it.sourceKey }.toSet()
        )
    }

    private fun analyzeRule(userid: String, sourceKey: String): AnalyzeRule {
        return AnalyzeRule(
            source = BookSource(
                bookSourceName = "stage9-test",
                bookSourceUrl = sourceKey,
                userid = userid
            )
        )
    }

    private fun privateMethod(name: String, vararg parameterTypes: Class<*>): java.lang.reflect.Method {
        return AnalyzeRule::class.java.getDeclaredMethod(name, *parameterTypes).apply {
            isAccessible = true
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun cacheKeys(analyzeRule: AnalyzeRule, fieldName: String): Set<AnalyzeRuleCacheKey> {
        val field = AnalyzeRule::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
        val cache = field.get(analyzeRule) as SynchronizedLruCache<AnalyzeRuleCacheKey, *>
        return cache.keys()
    }

    private fun cacheSize(analyzeRule: AnalyzeRule, fieldName: String): Int {
        val field = AnalyzeRule::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
        return (field.get(analyzeRule) as SynchronizedLruCache<*, *>).size()
    }
}
