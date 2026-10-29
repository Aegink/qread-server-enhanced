package web.controller.admin

import org.noear.solon.annotation.Controller
import org.noear.solon.annotation.Get
import org.noear.solon.annotation.Mapping
import org.noear.solon.core.handle.Context
import org.noear.solon.core.handle.ModelAndView
import web.util.admin.getOrCreateAdminCsrfToken



@Controller
@Mapping("/admin")
class HomeController {

    @Get
    @Mapping("/adduser")
    fun adduser(ctx: Context) = adminView("admin/adduser.html", ctx)

    @Get
    @Mapping("/addcookie")
    fun addcookie(ctx: Context) = adminView("admin/addcookie.html", ctx)

    @Get
    @Mapping("/login")
    fun login() = ModelAndView("login.html")

    @Get
    @Mapping("/?")
    fun index(ctx: Context) = adminView("admin/index.html", ctx, "index")

    @Get
    @Mapping("/book")
    fun book(ctx: Context) = adminView("admin/book.html", ctx, "book")

    @Get
    @Mapping("/rss")
    fun rss(ctx: Context) = adminView("admin/rss.html", ctx, "rss")

    @Get
    @Mapping("/code")
    fun code(ctx: Context) = adminView("admin/code.html", ctx, "code")

    private fun adminView(template: String, ctx: Context, active: String? = null) =
        ModelAndView(template).also {
            it.put("csrfToken", getOrCreateAdminCsrfToken(ctx))
            active?.let { key -> it.put(key, "layui-this") }
        }
}
