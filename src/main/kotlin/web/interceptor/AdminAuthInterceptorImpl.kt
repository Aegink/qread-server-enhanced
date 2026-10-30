package web.interceptor

import org.noear.solon.core.handle.Handler
import org.noear.solon.annotation.Component
import org.noear.solon.core.handle.Context
import org.noear.solon.core.route.PathRule
import org.noear.solon.core.route.RouterInterceptor
import org.noear.solon.core.route.RouterInterceptorChain
import book.util.GSON
import web.response.JsonResponse
import web.util.admin.islogin
import web.util.admin.verifyAdminCsrfToken


@Component
class AdminAuthInterceptorImpl: RouterInterceptor {
    private val safeMethods = setOf("GET", "HEAD", "OPTIONS")

    override fun pathPatterns(): PathRule {
        return PathRule().include("/admin").include("/admin/**").exclude("/admin/login")
    }

    @Throws(Throwable::class)
    override fun doIntercept(ctx: Context, mainHandler: Handler?, chain: RouterInterceptorChain) {
        if (!islogin(ctx)) {
            // 后台页面用 AJAX 调接口：会话失效时若直接 302 到登录页，前端拿到的是登录页 HTML，
            // 只能笼统提示"网络异常"。对这类请求回 401 + NEED_LOGIN，让前端能区分
            // 「登录过期」和「其它失败」。
            if (isAjaxRequest(ctx)) {
                ctx.status(401)
                ctx.outputAsJson(GSON.toJson(JsonResponse(false, "NEED_LOGIN: 登录已过期，请重新登录")))
            } else {
                ctx.redirect("/admin/login")
            }
            return
        }
        if (ctx.method()?.uppercase() !in safeMethods && !verifyAdminCsrfToken(ctx)) {
            ctx.status(403)
            ctx.outputAsJson(
                GSON.toJson(JsonResponse(false, "CSRF 校验失败：页面已过期或会话已重建，请刷新页面后重试"))
            )
            return
        }
        chain.doIntercept(ctx, mainHandler)
    }

    /** 判定是否为页面内 AJAX 请求（而不是浏览器地址栏导航）。 */
    private fun isAjaxRequest(ctx: Context): Boolean {
        if (ctx.header("X-Requested-With")?.equals("XMLHttpRequest", ignoreCase = true) == true) return true
        if (!ctx.header("X-CSRF-Token").isNullOrBlank()) return true
        val accept = ctx.header("Accept").orEmpty()
        return accept.contains("application/json", ignoreCase = true)
    }
}
