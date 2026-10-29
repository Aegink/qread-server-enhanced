package web.controller.api

import org.noear.solon.core.util.MultiMap
import org.noear.solon.net.websocket.WebSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ApiWebSocketTest {

    @AfterTest
    fun cleanup() {
        val current = ApiWebSocket.get("same-token")
        if (current != null) {
            ApiWebSocket.remove("same-token", current)
        }
    }

    @Test
    fun closingOldSocketDoesNotRemoveReplacement() {
        val oldSocket = TestWebSocket("same-token")
        val newSocket = TestWebSocket("same-token")

        ApiWebSocket.add("same-token", oldSocket, "user-1", false)
        ApiWebSocket.add("same-token", newSocket, "user-1", false)
        ApiWebSocket.remove("same-token", oldSocket)

        assertSame(newSocket, ApiWebSocket.get("same-token"))
    }

    @Test
    fun concurrentUpdatesDoNotCorruptTheConnectionTable() {
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val tasks = (0 until 8).map { worker ->
            executor.submit {
                start.await()
                repeat(500) { iteration ->
                    val token = "token-${worker % 4}"
                    val socket = TestWebSocket(token)
                    ApiWebSocket.add(token, socket, "user-$worker", false)
                    ApiWebSocket.get(token)
                    ApiWebSocket.remove(token, socket)
                }
            }
        }
        start.countDown()
        tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
    }

    private class TestWebSocket(private val token: String) : WebSocket {
        private val attributes = mutableMapOf<String, Any>()
        private val params = MultiMap<String>()
        private var socketName = ""
        private var socketPath = "/api/5/ws"
        private var idleTimeout = 0L

        init {
            params.add("id", token)
        }

        override fun id(): String = token
        override fun name(): String = socketName
        override fun nameAs(name: String) {
            socketName = name
        }
        override fun isValid(): Boolean = true
        override fun isSecure(): Boolean = false
        override fun url(): String = "ws://127.0.0.1$socketPath?id=$token"
        override fun path(): String = socketPath
        override fun pathNew(path: String) {
            socketPath = path
        }
        override fun paramMap(): MultiMap<String> = params
        override fun param(name: String): String = params[name]
        override fun paramOrDefault(name: String, defaultValue: String): String = params[name] ?: defaultValue
        override fun param(name: String, value: String) {
            params.put(name, value)
        }
        override fun remoteAddress(): InetSocketAddress = InetSocketAddress("127.0.0.1", 10000)
        override fun localAddress(): InetSocketAddress = InetSocketAddress("127.0.0.1", 8080)
        override fun attrMap(): MutableMap<String, Any> = attributes
        override fun attrHas(name: String): Boolean = attributes.containsKey(name)
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> attr(name: String): T = attributes[name] as T
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> attrOrDefault(name: String, defaultValue: T): T = attributes[name] as? T ?: defaultValue
        override fun <T : Any?> attr(name: String, value: T) {
            if (value == null) attributes.remove(name) else attributes[name] = value
        }
        override fun getIdleTimeout(): Long = idleTimeout
        override fun setIdleTimeout(timeout: Long) {
            idleTimeout = timeout
        }
        override fun send(text: String): Future<Void> = CompletableFuture.completedFuture(null)
        override fun send(buffer: ByteBuffer): Future<Void> = CompletableFuture.completedFuture(null)
        override fun close() = Unit
        override fun close(code: Int, reason: String) = Unit
        override fun sendPing(): Future<Void> = CompletableFuture.completedFuture(null)
        override fun sendPong(): Future<Void> = CompletableFuture.completedFuture(null)
    }
}
