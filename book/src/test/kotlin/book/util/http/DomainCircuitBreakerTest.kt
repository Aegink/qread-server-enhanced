package book.util.http

import org.junit.jupiter.api.Test
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Dns
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomainCircuitBreakerTest {
    @Test
    fun okhttpInterceptorStopsDnsCallsWhileCircuitIsOpen() {
        var dnsCalls = 0
        val breaker = DomainCircuitBreaker(initialOpenMillis = 100, maxOpenMillis = 400)
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    dnsCalls++
                    throw UnknownHostException(hostname)
                }
            })
            .addInterceptor(DomainCircuitBreakerInterceptor(breaker))
            .build()
        val request = Request.Builder().url("http://dead.example/").build()

        repeat(3) {
            assertFailsWith<UnknownHostException> { client.newCall(request).execute() }
        }
        assertFailsWith<DomainCircuitOpenException> { client.newCall(request).execute() }

        assertEquals(3, dnsCalls)
        assertTrue(okHttpClient.interceptors.any { it is DomainCircuitBreakerInterceptor })
    }

    @Test
    fun opensAfterThreeConsecutiveConnectionFailures() {
        val breaker = DomainCircuitBreaker(initialOpenMillis = 100, maxOpenMillis = 400)

        assertFalse(breaker.recordFailure("dead.example", UnknownHostException()))
        assertFalse(breaker.recordFailure("dead.example", UnknownHostException()))
        assertTrue(breaker.recordFailure("dead.example", UnknownHostException()))

        assertFailsWith<DomainCircuitOpenException> { breaker.beforeRequest("DEAD.EXAMPLE") }
    }

    @Test
    fun successfulRequestClearsFailureHistory() {
        val breaker = DomainCircuitBreaker(initialOpenMillis = 100, maxOpenMillis = 400)
        repeat(2) { breaker.recordFailure("flaky.example", UnknownHostException()) }

        breaker.recordSuccess("flaky.example")
        repeat(2) { breaker.recordFailure("flaky.example", UnknownHostException()) }

        breaker.beforeRequest("flaky.example")
    }

    @Test
    fun halfOpenAllowsOneProbeAndFailedProbeUsesLongerBackoff() {
        var now = 1_000L
        val breaker = DomainCircuitBreaker(
            initialOpenMillis = 100,
            maxOpenMillis = 400,
            nowMillis = { now }
        )
        repeat(3) { breaker.recordFailure("dead.example", UnknownHostException()) }

        now += 100
        breaker.beforeRequest("dead.example")
        assertFailsWith<DomainCircuitOpenException> { breaker.beforeRequest("dead.example") }
        assertTrue(breaker.recordFailure("dead.example", UnknownHostException()))

        now += 199
        assertFailsWith<DomainCircuitOpenException> { breaker.beforeRequest("dead.example") }
        now += 1
        breaker.beforeRequest("dead.example")
    }

    @Test
    fun unrelatedErrorsDoNotOpenCircuit() {
        val breaker = DomainCircuitBreaker(initialOpenMillis = 100, maxOpenMillis = 400)

        repeat(10) { assertFalse(breaker.recordFailure("valid.example", IOException("bad body"))) }

        breaker.beforeRequest("valid.example")
        assertEquals(0, breaker.trackedHostCount())
    }

    @Test
    fun trackedHostsAreBounded() {
        val breaker = DomainCircuitBreaker(
            initialOpenMillis = 100,
            maxOpenMillis = 400,
            maxTrackedHosts = 2
        )

        breaker.recordFailure("one.example", UnknownHostException())
        breaker.recordFailure("two.example", UnknownHostException())
        breaker.recordFailure("three.example", UnknownHostException())

        assertEquals(2, breaker.trackedHostCount())
    }
}
