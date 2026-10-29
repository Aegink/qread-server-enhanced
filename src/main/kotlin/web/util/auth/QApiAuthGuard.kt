package web.util.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class QApiAuthStatus {
    VALID,
    INVALID,
    BLOCKED
}

class QApiAuthGuard(
    private val maxFailures: Int = 5,
    private val windowMillis: Long = 10 * 60 * 1000L,
    private val blockMillis: Long = 15 * 60 * 1000L,
    private val maxTrackedClients: Int = 10_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class FailureState(
        val failures: Int,
        val windowStartedAt: Long,
        val blockedUntil: Long
    )

    private val failureStates = ConcurrentHashMap<String, FailureState>()
    private val operationCount = AtomicInteger()

    init {
        require(maxFailures > 0) { "maxFailures 必须大于 0" }
        require(windowMillis > 0) { "windowMillis 必须大于 0" }
        require(blockMillis > 0) { "blockMillis 必须大于 0" }
        require(maxTrackedClients > 0) { "maxTrackedClients 必须大于 0" }
    }

    fun verify(clientId: String, provided: String?, expected: String): QApiAuthStatus {
        val now = clock()
        cleanupExpiredStates(now)
        val key = trackedKey(clientId)
        val matches = MessageDigest.isEqual(
            provided.orEmpty().toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8)
        )
        var status = QApiAuthStatus.INVALID

        failureStates.compute(key) { _, previous ->
            when {
                previous != null && previous.blockedUntil > now -> {
                    status = QApiAuthStatus.BLOCKED
                    previous
                }

                matches -> {
                    status = QApiAuthStatus.VALID
                    null
                }

                else -> {
                    val activeWindow = previous?.takeIf {
                        now - it.windowStartedAt < windowMillis
                    }
                    val failures = (activeWindow?.failures ?: 0) + 1
                    val blocked = failures >= maxFailures
                    status = if (blocked) QApiAuthStatus.BLOCKED else QApiAuthStatus.INVALID
                    FailureState(
                        failures = failures,
                        windowStartedAt = activeWindow?.windowStartedAt ?: now,
                        blockedUntil = if (blocked) now + blockMillis else 0
                    )
                }
            }
        }
        return status
    }

    internal fun trackedClientCount(): Int = failureStates.size

    private fun trackedKey(clientId: String): String {
        val normalized = clientId.ifBlank { UNKNOWN_CLIENT }
        return if (failureStates.containsKey(normalized) || failureStates.size < maxTrackedClients) {
            normalized
        } else {
            OVERFLOW_CLIENT
        }
    }

    private fun cleanupExpiredStates(now: Long) {
        if (operationCount.incrementAndGet() and 127 != 0) return
        failureStates.entries.removeIf { (_, state) ->
            state.blockedUntil <= now && now - state.windowStartedAt >= windowMillis
        }
    }

    private companion object {
        const val UNKNOWN_CLIENT = "unknown"
        const val OVERFLOW_CLIENT = "overflow"
    }
}
