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
            ctx.redirect("/admin/login")
            return
        }
        if (ctx.method()?.uppercase() !in safeMethods && !verifyAdminCsrfToken(ctx)) {
            ctx.status(403)
            ctx.outputAsJson(GSON.toJson(JsonResponse(false, "CSRF 校验失败，请刷新页面后重试")))
            return
        }
        chain.doIntercept(ctx, mainHandler)
    }
}
