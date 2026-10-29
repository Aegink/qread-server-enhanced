package web.util.auth

import org.junit.jupiter.api.Test
import org.noear.solon.core.handle.ContextEmpty
import org.noear.solon.core.route.RouterInterceptorChain
import web.interceptor.ApiTokenExpiryInterceptor
import web.mapper.UsertockenMapper
import web.model.Usertocken
import web.util.admin.passsign
import web.util.admin.verifyConfiguredAdminPassword
import web.util.admin.verifyPassword
import web.util.hash.Sha256
import java.time.LocalDateTime
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CredentialSecurityTest {
    @Test
    fun bcryptHashesAreSaltedAndVerifiable() {
        val first = passsign("stage15-password")
        val second = passsign("stage15-password")

        assertTrue(first.startsWith("\$2"))
        assertEquals(60, first.length)
        assertNotEquals(first, second)
        assertTrue(verifyPassword("stage15-password", first).isValid)
        assertFalse(verifyPassword("wrong-password", first).isValid)
    }

    @Test
    fun legacySha256IsAcceptedOnlyForMigration() {
        val legacy = Sha256("legacy-password")
        val valid = verifyPassword("legacy-password", legacy)

        assertTrue(valid.isValid)
        assertTrue(valid.needsUpgrade)
        assertFalse(verifyPassword("wrong-password", legacy).isValid)
        assertFalse(verifyPassword("legacy-password", "invalid-hash").isValid)
    }

    @Test
    fun adminPasswordPrefersBcryptAndSupportsExplicitLegacyMigration() {
        val hash = passsign("stage25-admin-password")

        assertTrue(verifyConfiguredAdminPassword("stage25-admin-password", hash, "legacy").isValid)
        assertFalse(verifyConfiguredAdminPassword("legacy", hash, "legacy").isValid)
        assertTrue(verifyConfiguredAdminPassword("legacy", "", "legacy").isValid)
        assertTrue(verifyConfiguredAdminPassword("legacy", "", "legacy").usesLegacyPlaintext)
        assertFalse(verifyConfiguredAdminPassword("legacy", "not-a-bcrypt-hash", "legacy").isValid)
        assertFalse(verifyConfiguredAdminPassword("legacy", "", "").isValid)
    }

    @Test
    fun newTokensAreRandomAndStoredAsHashes() {
        val first = Usertocken().create()
        val second = Usertocken().create()

        assertTrue(first.accessToken!!.startsWith("qr_"))
        assertEquals(46, first.accessToken!!.length)
        assertNotEquals(first.accessToken, second.accessToken)
        assertEquals(userTokenStorageId(first.accessToken!!), first.id)
        assertNotEquals(first.accessToken, first.id)
    }

    @Test
    fun tokenExpirationUsesCreationTime() {
        val now = LocalDateTime.of(2026, 8, 8, 12, 0)
        val token = Usertocken().apply { createtime = now.minusDays(USER_TOKEN_TTL_DAYS).plusSeconds(1) }
        assertFalse(token.isExpired(now))

        token.createtime = now.minusDays(USER_TOKEN_TTL_DAYS)
        assertTrue(token.isExpired(now))
        token.createtime = null
        assertTrue(token.isExpired(now))
    }

    @Test
    fun expiredTokensAreDeletedBeforeTheActionChain() {
        val now = LocalDateTime.of(2026, 8, 8, 12, 0)
        val rawToken = createUserAccessToken()
        val token = Usertocken().apply {
            id = userTokenStorageId(rawToken)
            createtime = now.minusDays(USER_TOKEN_TTL_DAYS)
        }
        val (mapper, deleted) = mapperFor(token)
        val interceptor = ApiTokenExpiryInterceptor().apply { usertockenMapper = mapper }
        val context = ContextEmpty.create().apply { paramMap().put("accessToken", rawToken) }
        val chained = AtomicBoolean(false)

        interceptor.doIntercept(context, null, RouterInterceptorChain { _, _ -> chained.set(true) })
        assertTrue(deleted.get())
        assertFalse(chained.get())
    }

    @Test
    fun activeTokensContinueThroughTheActionChain() {
        val now = LocalDateTime.now()
        val rawToken = createUserAccessToken()
        val token = Usertocken().apply {
            id = userTokenStorageId(rawToken)
            createtime = now
        }
        val (mapper, deleted) = mapperFor(token)
        val interceptor = ApiTokenExpiryInterceptor().apply { usertockenMapper = mapper }
        val context = ContextEmpty.create().apply { paramMap().put("accessToken", rawToken) }
        val chained = AtomicBoolean(false)

        interceptor.doIntercept(context, null, RouterInterceptorChain { _, _ -> chained.set(true) })
        assertFalse(deleted.get())
        assertTrue(chained.get())
    }

    @Test
    fun legacyUuidTokensRemainCompatibleUntilTheyExpire() {
        val legacyToken = "123e4567-e89b-42d3-a456-426614174000"
        val token = Usertocken().apply {
            id = legacyToken
            createtime = LocalDateTime.now()
        }
        val (mapper, deleted) = mapperFor(token)

        assertEquals(token, resolveUserToken(mapper, legacyToken))
        assertFalse(deleted.get())
    }

    @Test
    fun storedTokenHashCannotBeUsedAsAnAccessToken() {
        val rawToken = createUserAccessToken()
        val token = Usertocken().apply {
            id = userTokenStorageId(rawToken)
            createtime = LocalDateTime.now()
        }
        val (mapper, _) = mapperFor(token)

        assertEquals(null, resolveUserToken(mapper, token.id!!))
    }

    private fun mapperFor(token: Usertocken): Pair<UsertockenMapper, AtomicBoolean> {
        val deleted = AtomicBoolean(false)
        val mapper = Proxy.newProxyInstance(
            UsertockenMapper::class.java.classLoader,
            arrayOf(UsertockenMapper::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getUsertocken" -> token.takeIf { args?.firstOrNull() == token.id }
                "deleteById" -> {
                    deleted.set(true)
                    1
                }
                else -> null
            }
        } as UsertockenMapper
        return mapper to deleted
    }
}
