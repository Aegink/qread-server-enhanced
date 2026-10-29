package book.util

import book.util.AppPattern.dataUriRegex
import java.net.InetAddress
import java.text.Collator
import java.util.Locale

// import org.apache.commons.text.StringEscapeUtils

fun String?.safeTrim() = if (this.isNullOrBlank()) null else this.trim()

fun String?.isAbsUrl() = if (this.isNullOrBlank()) false else this.startsWith("http://", true)
        || this.startsWith("https://", true)

fun String?.isDataUrl() =
    this?.let {
        dataUriRegex.matches(it)
    } ?: false

fun String?.isUri(): Boolean {
    this ?: return false
    return this.startsWith("file://", true)
}

fun String?.isJson(): Boolean = this?.run {
    val str = this.trim()
    when {
        str.startsWith("{") && str.endsWith("}") -> true
        str.startsWith("[") && str.endsWith("]") -> true
        else -> false
    }
} ?: false

fun String?.isJsonObject(): Boolean = this?.run {
    val str = this.trim()
    str.startsWith("{") && str.endsWith("}")
} ?: false

fun String?.isJsonArray(): Boolean = this?.run {
    val str = this.trim()
    str.startsWith("[") && str.endsWith("]")
} ?: false

fun String?.isXml(): Boolean =
    this?.run {
        val str = this.trim()
        str.startsWith("<") && str.endsWith(">")
    } ?: false

fun String?.isTrue(nullIsTrue: Boolean = false): Boolean {
    if (this.isNullOrBlank() || this == "null") {
        return nullIsTrue
    }
    return !this.trim().matches("(?i)^(?:false|no|not|0|0.0)$".toRegex())
}

fun String.isHex(): Boolean = all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }

fun String?.htmlFormat(): String = if (this.isNullOrBlank()) "" else
    this.replace("(?i)<(br[\\s/]*|/*p\\b.*?|/*div\\b.*?)>".toRegex(), "\n")// 替换特定标签为换行符
        .replace("<[script>]*.*?>|&nbsp;".toRegex(), "")// 删除script标签对和空格转义符
        .replace("\\s*\\n+\\s*".toRegex(), "\n　　")// 移除空行,并增加段前缩进2个汉字
        .replace("^[\\n\\s]+".toRegex(), "　　")//移除开头空行,并增加段前缩进2个汉字
        .replace("[\\n\\s]+$".toRegex(), "") //移除尾部空行

fun String.splitNotBlank(vararg delimiter: String, limit: Int = 0): Array<String> = run {
    this.split(*delimiter, limit = limit).map { it.trim() }.filterNot { it.isBlank() }.toTypedArray()
}

fun String.splitNotBlank(regex: Regex, limit: Int = 0): Array<String> = run {
    this.split(regex, limit).map { it.trim() }.filterNot { it.isBlank() }.toTypedArray()
}

fun String.startWithIgnoreCase(start: String): Boolean {
    return if (this.isBlank()) false else startsWith(start, true)
}

fun String.cnCompare(other: String): Int {
    return Collator.getInstance(Locale.CHINA).compare(this, other)
}

fun String?.memorySize(): Int = if (this == null) 0 else 40 + 2 * length

fun String.isChinese(): Boolean = "[\\u4e00-\\u9fa5]".toRegex().containsMatchIn(this)

/**
 * 将字符串拆分为单个字符,包含emoji
 */
fun String.toStringArray(): Array<String> {
    var codePointIndex = 0
    return try {
        Array(codePointCount(0, length)) {
            val start = codePointIndex
            codePointIndex = offsetByCodePoints(start, 1)
            substring(start, codePointIndex)
        }
    } catch (e: Exception) {
        split("").toTypedArray()
    }
}

fun String.escapeRegex(): String = replace("[{}()\\[\\].+*?^$\\\\|]".toRegex(), "\\\\$0")

fun String.normalizeFileName(): String = replace("[\\\\/:*?\"<>|]".toRegex(), "_")

fun String.parseIpsFromString(): List<InetAddress>? =
    split(",")
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        .takeIf(List<InetAddress>::isNotEmpty)

fun String.quoteReplacementJs(): String {
    if ('\\' !in this && '$' !in this) return this
    return buildString(length) {
        this@quoteReplacementJs.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '$' -> append("\\$")
                else -> append(char)
            }
        }
    }
}
