package book.webBook.analyzeRule

internal enum class AnalyzeRuleCacheKind {
    Script,
    Regex,
    StringRule,
    SingleRule
}

// 即使缓存当前属于单个 AnalyzeRule，也保留租户和书源维度，防止后续提升作用域时引入串用。
internal data class AnalyzeRuleCacheKey(
    val userid: String,
    val sourceKey: String,
    val kind: AnalyzeRuleCacheKind,
    val value: String,
    val jsonContent: Boolean = false,
    val regexMode: Boolean = false
)

internal data class CachedRegex(val value: Regex?)

internal object AnalyzeRuleCacheCapacity {
    const val SCRIPT = 16
    const val REGEX = 16
    const val RULE = 64
}
