package web.util.admin

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SecureRandomStringTest {

    @Test
    fun generatesRequestedLengthsAndCharacterSets() {
        val lettersAndDigits = getRandomString(64)
        val digits = getRandomNumString(64)

        assertEquals(64, lettersAndDigits.length)
        assertEquals(64, digits.length)
        assertTrue(lettersAndDigits.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXTZabcdefghiklmnopqrstuvwxyz0123456789" })
        assertTrue(digits.all(Char::isDigit))
    }

    @Test
    fun delegatesEveryCharacterSelectionToSecureRandom() {
        val random = CountingSecureRandom()

        val result = secureRandomString(32, "ab", random)

        assertEquals(32, result.length)
        assertEquals(32, random.calls)
    }

    @Test
    fun preservesMailCodeAndInvitationCodeFormats() {
        val mailCode = getMailCode()
        val invitationCodes = getcodes(100)

        assertTrue(mailCode.matches(Regex("\\d{6}")))
        assertEquals(100, invitationCodes.size)
        assertEquals(100, invitationCodes.toSet().size)
        assertTrue(invitationCodes.all { it.matches(Regex("\\d{13}[A-Za-z0-9]{6}")) })
    }

    @Test
    fun rejectsInvalidGeneratorArguments() {
        assertFailsWith<IllegalArgumentException> { secureRandomString(-1, "abc", SecureRandom()) }
        assertFailsWith<IllegalArgumentException> { secureRandomString(1, "", SecureRandom()) }
    }

    private class CountingSecureRandom : SecureRandom() {
        var calls = 0

        override fun nextInt(bound: Int): Int {
            calls++
            return super.nextInt(bound)
        }
    }
}
