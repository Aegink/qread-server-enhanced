package book.webBook


import book.model.Book
import book.model.BookChapter
import book.model.BookSource
import book.model.rule.ContentRule
import book.util.AppPattern
import book.util.HtmlFormatter
import book.util.NetworkUtils
import book.webBook.analyzeRule.AnalyzeRule
import book.webBook.analyzeRule.AnalyzeUrl
import book.webBook.exception.ContentEmptyException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.apache.commons.text.StringEscapeUtils

object BookContent {

    suspend fun analyzeContent(
        body: String?,
        book: Book,
        bookChapter: BookChapter,
        bookSource: BookSource,
        baseUrl: String,
        redirectUrl: String,
        nextChapterUrl: String? = null,
        debugLog: DebugLog? = null
    ): String {
        body ?: throw Exception(
            "error_get_web_content"
        )
        debugLog?.log(bookSource.bookSourceUrl, "≡获取成功:${baseUrl}")
        val mNextChapterUrl = if (!nextChapterUrl.isNullOrEmpty()) {
            nextChapterUrl
        } else {
            // appDb.bookChapterDao.getChapter(book.bookUrl, bookChapter.index + 1)?.url
            null
        }
        val content = StringBuilder()
        val nextUrlList = arrayListOf(redirectUrl)
        val contentRule = bookSource.getContentRule()
        val analyzeRule = AnalyzeRule(book,debugLog, bookSource).setRuleType("CONTENT").setContent(body, baseUrl)
        analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.chapter=bookChapter
        analyzeRule.nextChapterUrl = mNextChapterUrl
        var contentData = analyzeContent(
            book, baseUrl, redirectUrl, body, contentRule, bookChapter, bookSource, mNextChapterUrl, debugLog = debugLog
        )
        content.append(contentData.first)
        if (contentData.second.size == 1) {
            var nextUrl = contentData.second[0]
            while (nextUrl.isNotEmpty() && !nextUrlList.contains(nextUrl)) {
                if (!mNextChapterUrl.isNullOrEmpty()
                    && NetworkUtils.getAbsoluteURL(redirectUrl, nextUrl)
                    == NetworkUtils.getAbsoluteURL(redirectUrl, mNextChapterUrl)
                ) break

                nextUrlList.add(nextUrl)
                val res = AnalyzeUrl(
                    mUrl = nextUrl,
                    source = bookSource,
                    ruleData = book,
                    headerMapF = bookSource.getHeaderMap(),debugLog = debugLog
                ).getStrResponseAwait(
                    jsStr = contentRule.webJs,
                    sourceRegex = contentRule.sourceRegex
                )
                res.body?.let { nextBody ->
                    contentData = analyzeContent(
                        book, nextUrl, res.url, nextBody, contentRule,
                        bookChapter, bookSource, mNextChapterUrl, false
                    )
                    nextUrl =
                        if (contentData.second.isNotEmpty()) contentData.second[0] else ""
                    content.append("\n").append(contentData.first)
                }
            }
            debugLog?.log(bookSource.bookSourceUrl, "◇本章总页数:${nextUrlList.size}")
        } else if (contentData.second.size > 1) {
            debugLog?.log(bookSource.bookSourceUrl, "◇并发解析正文,总页数:${contentData.second.size}")
            withContext(IO) {
                val asyncArray = Array(contentData.second.size) {
                    async(IO) {
                        val urlStr = contentData.second[it]
                        val analyzeUrl = AnalyzeUrl(
                            mUrl = urlStr,
                            source = bookSource,
                            ruleData = book,
                            headerMapF = bookSource.getHeaderMap(),debugLog = debugLog
                        )
                        val res = analyzeUrl.getStrResponseAwait(
                            jsStr = contentRule.webJs,
                            sourceRegex = contentRule.sourceRegex
                        )
                        analyzeContent(
                            book, urlStr, res.url, res.body!!, contentRule,
                            bookChapter, bookSource, mNextChapterUrl, false, debugLog = debugLog
                        ).first
                    }
                }
                asyncArray.forEach { coroutine ->
                    content.append("\n").append(coroutine.await())
                }
            }
        }
        val subContentRule = contentRule.subContent
        if (!subContentRule.isNullOrBlank()) {
            analyzeRule.getString(subContentRule).let { rawContent ->
                runCatching {
                    if (book.isOnLineTxt()) {
                        content.append("\n").append(rawContent)
                        return@let
                    }
                    val subContent = rawContent.trim().let {
                        if (it.startsWith("http", true)) {
                            AnalyzeUrl(
                                mUrl = it,
                                source = bookSource,
                                ruleData = book,
                                chapter = bookChapter,
                                headerMapF = bookSource.getHeaderMap(),
                                debugLog = debugLog
                            ).getStrResponseAwait().body
                        } else {
                            it
                        }
                    }
                    when {
                        book.isAudio() -> bookChapter.putLyric(subContent)
                        book.isVideo() -> bookChapter.putDanmaku(subContent)
                    }
                }.onFailure {
                    Debug.log(bookSource.bookSourceUrl, "获取副文出错, ${it.localizedMessage}")
                }
            }
        }
        var contentStr = content.toString()

        val replaceRegex = contentRule.replaceRegex
        if (!replaceRegex.isNullOrEmpty()) {
            contentStr = contentStr.split(AppPattern.LFRegex).joinToString("\n") { it.trim() }
            contentStr = analyzeRule.getString(replaceRegex, contentStr)
            if (book.isOnLineTxt()) {
                contentStr = contentStr.split(AppPattern.LFRegex).joinToString("\n") { "　　$it" }
            }
        }
        val titleRule = contentRule.title
        if (!titleRule.isNullOrBlank()) {
            val title = analyzeRule.runCatching {
                getString(titleRule)
            }.onFailure {
                Debug.log(bookSource.bookSourceUrl, "获取标题出错, ${it.localizedMessage}")
            }.getOrNull()
            title?.takeIf { it.isNotBlank() }?.let { extractedTitle ->
                var resolvedTitle = extractedTitle
                AppPattern.titleImageRegex.find(extractedTitle)?.let { match ->
                    val titleText = match.groupValues[1]
                    resolvedTitle = titleText.ifBlank { bookChapter.title }
                    bookChapter.imgUrl = match.groupValues[2]
                }
                bookChapter.title = resolvedTitle
                bookChapter.titleMD5 = null
            }
        }
        debugLog?.log(bookSource.bookSourceUrl, "┌获取章节名称")
        debugLog?.log(bookSource.bookSourceUrl, "└${bookChapter.title}")
        debugLog?.log(bookSource.bookSourceUrl, "┌获取正文内容 (长度：${contentStr.length})")
        debugLog?.log(bookSource.bookSourceUrl, "└\n${contentStr}")
        if (!bookChapter.isVolume && contentStr.isBlank()) {
            throw ContentEmptyException("内容为空")
        }
        return contentStr
    }

    @Throws(Exception::class)
    private fun analyzeContent(
        book: Book,
        baseUrl: String,
        redirectUrl: String,
        body: String,
        contentRule: ContentRule,
        chapter: BookChapter,
        bookSource: BookSource,
        nextChapterUrl: String?,
        printLog: Boolean = true,
        debugLog: DebugLog? = null
    ): Pair<String, List<String>> {
        val analyzeRule = AnalyzeRule(book, debugLog,bookSource).setRuleType("CONTENT")
        analyzeRule.setContent(body, baseUrl)
        val rUrl = analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.nextChapterUrl = nextChapterUrl
        val nextUrlList = arrayListOf<String>()
        analyzeRule.chapter = chapter
        //获取正文
        var content = analyzeRule.getString(contentRule.content, unescape = false)
        if (!book.isAudio() && !book.isVideo()) {
            content = HtmlFormatter.formatKeepImg(content, rUrl)
            if (content.indexOf('&') > -1) {
                content = StringEscapeUtils.unescapeHtml4(content)
            }
        }
        //获取下一页链接
        val nextUrlRule = contentRule.nextContentUrl
        if (!nextUrlRule.isNullOrEmpty()) {
            if(printLog) debugLog?.log(bookSource.bookSourceUrl, "┌获取正文下一页链接")
            analyzeRule.getStringList(nextUrlRule, isUrl = true)?.let {
                nextUrlList.addAll(it)
            }
            if(printLog) debugLog?.log(bookSource.bookSourceUrl, "└" + nextUrlList.joinToString("，"))
        }

        return Pair(content, nextUrlList)
    }
}
