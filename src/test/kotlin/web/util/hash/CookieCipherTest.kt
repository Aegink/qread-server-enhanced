package web.util.hash

import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 用户 Cookie 加密回归。
 *
 * 历史实现用写死在源码里的固定 key + 固定 IV（本项目开源，等于谁都能解开库里的 Cookie）。
 * 现在新数据走 `v2:` 格式（随机 IV），存量 hex 数据仍必须能解密。
 */
class CookieCipherTest {

    private val sample = "sessionid=abc123; uid=42; token=x"

    @Test
    fun roundTripsWithV2Format() {
        val encoded = EncryptUtils.aesEncode(sample)

        assertTrue(encoded.startsWith("v2:"), "新密文应带 v2 前缀，实际: $encoded")
        assertEquals(sample, EncryptUtils.aesDecrypted(encoded))
    }

    @Test
    fun usesRandomIvSoSameInputProducesDifferentCiphertext() {
        assertNotEquals(EncryptUtils.aesEncode(sample), EncryptUtils.aesEncode(sample))
    }

    @Test
    fun stillDecryptsLegacyHexCiphertext() {
        // 用历史固定 key/IV 造一段老格式密文，验证升级后仍能读
        val key = SecretKeySpec("jhznhuanbznjuyqa".toByteArray(StandardCharsets.UTF_8), "AES")
        val iv = IvParameterSpec("jnzhyavblkjhsquy".toByteArray(StandardCharsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, iv)
        val legacyHex = cipher.doFinal(sample.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

        assertEquals(sample, EncryptUtils.aesDecrypted(legacyHex))
    }
}
