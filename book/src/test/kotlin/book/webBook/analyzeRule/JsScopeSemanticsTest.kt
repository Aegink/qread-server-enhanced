package book.webBook.analyzeRule

import book.model.BookSource
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 脚本作用域语义（记录当前设计，防止被无意改掉）。
 *
 * - 每个求值作用域都带内置 CryptoJS，标准对象通过 prototype 解析；
 * - 规则 JS 以 jsLib 作用域为 prototype，因此**规则里重复声明 jsLib 已有的顶层常量会按
 *   JS 语义报错**（`redeclaration of const`）。这不是引擎缺陷：真实 JS 环境里两个脚本在同一
 *   全局重复声明 const 同样报错，责任在书源（jsLib 与规则起同名常量）。
 *   引擎不做「偷偷把 const 改写成 var」这类兜底，否则会掩盖书源问题并改变语义。
 */
class JsScopeSemanticsTest {

    private fun newScope() = RhinoScriptEngine.getRuntimeScope(ScriptBindings())

    @Test
    fun everyScopeCanUseCryptoJs() {
        val md5 = RhinoScriptEngine.eval("CryptoJS.MD5('abc').toString()", newScope())?.toString()
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5)
    }

    @Test
    fun standardObjectsResolveThroughPrototype() {
        val scope = newScope()
        assertEquals("2", RhinoScriptEngine.eval("String(Math.max(1, 2))", scope)?.toString())
        assertEquals("{\"a\":1}", RhinoScriptEngine.eval("JSON.stringify({a: 1})", scope)?.toString())
    }

    @Test
    fun topLevelConstStaysInItsOwnScope() {
        val scope = newScope()
        RhinoScriptEngine.eval("const tagKeyName = 'scoped';", scope)
        assertEquals("scoped", scope.get("tagKeyName", scope)?.toString())
    }

    @Test
    fun ruleRedeclaringJsLibConstFailsByJsSemantics() {
        val source = BookSource(
            bookSourceName = "js-semantics",
            bookSourceUrl = "https://example.com/js-semantics"
        )
        source.userid = "test"
        source.jsLib = "const tagKeyName = 'lib';"

        val error = runCatching { source.evalJS("const tagKeyName = 'rule'; 'x'") }.exceptionOrNull()

        assertTrue(error != null, "重复声明顶层常量应按 JS 语义报错，而不是被引擎悄悄改写")
        assertTrue(
            error.message.orEmpty().contains("redeclaration of const") ||
                error.message.orEmpty().contains("重新声明了常量"),
            "错误信息应指明重复声明的常量，实际: ${error.message}"
        )
    }

    @Test
    fun ruleCanUseFunctionsFromJsLib() {
        val source = BookSource(
            bookSourceName = "js-semantics",
            bookSourceUrl = "https://example.com/js-semantics"
        )
        source.userid = "test"
        source.jsLib = "function libFn(){ return 'ok'; }"

        assertEquals("ok", source.evalJS("libFn()")?.toString())
    }
}
