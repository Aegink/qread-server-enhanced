package web.filter


import book.util.GSON
import org.noear.solon.annotation.Component
import org.noear.solon.core.exception.StatusException
import org.noear.solon.core.handle.Context
import org.noear.solon.core.handle.Filter
import org.noear.solon.core.handle.FilterChain
import org.noear.solon.core.handle.ModelAndView
import org.noear.solon.core.util.DataThrowable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import web.response.JsonResponse


/**
 * 统一异常出口。
 *
 * 以前这里把非 404 的异常原样抛出，Solon 只会回一个空 body 的 500 —— App 侧只能显示
 * 「网络错误」，运维侧也只知道「有 500」。现在：
 * - 业务异常（DataThrowable）照旧交给 Solon 渲染，行为不变；
 * - `/api/` 开头的接口出现未预期异常时，统一回 `{"isSuccess":false,"errorMsg":"服务内部错误"}` 并打一次带堆栈的日志；
 * - 其它路径（后台页面）仍然 500，但同样会记录方法、路径与堆栈。
 */
@Component(index = 0)
class ErrorsFilter : Filter {

    val log: Logger = LoggerFactory.getLogger(ErrorsFilter::class.java)

    override fun doFilter(ctx: Context?, chain: FilterChain?) {
        runCatching {
            chain!!.doFilter(ctx)
        }.onFailure { error ->
            when (error) {
                is StatusException -> {
                    if (error.code == 404) {
                        ctx!!.status(error.code)
                        ctx.render(ModelAndView("errors/404.html"))
                    } else {
                        ctx!!.status(error.code)
                        throw error
                    }
                }
                // 控制器里 throw DataThrowable().data(JsonResponse(...)) 是正常的业务返回，
                // 必须原样抛给 Solon，不能被下面统一拦成「服务内部错误」
                is DataThrowable -> throw error
                else -> {
                    log.error("请求处理失败 {} {} -> {}", ctx?.method(), ctx?.path(), error.toString(), error)
                    if (ctx != null && ctx.path().startsWith("/api/")) {
                        runCatching {
                            ctx.status(500)
                            ctx.outputAsJson(GSON.toJson(JsonResponse(false, "服务内部错误")))
                        }.onFailure {
                            log.warn("异常响应写出失败: {}", it.message)
                            throw error
                        }
                    } else {
                        ctx?.status(500)
                        throw error
                    }
                }
            }
        }
    }
}
