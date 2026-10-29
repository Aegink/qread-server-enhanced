package book.util.help

import book.webBook.exception.NoStackTraceException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * 书源级并发工具（对齐 LegadoTeam/legado 的 `SourceLock`，供 JS 的 `java.lock/tick/singleFlight` 使用）。
 *
 * 三个能力都按 "书源 key + 名字" 隔离，不同用户/不同书源互不影响：
 * - [tick]：同一 key 的调用计数，返回自增后的值（书源用它做轮询、第几次访问之类）。
 * - [lock]：同一 key 互斥执行，超过超时时间抛异常。
 * - [singleFlight]：同一 key 同时只跑一个，重复调用在等待窗口内直接跳过（防止并发重复请求打爆站点）。
 */
object SourceLock {

    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val flights = ConcurrentHashMap<String, ReentrantLock>()
    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    fun tick(key: String): Int = counters.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()

    fun lock(key: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, action: () -> Unit) {
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        val acquired = runCatching { lock.tryLock(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS) }
            .getOrElse { false }
        if (!acquired) {
            throw NoStackTraceException("java.lock 等待超时: $key")
        }
        try {
            action()
        } finally {
            lock.unlock()
        }
    }

    /**
     * 返回 true 表示本次调用真正执行了 action；false 表示同一 key 已有调用在执行，本次被合并跳过。
     */
    fun singleFlight(key: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, action: () -> Unit): Boolean {
        val lock = flights.computeIfAbsent(key) { ReentrantLock() }
        val acquired = runCatching { lock.tryLock(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS) }
            .getOrElse { false }
        if (!acquired) return false
        try {
            action()
            return true
        } finally {
            lock.unlock()
        }
    }

    internal fun resetForTest(key: String) {
        counters.remove(key)
        locks.remove(key)
        flights.remove(key)
    }

    private const val DEFAULT_TIMEOUT_MS = 15_000L
}
