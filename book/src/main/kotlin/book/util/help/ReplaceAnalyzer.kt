package book.util.help

import book.model.ReplaceRule
import book.util.GSON
import book.util.fromJsonObject
import book.util.jsonPath
import book.util.readBool
import book.util.readInt
import book.util.readLong
import book.util.readString
import book.webBook.exception.NoStackTraceException

object ReplaceAnalyzer {

    fun jsonToReplaceRules(json: String): Result<MutableList<ReplaceRule>> = runCatching {
        val rules = mutableListOf<ReplaceRule>()
        val items: List<Map<String, Any>> = jsonPath.parse(json).read("$")
        items.forEach { item ->
            jsonToReplaceRule(jsonPath.parse(item).jsonString()).getOrThrow().let { rule ->
                if (rule.isValid()) rules.add(rule)
            }
        }
        rules
    }

    fun jsonToReplaceRule(json: String): Result<ReplaceRule> = runCatching {
        val current = GSON.fromJsonObject<ReplaceRule>(json.trim()).getOrNull()
        if (current != null && current.pattern.isNotEmpty()) {
            return@runCatching current
        }

        val item = jsonPath.parse(json.trim())
        ReplaceRule(
            id = item.readLong("$.id") ?: System.currentTimeMillis(),
            name = item.readString("$.replaceSummary") ?: "",
            pattern = item.readString("$.regex")
                ?.takeIf(String::isNotEmpty)
                ?: throw NoStackTraceException("格式不对"),
            replacement = item.readString("$.replacement") ?: "",
            scope = item.readString("$.useTo"),
            isEnabled = item.readBool("$.enable") == true,
            isRegex = item.readBool("$.isRegex") == true,
            order = item.readInt("$.serialNumber") ?: 0
        )
    }
}
