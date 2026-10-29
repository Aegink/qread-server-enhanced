package web.cron

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeJobTest {

    @Test
    fun acceptsGsonLongAndDoubleCodeValues() {
        val job = CodeJob()

        assertTrue(job.isInvitationCodeRequired("{\"code\":0}"))
        assertFalse(job.isInvitationCodeRequired("{\"code\":1}"))
        assertTrue(job.isInvitationCodeRequired("{\"code\":0.0}"))
    }
}
