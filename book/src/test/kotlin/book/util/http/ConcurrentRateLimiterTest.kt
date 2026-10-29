package book.util.http

import book.model.BookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ConcurrentRateLimiterTest {

    @AfterTest
    fun clearRecords() {
        ConcurrentRateLimiter.clearRecords()
    }

    @Test
    fun doesNotAllowAnExtraRequestInTheSameWindow() = runBlocking {
        val source = BookSource(
            bookSourceUrl = "https://rate-limit.test/source",
            concurrentRate = "1/200"
        )
        val limiter = ConcurrentRateLimiter(source)

        val elapsed = measureTimeMillis {
            listOf(
                async { limiter.getConcurrentRecord() },
                async { limiter.getConcurrentRecord() }
            ).awaitAll()
        }

        assertTrue(elapsed >= 150, "second request should wait for the next window: ${elapsed}ms")
    }

    @Test
    fun numericRateLimitsStartIntervalInsteadOfRequestLifetime() = runBlocking {
        val source = BookSource(
            bookSourceUrl = "https://rate-limit.test/numeric",
            concurrentRate = "200"
        )
        val limiter = ConcurrentRateLimiter(source)
        val startedAt = System.nanoTime()
        val secondStartedAt = AtomicLong()

        val first = async(Dispatchers.Default) {
            limiter.withLimit {
                Thread.sleep(500)
            }
        }
        delay(20)
        val second = async(Dispatchers.Default) {
            limiter.withLimit {
                secondStartedAt.set(System.nanoTime())
            }
        }
        awaitAll(first, second)

        val secondStartMillis = (secondStartedAt.get() - startedAt) / 1_000_000
        assertTrue(secondStartMillis >= 150, "numeric rate must keep the configured start interval")
        assertTrue(secondStartMillis < 450, "numeric rate must not wait for the previous request to finish")
    }

    @Test
    fun handlesConcurrentRequestsWithoutCorruptingTheRecordMap() = runBlocking {
        val source = BookSource(
            bookSourceUrl = "https://rate-limit.test/concurrent",
            concurrentRate = "4/100"
        )
        val limiter = ConcurrentRateLimiter(source)

        val elapsed = measureTimeMillis {
            List(20) { async { limiter.getConcurrentRecord() } }.awaitAll()
        }

        assertTrue(elapsed >= 350, "20 requests at 4/100ms should span multiple windows: ${elapsed}ms")
    }
}
