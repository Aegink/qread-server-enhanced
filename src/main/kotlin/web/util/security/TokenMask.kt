package web.util.security

/**
 * 日志脱敏：accessToken 是等价于密码的凭证，不能落进日志（日志常被采集、备份、共享）。
 * 只保留前 6 位便于对账，其余打码，并带上长度方便判断是不是同一个。
 */
fun maskToken(token: String?): String {
    val value = token.orEmpty()
    return when {
        value.isEmpty() -> "(empty)"
        value.length <= 8 -> "***"
        else -> value.take(6) + "***(" + value.length + ")"
    }
}
