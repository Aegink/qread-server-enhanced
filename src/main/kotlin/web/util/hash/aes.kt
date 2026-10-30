package web.util.hash

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 用户 Cookie 的落库加密。
 *
 * 历史实现用的是**写死在源码里的固定 key + 固定 IV**：本项目开源，等于任何人都能解开数据库里的
 * Cookie；固定 IV 还会让相同明文产生相同密文。现在：
 *
 * - 新写入一律走 `v2:` 格式：**每次随机 IV** + Base64（`v2:<iv>:<cipher>`）；
 * - key 从部署配置读取（环境变量 `QREAD_COOKIE_KEY` 或 JVM 参数 `-Dqread.cookieKey`），
 *   长度为 16/24/32 字节才生效；
 * - 没有配置时退回历史固定 key，保证**存量数据仍能解密**（只在未配置时打一次告警）。
 */
class EncryptUtils {
    companion object {
        /** 历史固定 key/IV：仅用于解密存量数据与未配置密钥时的兜底。 */
        private const val LEGACY_KEY = "jhznhuanbznjuyqa"
        private const val LEGACY_IV = "jnzhyavblkjhsquy"
        private const val V2_PREFIX = "v2:"

        private val configuredKey: String? = resolveConfiguredKey()
        private var warnedLegacy = false

        /** 新写入使用：配置了合法 key 就用配置，否则退回历史 key（并告警一次）。 */
        private fun currentKey(): ByteArray {
            configuredKey?.let { return it.toByteArray(StandardCharsets.UTF_8) }
            if (!warnedLegacy) {
                warnedLegacy = true
                System.err.println(
                    "Cookie 加密仍在使用内置默认密钥，建议设置环境变量 QREAD_COOKIE_KEY（16/24/32 字节）"
                )
            }
            return LEGACY_KEY.toByteArray(StandardCharsets.UTF_8)
        }

        private fun resolveConfiguredKey(): String? {
            val raw = System.getenv("QREAD_COOKIE_KEY")?.takeIf { it.isNotBlank() }
                ?: System.getProperty("qread.cookieKey")?.takeIf { it.isNotBlank() }
                ?: return null
            return if (raw.toByteArray(StandardCharsets.UTF_8).size in intArrayOf(16, 24, 32)) {
                raw
            } else {
                System.err.println("QREAD_COOKIE_KEY 长度非法（需 16/24/32 字节），已忽略并使用内置默认密钥")
                null
            }
        }

        fun aesEncode(content: String): String {
            val ivBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(currentKey(), "AES"), IvParameterSpec(ivBytes))
            val encrypted = cipher.doFinal(content.toByteArray(StandardCharsets.UTF_8))
            val encoder = Base64.getEncoder()
            return V2_PREFIX + encoder.encodeToString(ivBytes) + ":" + encoder.encodeToString(encrypted)
        }

        fun aesDecrypted(data: String): String {
            if (data.startsWith(V2_PREFIX)) {
                val parts = data.removePrefix(V2_PREFIX).split(":")
                require(parts.size == 2) { "Cookie 密文格式错误" }
                val decoder = Base64.getDecoder()
                val iv = decoder.decode(parts[0])
                val cipherBytes = decoder.decode(parts[1])
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(currentKey(), "AES"), IvParameterSpec(iv))
                return String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
            }
            // 存量数据：固定 key + 固定 IV + Hex。始终用历史 key，不受新配置影响。
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(LEGACY_KEY.toByteArray(StandardCharsets.UTF_8), "AES"),
                IvParameterSpec(LEGACY_IV.toByteArray(StandardCharsets.UTF_8))
            )
            return String(cipher.doFinal(hexToBytes(data)), StandardCharsets.UTF_8)
        }

        private fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "Cookie 密文格式错误" }
            val data = ByteArray(hex.length / 2)
            var i = 0
            while (i < hex.length) {
                data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                        Character.digit(hex[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }
    }
}
