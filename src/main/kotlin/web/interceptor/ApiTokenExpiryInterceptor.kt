package web.interceptor

import book.util.GSON
import org.noear.solon.annotation.Component
import org.noear.solon.annotation.Inject
import org.noear.solon.core.handle.Context
import org.noear.solon.core.handle.Handler
import org.noear.solon.core.route.PathRule
import org.noear.solon.core.route.RouterInterceptor
import org.noear.solon.core.route.RouterInterceptorChain
import web.mapper.UsertockenMapper
import web.response.JsonResponse
import web.response.NEED_LOGIN
import web.util.auth.resolveUserToken

/** 在方法缓存命中前淘汰过期 token。 */
@Component
class ApiTokenExpiryInterceptor : RouterInterceptor {
    @Inject
    lateinit var usertockenMapper: UsertockenMapper

    override fun pathPatterns(): PathRule = PathRule().include("/api/**")

    override fun doIntercept(ctx: Context, mainHandler: Handler?, chain: RouterInterceptorChain) {
        val accessToken = ctx.param("accessToken")
        if (!accessToken.isNullOrBlank() && resolveUserToken(usertockenMapper, accessToken) == null) {
            ctx.outputAsJson(GSON.toJson(JsonResponse(false, NEED_LOGIN)))
            return
        }
        chain.doIntercept(ctx, mainHandler)
    }
}
