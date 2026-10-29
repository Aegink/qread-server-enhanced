package book.util.http

import book.util.Base64
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.*

class JsonpResponse : Connection.Response {

    private var responseCharset: String? = null
    private val responseCookies = linkedMapOf<String, String>()
    private val removedCookies = hashSetOf<String>()

    var url: String = ""

    var method:String = "get"

    var headers:MutableMap<String,List<String>> = mutableMapOf()

    var body:String =""

    var statusCode:Int=200

    var statusMessage:String=""

    override fun url(): URL {
        return URI.create(url).toURL()
    }

    override fun url(url: URL): Connection.Response {
        this.url = url.toString()
        return this
    }

    override fun method(): Connection.Method {
        return runCatching { Connection.Method.valueOf(method.uppercase(Locale.ROOT)) }
            .getOrElse { throw IllegalStateException("不支持的 HTTP 方法: $method", it) }
    }

    override fun method(method: Connection.Method): Connection.Response {
        this.method = method.name
        return this
    }

    override fun header(name: String): String? {
        return findHeaderKey(name)?.let { headers[it]?.firstOrNull() }
    }

    override fun header(name: String, value: String): Connection.Response {
        val key = findHeaderKey(name) ?: name
        headers[key] = mutableListOf(value)
        return this
    }

    override fun headers(name: String): MutableList<String> {
        return findHeaderKey(name)?.let { headers[it]?.toMutableList() } ?: mutableListOf()
    }

    override fun headers(): MutableMap<String, String> {
        val myheader:MutableMap<String,String> = mutableMapOf()
        headers.forEach{
            kotlin.runCatching {
                myheader[it.key]=it.value.first()
            }
        }
       return myheader
    }

    override fun addHeader(name: String, value: String): Connection.Response {
        val key = findHeaderKey(name) ?: name
        headers.getOrPut(key) { mutableListOf() }.let { values ->
            headers[key] = values.toMutableList().apply { add(value) }
        }
        return this
    }

    override fun hasHeader(name: String): Boolean {
        return findHeaderKey(name) != null
    }

    override fun hasHeaderWithValue(name: String, value: String): Boolean {
        return headers(name).any { it.equals(value, ignoreCase = true) }
    }

    override fun removeHeader(name: String): Connection.Response {
        findHeaderKey(name)?.let(headers::remove)
        return this
    }

    override fun multiHeaders(): MutableMap<String, MutableList<String>> {
        val newh:MutableMap<String, MutableList<String>> = mutableMapOf()
        headers.forEach{
            newh[it.key]=it.value.toMutableList()
        }
        return  newh
    }

    override fun cookie(name: String): String? {
        return cookies()[name]
    }

    override fun cookie(name: String, value: String): Connection.Response {
        responseCookies[name] = value
        removedCookies.remove(name)
        return this
    }

    override fun hasCookie(name: String): Boolean {
        return  cookies().containsKey(name)
    }

    override fun removeCookie(name: String): Connection.Response {
        responseCookies.remove(name)
        removedCookies.add(name)
        return this
    }

    override fun cookies(): MutableMap<String, String> {
        val cookieMap = linkedMapOf<String, String>()
        findHeaderKey("Set-Cookie")?.let { key ->
            headers[key].orEmpty().forEach { header ->
                val pair = header.substringBefore(';')
                val separator = pair.indexOf('=')
                if (separator > 0) {
                    cookieMap[pair.substring(0, separator).trim()] = pair.substring(separator + 1).trim()
                }
            }
        }
        cookieMap.putAll(responseCookies)
        removedCookies.forEach(cookieMap::remove)
        return cookieMap
    }

    override fun statusCode(): Int {
        return statusCode
    }

    override fun statusMessage(): String {
        return statusMessage
    }

    override fun charset(): String? {
        responseCharset?.let { return it }
        val match = contentType()?.let { CHARSET_PATTERN.find(it) }
        return match?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
    }

    override fun charset(charset: String): Connection.Response {
        responseCharset = Charset.forName(charset).name()
        return this
    }

    override fun contentType(): String? {
        return header("Content-Type")
    }

    override fun parse(): Document {
        return Jsoup.parse(body, url)
    }

    override fun body(): String {
        return body
    }

    override fun bodyAsBytes(): ByteArray {
        val charset = charset()?.let(Charset::forName) ?: Charsets.UTF_8
        return body.toByteArray(charset)
    }

    override fun bufferUp(): Connection.Response {
        return this
    }

    override fun bodyStream(): BufferedInputStream {
        return BufferedInputStream(ByteArrayInputStream(bodyAsBytes()))
    }

    private fun findHeaderKey(name: String): String? =
        headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }

    companion object {
        private val CHARSET_PATTERN = Regex("(?i)(?:^|;)\\s*charset\\s*=\\s*[\\\"']?([^;\\\"']+)")
    }
}

data class  MyResponse (
    var url: String = "",

    var method:String = "get",

    var headers:MutableMap<String,List<String>> = mutableMapOf(),

    var body:String ="",

    var statusCode:Int=200,

    var statusMessage:String="",
){
    fun  tojsonresponse():JsonpResponse{
        val response=JsonpResponse()
        response.url=url
        response.method=method
        response.headers=headers
        val mybody=Base64.decode(body,Base64.DEFAULT)
        val contentType=response.contentType()
        var encoding = "utf-8"
        if (contentType != null) {
            response.charset()?.let { encoding = it }
            if (!contentType.contains("charset=", ignoreCase = true) && contentType.contains("html", ignoreCase = true)) {
                val str = String(mybody, Charsets.ISO_8859_1)
                if (str.contains("gb2312", ignoreCase = true) || str.contains("gbk", ignoreCase = true)) {
                   encoding = "gbk"
               }
            }
        }
        val charset = if (encoding.equals("gb2312", ignoreCase = true)) {
            Charset.forName("GBK")
        } else {
            runCatching { Charset.forName(encoding) }.getOrDefault(Charsets.UTF_8)
        }
        response.charset(charset.name())
        response.body = String(mybody, charset)
       // println(response.body)
        response.statusCode=statusCode
        response.statusMessage=statusMessage
        return response
    }

    fun text(responseBytes: ByteArray ,charsetName: String? ): String {
        val charset = if (charsetName.equals("gb2312", ignoreCase = true)) {
            Charset.forName("GBK")
        } else {
            runCatching { Charset.forName(charsetName ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
        }
        return String(responseBytes, charset)
    }
}
