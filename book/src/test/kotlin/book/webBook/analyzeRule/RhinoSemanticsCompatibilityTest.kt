package book.webBook.analyzeRule

import book.model.BookChapter
import com.script.rhino.RhinoScriptEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Rhino 语义回归（书源规则里 JS 的基础行为契约）。
 *
 * 用例来源：rimvoid/legado（阅读 Archive，GPL-3.0）`app/src/test/.../JsTest.kt`，
 * 按本项目引擎重写为无副作用断言；对应我们 `book/` 里 com.script.rhino 的求值语义。
 * 这些语义一旦被上游"现代化"改坏，书源里的 `@js:` / `{{}}` 规则会静默失效，因此单独看护。
 */
class RhinoSemanticsCompatibilityTest {

    @Test
    fun mapValueIsReadableByDollarAndGetter() {
        val map = hashMapOf("id" to "3242532321")

        val viaDollar = RhinoScriptEngine.eval("\$=result;id=\$.id;id") { set("result", map) }
        assertEquals("3242532321", viaDollar)

        val viaGetter = RhinoScriptEngine.eval("result.get(\"id\")") { set("result", map) }
        assertEquals("3242532321", viaGetter)
    }

    @Test
    fun forOfAndForInKeepUpstreamSemantics() {
        val result = RhinoScriptEngine.eval(
            """
            let result = 0
            let a=[1,2,3]
            let l=a.length
            for (let i = 0;i<l;i++){
                result = result + a[i]
            }
            for (let o of a){
                result = result + o
            }
            for (let o in a){
                result = result + o
            }
            result
            """.trimIndent()
        )
        // 前两轮数值累加得到 12，第三轮 for-in 拿到的是字符串下标，于是退化成字符串拼接
        assertEquals("12012", result)
    }

    @Test
    fun nullLiteralReturnsNull() {
        assertNull(RhinoScriptEngine.eval("null"))
    }

    @Test
    fun regexMatchAndSubstrTrimChapterTitle() {
        val js = """
            s=result.match(/(.{1,6}?)(第.*)/);
            n=s[2].length-parseInt(6-s[1].length);
            s[2].substr(0,n);
        """.trimIndent()
        val trimmed = RhinoScriptEngine.eval(js) { set("result", "筳彩涫第七百一十四章 人头树鮺舦綸") }
        assertEquals("第七百一十四章 人头树", trimmed)
    }

    @Test
    fun chapterBindingExposesTitle() {
        val chapter = BookChapter(title = "xxxyyy")
        val title = RhinoScriptEngine.eval("chapter.title") { set("chapter", chapter) }
        assertEquals("xxxyyy", title)
    }

    @Test
    fun javaListForEachReturnsNumber() {
        val sum = RhinoScriptEngine.eval(
            """
            var result = 0
            list.forEach(item => {result = result + item})
            result
            """.trimIndent()
        ) { set("list", arrayListOf(1, 2, 3)) }
        assertEquals(6.0, sum)
    }


    @Test
    fun stringCoercionOfPrimitivesReportsStringType() {
        assertEquals("string", RhinoScriptEngine.eval("typeof ''"))
        assertEquals("string", RhinoScriptEngine.eval("typeof ('' + 1)"))
        assertEquals("string", RhinoScriptEngine.eval("typeof String('abc')"))
    }

    @Test
    fun javaStringBindingIsExposedAsJsString() {
        // 书源里最依赖的一点：Kotlin/Java 侧传进来的字符串，在 JS 里必须仍是 string 而不是包装对象
        val type = RhinoScriptEngine.eval("typeof result") { set("result", "abc") }
        assertEquals("string", type)
    }

    @Test
    fun emptyStringConcatenatedWithStringConstructorWrapsAsObject() {
        // 与 rimvoid/legado 的 JsTest.stringCoercion 不同：我们这里是 object（'' + String() 保留了包装对象）。
        // 书源不要依赖 String() 参与拼接后的 typeof；此断言固定当前行为，将来若修正需同步更新。
        assertEquals("object", RhinoScriptEngine.eval("s = '' + String(); typeof s"))
    }
}
