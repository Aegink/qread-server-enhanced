package web


import org.noear.solon.Solon
import org.noear.solon.annotation.SolonMain
import org.noear.solon.scheduling.annotation.EnableScheduling
import org.noear.solon.web.cors.CrossFilter
import org.noear.solon.web.staticfiles.StaticMappings
import org.noear.solon.web.staticfiles.repository.FileStaticRepository


@SolonMain
@EnableScheduling
class App


fun main(args: Array<String>) {

    Solon.start(App::class.java, args) { app ->
        app.enableSessionState(true)
        app.enableWebSocket(true)
        // Solon 4：SolonApp.filter(...) 已移除，改用 ChainManager.addFilter
        app.chains().addFilter(CrossFilter().pathPatterns("/assets/covers/**").allowedOrigins("*"), 0)
        app.chains().addFilter(CrossFilter().pathPatterns("/assets/codes/**").allowedOrigins("*"), 0)
        StaticMappings.add("/assets/",  FileStaticRepository("storage/assets/"))
       // app.http("/webdav/*", handler)
       // app.get("/") { ctx -> ctx.forward("/index.html"); }
    }
}