package com.script

import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

class ScriptBindings : NativeObject() {

    companion object {
        private const val CRYPTO_JS_RESOURCE = "/js/crypto-js.min.js"
        private val BUILTIN_GLOBAL_NAMES = arrayOf("CryptoJS")
        private val topLevelScope: ScriptableObject by lazy {
            val cx = Context.enter()
            try {
                val scope = cx.initStandardObjects()
                installCryptoJs(cx, scope)
                scope
            } finally {
                Context.exit()
            }
        }

        /** 共享顶层作用域（含 CryptoJS 等内置库），供引擎构造求值作用域时复用。 */
        internal fun sharedTopLevelScope(): ScriptableObject = topLevelScope

        /**
         * 内置 CryptoJS 4.2.0（MIT），与 legado 系的 `SharedJsScope.installCryptoJs` 对齐。
         *
         * 大量书源在搜索/正文规则里直接用 `CryptoJS.MD5(...)`、`CryptoJS.AES.decrypt(...)`。
         * 之前没有注入这个全局对象，这类书源会直接抛 `ReferenceError: CryptoJS 未定义`（真源实测遇到过）。
         */
        private fun installCryptoJs(cx: Context, scope: ScriptableObject) {
            val source = runCatching {
                ScriptBindings::class.java.getResource(CRYPTO_JS_RESOURCE)?.readText()
            }.getOrNull()
            if (source.isNullOrBlank()) {
                System.err.println("installCryptoJs: 资源缺失 $CRYPTO_JS_RESOURCE")
                return
            }
            runCatching {
                cx.evaluateString(scope, source, "crypto-js.min.js", 1, null)
            }.onFailure {
                System.err.println("installCryptoJs 失败: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    init {
        prototype = topLevelScope
        installBuiltinGlobals()
    }

    /**
     * 把内置库挂成 bindings 的自有属性。
     *
     * Rhino 的标识符查找（parentScope 链 / prototype 链）在不同求值入口下行为不一致，
     * 仅靠 prototype 时有入口拿不到全局 CryptoJS；挂成自有属性对所有入口都成立。
     */
    private fun installBuiltinGlobals() {
        Context.enter()
        try {
            BUILTIN_GLOBAL_NAMES.forEach { name ->
                val value = topLevelScope.get(name, topLevelScope)
                if (value != Scriptable.NOT_FOUND) {
                    put(name, this, value)
                }
            }
        } finally {
            Context.exit()
        }
    }

    operator fun set(key: String, value: Any?) {
        Context.enter()
        try {
            put(key, this, Context.javaToJS(value, this))
        } finally {
            Context.exit()
        }
    }

    operator fun set(index: Int, value: Any?) {
        Context.enter()
        try {
            put(index, this, Context.javaToJS(value, this))
        } finally {
            Context.exit()
        }
    }

    fun put(key: String, value: Any?) {
        set(key, value)
    }

}
