package book.webBook.rss

import book.model.RssSource
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RssCompatibilityTest {

    @Test
    fun appliesLoginCheckToArticleListAndContentAndPropagatesType() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/list") { exchange ->
            val bytes = "<div class='item'><h1>Stage 20</h1><a href='/content'>read</a></div>".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/content") { exchange ->
            val bytes = "<article id='content'>RSS CONTENT</article>".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val source = RssSource(
                sourceUrl = baseUrl,
                sourceName = "stage20-source",
                ruleArticles = ".item",
                ruleTitle = "h1@text",
                ruleLink = "a@href",
                ruleContent = "#content@text",
                type = 2,
                userid = "stage20-user",
                loginCheckJs = "java.put('listChecked', 'yes'); result"
            )

            val (articles, nextUrl) = Rss().getArticles("测试", "$baseUrl/list", source, 1)
            val article = articles.single()
            assertEquals(null, nextUrl)
            assertEquals("Stage 20", article.title)
            assertEquals("$baseUrl/content", article.link)
            assertEquals(2, article.type)
            assertEquals("yes", article.getVariable("listChecked"))

            source.loginCheckJs = "java.put('contentChecked', 'yes'); result"
            val content = Rss().getContent(article, source.ruleContent.orEmpty(), source)
            assertEquals("RSS CONTENT", content)
            assertEquals("yes", article.getVariable("contentChecked"))
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun preservesNewSourceFieldsAndExecutesUrlOverrideRule() {
        val source = RssSource(
            sourceUrl = "https://stage20.example",
            sourceName = "stage20-fields",
            type = 1,
            searchUrl = "/search?q={{key}}",
            startHtml = "<main>start</main>",
            startJs = "start()",
            startStyle = "main{display:block}",
            preload = true,
            preloadJs = "preload()",
            cacheFirst = true,
            showWebLog = true,
            shouldOverrideUrlLoading = "url.indexOf('blocked') >= 0"
        )

        val restored = RssSource.fromJson(book.util.GSON.toJson(source))
        assertTrue(source.equal(restored))
        assertEquals("true", restored.shouldOverrideUrlLoadingdo(restored.shouldOverrideUrlLoading!!, "https://blocked.example"))
        assertEquals("false", restored.shouldOverrideUrlLoadingdo(restored.shouldOverrideUrlLoading!!, "https://allowed.example"))
    }

    @Test
    fun letsLoginCheckRecoverFromNetworkFailureWithReplacementResponse() = runBlocking {
        val unusedPort = ServerSocket(0).use { it.localPort }
        val failedUrl = "http://127.0.0.1:$unusedPort/list"
        val replacementBody = "<div class='item'><h1>Recovered</h1><a href='/content'>read</a></div>"
        val source = RssSource(
            sourceUrl = failedUrl,
            sourceName = "stage20-recovery",
            ruleArticles = ".item",
            ruleTitle = "h1@text",
            ruleLink = "a@href",
            loginCheckJs = "new Packages.book.util.http.StrResponse('http://recovered.example/list', " +
                "${book.util.GSON.toJson(replacementBody)})"
        )

        val (articles, _) = Rss().getArticles("恢复", failedUrl, source, 1)

        assertEquals("Recovered", articles.single().title)
        assertEquals("http://127.0.0.1:$unusedPort/content", articles.single().link)
    }
}
