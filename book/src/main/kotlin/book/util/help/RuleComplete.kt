package book.util.help

@Suppress("RegExpRedundantEscape")
object RuleComplete {
    private val needComplete = Regex(
        """(?<!(@|/|^|[|%&]{2})(attr|text|ownText|textNodes|href|content|html|alt|all|value|src)(\(\))?)(?<seq>\&{2}|%%|\|{2}|$)"""
    )
    private val notComplete = Regex("""^:|^##|\{\{|@js:|<js>|@Json:|\$\.""")
    private val fixImgInfo =
        Regex("""(?<=(^|tag\.|[\+/@>~| &]))img(?<at>(\[@?.+\]|\.[-\w]+)?)[@/]+text(\(\))?(?<seq>\&{2}|%%|\|{2}|$)""")
    private val isXpath = Regex("^//|^@Xpath:")

    fun autoComplete(rules: String?, preRule: String? = null, type: Int = 1): String? {
        if (rules.isNullOrEmpty() || rules.contains(notComplete) || preRule?.contains(notComplete) == true) {
            return rules
        }

        val parts = rules.split("""##|,\{""".toRegex(), 2)
        val cleanedRule = parts[0]
        val split = if (parts.size > 1) """##|,\{""".toRegex().find(rules)?.value.orEmpty() else ""
        val tail = if (parts.size > 1) split + parts[1] else ""
        val xpath = cleanedRule.contains(isXpath)
        val textRule = if (xpath) "//text()\${seq}" else "@text\${seq}"
        val linkRule = if (xpath) "//@href\${seq}" else "@href\${seq}"
        val imageRule = if (xpath) "//@src\${seq}" else "@src\${seq}"
        val imageText = if (xpath) "img\${at}/@alt\${seq}" else "img\${at}@alt\${seq}"

        return when (type) {
            1 -> needComplete.replace(cleanedRule, textRule).replace(fixImgInfo, imageText) + tail
            2 -> needComplete.replace(cleanedRule, linkRule) + tail
            3 -> needComplete.replace(cleanedRule, imageRule) + tail
            else -> rules
        }
    }
}
