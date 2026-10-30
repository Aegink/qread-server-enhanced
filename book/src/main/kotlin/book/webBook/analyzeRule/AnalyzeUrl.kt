package book.webBook.analyzeRule

import book.app.App
import book.model.BaseSource
import book.model.Book
import book.model.BookChapter
import book.util.*
import book.util.AppConst.UA_NAME
import book.util.AppPattern.JS_PATTERN
import book.util.AppPattern.dataUriRegex
import book.util.Base64
import book.util.help.CacheManager
import book.util.help.cookieJarHeader
import book.util.http.*
import book.webBook.DebugLog
import cn.hutool.core.codec.PercentCodec
import cn.hutool.core.net.RFC3986
import com.script.buildScriptBindings
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.regex.Pattern
import com.script.rhino.RhinoScriptEngine
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Response
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap
import kotlin.collections.LinkedHashMap
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.max


class AnalyzeUrl(
    val mUrl: String,
    val key: String? = null,
    val page: Int? = null,
    val speakText: String? = null,
    val speakSpeed: Int? = null,
    var baseUrl: String = "",
    private val source: BaseSource? = null,
    private val ruleData: RuleDataInterface? = null,
    private var readTimeout: Long? = null,
    private val chapter: BookChapter? = null,
    private var coroutineContext: CoroutineContext = EmptyCoroutineContext,
    headerMapF: Map<String, String>? = null,
    val needanalyzeUrl :Boolean=true,
    override var debugLog: DebugLog?,
    private val hasLoginHeader: Boolean = true,
    private val callTimeout: Long? = null,
    private val infoMap: MutableMap<String, String>? = null
) :JsExtensions {

    /** URL 选项 `{"followRedirects":false}`：null 表示沿用 OkHttp 默认（跟随）。 */
    private var followRedirects: Boolean? = null

    companion object {
        /** 书源自定义 readTimeout 的上限：再大就只是白占线程（默认 15s，这里给到 120s）。 */
        private const val MAX_READ_TIMEOUT = 120 * 1000L
        /** 单次请求的总耗时上限。 */
        private const val MAX_CALL_TIMEOUT = 300 * 1000L
        val paramPattern: Pattern = Pattern.compile("\\s*,\\s*(?=\\{)")
        private val pagePattern = Pattern.compile("<(.*?)>")
        private val concurrentRecordMap = hashMapOf<String, ConcurrentRecord>()
        private val queryEncoder =
            RFC3986.UNRESERVED.orNew(PercentCodec.of("!$%&()*+,/:;=?@[\\]^`{|}"))

        fun safeLogUrl(value: String): String {
            val rawUrl = value.substringBefore(",{")
            return runCatching {
                val uri = URI(rawUrl)
                if (uri.scheme == null || uri.host == null) {
                    rawUrl.substringBefore('?').substringBefore('#').take(200)
                } else {
                    URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString().take(200)
                }
            }.getOrElse {
                rawUrl.substringBefore('?').substringBefore('#').take(200)
            }
        }
    }

    override val logger: Logger
        get() =  LoggerFactory.getLogger(AnalyzeUrl::class.java)

    var ruleUrl = ""
        private set
    var url: String = ""
        private set
    var body: String? = null
        private set
    var type: String? = null
        private set
    val headerMap = HashMap<String, String>()

    private var urlNoQuery: String = ""
    private var encodedForm: String? = null
    private var encodedQuery: String? = null
    private var charset: String? = null
    private var method = RequestMethod.GET
    private var proxy: String? = null
    private var retry: Int = 0
    private var useWebView: Boolean = false
    private var usePhone: Boolean = false
    private var webJs: String? = null
    private var bodyJs: String? = null
    private var dnsIp: String? = null
    private var webViewDelayTime: Long = 0
    private val enabledCookieJar = source?.enabledCookieJar ?: false
    private val concurrentRateLimiter = ConcurrentRateLimiter(source)
    private val domain: String
    var serverID: Long? = null
        private set


    init {
        if(source != null) {
            if(headerMapF != null){
                headerMap.putAll(headerMapF)
            }
            runCatching {
                // 基础头（含书源 header JSON / UA），登录头留到 URL 解析完按站点决定
                headerMap.putAll(source!!.getHeaderMap(false))
            }
            if (headerMap.containsKey("proxy")) {
                proxy = headerMap["proxy"]
                headerMap.remove("proxy")
            }
            if(mUrl.isNotBlank() && baseUrl.isBlank()){
                if(!mUrl.startsWith("http://") && !mUrl.startsWith("https://")){
                    baseUrl = source.getKey()
                }
            }
        }
        initUrl()
        domain = NetworkUtils.getSubDomain(source?.getKey() ?: url)
    }

    @JvmOverloads
    fun showBrowser(url: String, html: String? = null, preloadJs: String? = null, config: String? = null) {
        println("showBrowser $url,$html,$preloadJs,$config")
        val headerMap : java.util.HashMap<String, String> = hashMapOf()
        val headerMapF: java.util.HashMap<String, String> = hashMapOf()


        val analyzeUrl = AnalyzeUrl(
            url, source = getSource(),
            debugLog = debugLog
        )
        val baseUrl = analyzeUrl.url
        headerMap.putAll(analyzeUrl.headerMap)
        headerMapF.putAll(analyzeUrl.headerMap)


        runCatching {
            val store=getSource()?.getCookieManger()
            val cookie = (store?.getCookie(baseUrl))?:""
            if (cookie.isNotEmpty()) {
                store?.mergeCookies(cookie, headerMap["Cookie"])?.let {
                    headerMap.put("Cookie", it)
                }
            }
        }

        val header= GSON.toJson(headerMap)
        logger.debug("showBrowser url:${safeLogUrl(url)}, headerKeys:${headerMap.keys.sorted()}")
        App.showBrowser(url,html?:"",preloadJs?:"",header,getSource()?.usertocken?:"")
    }


    fun initUrl() {

        coroutineContext = coroutineContext.minusKey(ContinuationInterceptor)
        if ( chapter != null ) {
            if(source != null){
                chapter.userid=source.userid?:""
                ruleData?.userid=source.userid?:""
            }
        }
        ruleUrl = mUrl

        //执行@js,<js></js>
        analyzeJs()
        //println("ruleUrl1 $ruleUrl")
        //替换参数
        if(needanalyzeUrl)  replaceKeyPageJs()
        //println("ruleUrl2 $ruleUrl")

        //处理URL
        if(needanalyzeUrl) analyzeUrl()

        logger.debug("ruleUrl ${safeLogUrl(ruleUrl)}")
        logger.debug("baseUrl ${safeLogUrl(baseUrl)}")
        //debugLog?.log(source?. getKey(), ruleUrl)
        // URL 解析完成后（可能被 js 改写或跳到别的域名）再按最终站点决定是否带登录头
        applyLoginHeaderIfSameSite(url)
    }

    override fun toString(): String {
        val hashCode = this.hashCode()
        val hexHash = Integer.toHexString(hashCode)
        val s="io.legado.app.model.analyzeRule.AnalyzeUrl@"+hexHash
        return s
    }

    private fun analyzeJs() {
        var start = 0
        val jsMatcher = JS_PATTERN.matcher(ruleUrl)
        var result = ruleUrl
        while (jsMatcher.find()) {
            if (jsMatcher.start() > start) {
                ruleUrl.substring(start, jsMatcher.start()).trim().let {
                    if (it.isNotEmpty()) {
                        result = it.replace("@result", result)
                    }
                }
            }
            result = evalJS(jsMatcher.group(2) ?: jsMatcher.group(1), result).toString()
            start = jsMatcher.end()
        }
        if (ruleUrl.length > start) {
            ruleUrl.substring(start).trim().let {
                if (it.isNotEmpty()) {
                    result = it.replace("@result", result)
                }
            }
        }
        ruleUrl = result
    }

    /**
     * 替换关键字,页数,JS
     */
    private fun replaceKeyPageJs() { //先替换内嵌规则再替换页数规则，避免内嵌规则中存在大于小于号时，规则被切错
        //js
        if (ruleUrl.contains("{{") && ruleUrl.contains("}}")) {
            val analyze = RuleAnalyzer(ruleUrl) //创建解析
            //替换所有内嵌{{js}}
            val url = analyze.innerRule("{{", "}}") {
                val jsEval = evalJS(it) ?: ""
                when {
                    jsEval is String -> jsEval
                    jsEval is Double && jsEval % 1.0 == 0.0 -> String.format("%.0f", jsEval)
                    else -> jsEval.toString()
                }
            }
            if (url.isNotEmpty()) ruleUrl = url
        }
        //page
        page?.let {
            val matcher = pagePattern.matcher(ruleUrl)
            while (matcher.find()) {
                val pages = matcher.group(1)!!.split(",")
                ruleUrl = if (page < pages.size) { //pages[pages.size - 1]等同于pages.last()
                    ruleUrl.replace(matcher.group(), pages[page - 1].trim { it <= ' ' })
                } else {
                    ruleUrl.replace(matcher.group(), pages.last().trim { it <= ' ' })
                }
            }
        }
        //println(ruleUrl)
    }

    /**
     * 解析Url
     */
    private fun analyzeUrl() {
        //replaceKeyPageJs已经替换掉额外内容，此处url是基础形式，可以直接切首个‘,’之前字符串。
        val urlMatcher = paramPattern.matcher(ruleUrl)
        val urlNoOption =
            if (urlMatcher.find()) ruleUrl.substring(0, urlMatcher.start()) else ruleUrl
        url = NetworkUtils.getAbsoluteURL(baseUrl, urlNoOption)
        NetworkUtils.getBaseUrl(url)?.let {
            baseUrl = it
        }
        if (urlNoOption.length != ruleUrl.length) {
            val optionJson = ruleUrl.substring(urlMatcher.end())
            var urlOption = GSONStrict.fromJsonObject<UrlOption>(optionJson).getOrNull()
            if (urlOption == null) {
                urlOption = GSON.fromJsonObject<UrlOption>(optionJson).getOrNull()
                if (urlOption != null) {
                    log("链接参数 JSON 格式不规范，请改为规范格式")
                }
            }
            urlOption?.let { option ->
                    option.getMethod()?.let {
                        method = when (it.uppercase(Locale.ROOT)) {
                            "POST" -> RequestMethod.POST
                            "HEAD" -> RequestMethod.HEAD
                            else -> RequestMethod.GET
                        }
                    }
                    option.getHeaderMap()?.forEach { entry ->
                        headerMap[entry.key.toString()] = entry.value.toString()
                    }
                    option.getBody()?.let {
                        body = it
                    }
                    type = option.getType()
                    charset = option.getCharset()
                    retry = option.getRetry()
                    useWebView = option.useWebView()
                    usePhone = option.usePhone()
                    webJs = option.getWebJs()
                    bodyJs = option.getBodyJs()
                    dnsIp = option.getDnsIp()
                    serverID = option.getServerID()
                    webViewDelayTime = max(0, option.getWebViewDelayTime() ?: 0)
                    option.getTimeout()?.let { readTimeout = it }
                    option.getFollowRedirects()?.let { followRedirects = it }
                    option.getOrigin()?.let { setRequestHeader("Origin", it) }
                    (option.getClick()?:option.getJs())?.let { jsStr ->
                        //println(jsStr)
                        evalJS(jsStr, url)?.toString()?.let {
                            url = it
                        }
                    }
                }
        }
        headerMap[UA_NAME] ?: let {
            headerMap[UA_NAME] = AppConst.userAgent
        }
        urlNoQuery = url
        when (method) {
            RequestMethod.GET, RequestMethod.HEAD -> {
                val pos = url.indexOf('?')
                if (pos != -1) {
                    analyzeQuery(url.substring(pos + 1))
                    urlNoQuery = url.substring(0, pos)
                }
            }
            RequestMethod.POST -> body?.let {
                if (!it.isJson() && !it.isXml() && headerMap["Content-Type"].isNullOrEmpty()) {
                    analyzeFields(it)
                }
            }
        }
    }

    private fun setRequestHeader(name: String, value: String) {
        headerMap.keys.filter { it.equals(name, true) }.forEach(headerMap::remove)
        headerMap[name] = value
    }

    /**
     * 登录态请求头只发给书源本站（对齐 LegadoTeam/legado #1099）。
     *
     * 书源里 `header` 可能带登录 Cookie/Authorization，过去会对任意目标域名外发，
     * 跨域请求（图床、CDN、第三方接口）会把凭据一起送出去。跨域确实需要认证头的书源，
     * 应在 urlOption 的 `headers` 里显式声明，而不是依赖 source.header 外溢。
     */
    private fun applyLoginHeaderIfSameSite(target: String?) {
        val src = source ?: return
        if (!hasLoginHeader || !isLoginHeaderSite(target)) return
        runCatching {
            src.getLoginHeaderMap()?.let { headerMap.putAll(it) }
        }
    }

    private fun isLoginHeaderSite(target: String?): Boolean {
        val src = source ?: return true
        val sourceDomain = subDomainOrNull(src.getKey()) ?: return true
        val candidate = target?.takeIf { it.startsWith("http", true) }
            ?: baseUrl.takeIf { it.isNotBlank() }
            ?: return true
        val targetDomain = subDomainOrNull(candidate) ?: return true
        return targetDomain.equals(sourceDomain, ignoreCase = true)
    }

    private fun subDomainOrNull(value: String?): String? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty() || !text.startsWith("http", true)) return null
        return runCatching { NetworkUtils.getSubDomain(text) }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    /**
     * 解析QueryMap
     */
    private fun analyzeFields(fieldsTxt: String) {
        encodedForm = encodeParams(fieldsTxt, charset, false)
    }

    private fun analyzeQuery(query: String) {
        encodedQuery = encodeParams(query, charset, true)
    }

    private fun encodeParams(params: String, charsetName: String?, isQuery: Boolean): String {
        val checkEncoded = charsetName.isNullOrEmpty()
        val targetCharset = when {
            charsetName.isNullOrEmpty() -> Charsets.UTF_8
            charsetName == "escape" -> null
            else -> Charset.forName(charsetName)
        }
        if (isQuery && targetCharset != null) {
            if (NetworkUtils.encodedQuery(params)) return params
            return queryEncoder.encode(params, targetCharset)
        }
        val result = StringBuilder()
        var position = 0
        while (position <= params.length) {
            if (result.isNotEmpty()) result.append('&')
            val ampersand = params.indexOf('&', position).takeIf { it >= 0 } ?: params.length
            val equals = params.indexOf('=', position)
            val hasValue = equals >= 0 && equals <= ampersand
            val keyEnd = if (hasValue) equals else ampersand
            result.appendEncoded(params.substring(position, keyEnd), checkEncoded, targetCharset)
            if (hasValue) {
                result.append('=')
                result.appendEncoded(params.substring(equals + 1, ampersand), checkEncoded, targetCharset)
            }
            position = ampersand + 1
        }
        return result.toString()
    }

    private fun StringBuilder.appendEncoded(
        value: String,
        checkEncoded: Boolean,
        targetCharset: Charset?
    ) {
        when {
            checkEncoded && NetworkUtils.encodedForm(value) -> append(value)
            targetCharset == null -> append(EncoderUtils.escape(value))
            else -> append(URLEncoder.encode(value, targetCharset))
        }
    }


    fun evalJS(jsStr: String, result: Any? = null): Any? {
        var userid=""
        if(source != null){
            userid = source.userid?:""
            chapter?.userid = userid
            ruleData?.userid = userid
        }
        val bindings = buildScriptBindings { bindings ->
            bindings["java"] = this
            bindings["baseUrl"] = baseUrl
            bindings["cookie"] = source?.getCookieManger()
            bindings["cache"] = source?.getCacheManger()
            bindings["page"] = page
            bindings["key"] = key
            bindings["speakText"] = speakText
            bindings["speakSpeed"] = speakSpeed
            bindings["book"] = ruleData as? Book
            bindings["source"] = source
            bindings["result"] = result
            bindings["infoMap"] = infoMap
            binding(bindings)
        }
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        source?.getShareScope()?.let {
            scope.prototype = it
        }

        return RhinoScriptEngine.eval(getjs(jsStr), scope, coroutineContext).also { toastc =0 }
    }

    fun put(key: String, value: String): String {
        if(source != null){
            val userid = source.userid?:""
            chapter?.userid = userid
            ruleData?.userid = userid
        }
        //println("put key: $key")
        chapter?.putVariable(key, value)
            ?: ruleData?.putVariable(key, value)
        return value
    }

    fun get(key: String): String {
        if(source != null){
            val userid = source.userid?:""
            chapter?.userid = userid
            ruleData?.userid = userid
        }
        //println("get key: $key")
        return ( chapter?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: ruleData?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: "")
    }

    fun setCookie() {
        val store=source?.getCookieManger()
        val cookie = (store?.getCookie(urlNoQuery))?:""
        if (cookie.isNotEmpty()) {
            var key="Cookie"
            headerMap.keys.forEach {
                if(it.lowercase(Locale.getDefault()) == "Cookie".lowercase(Locale.getDefault())){
                    key = it
                }
            }
            val oldc=headerMap[key]
            headerMap.remove(key)
            store?.mergeCookies( oldc,cookie)?.let {
                headerMap.put("Cookie", it)
            }
        }
        if (enabledCookieJar) {
            headerMap[cookieJarHeader] = source?.getcookieJarHeaderid()?:""
        } else {
            headerMap.remove(cookieJarHeader)
        }
    }

    /**
     * 访问网站,返回StrResponse
     */
    suspend fun getStrResponseAwait(
        jsStr: String? = null,
        sourceRegex: String? = null,
        useWebView: Boolean = true,
        isTest: Boolean = false,
        skipRateLimit: Boolean = false
    ): StrResponse {
        if (type != null) {
            return StrResponse(url, StringUtils.byteToHexString(getByteArrayAwait()))
        }
        logger.debug("ajax url:${safeLogUrl(urlNoQuery)}, type:$type")
        return if (skipRateLimit) {
            executeStrRequest(jsStr, sourceRegex, useWebView, isTest)
        } else {
            concurrentRateLimiter.withLimit {
                executeStrRequest(jsStr, sourceRegex, useWebView, isTest)
            }
        }
    }

    fun isSimpleGetRequest(): Boolean {
        return method == RequestMethod.GET && encodedForm.isNullOrEmpty() && body.isNullOrEmpty()
    }

    private suspend fun executeStrRequest(
        jsStr: String?,
        sourceRegex: String?,
        useWebView: Boolean,
        isTest: Boolean
    ): StrResponse {
        setCookie()
        val startTime = System.currentTimeMillis()
        return try {
            val response = if (this.useWebView && useWebView && method != RequestMethod.HEAD) {
                when (method) {
                    RequestMethod.POST -> {
                        if(headerMap["Content-Type"].isNullOrBlank()){
                            if (!encodedForm.isNullOrEmpty() || body.isNullOrBlank()) {
                                headerMap["Content-Type"]="application/x-www-form-urlencoded"
                                body = encodedForm.orEmpty()
                            } else {
                                headerMap["Content-Type"]="application/json; charset=UTF-8"
                            }
                        }
                        App.webviewbody("",url,webJs ?: jsStr,getSource()?.usertocken?:"",GSON.toJson(headerMap),body?:"",sourceRegex?:"","",webViewDelayTime)
                    }
                    else -> {
                        App.webview("",url,webJs ?: jsStr,getSource()?.usertocken?:"",GSON.toJson(headerMap),sourceRegex?:"","",webViewDelayTime)
                    }
                }
            } else  if(source?.phonehttp == true || this.usePhone){
                when (method) {
                    RequestMethod.POST -> {
                        if(headerMap["Content-Type"].isNullOrBlank()){
                            if (!encodedForm.isNullOrEmpty() || body.isNullOrBlank()) {
                                headerMap["Content-Type"]="application/x-www-form-urlencoded"
                                body = encodedForm.orEmpty()
                            } else {
                                headerMap["Content-Type"]="application/json; charset=UTF-8"
                            }
                        }
                        val body=App.post(url,body?:"" ,GSON.toJson(headerMap),getSource()?.usertocken?:"",true)
                        StrResponse(body.url,body.body())
                    }
                    RequestMethod.HEAD -> {
                        val response = App.head(
                            url,
                            GSON.toJson(headerMap),
                            getSource()?.usertocken ?: "",
                            true
                        )
                        StrResponse(response.url, "")
                    }
                    else -> {
                        val body=App.get(url,GSON.toJson(headerMap),getSource()?.usertocken?:"",true)
                        StrResponse(body.url,body.body())
                    }
                }
            }else {
                getClient().newCallStrResponse(retry) {
                   // println(GSON.toJson(headerMap))
                    addHeaders(headerMap)
                    when (method) {
                        RequestMethod.POST -> {
                            url(urlNoQuery)
                            val contentType = headerMap["Content-Type"]
                            val body = body
                            if (!encodedForm.isNullOrEmpty() || body.isNullOrBlank()) {
                                postForm(encodedForm.orEmpty())
                            } else if (!contentType.isNullOrBlank()) {
                                val requestBody = body.toRequestBody(contentType.toMediaType())
                                post(requestBody)
                            } else {
                                postJson(body)
                            }
                        }
                        RequestMethod.HEAD -> {
                            get(urlNoQuery, encodedQuery)
                            head()
                        }
                        else -> get(urlNoQuery, encodedQuery)
                    }
                }.let {
                    val isXml = it.raw.body?.contentType()?.toString()
                        ?.matches(AppPattern.xmlContentTypeRegex) == true
                    if (isXml && it.body?.trim()?.startsWith("<?xml", true) == false) {
                        StrResponse(it.raw, "<?xml version=\"1.0\"?>" + it.body)
                    } else it
                }
            }
            applyBodyJs(response).apply {
                putCallTime((System.currentTimeMillis() - startTime).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
        } catch (e: Exception) {
            if (!isTest) throw e
            StrResponse(url, e.message).apply {
                putCallTime(testErrorCode(e))
            }
        }
    }

    private fun testErrorCode(error: Exception): Int {
        return when (error) {
            is java.net.SocketTimeoutException -> -2
            is java.net.UnknownHostException -> -3
            is java.net.ConnectException -> -4
            is java.net.SocketException -> -5
            is javax.net.ssl.SSLException -> -6
            is java.io.InterruptedIOException -> {
                if (error.message?.contains("timeout", ignoreCase = true) == true) -1 else -7
            }
            else -> -7
        }
    }

    private fun applyBodyJs(response: StrResponse): StrResponse {
        val script = bodyJs ?: return response
        return StrResponse(response.raw, evalJS(script, response.body).toString())
    }


    @JvmOverloads
    fun getStrResponse(
        jsStr: String? = null,
        sourceRegex: String? = null,
        useWebView: Boolean = true,
    ): StrResponse {
        return runBlocking {
            getStrResponseAwait(jsStr, sourceRegex, useWebView)
        }
    }

    fun getErrResponse(error: Throwable): Response = Response.Builder()
        .request(okhttp3.Request.Builder().url(url).build())
        .protocol(okhttp3.Protocol.HTTP_1_1)
        .code(500)
        .message(error.message ?: "Error Response")
        .body(error.stackTraceToString().toResponseBody(null))
        .build()

    fun getErrStrResponse(error: Throwable): StrResponse =
        StrResponse(getErrResponse(error), error.stackTraceToString())


    /**
     * 访问网站,返回Response
     */
    suspend fun getResponseAwait(): Response {
        concurrentRateLimiter.withLimit {
            setCookie()
            val response = getClient().newCallResponse(retry) {
                addHeaders(headerMap)
                when (method) {
                    RequestMethod.POST -> {
                       // println("urlNoQuery:$urlNoQuery")
                        url(urlNoQuery)
                        val contentType = headerMap["Content-Type"]
                        val body = body
                        if (!encodedForm.isNullOrEmpty() || body.isNullOrBlank()) {
                            postForm(encodedForm.orEmpty())
                        } else if (!contentType.isNullOrBlank()) {
                            val requestBody = body.toRequestBody(contentType.toMediaType())
                            post(requestBody)
                        } else {
                            postJson(body)
                        }
                    }
                    RequestMethod.HEAD -> {
                        get(urlNoQuery, encodedQuery)
                        head()
                    }
                    else -> get(urlNoQuery, encodedQuery)
                }
            }

            return response
        }
    }

    private fun getClient(): OkHttpClient {
        val client = getProxyClient(proxy)
        if (readTimeout == null && callTimeout == null && dnsIp == null && followRedirects == null) {
            return client
        }
        return client.newBuilder().apply {
            // 书源可以自定义超时；这里加上限，避免一个坏源把请求线程占住十几分钟
            readTimeout?.let {
                val ms = it.coerceIn(1L, MAX_READ_TIMEOUT)
                readTimeout(ms, TimeUnit.MILLISECONDS)
                callTimeout(max(60 * 1000L, ms * 2).coerceAtMost(MAX_CALL_TIMEOUT), TimeUnit.MILLISECONDS)
            }
            callTimeout?.let {
                callTimeout(it.coerceIn(1L, MAX_CALL_TIMEOUT), TimeUnit.MILLISECONDS)
            }
            followRedirects?.let {
                followRedirects(it)
            }
            dnsIp?.parseDnsAddresses()?.let { addresses ->
                val targetHost = extractHostFromUrl(urlNoQuery)
                dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        return if (targetHost != null && hostname.equals(targetHost, true)) {
                            addresses
                        } else {
                            Dns.SYSTEM.lookup(hostname)
                        }
                    }
                })
            }
        }.build()
    }

    private fun extractHostFromUrl(value: String): String? {
        return runCatching { URI(value).host }.getOrNull()
    }

    private fun String.parseDnsAddresses(): List<InetAddress>? {
        return split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            .takeIf(List<InetAddress>::isNotEmpty)
    }

    fun getResponse(): Response {
        return runBlocking(coroutineContext) {
            getResponseAwait()
        }
    }

    private fun getByteArrayIfDataUri(): ByteArray? {
        val dataUriFindResult = dataUriRegex.find(urlNoQuery)
        if (dataUriFindResult != null) {
            val dataUriBase64 = dataUriFindResult.groupValues[1]
            val byteArray = Base64.decode(dataUriBase64, Base64.DEFAULT)
            return byteArray
        }
        return null
    }

    /**
     * 访问网站,返回ByteArray
     */
    suspend fun getByteArrayAwait(): ByteArray {
        getByteArrayIfDataUri()?.let {
            return it
        }
        return getResponseAwait().body!!.bytes()
    }

    fun getByteArray(): ByteArray {
        return runBlocking(coroutineContext) {
            getByteArrayAwait()
        }
    }

    /**
     * 访问网站,返回InputStream
     */
    suspend fun getInputStreamAwait(): InputStream {
        getByteArrayIfDataUri()?.let {
            return ByteArrayInputStream(it)
        }
        return getResponseAwait().body!!.byteStream()
    }

    var toastc=0
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

    fun getInputStream(): InputStream {
        return runBlocking(coroutineContext) {
            getInputStreamAwait()
        }
    }

    /**
     * 上传文件
     */
    suspend fun upload(fileName: String, file: Any, contentType: String): StrResponse {
        return getProxyClient(proxy).newCallStrResponse(retry) {
            url(urlNoQuery)
            val bodyMap = GSON.fromJsonObject<HashMap<String, Any>>(body).getOrNull()!!
            bodyMap.forEach { entry ->
                if (entry.value.toString() == "fileRequest") {
                    bodyMap[entry.key] = mapOf(
                        Pair("fileName", fileName),
                        Pair("file", file),
                        Pair("contentType", contentType)
                    )
                }
            }
            postMultipart(type, bodyMap)
        }
    }


    fun getUserAgent(): String {
        return headerMap.get(UA_NAME, true) ?: AppConst.userAgent
    }

    fun isPost(): Boolean {
        return method == RequestMethod.POST
    }



    override fun getSource(): BaseSource? {
        return source
    }

    data class UrlOption(
        private var method: String? = null,
        private var charset: String? = null,
        private var headers: Any? = null,
        private var body: Any? = null,
        private var origin: String? = null,
        private var retry: Int? = null,
        private var type: String? = null,
        private var webView: Any? = null,
        private var webJs: String? = null,
        private var dnsIp: String? = null,
        private var js: String? = null,
        private var bodyJs: String? = null,
        private var click: String? = null,
        private var usePhone: Any? = null,
        private var serverID: Long? = null,
        private var webViewDelayTime: Long? = null,
        private var timeout: Long? = null,
        private var followRedirects: Boolean? = null,
    ) {
        fun setMethod(value: String?) {
            method = if (value.isNullOrBlank()) null else value
        }

        fun getMethod(): String? {
            return method
        }

        fun getClick(): String? {
            return click
        }

        fun setClick(value: String?) {
            click = if (value.isNullOrBlank()) null else value
        }

        fun setCharset(value: String?) {
            charset = if (value.isNullOrBlank()) null else value
        }

        fun getCharset(): String? {
            return charset
        }

        fun setOrigin(value: String?) {
            origin = if (value.isNullOrBlank()) null else value
        }

        fun getOrigin(): String? {
            return origin
        }

        fun setRetry(value: String?) {
            retry = if (value.isNullOrEmpty()) null else value.toIntOrNull()
        }

        fun getRetry(): Int {
            return retry ?: 0
        }

        fun setTimeout(value: Long?) {
            timeout = if (value == null || value <= 0) null else value
        }

        fun getTimeout(): Long? {
            return timeout
        }

        fun setFollowRedirects(value: Boolean?) {
            followRedirects = value
        }

        fun getFollowRedirects(): Boolean? {
            return followRedirects
        }

        fun setType(value: String?) {
            type = if (value.isNullOrBlank()) null else value
        }

        fun getType(): String? {
            return type
        }

        fun useWebView(): Boolean {
            return when (webView) {
                null, "", false, "false" -> false
                else -> true
            }
        }

        fun usePhone(): Boolean {
            return when (usePhone) {
                null, "", false, "false" -> false
                else -> true
            }
        }

        fun usePhone(boolean: Boolean) {
            usePhone = if (boolean) true else null
        }

        fun useWebView(boolean: Boolean) {
            webView = if (boolean) true else null
        }

        fun setHeaders(value: String?) {
            headers = if (value.isNullOrBlank()) {
                null
            } else {
                GSON.fromJsonObject<Map<String, Any>>(value).getOrNull()
            }
        }

        fun getHeaderMap(): Map<*, *>? {
            return when (val value = headers) {
                is Map<*, *> -> value
                is String -> GSON.fromJsonObject<Map<String, Any>>(value).getOrNull()
                else -> null
            }
        }

        fun setBody(value: String?) {
            body = when {
                value.isNullOrBlank() -> null
                value.isJsonObject() -> GSON.fromJsonObject<Map<String, Any>>(value)
                value.isJsonArray() -> GSON.fromJsonArray<Map<String, Any>>(value)
                else -> value
            }
        }

        fun getBody(): String? {
            return body?.let {
                if (it is String) it else GSON.toJson(it)
            }
        }

        fun setWebJs(value: String?) {
            webJs = if (value.isNullOrBlank()) null else value
        }

        fun getWebJs(): String? {
            return webJs
        }

        fun setDnsIp(value: String?) {
            dnsIp = if (value.isNullOrBlank()) null else value
        }

        fun getDnsIp(): String? {
            return dnsIp
        }

        fun setJs(value: String?) {
            js = if (value.isNullOrBlank()) null else value
        }

        fun getJs(): String? {
            return js
        }

        fun setBodyJs(value: String?) {
            bodyJs = if (value.isNullOrBlank()) null else value
        }

        fun getBodyJs(): String? {
            return bodyJs
        }

        fun setServerID(value: String?) {
            serverID = if (value.isNullOrBlank()) null else value.toLong()
        }

        fun getServerID(): Long? {
            return serverID
        }

        fun setWebViewDelayTime(value: String?) {
            webViewDelayTime = if (value.isNullOrBlank()) null else value.toLong()
        }

        fun getWebViewDelayTime(): Long? {
            return webViewDelayTime
        }
    }

    data class ConcurrentRecord(
        val isConcurrent: Boolean,
        var time: Long,
        var frequency: Int
    )

}
