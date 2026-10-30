package book.webBook.analyzeRule

import book.app.App
import book.model.BaseBook
import book.model.BaseSource
import book.model.Book
import book.model.BookChapter
import book.model.BookSource
import book.model.RssArticle
import book.util.*
import book.util.AppPattern.JS_PATTERN
import book.util.AppPattern.WEB_JS_PATTERN
import book.util.help.CacheManager
import book.util.help.CookieStore
import book.webBook.Debug
import book.webBook.DebugLog
import book.webBook.WBook
import book.webBook.exception.NoStackTraceException
import com.script.CompiledScript
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Entities
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URL
import java.lang.ref.WeakReference
import java.util.regex.Pattern
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import org.apache.commons.text.StringEscapeUtils
import org.jsoup.nodes.Node
import java.util.*
import kotlin.collections.HashMap


open class AnalyzeRule(
    var ruleData: RuleDataInterface? =null,
    override  var debugLog: DebugLog?=null,
    private val source: BaseSource? = null,
    private val preUpdateJs: Boolean = false,
    private val isFromBookInfo: Boolean = false
):JsExtensions {

    override fun toString(): String {
        val hashCode = this.hashCode()
        val hexHash = Integer.toHexString(hashCode)
        val s="io.legado.app.model.analyzeRule.AnalyzeRule@"+hexHash
        return s
    }

    override val logger: Logger
        get() =  LoggerFactory.getLogger(AnalyzeRule::class.java)

    val book get() = ruleData as? BaseBook

    val rssArticle get() = ruleData as? RssArticle

    var chapter: BookChapter? = null
        set(value) {
            if(source != null){
                value?.userid=source.userid?:""
            }
            field = value
        }
    var nextChapterUrl: String? = null
    var content: Any? = null
        private set
    var baseUrl: String? = null
        private set
    var redirectUrl: URL? = null
        private set
    private var isJSON: Boolean = false
    private var isRegex: Boolean = false

    private var analyzeByXPath: AnalyzeByXPath? = null
    private var analyzeByJSoup: AnalyzeByJSoup? = null
    private var analyzeByJSonPath: AnalyzeByJSonPath? = null

    private val stringRuleCache = SynchronizedLruCache<AnalyzeRuleCacheKey, List<SourceRule>>(
        AnalyzeRuleCacheCapacity.RULE
    )
    private val regexCache = SynchronizedLruCache<AnalyzeRuleCacheKey, CachedRegex>(
        AnalyzeRuleCacheCapacity.REGEX
    )
    private val scriptCache = SynchronizedLruCache<AnalyzeRuleCacheKey, CompiledScript>(
        AnalyzeRuleCacheCapacity.SCRIPT
    )
    private var topScopeRef: WeakReference<Scriptable>? = null
    private var evalJSCallCount = 0
    private var loggedNonStandardJSON = false
    private var ruleType: String? = null

    private var objectChangedXP = false
    private var objectChangedJS = false
    private var objectChangedJP = false

    private var coroutineContext: CoroutineContext = EmptyCoroutineContext

    fun setCoroutineContext(context: CoroutineContext): AnalyzeRule {
        coroutineContext = context.minusKey(ContinuationInterceptor)
        return this
    }

    init {
        if(source != null){
            val userid = source.userid?:""
            chapter?.userid = userid
            ruleData?.userid = userid
            book?.userid = userid
        }
    }

    @JvmOverloads
    fun setContent(content: Any?, baseUrl: String? = null): AnalyzeRule {
        if (content == null) throw AssertionError("内容不可空（Content cannot be null）")
        this.content = content
        isJSON = when (content) {
            is Node -> false
            else -> content.toString().isJson()
        }
        setBaseUrl(baseUrl)
        analyzeByXPath = null
        analyzeByJSoup = null
        analyzeByJSonPath = null
        return this
    }




    fun setBaseUrl(baseUrl: String?): AnalyzeRule {
        baseUrl?.let {
            this.baseUrl = baseUrl
        }
        return this
    }

    fun setRedirectUrl(url: String): URL? {
        try {
            redirectUrl = URL(url)
        } catch (e: Exception) {
            logger.info("URL(${AnalyzeUrl.safeLogUrl(url)}) error\n${e.localizedMessage}")
        }
        return redirectUrl
    }

    /**
     * 获取XPath解析类
     */
    private fun getAnalyzeByXPath(o: Any): AnalyzeByXPath {
        return if (o != content) {
            AnalyzeByXPath(o)
        } else {
            if (analyzeByXPath == null || objectChangedXP) {
                analyzeByXPath = AnalyzeByXPath(content!!)
                objectChangedXP = false
            }
            analyzeByXPath!!
        }
    }

    /**
     * 获取JSOUP解析类
     */
    private fun getAnalyzeByJSoup(o: Any): AnalyzeByJSoup {
        return if (o != content) {
            AnalyzeByJSoup(o)
        } else {
            if (analyzeByJSoup == null || objectChangedJS) {
                analyzeByJSoup = AnalyzeByJSoup(content!!)
                objectChangedJS = false
            }
            analyzeByJSoup!!
        }
    }

    /**
     * 获取JSON解析类
     */
    private fun getAnalyzeByJSonPath(o: Any): AnalyzeByJSonPath {
        return if (o != content) {
            AnalyzeByJSonPath(o)
        } else {
            if (analyzeByJSonPath == null || objectChangedJP) {
                analyzeByJSonPath = AnalyzeByJSonPath(content!!)
                objectChangedJP = false
            }
            analyzeByJSonPath!!
        }
    }

    private fun getWebJsResult(jsStr: String, result: Any): String {
        val script = "var result = ${GSON.toJson(result)};\n$jsStr"
        return App.webview(
            content.toString(),
            baseUrl,
            script,
            source?.usertocken.orEmpty(),
            GSON.toJson(source?.getHeaderMap(true) ?: emptyMap<String, String>()),
            "",
            "",
            0
        ).body.toString()
    }

    /**
     * 获取文本列表
     */
    @JvmOverloads
    fun getStringList(rule: String?, mContent: Any? = null, isUrl: Boolean = false): List<String>? {
        if (rule.isNullOrEmpty()) return null
        val ruleList = splitSourceRuleCacheString(rule)
        return getStringList(ruleList, mContent, isUrl)
    }

    @JvmOverloads
    fun getStringList(
        ruleList: List<SourceRule>,
        mContent: Any? = null,
        isUrl: Boolean = false
    ): List<String>? {
        var result: Any? = null
        val content = mContent ?: this.content
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            if (content is NativeObject) {
                result = content[ruleList[0].rule]?.toString()
            } else {
                for (sourceRule in ruleList) {
                    putRule(sourceRule.putMap)
                    sourceRule.makeUpRule(result)
                    result?.let {
                        if (sourceRule.rule.isNotEmpty()) {
                            result = when (sourceRule.mode) {
                                Mode.WebJs -> getWebJsResult(sourceRule.rule, result).let {
                                    GSON.fromJsonArray<String>(it).getOrNull() ?: it
                                }
                                Mode.Js -> evalJS(sourceRule.rule, result)
                                Mode.Json -> getAnalyzeByJSonPath(it).getStringList(sourceRule.rule)
                                Mode.XPath -> getAnalyzeByXPath(it).getStringList(sourceRule.rule)
                                Mode.Default -> getAnalyzeByJSoup(it).getStringList(sourceRule.rule)
                                else -> sourceRule.rule
                            }
                        }
                        if (sourceRule.replaceRegex.isNotEmpty() && result is List<*>) {
                            val newList = ArrayList<String>()
                            for (item in result as List<*>) {
                                newList.add(replaceRegex(item.toString(), sourceRule))
                            }
                            result = newList
                        } else if (sourceRule.replaceRegex.isNotEmpty()) {
                            result = replaceRegex(result.toString(), sourceRule)
                        }
                    }
                }
            }
        }
        if (result == null) return null
        if (result is String) {
            result = (result as String).split("\n")
        }
        if (isUrl) {
            val urlList = ArrayList<String>()
            if (result is List<*>) {
                for (url in result as List<*>) {
                    val absoluteURL = NetworkUtils.getAbsoluteURL(redirectUrl, url.toString())
                    if (absoluteURL.isNotEmpty() && !urlList.contains(absoluteURL)) {
                        urlList.add(absoluteURL)
                    }
                }
            }
            return urlList
        }
        @Suppress("UNCHECKED_CAST")
        return result as? List<String>
    }

    /**
     * 获取文本
     */
    @JvmOverloads
    fun getString(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false): String {
        if (TextUtils.isEmpty(ruleStr)) return ""
        val ruleList = splitSourceRuleCacheString(ruleStr)
        return getString(ruleList, mContent, isUrl)
    }

    fun getString(ruleStr: String?, unescape: Boolean): String {
        if (TextUtils.isEmpty(ruleStr)) return ""
        val ruleList = splitSourceRuleCacheString(ruleStr)
        return getString(ruleList, unescape = unescape)
    }


    @JvmOverloads
    fun getString(
        ruleList: List<SourceRule>,
        mContent: Any? = null,
        isUrl: Boolean = false,
        unescape: Boolean = true
    ): String {
        var result: Any? = null
        val content = mContent ?: this.content
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            if (result is NativeObject) {
                val sourceRule = ruleList.first()
                putRule(sourceRule.putMap)
                sourceRule.makeUpRule(result)
                result = if (sourceRule.getParamSize() > 1) {
                    sourceRule.rule
                } else {
                    // 键值直接访问
                    result[sourceRule.rule]?.toString()
                }?.let {
                    replaceRegex(it, sourceRule)
                }
            } else {
                for (sourceRule in ruleList) {
                    putRule(sourceRule.putMap)
                    sourceRule.makeUpRule(result)
                    result?.let {
                        if (sourceRule.rule.isNotBlank() || sourceRule.replaceRegex.isEmpty()) {
                            result = when (sourceRule.mode) {
                                Mode.WebJs -> getWebJsResult(sourceRule.rule, it)
                                Mode.Js -> evalJS(sourceRule.rule, it)
                                Mode.Json -> getAnalyzeByJSonPath(it).getString(sourceRule.rule)
                                Mode.XPath -> getAnalyzeByXPath(it).getString(sourceRule.rule)
                                Mode.Default -> if (isUrl) {
                                    getAnalyzeByJSoup(it).getString0(sourceRule.rule)
                                } else {
                                    getAnalyzeByJSoup(it).getString(sourceRule.rule)
                                }

                                else -> sourceRule.rule
                            }
                        }
                        if ((result != null) && sourceRule.replaceRegex.isNotEmpty()) {
                            result = replaceRegex(result.toString(), sourceRule)
                        }
                    }
                }
            }
        }
        if (result == null) result = ""
        val resultStr = result.toString()
        var str = if (unescape && resultStr.indexOf('&') > -1) {
            StringEscapeUtils.unescapeHtml4(resultStr)
        } else {
            resultStr
        }
        if (isUrl) {
            return if (str.isBlank()) {
                baseUrl ?: ""
            } else {
                NetworkUtils.getAbsoluteURL(redirectUrl, str)
            }
        }
        if(isUnicodeEscaped(str)){
            str=unescapeUnicode(str)
        }
        return str
    }

    fun isUnicodeEscaped(input: String): Boolean {
        val pattern = """\\u[0-9a-fA-F]{4}""".toRegex()
        return pattern.containsMatchIn(input)
    }

    fun unescapeUnicode(input: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            if (input[i] == '\\' && i + 5 < input.length && input[i + 1] == 'u') {
                val hex = input.substring(i + 2, i + 6)
                try {
                    val charCode = hex.toInt(16)
                    sb.append(charCode.toChar())
                    i += 6
                } catch (e: NumberFormatException) {
                    sb.append(input[i])
                    i++
                }
            } else {
                sb.append(input[i])
                i++
            }
        }
        return sb.toString()
    }

    /**
     * 获取Element
     */
    fun getElement(ruleStr: String): Any? {
        if (TextUtils.isEmpty(ruleStr)) return null
        var result: Any? = null
        val content = this.content
        val ruleList = splitSourceRule(ruleStr, true)
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            for (sourceRule in ruleList) {
                putRule(sourceRule.putMap)
                sourceRule.makeUpRule(result)
                result?.let {
                    result = when (sourceRule.mode) {
                        Mode.Regex -> AnalyzeByRegex.getElement(
                            result.toString(),
                            sourceRule.rule.splitNotBlank("&&")
                        )

                        Mode.WebJs -> GSON.fromJsonObject<Map<String, Any?>>(
                            getWebJsResult(sourceRule.rule, it)
                        ).getOrNull()
                        Mode.Js -> evalJS(sourceRule.rule, it)
                        Mode.Json -> getAnalyzeByJSonPath(it).getObject(sourceRule.rule)
                        Mode.XPath -> getAnalyzeByXPath(it).getElements(sourceRule.rule)
                        else -> getAnalyzeByJSoup(it).getElements(sourceRule.rule)
                    }
                    if (sourceRule.replaceRegex.isNotEmpty()) {
                        result = replaceRegex(result.toString(), sourceRule)
                    }
                }
            }
        }
        return result
    }

    /**
     * 获取列表
     */
    @Suppress("UNCHECKED_CAST")
    fun getElements(ruleStr: String): List<Any> {
        var result: Any? = null
        val content = this.content
        val ruleList = splitSourceRule(ruleStr, true)
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            for (sourceRule in ruleList) {
                putRule(sourceRule.putMap)
                result ?: continue
                val rule = sourceRule.rule
                result = when (sourceRule.mode) {
                    Mode.Regex -> AnalyzeByRegex.getElements(
                        result.toString(),
                        rule.splitNotBlank("&&")
                    )

                    Mode.WebJs -> GSON.fromJsonArray<Map<String, Any?>>(
                        getWebJsResult(rule, result)
                    ).getOrNull()
                    Mode.Js -> evalJS(rule, result)
                    Mode.Json -> getAnalyzeByJSonPath(result).getList(rule)
                    Mode.XPath -> getAnalyzeByXPath(result).getElements(rule)
                    else -> getAnalyzeByJSoup(result).getElements(rule)
                }
            }
        }
        // 与上游一致：JS 规则可能返回字符串/数组/NativeArray 等，非列表结果不能硬转（曾导致
        // ClassCastException: String cannot be cast to List，把整个搜索打断）
        when (val value = result) {
            is List<*> -> {
                if (value.none { it == null || it === Scriptable.NOT_FOUND }) {
                    @Suppress("UNCHECKED_CAST")
                    return value as List<Any>
                }
                return value.mapNotNull { it?.takeUnless { item -> item === Scriptable.NOT_FOUND } }
            }

            is Array<*> -> {
                return value.mapNotNull { it?.takeUnless { item -> item === Scriptable.NOT_FOUND } }
            }

            is NativeArray -> {
                val values = ArrayList<Any>(value.length.toInt())
                for (index in 0 until value.length.toInt()) {
                    val item = value.get(index, value)
                    if (item != null && item !== Scriptable.NOT_FOUND) {
                        values.add(item)
                    }
                }
                return values
            }
        }
        return ArrayList()
    }

    /**
     * 保存变量
     */
    private fun putRule(map: Map<String, String>) {
        for ((key, value) in map) {
            put(key, getString(value))
        }
    }

    /**
     * 分离put规则
     */
    private fun splitPutRule(ruleStr: String, putMap: HashMap<String, String>): String {
        var vRuleStr = ruleStr
        val putMatcher = putPattern.matcher(vRuleStr)
        while (putMatcher.find()) {
            vRuleStr = vRuleStr.replace(putMatcher.group(), "")
            val json = putMatcher.group(1)
            val strict = GSONStrict.fromJsonObject<Map<String, String>>(json).getOrNull()
            if (strict != null) {
                putMap.putAll(strict)
                continue
            }
            GSON.fromJsonObject<Map<String, String>>(json).getOrNull()?.let {
                if (!loggedNonStandardJSON) {
                    val message = "@put 规则 JSON 格式不规范，请改为规范格式"
                    debugLog?.log(source?.getKey(), message)
                    logger.warn(message)
                    loggedNonStandardJSON = true
                }
                putMap.putAll(it)
            }
        }
        return vRuleStr
    }


    /**
     * 正则替换
     */
    private fun replaceRegex(result: String, rule: SourceRule): String {
        if (rule.replaceRegex.isEmpty()) return result
        var vResult = result
        val regex = compileRegexCache(rule.replaceRegex)
        vResult = if (rule.replaceFirst) {
            if (regex != null) kotlin.runCatching {
                val pattern = regex.toPattern()
                val matcher = pattern.matcher(vResult)
                if (matcher.find()) {
                    matcher.group(0)!!.replaceFirst(regex, rule.replacement)
                } else {
                    ""
                }
            }.getOrElse {
                vResult.replaceFirst(rule.replaceRegex, rule.replacement)
            } else vResult.replaceFirst(rule.replaceRegex, rule.replacement)
        } else {
            if (regex != null) kotlin.runCatching {
                vResult.replace(regex, rule.replacement)
            }.getOrElse {
                vResult.replace(rule.replaceRegex, rule.replacement)
            } else vResult.replace(rule.replaceRegex, rule.replacement)
        }
        return vResult
    }

    private fun compileRegexCache(regex: String): Regex? {
        val key = cacheKey(AnalyzeRuleCacheKind.Regex, regex)
        return regexCache.getOrPut(key) {
            CachedRegex(runCatching { regex.toRegex() }.getOrNull())
        }.value
    }

    private fun splitSourceRuleCacheString(ruleStr: String?): List<SourceRule> {
        if (ruleStr.isNullOrEmpty()) return emptyList()
        val key = cacheKey(AnalyzeRuleCacheKind.StringRule, ruleStr, includeParserState = true)
        return stringRuleCache.getOrPut(key) {
            splitSourceRule(ruleStr)
        }
    }

    /**
     * 分解规则生成规则列表
     */
    fun splitSourceRule(ruleStr: String?, allInOne: Boolean = false): List<SourceRule> {
        if (ruleStr.isNullOrEmpty()) return emptyList()
        val ruleList = ArrayList<SourceRule>()
        var mMode: Mode = Mode.Default
        var start = 0
        //仅首字符为:时为AllInOne，其实:与伪类选择器冲突，建议改成?更合理
        if (allInOne && ruleStr.startsWith(":")) {
            mMode = Mode.Regex
            isRegex = true
            start = 1
        } else if (isRegex) {
            mMode = Mode.Regex
        }
        var tmp: String
        val jsMatcher = JS_PATTERN.matcher(ruleStr)
        while (jsMatcher.find()) {
            if (jsMatcher.start() > start) {
                tmp = ruleStr.substring(start, jsMatcher.start()).trim { it <= ' ' }
                if (tmp.isNotEmpty()) {
                    ruleList.add(SourceRule(tmp, mMode))
                }
            }
            ruleList.add(SourceRule(jsMatcher.group(2) ?: jsMatcher.group(1), Mode.Js))
            start = jsMatcher.end()
        }

        val webJsMatcher = WEB_JS_PATTERN.matcher(ruleStr)
        while (webJsMatcher.find()) {
            if (webJsMatcher.start() > start) {
                tmp = ruleStr.substring(start, webJsMatcher.start()).trim { it <= ' ' }
                if (tmp.isNotEmpty()) {
                    ruleList.add(SourceRule(tmp, mMode))
                }
            }
            ruleList.add(SourceRule(webJsMatcher.group(1) ?: "", Mode.WebJs))
            start = webJsMatcher.end()
        }

        if (ruleStr.length > start) {
            tmp = ruleStr.substring(start).trim { it <= ' ' }
            if (tmp.isNotEmpty()) {
                ruleList.add(SourceRule(tmp, mMode))
            }
        }

        return ruleList
    }

    private fun getOrCreateSingleSourceRule(rule: String): List<SourceRule> {
        val key = cacheKey(AnalyzeRuleCacheKind.SingleRule, rule, includeParserState = true)
        return stringRuleCache.getOrPut(key) {
            listOf(SourceRule(rule))
        }
    }

    /**
     * 规则类
     */
    inner class SourceRule internal constructor(
        ruleStr: String,
        internal var mode: Mode = Mode.Default
    ) {
        internal var rule: String
        internal var replaceRegex = ""
        internal var replacement = ""
        internal var replaceFirst = false
        internal val putMap = HashMap<String, String>()
        private val ruleParam = ArrayList<String>()
        private val ruleType = ArrayList<Int>()
        private val getRuleType = -2
        private val jsRuleType = -1
        private val defaultRuleType = 0

        init {
            rule = when {
                mode == Mode.Js || mode == Mode.WebJs || mode == Mode.Regex -> ruleStr
                ruleStr.startsWith("@CSS:", true) -> {
                    mode = Mode.Default
                    ruleStr
                }
                ruleStr.startsWith("@@") -> {
                    mode = Mode.Default
                    ruleStr.substring(2)
                }
                ruleStr.startsWith("@XPath:", true) -> {
                    mode = Mode.XPath
                    ruleStr.substring(7)
                }
                ruleStr.startsWith("@Json:", true) -> {
                    mode = Mode.Json
                    ruleStr.substring(6)
                }
                isJSON || ruleStr.startsWith("$.") || ruleStr.startsWith("$[") -> {
                    mode = Mode.Json
                    ruleStr
                }
                ruleStr.startsWith("/") -> {//XPath特征很明显,无需配置单独的识别标头
                    mode = Mode.XPath
                    ruleStr
                }
                else -> ruleStr
            }
            //分离put
            rule = splitPutRule(rule, putMap)
            //@get,{{ }}, 拆分
            var start = 0
            var tmp: String
            val evalMatcher = evalPattern.matcher(rule)

            if (evalMatcher.find()) {
                tmp = rule.substring(start, evalMatcher.start())
                if (mode != Mode.Js && mode != Mode.Regex &&
                    (evalMatcher.start() == 0 || !tmp.contains("##"))
                ) {
                    mode = Mode.Regex
                }
                do {
                    if (evalMatcher.start() > start) {
                        tmp = rule.substring(start, evalMatcher.start())
                        splitRegex(tmp)
                    }
                    tmp = evalMatcher.group()
                    when {
                        tmp.startsWith("@get:", true) -> {
                            ruleType.add(getRuleType)
                            ruleParam.add(tmp.substring(6, tmp.lastIndex))
                        }
                        tmp.startsWith("{{") -> {
                            ruleType.add(jsRuleType)
                            ruleParam.add(tmp.substring(2, tmp.length - 2))
                        }
                        else -> {
                            splitRegex(tmp)
                        }
                    }
                    start = evalMatcher.end()
                } while (evalMatcher.find())
            }
            if (rule.length > start) {
                tmp = rule.substring(start)
                splitRegex(tmp)
            }
        }

        /**
         * 拆分\$\d{1,2}
         */
        private fun splitRegex(ruleStr: String) {
            var start = 0
            var tmp: String
            val ruleStrArray = ruleStr.split("##")
            val regexMatcher = regexPattern.matcher(ruleStrArray[0])

            if (regexMatcher.find()) {
                if (mode != Mode.Js && mode != Mode.Regex) {
                    mode = Mode.Regex
                }
                do {
                    if (regexMatcher.start() > start) {
                        tmp = ruleStr.substring(start, regexMatcher.start())
                        ruleType.add(defaultRuleType)
                        ruleParam.add(tmp)
                    }
                    tmp = regexMatcher.group()
                    ruleType.add(tmp.substring(1).toInt())
                    ruleParam.add(tmp)
                    start = regexMatcher.end()
                } while (regexMatcher.find())
            }
            if (ruleStr.length > start) {
                tmp = ruleStr.substring(start)
                ruleType.add(defaultRuleType)
                ruleParam.add(tmp)
            }
        }

        /**
         * 替换@get,{{ }}
         */
        fun makeUpRule(result: Any?) {
            replaceRegex = ""
            replacement = ""
            replaceFirst = false
            val infoVal = StringBuilder()
            if (ruleParam.isNotEmpty()) {
                var index = ruleParam.size
                while (index-- > 0) {
                    val regType = ruleType[index]
                    when {
                        regType > defaultRuleType -> {
                            @Suppress("UNCHECKED_CAST")
                            (result as? List<String?>)?.run {
                                if (this.size > regType) {
                                    this[regType]?.let {
                                        infoVal.insert(0, it)
                                    }
                                }
                            } ?: infoVal.insert(0, ruleParam[index])
                        }

                        regType == jsRuleType -> {
                            if (isRule(ruleParam[index])) {
                                getString(getOrCreateSingleSourceRule(ruleParam[index])).let {
                                    infoVal.insert(0, it)
                                }
                            } else {
                                val jsEval: Any? = evalJS(ruleParam[index], result)
                                when {
                                    jsEval == null -> Unit
                                    jsEval is String -> infoVal.insert(0, jsEval)
                                    jsEval is Double && jsEval % 1.0 == 0.0 -> infoVal.insert(
                                        0,
                                        String.format(Locale.ROOT, "%.0f", jsEval)
                                    )

                                    else -> infoVal.insert(0, jsEval.toString())
                                }
                            }
                        }

                        regType == getRuleType -> {
                            infoVal.insert(0, get(ruleParam[index]))
                        }

                        else -> infoVal.insert(0, ruleParam[index])
                    }
                }
                rule = infoVal.toString()
            }
            //分离正则表达式
            val ruleStrS = rule.split("##")
            rule = ruleStrS[0].trim()
            if (ruleStrS.size > 1) {
                replaceRegex = ruleStrS[1]
            }
            if (ruleStrS.size > 2) {
                replacement = ruleStrS[2]
            }
            if (ruleStrS.size > 3) {
                replaceFirst = true
            }
        }


        private fun isRule(ruleStr: String): Boolean {
            return ruleStr.startsWith('@') //js首个字符不可能是@，除非是装饰器，所以@开头规定为规则
                    || ruleStr.startsWith("$.")
                    || ruleStr.startsWith("$[")
                    || ruleStr.startsWith("//")
        }

        fun getParamSize(): Int {
            return ruleParam.size
        }
    }

    enum class Mode {
        XPath, Json, Default, Js, WebJs, Regex
    }

    fun put(key: String, value: String): String {
        // 规则级内存：保证 java.put -> java.get 在同一规则实例内一定读得回（上游 setLocal 语义）
        localBindings[key] = value
        if(source != null){
            val userid = source.userid?:""
            chapter?.userid = userid
            book?.userid = userid
            ruleData?.userid = userid
        }
        //println("put2: $key: $value")
        chapter?.putVariable(key, value)
            ?: book?.putVariable(key, value)
            ?: ruleData?.putVariable(key, value)
            ?: source?.put(key, value)
        return value
    }

    fun get(key: String): String {
        localBindings[key]?.let { return it }
        when (key) {
            "bookName" -> book?.let {
                return it.name
            }
            "title" -> chapter?.let {
                return it.title
            }
        }
        if(source != null){
            val userid = source.userid?:""
            chapter?.userid = userid
            book?.userid = userid
            ruleData?.userid = userid
        }
        //println("get key: $key")
        return ( chapter?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: book?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: ruleData?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?:source?.get(key)?.takeIf { it.isNotEmpty() }
            ?: "")
    }

    /**
     * 保存规则级局部变量（对齐上游 setLocal）：同一次规则解析内跨步骤共享，JS 侧用 java.get(key) 读回。
     */
    fun setLocal(key: String, value: String): AnalyzeRule {
        localBindings[key] = value
        return this
    }

    /**
     * 给当前规则命名（对齐上游 setRuleName）：没有书源上下文时 getTag 返回它。
     */
    fun setRuleName(name: String) {
        if (name.isNotBlank()) ruleName = name
    }

    var toastc=0

    /** 规则级局部变量（`java.put/get` 的进程内兜底存储，对齐上游 setLocal） */
    private val localBindings = HashMap<String, String>()

    /** 规则名，供 getTag 在无书源上下文时返回（对齐上游 setRuleName） */
    private var ruleName: String? = null
    /**
     * 弹窗提示
     */
    override  fun toast(msg: Any?) {
        logger.info("toast:$msg")
        if(toastc >= 50){
            throw Exception("toast 调用次数超过50次")
        }
        toastc=toastc+1
        App.toast("$msg",getSource()?.usertocken?:"")
    }

    override fun longToast(msg: Any?) {
        logger.info("longToast:$msg")
        if(toastc >= 50){
            throw Exception("toast 调用次数超过50次")
        }
        toastc=toastc+1
        App.longToast("$msg",getSource()?.usertocken?:"")
    }


    /**
     * 执行JS
     */
    fun evalJS(jsStr: String, result: Any? =null): Any? {
        val startTime = System.currentTimeMillis()
        val isLoggingEnabled = debugLog != null
        val jsContext = if (isLoggingEnabled) buildJsExecutionContext(result) else null
        var userid=""
        if(source != null){
            userid = source.userid?:""
            chapter?.userid = userid
            ruleData?.userid = userid
            book?.userid = userid
        }
        val bindings = buildScriptBindings { bindings ->
            bindings["java"] = this
            bindings["cookie"] = source?.getCookieManger()
            bindings["cache"] = source?.getCacheManger()
            bindings["source"] = source
            bindings["book"] = book
            bindings["result"] = result
            bindings["baseUrl"] = baseUrl
            bindings["chapter"] = chapter
            bindings["title"] = chapter?.title
            bindings["src"] = content
            bindings["nextChapterUrl"] = nextChapterUrl
            bindings["rssArticle"] = rssArticle
            bindings["fromBookInfo"] = isFromBookInfo
            binding(bindings)
        }
        val topScope = source?.getShareScope() ?: topScopeRef?.get()
        val scope = if (topScope == null) {
            RhinoScriptEngine.getRuntimeScope(bindings).apply {
                if (evalJSCallCount++ >= 16) {
                    topScopeRef = WeakReference(prototype)
                }
            }
        } else {
            bindings.apply { prototype = topScope }
        }
        val previousRuleType = currentRuleTypeThreadLocal.get()
        return try {
            currentRuleTypeThreadLocal.set(ruleType)
            compileScriptCache(getjs(jsStr)).eval(scope, coroutineContext)
        } finally {
            currentRuleTypeThreadLocal.set(previousRuleType)
            toastc = 0
            if (isLoggingEnabled) {
                val duration = System.currentTimeMillis() - startTime
                debugLog?.log(
                    source?.getKey(),
                    "JS 执行完成：类型=${ruleType ?: "UNKNOWN"}，耗时=${duration}ms，上下文=${jsContext?.keys?.joinToString()}"
                )
            }
        }
    }

    private fun buildJsExecutionContext(result: Any?): Map<String, Any?> {
        return linkedMapOf(
            "resultType" to result?.javaClass?.simpleName,
            "source" to source?.getTag(),
            "book" to book?.name,
            "chapter" to chapter?.title,
            "baseUrl" to baseUrl,
            "fromBookInfo" to isFromBookInfo
        )
    }

    fun setRuleType(type: String?): AnalyzeRule {
        ruleType = type
        return this
    }

    fun getActiveRuleType(): String? = currentRuleTypeThreadLocal.get() ?: ruleType

    internal fun hasReusableTopScope(): Boolean = topScopeRef?.get() != null

    private fun compileScriptCache(jsStr: String): CompiledScript {
        val key = cacheKey(AnalyzeRuleCacheKind.Script, jsStr)
        return scriptCache.getOrPut(key) {
            RhinoScriptEngine.compile(jsStr)
        }
    }

    private fun cacheKey(
        kind: AnalyzeRuleCacheKind,
        value: String,
        includeParserState: Boolean = false
    ): AnalyzeRuleCacheKey {
        return AnalyzeRuleCacheKey(
            userid = source?.userid.orEmpty(),
            sourceKey = source?.getKey().orEmpty(),
            kind = kind,
            value = value,
            jsonContent = includeParserState && isJSON,
            regexMode = includeParserState && isRegex
        )
    }

    override fun getSource(): BaseSource? {
        return source
    }

    override fun getTag(): String? = source?.getTag() ?: ruleName ?: ruleType

    /**
     * js实现跨域访问,不能删
     */
    override fun ajax(urlStr: String): String? {
        logger.debug("ajax url: ${AnalyzeUrl.safeLogUrl(urlStr)}")
        return runBlocking {
            kotlin.runCatching {
                val analyzeUrl = AnalyzeUrl(urlStr, source = source, ruleData = book,debugLog = debugLog)
                analyzeUrl.getStrResponseAwait().body
            }.onFailure {
                log("ajax(${AnalyzeUrl.safeLogUrl(urlStr)}) error\n${it.stackTraceToString()}")
                // it.printStackTrace()
            }.getOrElse {
                it.message
            }
        }
    }



    /**
     * 更新BookUrl,如果搜索结果有tocUrl也会更新,有些书源bookUrl定期更新,可以在js内调用更新
     */
    fun reGetBook() {
        requirePreUpdateJs()
        val bookSource = source as? BookSource ?: return
        val target = book as? Book ?: return
        runBlocking(coroutineContext) {
            val webBook = WBook(
                bookSource = bookSource,
                debugLog = debugLog != null,
                debugLogger = debugLog,
                userid = bookSource.userid,
                usertocken = bookSource.usertocken
            )
            val found = webBook.preciseSearch(target.name, target.author)
            target.bookUrl = found.bookUrl
            target.tocUrl = found.tocUrl
            found.variableMap.forEach { (key, value) -> target.putVariable(key, value) }
            webBook.refreshBookInfo(target, false)
        }
    }

    /** 兼容旧版 qread 书源中的函数名。 */
    fun refreshBookUrl() = reGetBook()

    /**
     * 更新tocUrl,有些书源目录url定期更新,可以在js调用更新
     */
    fun refreshTocUrl() {
        requirePreUpdateJs()
        val bookSource = source as? BookSource ?: return
        val target = book as? Book ?: return
        runBlocking(coroutineContext) {
            WBook(
                bookSource = bookSource,
                debugLog = debugLog != null,
                debugLogger = debugLog,
                userid = bookSource.userid,
                usertocken = bookSource.usertocken
            ).refreshBookInfo(target, false)
        }
    }

    private fun requirePreUpdateJs() {
        if (!preUpdateJs) {
            throw NoStackTraceException("只能在 preUpdateJs 中调用")
        }
    }

    companion object {
        private val putPattern = Pattern.compile("@put:(\\{[^}]+?\\})", Pattern.CASE_INSENSITIVE)
        private val evalPattern =
            Pattern.compile("@get:\\{[^}]+?\\}|\\{\\{[\\w\\W]*?\\}\\}", Pattern.CASE_INSENSITIVE)
        private val regexPattern = Pattern.compile("\\$\\d{1,2}")
        private val titleNumPattern = Pattern.compile("(第)(.+?)(章)")
        val currentRuleTypeThreadLocal = ThreadLocal<String?>()
    }

}
