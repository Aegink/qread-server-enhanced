package web.interceptor

import org.junit.jupiter.api.Test
import org.noear.solon.core.handle.ContextEmpty
import org.noear.solon.core.route.RouterInterceptorChain
import web.util.admin.getOrCreateAdminCsrfToken
import web.util.admin.loginok
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AdminCsrfSecurityTest {
    private val interceptor = AdminAuthInterceptorImpl()

    @Test
    fun unauthenticatedRequestDoesNotReachAction() {
        val (context, chained) = context("POST", loggedIn = false)

        intercept(context, chained)

        assertFalse(chained.get())
    }

    @Test
    fun authenticatedSafeRequestDoesNotRequireToken() {
        val (context, chained) = context("GET")

        intercept(context, chained)

        assertTrue(chained.get())
    }

    @Test
    fun authenticatedPostWithoutTokenIsRejected() {
        val (context, chained) = context("POST")

        intercept(context, chained)

        assertFalse(chained.get())
        assertEquals(403, context.status())
    }

    @Test
    fun authenticatedPostWithWrongTokenIsRejected() {
        val (context, chained) = context("POST", requestToken = "wrong-token")

        intercept(context, chained)

        assertFalse(chained.get())
        assertEquals(403, context.status())
    }

    @Test
    fun authenticatedPostWithSessionTokenReachesAction() {
        val (context, chained) = context("POST", validToken = true)

        intercept(context, chained)

        assertTrue(chained.get())
    }

    @Test
    fun successfulLoginRotatesSessionAndCsrfToken() {
        val context = TestContext("GET")
        val previous = getOrCreateAdminCsrfToken(context)

        loginok("admin", context)

        assertEquals("admin", context.session("username"))
        assertNotEquals(previous, getOrCreateAdminCsrfToken(context))
    }

    @Test
    fun bookSourceUploadShowsExecutableRuleWarning() {
        val template = Path.of("src", "main", "resources", "templates", "admin", "book.html").readText()

        assertTrue(template.contains("书源规则可执行 Java 和 JavaScript 代码"))
        assertTrue(template.contains("仅导入可信来源"))
        assertTrue(template.contains("role=\"alert\""))
    }

    private fun context(
        method: String,
        loggedIn: Boolean = true,
        requestToken: String? = null,
        validToken: Boolean = false
    ): Pair<ContextEmpty, AtomicBoolean> {
        val context = TestContext(method)
        if (loggedIn) context.sessionSet("username", "admin")
        val sessionToken = getOrCreateAdminCsrfToken(context)
        val headerToken = if (validToken) sessionToken else requestToken
        headerToken?.let { context.headerMap().put("X-CSRF-Token", it) }
        return context to AtomicBoolean(false)
    }

    private fun intercept(context: ContextEmpty, chained: AtomicBoolean) {
        interceptor.doIntercept(context, null, RouterInterceptorChain { _, _ -> chained.set(true) })
    }

    private class TestContext(private val requestMethod: String) : ContextEmpty() {
        private val sessionValues = mutableMapOf<String, Any>()

        override fun method(): String = requestMethod

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> session(name: String, type: Class<T>): T? = sessionValues[name] as T?

        override fun sessionSet(name: String, value: Any) {
            sessionValues[name] = value
        }

        override fun sessionRemove(name: String) {
            sessionValues.remove(name)
        }

        override fun sessionClear() {
            sessionValues.clear()
        }

        override fun sessionReset() {
            sessionValues.clear()
        }
    }
}
