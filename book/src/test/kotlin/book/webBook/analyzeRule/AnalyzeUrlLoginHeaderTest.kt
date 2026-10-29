package book.webBook.analyzeRule

import book.model.BookSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 登录态请求头只发同站（对齐 LegadoTeam/legado #1099）。
 *
 * 书源登录后 `source.loginHeader` 会带 Cookie/Authorization；过去它对任意目标域名都外发，
 * 跨域请求（图床、CDN、第三方接口）会把凭据一起送出去。这里用 localhost 与 127.0.0.1
 * 构造"同站/跨站"两种目标确认门禁生效。
 */
class AnalyzeUrlLoginHeaderTest {

    @Test
    fun loginHeaderIsOnlySentToSameSite() {
        val source = BookSource(
            bookSourceName = "login-header",
            bookSourceUrl = "http://localhost:1",
            userid = "login-header-user"
        )
        source.putLoginHeader("""{"Authorization":"Bearer secret"}""")
        try {
            // 同站：书源域名 = 目标域名
            val sameSite = AnalyzeUrl("http://localhost:1/source/page", source = source, debugLog = null)
            assertEquals("Bearer secret", sameSite.headerMap["Authorization"])

            // 跨站：目标换成 127.0.0.1，登录头不得跟着走
            val crossSite = AnalyzeUrl("http://127.0.0.1:1/other/page", source = source, debugLog = null)
            assertNull(crossSite.headerMap["Authorization"], "登录头被带到了跨站目标")

            // 书源自带的普通 header 仍应外发
            val plain = BookSource(
                bookSourceName = "plain-header",
                bookSourceUrl = "http://localhost:1",
                header = """{"X-Plain":"1"}""",
                userid = "login-header-user"
            )
            val crossPlain = AnalyzeUrl("http://127.0.0.1:1/other/page", source = plain, debugLog = null)
            assertEquals("1", crossPlain.headerMap["X-Plain"])
        } finally {
            source.removeLoginHeader()
        }
    }
}
