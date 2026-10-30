package web.util.session

import org.noear.solon.core.handle.Context
import org.noear.solon.core.handle.SessionState
import org.noear.solon.core.handle.SessionStateFactory
import org.noear.solon.server.handle.SessionProps
import org.noear.solon.sessionstate.local.LocalSessionState
import org.noear.solon.sessionstate.local.LocalSessionStateFactory

/**
 * 按需创建会话。
 *
 * Solon 在每次响应发头时都会调用 `sessionPublish()`，默认实现里的 `sessionId()` 遇到没带
 * 会话 cookie 的请求就会新建一个 id 并 `Set-Cookie`。结果是静态资源、公开 API 也都带
 * `Set-Cookie`，CDN 因此不缓存这些响应（Cloudflare 显示 `cf-cache-status: BYPASS`）。
 *
 * 这里只改发布时机：请求既没有带会话 cookie、本次处理中也没有读写过会话（读写会先生成 id
 * 并写进 cookieMap）时，跳过发布。后台登录与 CSRF 等真正用到会话的路径行为不变。
 */
class LazySessionState(ctx: Context) : LocalSessionState(ctx) {

    override fun sessionPublish() {
        val name = SessionProps.session_cookieName
        val touched = !ctx.cookie(name).isNullOrEmpty() || ctx.cookieMap().containsKey(name)
        if (!touched) return
        super.sessionPublish()
    }
}

object LazySessionStateFactory : SessionStateFactory {
    /** 与本地会话插件同优先级：插件只在现有工厂优先级更低时才覆盖，因此不会把它换回去。 */
    override fun priority(): Int = LocalSessionStateFactory.SESSION_STATE_PRIORITY

    override fun create(ctx: Context): SessionState = LazySessionState(ctx)
}
