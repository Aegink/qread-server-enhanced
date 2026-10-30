package web.controller.api

import book.app.ToastMessage
import web.util.security.maskToken
import com.google.gson.Gson
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.noear.solon.annotation.Controller
import org.noear.solon.annotation.Inject
import org.noear.solon.net.annotation.ServerEndpoint
import org.noear.solon.net.websocket.WebSocket
import org.noear.solon.net.websocket.listener.SimpleWebSocketListener
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import web.mapper.UsersMapper
import web.mapper.UsertockenMapper
import web.util.ResponseManager
import web.util.auth.resolveUserToken
import web.util.hash.Md5
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread


@Controller
@ServerEndpoint("$routepath/ws")
class ApiWebSocket : SimpleWebSocketListener() {

    @Inject(value = "\${user.allowchange:false}", autoRefreshed=true)
    var allowchange:Boolean=false

    @Inject(value = "\${admin.gonggao:}", autoRefreshed=true)
    var gonggao: String=""

    companion object{
        val logger: Logger = LoggerFactory.getLogger(ApiWebSocket::class.java)
        private val ma = ConcurrentHashMap<String, WebSocketMap>()

        suspend fun WaitForResponse(correlationId: String, accessToken: String, timeout: Long = 120000): String? = coroutineScope {
            val deferredResult = ResponseManager.registerRequest(correlationId, accessToken)
            try {
                logger.info("Wait for $correlationId")
                withTimeoutOrNull(timeout) {
                    deferredResult.await()
                }.also { result ->
                    if (result == null) {
                        logger.info("Timeout reached for $correlationId")
                    }
                }
            }catch (e:Exception){
                logger.info("Error for $correlationId",e)
                null
            }.also {
                ResponseManager.cleanupExpiredRequest(correlationId)
            }
        }


        fun add(key: String, value: WebSocket, userid: String, sg: Boolean) {
            logger.info("WebSocket Adding $key")
            ma[key] = WebSocketMap(value, userid, sg)
        }

        fun remove(key: String, socket: WebSocket) {
            ma.computeIfPresent(key) { _, current ->
                if (current.ws === socket) null else current
            }
        }

        fun get(key:String):WebSocket?{
            return ma[key]?.ws
        }
        fun getall(key:String):WebSocketMap?{
            return ma[key]
        }

        fun getByuserids(userids: List<String>):List<WebSocket>{
            val userIdSet = userids.toHashSet()
            return ma.values
                .asSequence()
                .filter { it.userid in userIdSet }
                .map { it.ws }
                .toList()
        }

        fun getByuserid(userid:String):List<WebSocket>{
            return ma.values
                .asSequence()
                .filter { it.userid == userid }
                .map { it.ws }
                .toList()
        }


        fun colseByuserid(userid:String){
            getByuserid(userid).forEach { socket ->
                thread {
                    runCatching {
                        socket.close()
                    }
                }
            }
        }


        fun getByuserid(userid:String,tocken: String):List<WebSocket>{
            return ma.entries
                .asSequence()
                .filter { it.key != tocken && it.value.userid == userid }
                .map { it.value.ws }
                .toList()
        }
    }

    @Inject
    lateinit var usersMapper: UsersMapper

    @Inject
    lateinit var usertockenMapper: UsertockenMapper


    override fun onOpen(socket: WebSocket) {
        val accessToken: String = socket.param("id")
        logger.info("websocket Open ${maskToken(accessToken)}")
        if (accessToken.isBlank()) {
            socket.close()
            return
        }

        val tocken = resolveUserToken(usertockenMapper, accessToken)
        if (tocken == null) {
            logger.info("websocket tocken is null")
            socket.send(Gson().toJson(ToastMessage(msg = "logout", str="logout" )))
            socket.close()
            return
        }

        val user= tocken.userid?.let { usersMapper.getUser(it) }
        // id 为空的脏数据按「需重新登录」处理，避免下面 user.id!! 抛 NPE
        if (user == null || user.id.isNullOrBlank()) {
            logger.info("websocket user is null")
            socket.send(Gson().toJson(ToastMessage(msg = "logout", str="logout" )))
            socket.close()
            return
        }
        if((user.sourcemd5?:"").isEmpty() && user.source != 2){
            user.sourcemd5=Md5(System.currentTimeMillis().toString())
        }
        if((user.rssmd5?:"").isEmpty() && user.source != 2){
            user.rssmd5=Md5(System.currentTimeMillis().toString())
        }
        val sg: String? = socket.param("sg")
        add(accessToken, socket, user.id!!, !sg.isNullOrBlank())
        runCatching {
            socket.send(Gson().toJson(Md5Message(
                msg = "init",
                md5 = "",
            )))
            socket.send(Gson().toJson(Md5Message(
                msg = "source",
                md5 = user.source.let { if(it == 2 || it == 1) "1" else "0" },
            )))
            //将md5发送至客户端
            socket.send(Gson().toJson(Md5Message(
                msg = "bookmd5",
                md5 =user.bookmd5?:"",
            )))
            socket.send(Gson().toJson(Md5Message(
                msg = "sourcemd5",
                md5 =(user.sourcemd5?:"")+"${user.source}" ,
            )))
            socket.send(Gson().toJson(Md5Message(
                msg = "rssmd5",
                md5 =(user.rssmd5?:"")+"${user.source}" ,
            )))
            socket.send(Gson().toJson(Md5Message(
                msg = "tssmd5",
                md5 =user.tssmd5?:"",
            )))
            socket.send(Gson().toJson(Md5Message(
                msg = "replacemd5",
                md5 =user.replacemd5?:"",
            )))
            socket.send(Gson().toJson(Md5Message(
                msg = "groundmd5",
                md5 =user.groundmd5?:"",
            )))
            if(allowchange){
                socket.send(Gson().toJson(Md5Message(
                    msg = "allowchange",
                    md5 = "${user.source}",
                )))
            }else{
                socket.send(Gson().toJson(Md5Message(
                    msg = "allowchange",
                    md5 = "1",
                )))
            }
            if(gonggao.isNotBlank()){
                socket.send(Gson().toJson(Md5Message(
                    msg = "gonggao",
                    md5 = gonggao,
                )))
            }
        }

    }

    @Throws(IOException::class)
    override fun onMessage(socket: WebSocket, text: String) {
        //logger.info("我收到了：$text")
        //socket.send("我收到了：$text")
    }

    override  fun onClose(socket: WebSocket?){
        super.onClose(socket)
        socket?.let { currentSocket ->
            remove(currentSocket.param("id"), currentSocket)
        }
    }
}

class WebSocketMap(
    var ws:WebSocket,
    var userid:String,
    var sg: Boolean,
)

class Md5Message(
    var msg: String,
    var md5: String,
    var bookurl: String? = null
)
