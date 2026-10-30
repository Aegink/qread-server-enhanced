package web.util.page

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 排序参数白名单回归。
 *
 * `QueryWrapper.orderByAsc/Desc` 会把参数原样拼进 SQL，之前直接把请求里的 `order` 传进去，
 * `/admin/seachusers?order=...` 就成了 SQL 注入点。
 */
class PageOrderSecurityTest {

    @Test
    fun parsesPlainColumnAscending() {
        assertEquals("id" to false, parseOrder("id"))
    }

    @Test
    fun parsesDescendingCaseInsensitively() {
        assertEquals("id" to true, parseOrder("id desc"))
        assertEquals("id" to true, parseOrder("  id   DESC "))
    }

    @Test
    fun blankOrderIsIgnored() {
        assertNull(parseOrder(null))
        assertNull(parseOrder("   "))
    }

    @Test
    fun rejectsInjectionAttempts() {
        listOf(
            "id;drop table users",
            "id,(select 1)",
            "id/**/desc",
            "(select 1)",
            "id`",
            "id desc extra"
        ).forEach { payload ->
            assertFailsWith<PageException>("应拒绝: $payload") { parseOrder(payload) }
        }
    }

    @Test
    fun acceptsSnakeCaseColumns() {
        assertEquals("create_time" to true, parseOrder("create_time DESC"))
    }
}
