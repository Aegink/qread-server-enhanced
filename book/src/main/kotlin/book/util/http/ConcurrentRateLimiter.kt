package book.util.http

import book.model.BaseSource
import book.webBook.analyzeRule.AnalyzeUrl.ConcurrentRecord
import book.webBook.exception.ConcurrentException
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap


class ConcurrentRateLimiter(val source: BaseSource?) {

    companion object {
        private val concurrentRecordMap = ConcurrentHashMap<String, ConcurrentRecord>()

        internal fun clearRecords() {
            concurrentRecordMap.clear()
        }
    }

    /**
     * 开始访问,并发判断
     */
    @Throws(ConcurrentException::class)
    private fun fetchStart(): ConcurrentRecord? {
        source ?: return null
        var concurrentRate = source.concurrentRate
        if (concurrentRate.isNullOrEmpty() || concurrentRate == "0") {
            return null
        }
        var rateIndex = concurrentRate.indexOf("/")
        if (rateIndex == -1) {
            concurrentRate = "1/${source.concurrentRate}"
            rateIndex = concurrentRate.indexOf("/")
        }
        var isNewRecord = false
        val fetchRecord = concurrentRecordMap.compute(source.getKey()) { _, record ->
            record ?: ConcurrentRecord(rateIndex > 0, System.currentTimeMillis(), 1).also {
                isNewRecord = true
            }
        } ?: return null
        if (isNewRecord) return fetchRecord
        val waitTime: Long = synchronized(fetchRecord) {
            try {
                if (!fetchRecord.isConcurrent) {
                    //并发控制非 次数/毫秒
                    if (fetchRecord.frequency > 0) {
                        //已经有访问线程,直接等待
                        return@synchronized concurrentRate.toLong()
                    }
                    //没有线程访问,判断还剩多少时间可以访问
                    val nextTime = fetchRecord.time + concurrentRate.toLong()
                    if (System.currentTimeMillis() >= nextTime) {
                        fetchRecord.time = System.currentTimeMillis()
                        fetchRecord.frequency = 1
                        return@synchronized 0L
                    }
                    return@synchronized nextTime - System.currentTimeMillis()
                } else {
                    //并发控制为 次数/毫秒
                    val interval = concurrentRate.substring(rateIndex + 1).toLong()
                    val nextTime = fetchRecord.time + interval
                    if (System.currentTimeMillis() >= nextTime) {
                        //已经过了限制时间,重置开始时间
                        fetchRecord.time = System.currentTimeMillis()
                        fetchRecord.frequency = 1
                        return@synchronized 0L
                    }
                    val accessLimit = concurrentRate.substring(0, rateIndex).toInt()
                    if (fetchRecord.frequency >= accessLimit) {
                        return@synchronized nextTime - System.currentTimeMillis()
                    } else {
                        fetchRecord.frequency += 1
                        return@synchronized 0L
                    }
                }
            } catch (_: Exception) {
                return@synchronized 0L
            }
        }
        if (waitTime > 0) {
            throw ConcurrentException(
                "根据并发率还需等待${waitTime}毫秒才可以访问",
                waitTime = waitTime.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
        }
        return fetchRecord
    }

    /**
     * 访问结束
     */
    fun fetchEnd(concurrentRecord: ConcurrentRecord?) {
        if (concurrentRecord != null && !concurrentRecord.isConcurrent) {
            synchronized(concurrentRecord) {
                concurrentRecord.frequency -= 1
            }
        }
    }

    /**
     * 获取并发记录，若处于并发限制状态下则会等待
     */
    suspend fun getConcurrentRecord(): ConcurrentRecord? {
        while (true) {
            try {
                return fetchStart()
            } catch (e: ConcurrentException) {
                delay(e.waitTime.toLong())
            }
        }
    }

    fun getConcurrentRecordBlocking(): ConcurrentRecord? {
        while (true) {
            try {
                return fetchStart()
            } catch (e: ConcurrentException) {
                Thread.sleep(e.waitTime.toLong())
            }
        }
    }

    suspend inline fun <T> withLimit(block: () -> T): T {
        val concurrentRecord = getConcurrentRecord()
        try {
            return block()
        } finally {
            fetchEnd(concurrentRecord)
        }
    }

    inline fun <T> withLimitBlocking(block: () -> T): T {
        val concurrentRecord = getConcurrentRecordBlocking()
        try {
            return block()
        } finally {
            fetchEnd(concurrentRecord)
        }
    }

}
