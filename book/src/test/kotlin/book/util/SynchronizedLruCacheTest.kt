package book.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SynchronizedLruCacheTest {

    @Test
    fun evictsLeastRecentlyUsedEntry() {
        val cache = SynchronizedLruCache<String, Int>(2)
        cache.put("a", 1)
        cache.put("b", 2)

        assertEquals(1, cache["a"])
        cache.put("c", 3)

        assertNull(cache["b"])
        assertEquals(setOf("a", "c"), cache.keys())
    }

    @Test
    fun getsValidValueAndRemovesInvalidValueAtomically() {
        val cache = SynchronizedLruCache<String, String>(2)
        cache.put("valid", "keep")
        cache.put("invalid", "remove")

        assertEquals("keep", cache.getOrRemove("valid") { it == "keep" })
        assertNull(cache.getOrRemove("invalid") { it == "keep" })
        assertNull(cache["invalid"])
    }

    @Test
    fun staysWithinCapacityDuringConcurrentAccess() {
        val cache = SynchronizedLruCache<Int, Int>(16)
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)

        repeat(8) { worker ->
            executor.submit {
                start.await()
                repeat(1_000) { index ->
                    val key = worker * 1_000 + index
                    cache.put(key, key)
                    cache[key]
                }
            }
        }

        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(cache.size() <= 16)
    }
}
