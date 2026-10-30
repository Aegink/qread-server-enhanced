package web.util.page

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper
import com.baomidou.mybatisplus.core.mapper.BaseMapper
import web.response.PageResponse

/** 排序字段只允许「字母/数字/下划线」的列名，杜绝把用户输入直接拼进 ORDER BY。 */
private val ORDER_COLUMN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

/** 单页上限，避免 limit 被放大成拖库。 */
private const val MAX_LIMIT = 500

fun <T> Page(
    mapper: BaseMapper<T>,
    queryWrapper: QueryWrapper<T>,
    _page: Int,
    _limit: Int,
    _order: String?
): Triple<Long, Int, List<T>> {
    val page = _page.let { if (_page < 1) 0 else _page }
    val limit = _limit.let { if (_limit <= 0) 20 else minOf(_limit, MAX_LIMIT) }

    val count = mapper.selectCount(queryWrapper)
    if (count == 0.toLong()) {
        return Triple(0, page, emptyList())
    }

    parseOrder(_order)?.let { (column, desc) ->
        if (desc) queryWrapper.orderByDesc(column) else queryWrapper.orderByAsc(column)
    }

    val rowBegin = (page - 1) * limit
    if (rowBegin > 0) {
        queryWrapper.last("LIMIT $limit offset $rowBegin")
    } else {
        queryWrapper.last("LIMIT $limit")
    }

    val list = mapper.selectList(queryWrapper)
    return Triple(count, page, list)
}

fun <T> PageByAjax(
    mapper: BaseMapper<T>,
    queryWrapper: QueryWrapper<T>,
    _page: Int,
    _limit: Int,
    _order: String?
): PageResponse {
    val (count, page, data) = Page(mapper, queryWrapper, _page, _limit, _order)
    return PageResponse(count, page, data)
}

/**
 * 解析排序参数：`字段名 [ASC|DESC]`。
 *
 * `orderByAsc/Desc` 会把参数原样拼进 SQL，所以字段名必须白名单校验（只允许字母/数字/下划线），
 * 否则 `/admin/seach*?order=` 就成了 SQL 注入点。
 *
 * @return 字段名 + 是否倒序；参数为空返回 null
 */
internal fun parseOrder(_order: String?): Pair<String, Boolean>? {
    if (_order.isNullOrBlank()) return null
    val parts = _order.trim().split(Regex("\\s+"))
    if (parts.size > 2) throw PageException("排序错误")
    val column = parts[0]
    if (!ORDER_COLUMN.matches(column)) throw PageException("排序字段非法")
    val desc = parts.size == 2 && parts[1].equals("DESC", true)
    return column to desc
}
