package web.controller.admin

import org.noear.solon.annotation.*
import org.noear.solon.core.handle.Context
import org.noear.solon.core.util.DataThrowable
import org.slf4j.LoggerFactory
import web.response.*
import web.util.admin.loginok
import web.util.admin.verifyConfiguredAdminPassword
import web.util.admin.logout as logout2
import java.util.concurrent.atomic.AtomicBoolean


@Controller
@Mapping("/admin")
class LoginContorller {
    private val logger = LoggerFactory.getLogger(LoginContorller::class.java)
    private val legacyPasswordWarningLogged = AtomicBoolean()

    @Inject(value = "\${admin.username}", autoRefreshed=true)
    lateinit var adminusername:String

    @Inject(value = "\${admin.passwordHash:}", autoRefreshed=true)
    var adminPasswordHash: String = ""

    @Inject(value = "\${admin.password:}", autoRefreshed=true)
    var legacyAdminPassword: String = ""



    @Post
    @Mapping("/login")
    fun login(ctx: Context, username: String?, password: String?)=run {
        if (username.isNullOrBlank() || password.isNullOrBlank() )  {
            throw DataThrowable().data(JsonResponse(isSuccess = false, errorMsg = NOT_BANK))
        }

        val verification = verifyConfiguredAdminPassword(
            password,
            adminPasswordHash,
            legacyAdminPassword
        )
        if (verification.usesLegacyPlaintext && legacyPasswordWarningLogged.compareAndSet(false, true)) {
            logger.warn("检测到旧版 admin.password 明文配置，请迁移到 admin.passwordHash")
        }

        if(adminusername == username && verification.isValid) {
            loginok(username,ctx)
            JsonResponse(true)
        }else{
            JsonResponse(isSuccess = false, errorMsg =PASS_ERROR )
        }
    }



    @Post
    @Mapping("/logout")
    fun logout(ctx: Context){
        logout2(ctx)
        ctx.redirect("/admin/login")
    }
}
