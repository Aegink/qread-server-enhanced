package web.config

import book.app.WebMessage
import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals

class WebMessageCompatibilityTest {

    @Test
    fun serializesWebViewDelayForTheClientBridge() {
        val json = Gson().toJson(
            WebMessage(
                msg = "webview",
                url = "https://stage38.example",
                title = "",
                id = "stage38",
                delayTime = 1750
            )
        )
        val message = Gson().fromJson(json, WebMessage::class.java)

        assertEquals(1750L, message.delayTime)
    }
}
