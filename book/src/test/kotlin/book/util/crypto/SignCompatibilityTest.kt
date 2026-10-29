package book.util.crypto

import book.webBook.analyzeRule.JsEncodeUtils
import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SignCompatibilityTest {

    @Test
    fun configuresEncodedKeysAndSignsThroughJsFactory() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val sign = object : JsEncodeUtils {}.createSign("SHA256withRSA")

        assertSame(sign, sign.setPrivateKey(keyPair.private.encoded))
        assertSame(sign, sign.setPublicKey(keyPair.public.encoded))

        val content = "stage38-sign".encodeToByteArray()
        assertTrue(sign.verify(content, sign.sign(content)))
    }
}
