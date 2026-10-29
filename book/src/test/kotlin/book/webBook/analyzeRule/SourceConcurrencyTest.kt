package book.webBook.analyzeRule

import book.model.BookSource
import book.util.help.SourceLock
import book.webBook.exception.NoStackTraceException
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 书源级并发工具：`java.tick` / `java.lock` / `java.singleFlight`（对齐 LegadoTeam/legado 的 SourceLock）。
 *
 * 服务端比 App 端更需要这三个能力：同一书源会被多个用户同时拉，`singleFlight` 能把重复请求合并，
 * `lock` 能给必须串行的登录/签到流程加互斥，`tick` 提供稳定计数。
 */
class SourceConcurrencyTest {

    private val key = "http://127.0.0.1:1@unit"
    private val otherKey = "http://127.0.0.1:1@other"

    @Test
    fun tickCountsPerKey() {
        SourceLock.resetForTest(key)
        SourceLock.resetForTest(otherKey)

        assertEquals(1, SourceLock.tick(key))
        assertEquals(2, SourceLock.tick(key))
        assertEquals(1, SourceLock.tick(otherKey))
    }

    @Test
    fun lockIsExclusiveAndTimesOut() {
        SourceLock.resetForTest(key)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread {
            SourceLock.lock(key, 2_000) {
                holding.countDown()
                release.await()
            }
        }
        try {
            holding.await()
            assertFailsWith<NoStackTraceException> {
                SourceLock.lock(key, 100) { }
            }
        } finally {
            release.countDown()
            holder.join(3_000)
        }
    }

    @Test
    fun singleFlightSkipsConcurrentCaller() {
        SourceLock.resetForTest(key)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        var executed = 0
        val holder = thread {
            SourceLock.singleFlight(key, 2_000) {
                executed++
                holding.countDown()
                release.await()
            }
        }
        try {
            holding.await()
            val skipped = SourceLock.singleFlight(key, 50) { executed++ }
            assertTrue(!skipped, "并发调用应被合并跳过")
            assertEquals(1, executed)
        } finally {
            release.countDown()
            holder.join(3_000)
        }
    }

    @Test
    fun jsBindingsExposeTickAndLock() {
        val rule = AnalyzeRule(
            source = BookSource(bookSourceName = "concurrency", bookSourceUrl = "http://127.0.0.1:1")
        )
        val jsKey = "http://127.0.0.1:1@js"
        SourceLock.resetForTest(jsKey)

        assertEquals("1", rule.evalJS("java.tick('js')").toString())
        assertEquals("2", rule.evalJS("java.tick('js')").toString())

        val locked = rule.evalJS(
            "var hits = 0; java.lock('js-lock', function(){ hits = hits + 1 }); hits"
        )
        assertEquals(1.0, (locked as Number).toDouble())
    }
}
