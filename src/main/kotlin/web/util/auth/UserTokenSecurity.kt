package web.util.auth

import web.mapper.UsertockenMapper
import web.model.Usertocken
import web.util.hash.Sha256
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.Base64

const val USER_TOKEN_TTL_DAYS = 30L

private val secureRandom = SecureRandom()
private val legacyUuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")

fun createUserAccessToken(random: SecureRandom = secureRandom): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return "qr_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

fun userTokenStorageId(accessToken: String): String = Sha256(accessToken)

fun Usertocken.isExpired(now: LocalDateTime = LocalDateTime.now()): Boolean {
    val createdAt = createtime ?: return true
    return !createdAt.plusDays(USER_TOKEN_TTL_DAYS).isAfter(now)
}

fun resolveUserToken(
    mapper: UsertockenMapper,
    accessToken: String,
    now: LocalDateTime = LocalDateTime.now()
): Usertocken? {
    val token = mapper.getUsertocken(userTokenStorageId(accessToken))
        ?: accessToken.takeIf(legacyUuidPattern::matches)?.let(mapper::getUsertocken)
        ?: return null
    if (token.isExpired(now)) {
        token.id?.let { mapper.deleteById(it) }
        return null
    }
    return token
}
