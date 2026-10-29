package book.webBook.analyzeRule

import book.app.App
import book.model.BookSource
import book.util.GSON
import book.util.fromJsonObject
import book.util.http.StrResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebJsRuleTest {

    @Test
    fun dispatchesWebJsRulesThroughServerBridgeForAllResultTypes() {
        synchronized(App) {
            val originalWebview = App.webview
            val calls = mutableListOf<WebViewCall>()
            App.webview = { html, url, js, token, header, _, _, _ ->
                calls += WebViewCall(html, url, js, token, header)
                val body = when {
                    js?.contains("stringRule()") == true -> "seed-web"
                    js?.contains("listRule()") == true -> "[\"A\",\"B\"]"
                    js?.contains("objectRule()") == true -> "{\"name\":\"value\"}"
                    js?.contains("objectsRule()") == true -> "[{\"id\":1},{\"id\":2}]"
                    else -> error("未识别的 WebJs 测试脚本：$js")
                }
                StrResponse(url.orEmpty(), body)
            }

            try {
                val source = BookSource(
                    bookSourceName = "stage11-source",
                    bookSourceUrl = "https://stage11.example",
                    header = "{\"X-Stage\":\"11\"}",
                    userid = "stage11-user",
                    usertocken = "stage11-token"
                )
                val analyzeRule = AnalyzeRule(source = source)
                    .setContent("<p>seed</p>", "https://stage11.example/page")

                assertEquals("seed-web", analyzeRule.getString("p@text@webjs:stringRule()"))
                assertEquals(listOf("A", "B"), analyzeRule.getStringList("p@text@webjs:listRule()"))
                assertEquals("value", (analyzeRule.getElement("@webjs:objectRule()") as Map<*, *>)["name"])
                assertEquals(
                    listOf(1, 2),
                    analyzeRule.getElements("@webjs:objectsRule()").map {
                        ((it as Map<*, *>)["id"] as Number).toInt()
                    }
                )

                assertEquals(4, calls.size)
                assertTrue(calls.all { it.html == "<p>seed</p>" })
                assertTrue(calls.all { it.url == "https://stage11.example/page" })
                assertTrue(calls.all { it.token == "stage11-token" })
                assertTrue(calls.all {
                    GSON.fromJsonObject<Map<String, String>>(it.header).getOrNull()?.get("X-Stage") == "11"
                })
                assertTrue(calls.first().js?.contains("var result = \"seed\";") == true)
            } finally {
                App.webview = originalWebview
            }
        }
    }

    private data class WebViewCall(
        val html: String?,
        val url: String?,
        val js: String?,
        val token: String,
        val header: String
    )
}
