package web.util.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QApiAuthGuardTest {

    @Test
    fun blocksAfterFiveFailuresAndUnlocksAfterTimeout() {
        var now = 1_000L
        val guard = QApiAuthGuard(clock = { now })

        repeat(4) {
            assertEquals(QApiAuthStatus.INVALID, guard.verify("client-a", "wrong", "secret"))
        }
        assertEquals(QApiAuthStatus.BLOCKED, guard.verify("client-a", "wrong", "secret"))
        assertEquals(QApiAuthStatus.BLOCKED, guard.verify("client-a", "secret", "secret"))

        now += 15 * 60 * 1000L
        assertEquals(QApiAuthStatus.VALID, guard.verify("client-a", "secret", "secret"))
        assertEquals(0, guard.trackedClientCount())
    }

    @Test
    fun successfulVerificationClearsEarlierFailures() {
        val guard = QApiAuthGuard()

        assertEquals(QApiAuthStatus.INVALID, guard.verify("client-b", null, "密钥"))
        assertEquals(QApiAuthStatus.INVALID, guard.verify("client-b", "wrong", "密钥"))
        assertEquals(QApiAuthStatus.VALID, guard.verify("client-b", "密钥", "密钥"))
        assertEquals(QApiAuthStatus.INVALID, guard.verify("client-b", "wrong", "密钥"))
    }

    @Test
    fun capsTrackedClientStateWithSharedOverflowBucket() {
        val guard = QApiAuthGuard(maxTrackedClients = 2)

        guard.verify("client-1", "wrong", "secret")
        guard.verify("client-2", "wrong", "secret")
        repeat(20) { guard.verify("client-${it + 3}", "wrong", "secret") }

        assertTrue(guard.trackedClientCount() <= 3)
    }
}
