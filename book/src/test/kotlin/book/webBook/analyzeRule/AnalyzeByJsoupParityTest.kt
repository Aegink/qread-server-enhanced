package book.webBook.analyzeRule

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AnalyzeByJsoupParityTest {
    @Test
    fun singleAndMultipleResultsKeepTheSamePublicValues() {
        val analyzer = AnalyzeByJSoup("<div><p>A</p><p>B</p></div>")

        assertEquals("A", analyzer.getString("tag.p.0@text"))
        assertEquals("A\nB", analyzer.getString("tag.p@text"))
    }

    @Test
    fun resultHelpersUseMutableArrayListWithoutLegacyUnsafePatterns() {
        val returnTypes = AnalyzeByJSoup::class.java.declaredMethods
            .filter { it.name.startsWith("getResultList") || it.name.startsWith("getResultLast") }
            .map { it.returnType }
        val source = Path.of(
            "src", "main", "kotlin", "book", "webBook", "analyzeRule", "AnalyzeByJSoup.kt"
        ).readText()

        assertEquals(setOf(java.util.ArrayList::class.java), returnTypes.toSet())
        assertFalse(source.contains("removeAll(listOf(null))"))
        assertFalse(source.contains("substring(0,"))
        assertFalse(source.contains(".size > 0"))
    }
}
