package web.util.admin

import cn.hutool.crypto.digest.BCrypt
import org.noear.solon.core.handle.Context
import web.util.hash.Sha256
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Date

private const val alphaNumericChars = "ABCDEFGHIJKLMNOPQRSTUVWXTZabcdefghiklmnopqrstuvwxyz0123456789"
private const val numericChars = "0123456789"
private val secureRandom = SecureRandom()
private const val adminCsrfSessionKey = "adminCsrfToken"

internal fun secureRandomString(length: Int, charset: String, random: SecureRandom = secureRandom): String {
    require(length >= 0) { "length must not be negative" }
    require(charset.isNotEmpty()) { "charset must not be empty" }
    return buildString(length) {
        repeat(length) {
            append(charset[random.nextInt(charset.length)])
        }
    }
}

fun getRandomString(length: Int): String {
    return secureRandomString(length, alphaNumericChars)
}

fun getRandomNumString(length: Int): String {
    return secureRandomString(length, numericChars)
}


fun loginok(username:String, ctx: Context){
    ctx.sessionReset()
    ctx.sessionSet("username", username)
    rotateAdminCsrfToken(ctx)
}

fun logout(ctx: Context) {
    ctx.sessionClear()
    ctx.sessionReset()
}

fun islogin(ctx: Context) :Boolean =run{
    ctx.session("username") != null && ctx.session("username").toString().isNotBlank()
}

fun getOrCreateAdminCsrfToken(ctx: Context): String {
    val existing = ctx.session(adminCsrfSessionKey)?.toString()
    return existing?.takeIf { it.isNotBlank() } ?: rotateAdminCsrfToken(ctx)
}

fun verifyAdminCsrfToken(ctx: Context): Boolean {
    val expected = ctx.session(adminCsrfSessionKey)?.toString()?.takeIf { it.isNotBlank() } ?: return false
    val actual = ctx.header("X-CSRF-Token")?.takeIf { it.isNotBlank() }
        ?: ctx.param("_csrf")?.takeIf { it.isNotBlank() }
        ?: return false
    return MessageDigest.isEqual(
        actual.toByteArray(StandardCharsets.US_ASCII),
        expected.toByteArray(StandardCharsets.US_ASCII)
    )
}

private fun rotateAdminCsrfToken(ctx: Context): String {
    val bytes = ByteArray(32).also(secureRandom::nextBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).also {
        bytes.fill(0)
        ctx.sessionSet(adminCsrfSessionKey, it)
    }
}

fun getMailCode():String{
    val randoms = getRandomNumString(6)
    return randoms
}


fun getcodes(num:Int):MutableList<String>{
    val codes = mutableListOf<String>()
    val date=  Date().time
    for(i in 1..num){
        val randoms = getRandomString(6)
        val code= "$date$randoms"
        codes.add(code)
    }
    return codes
}


data class PasswordVerification(val isValid: Boolean, val needsUpgrade: Boolean = false)

data class AdminPasswordVerification(val isValid: Boolean, val usesLegacyPlaintext: Boolean)

fun passsign(password: String): String {
    return BCrypt.hashpw(password, BCrypt.gensalt(12, secureRandom))
}

fun verifyPassword(password: String, storedHash: String?): PasswordVerification {
    if (storedHash.isNullOrBlank()) return PasswordVerification(false)
    if (storedHash.startsWith("\$2") && storedHash.length == 60) {
        return PasswordVerification(runCatching { BCrypt.checkpw(password, storedHash) }.getOrDefault(false))
    }
    if (storedHash.matches(Regex("[0-9a-fA-F]{64}"))) {
        val actual = Sha256(password).lowercase().toByteArray(StandardCharsets.US_ASCII)
        val expected = storedHash.lowercase().toByteArray(StandardCharsets.US_ASCII)
        val valid = MessageDigest.isEqual(actual, expected)
        return PasswordVerification(valid, needsUpgrade = valid)
    }
    return PasswordVerification(false)
}

fun verifyConfiguredAdminPassword(
    password: String,
    passwordHash: String?,
    legacyPlaintext: String?
): AdminPasswordVerification {
    if (!passwordHash.isNullOrBlank()) {
        val isBcryptHash = passwordHash.startsWith("\$2") && passwordHash.length == 60
        return AdminPasswordVerification(
            isValid = isBcryptHash && verifyPassword(password, passwordHash).isValid,
            usesLegacyPlaintext = false
        )
    }
    if (!legacyPlaintext.isNullOrBlank()) {
        val valid = MessageDigest.isEqual(
            password.toByteArray(StandardCharsets.UTF_8),
            legacyPlaintext.toByteArray(StandardCharsets.UTF_8)
        )
        return AdminPasswordVerification(valid, usesLegacyPlaintext = true)
    }
    return AdminPasswordVerification(false, usesLegacyPlaintext = false)
}
