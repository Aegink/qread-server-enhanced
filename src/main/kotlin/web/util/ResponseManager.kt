package web.util

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

// 请求响应管理器（线程安全）
object ResponseManager {
    private data class PendingResponse(
        val accessToken: String,
        val deferred: CompletableDeferred<String>
    )

    private val pendingRequests = ConcurrentHashMap<String, PendingResponse>()

    fun registerRequest(correlationId: String, accessToken: String): CompletableDeferred<String> {
        return CompletableDeferred<String>().also { deferred ->
            pendingRequests[correlationId] = PendingResponse(accessToken, deferred)
        }
    }

    fun completeRequest(correlationId: String, accessToken: String, result: String): Boolean {
        val pending = pendingRequests[correlationId] ?: return false
        if (pending.accessToken != accessToken || !pendingRequests.remove(correlationId, pending)) {
            return false
        }
        pending.deferred.complete(result)
        return true
    }

    fun cleanupExpiredRequest(correlationId: String) {
        pendingRequests.remove(correlationId)?.deferred?.cancel()
    }
}