package book.util.http

import okhttp3.Interceptor
import okhttp3.Response
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

internal class DomainCircuitOpenException(host: String, retryAfterMillis: Long) : IOException(
    "域名 $host 暂时熔断，${(retryAfterMillis.coerceAtLeast(1) + 999) / 1000} 秒后重试"
)

internal class DomainCircuitBreaker(
    private val failureThreshold: Int = 3,
    private val initialOpenMillis: Long = 15 * 60 * 1000L,
    private val maxOpenMillis: Long = 6 * 60 * 60 * 1000L,
    private val maxTrackedHosts: Int = 4096,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private data class State(
        val failures: Int,
        val openCount: Int = 0,
        val openUntil: Long = 0,
        val probeInProgress: Boolean = false
    )

    private val states = ConcurrentHashMap<String, State>()

    init {
        require(failureThreshold > 0)
        require(initialOpenMillis > 0)
        require(maxOpenMillis >= initialOpenMillis)
        require(maxTrackedHosts > 0)
    }

    fun beforeRequest(host: String) {
        val key = normalize(host)
        val now = nowMillis()
        var blockedUntil = 0L
        states.computeIfPresent(key) { _, state ->
            when {
                state.openUntil > now -> {
                    blockedUntil = state.openUntil
                    state
                }
                state.openUntil > 0 && state.probeInProgress -> {
                    blockedUntil = now + 1000
                    state
                }
                state.openUntil > 0 -> state.copy(probeInProgress = true)
                else -> state
            }
        }
        if (blockedUntil > 0) {
            throw DomainCircuitOpenException(key, blockedUntil - now)
        }
    }

    fun recordSuccess(host: String) {
        states.remove(normalize(host))
    }

    fun recordFailure(host: String, error: Throwable): Boolean {
        val key = normalize(host)
        if (!isConnectionFailure(error)) {
            recordSuccess(key)
            return false
        }
        if (!states.containsKey(key) && states.size >= maxTrackedHosts) return false

        val now = nowMillis()
        var opened = false
        states.compute(key) { _, previous ->
            val failures = (previous?.failures ?: 0) + 1
            if (failures < failureThreshold) {
                State(failures = failures)
            } else {
                val openCount = (previous?.openCount ?: 0) + 1
                val duration = openDuration(openCount)
                opened = previous?.openUntil?.let { it <= now } ?: true
                State(
                    failures = failures,
                    openCount = openCount,
                    openUntil = now + duration
                )
            }
        }
        return opened
    }

    internal fun trackedHostCount(): Int = states.size

    private fun openDuration(openCount: Int): Long {
        var duration = initialOpenMillis
        repeat((openCount - 1).coerceAtMost(30)) {
            duration = min(maxOpenMillis, duration * 2)
        }
        return duration
    }

    private fun normalize(host: String): String = host.trim().lowercase()

    private fun isConnectionFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it is UnknownHostException || it is ConnectException || it is NoRouteToHostException
        }
}

internal class DomainCircuitBreakerInterceptor(
    private val breaker: DomainCircuitBreaker = DomainCircuitBreaker()
) : Interceptor {
    private val logger = LoggerFactory.getLogger(DomainCircuitBreakerInterceptor::class.java)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        breaker.beforeRequest(host)
        return try {
            chain.proceed(request).also { breaker.recordSuccess(host) }
        } catch (error: IOException) {
            if (breaker.recordFailure(host, error)) {
                logger.warn("域名连接连续失败，已临时熔断: {}", host)
            }
            throw error
        }
    }
}
