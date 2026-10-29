package book.model

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SharedJsScopeTest {

    @AfterTest
    fun clearCache() {
        SharedJsScope.clearCache()
    }

    @Test
    fun keepsScopesIsolatedByUser() {
        val js = "var sharedValue = 1;"
        val userOneScope = SharedJsScope.getScope(js, "user-1")
        val sameUserScope = SharedJsScope.getScope(js, "user-1")
        val userTwoScope = SharedJsScope.getScope(js, "user-2")

        assertSame(userOneScope, sameUserScope)
        assertNotSame(userOneScope, userTwoScope)
    }

    @Test
    fun initializesTheSameScopeOnlyOnceUnderConcurrency() {
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val scopes = Collections.synchronizedList(mutableListOf<Any?>())
        val tasks = List(8) {
            executor.submit {
                start.await()
                scopes += SharedJsScope.getScope("var sharedValue = 1;", "same-user")
            }
        }

        start.countDown()
        tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1, scopes.map { System.identityHashCode(it) }.toSet().size)
    }

    @Test
    fun limitsTheNumberOfCachedScopeKeys() {
        repeat(40) { index ->
            SharedJsScope.getScope("var value$index = $index;", "user-$index")
        }

        assertTrue(SharedJsScope.cacheSize() <= 16)
        assertEquals(16, SharedJsScope.cacheSize())
    }
}
