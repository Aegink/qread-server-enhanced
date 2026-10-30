package book.webBook.analyzeRule

import com.script.rhino.RhinoScriptEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 内置 CryptoJS 兼容性（对齐 LegadoTeam/legado 的 `SharedJsScope.installCryptoJs`）。
 *
 * 真源实测里遇到过 `ReferenceError: CryptoJS 未定义`：大量书源在搜索/正文规则里直接用
 * `CryptoJS.MD5(...)`、`CryptoJS.AES.decrypt(...)` 做签名与解密。这里钉住常用能力。
 */
class CryptoJsCompatibilityTest {

    @Test
    fun md5Sha1Sha256MatchKnownVectors() {
        assertEquals(
            "900150983cd24fb0d6963f7d28e17f72",
            RhinoScriptEngine.eval("CryptoJS.MD5('abc').toString()")
        )
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            RhinoScriptEngine.eval("CryptoJS.SHA1('abc').toString()")
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            RhinoScriptEngine.eval("CryptoJS.SHA256('abc').toString()")
        )
    }

    @Test
    fun hmacAndBase64Work() {
        val hmac = RhinoScriptEngine.eval("CryptoJS.HmacMD5('abc', 'key').toString()").toString()
        assertEquals(32, hmac.length, "HmacMD5 应输出 32 位 hex，实际: $hmac")
        assertTrue(hmac.all { it in "0123456789abcdef" }, "HmacMD5 输出不是 hex: $hmac")

        assertEquals(
            "5Lit5paH",
            RhinoScriptEngine.eval("CryptoJS.enc.Base64.stringify(CryptoJS.enc.Utf8.parse('中文'))")
        )
    }

    @Test
    fun aesCbcRoundTrip() {
        val round = RhinoScriptEngine.eval(
            """
            var key = CryptoJS.enc.Utf8.parse('0123456789abcdef');
            var iv = CryptoJS.enc.Utf8.parse('abcdef0123456789');
            var cfg = {iv: iv, mode: CryptoJS.mode.CBC, padding: CryptoJS.pad.Pkcs7};
            var enc = CryptoJS.AES.encrypt('hello-legado', key, cfg).toString();
            CryptoJS.AES.decrypt(enc, key, cfg).toString(CryptoJS.enc.Utf8);
            """.trimIndent()
        )

        assertEquals("hello-legado", round)
    }
}
