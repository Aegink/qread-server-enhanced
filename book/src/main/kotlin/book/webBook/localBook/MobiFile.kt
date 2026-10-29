package book.webBook.localBook

import book.model.Book
import book.model.BookChapter
import book.util.FileUtils
import book.util.HtmlFormatter
import io.legado.app.lib.mobi.KF6Book
import io.legado.app.lib.mobi.KF8Book
import io.legado.app.lib.mobi.MobiBook
import io.legado.app.lib.mobi.MobiReader
import io.legado.app.lib.mobi.entities.TOC
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.InputStream
import java.nio.file.Paths
import java.security.MessageDigest

class MobiFile(override var book: Book) : CachedLocalBookParser {

    companion object : BaseLocalBookParse {
        private val parserCache = LocalBookParserCache(8, ::MobiFile)
        private val chapterUrlPrefixRegex = "^(?:skip:)?\\d+:".toRegex()
        private val xmlDeclarationRegex = "<\\?xml[^>]*>".toRegex()
        private val doctypeDeclarationRegex = "<!DOCTYPE[^>]*>".toRegex(RegexOption.IGNORE_CASE)

        override fun getChapterList(book: Book): ArrayList<BookChapter> =
            parserCache.withParser(book) { it.getChapterList() }

        override fun getContent(book: Book, chapter: BookChapter): String? =
            parserCache.withParser(book) { it.getContent(chapter) }

        override fun getImage(book: Book, href: String): InputStream? =
            parserCache.withParser(book) { it.getImage(href) }

        override fun upBookInfo(book: Book) {
            upBookInfo(book, false)
        }

        fun upBookInfo(book: Book, onlyCover: Boolean) {
            parserCache.withParser(book) {
                if (onlyCover) it.updateCover() else it.updateBookInfo()
            }
        }

        fun remove(book: Book) {
            parserCache.remove(book)
        }
    }

    private var mobiBook: MobiBook? = null

    private fun parsedBook(): MobiBook {
        mobiBook?.let { return it }
        return MobiReader().readMobi(book.getLocalFile()).also { mobiBook = it }
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val chapterList = when (val parsed = parsedBook()) {
            is KF8Book -> getChapterListKF8(parsed)
            is KF6Book -> getChapterListKF6(parsed)
            else -> error("不支持的 Mobi 版本")
        }
        chapterList.forEachIndexed { index, chapter ->
            chapter.index = index
            chapter.bookUrl = book.bookUrl
            chapter.userid = book.userid
        }
        book.latestChapterTitle = chapterList.lastOrNull()?.title
        book.totalChapterNum = chapterList.size
        return chapterList
    }

    private fun getChapterListKF6(kf6Book: KF6Book): ArrayList<BookChapter> {
        val chapterList = arrayListOf<BookChapter>()
        if (kf6Book.sectionIdMap[0] == null) {
            kf6Book.sections.firstOrNull()?.let { section ->
                val title = Jsoup.parse(kf6Book.getSectionText(section)).title().ifBlank { "卷首" }
                chapterList.add(BookChapter(title = title, url = "0:${section.href}"))
            }
        }
        appendToc(chapterList, kf6Book.toc)
        return chapterList
    }

    private fun getChapterListKF8(kf8Book: KF8Book): ArrayList<BookChapter> {
        val chapterList = arrayListOf<BookChapter>()
        if (kf8Book.sectionIdMap[0] == null) {
            kf8Book.sections.firstOrNull { it.href.isNotEmpty() }?.let { section ->
                val title = Jsoup.parse(kf8Book.getSectionText(section)).title().ifBlank { "卷首" }
                chapterList.add(BookChapter(title = title, url = "0:${section.href}"))
            }
        }
        appendToc(chapterList, kf8Book.toc)
        return chapterList
    }

    private fun appendToc(chapterList: ArrayList<BookChapter>, toc: List<TOC>?) {
        fun append(ref: TOC) {
            val chapter = BookChapter(
                title = ref.label,
                url = "${chapterList.size}:${ref.href}",
                isVolume = ref.subitems != null
            )
            val previous = chapterList.lastOrNull()
            if (previous?.isVolume == true && hrefOf(previous.url) == hrefOf(chapter.url)) {
                previous.url = "skip:${previous.url}"
            }
            previous?.putVariable("nextUrl", chapter.url)
            chapterList.add(chapter)
            ref.subitems?.forEach(::append)
        }
        toc?.forEach(::append)
    }

    private fun getContent(chapter: BookChapter): String? {
        if (chapter.isVolume && chapter.url.startsWith("skip:")) return ""
        return when (val parsed = parsedBook()) {
            is KF8Book -> getContentKF8(parsed, chapter)
            is KF6Book -> getContentKF6(parsed, chapter)
            else -> error("不支持的 Mobi 版本")
        }
    }

    private fun getContentKF6(kf6Book: KF6Book, chapter: BookChapter): String? {
        var section = kf6Book.getSectionByHref(hrefOf(chapter.url)) ?: return null
        val nextHref = chapter.getVariable("nextUrl")?.let(::hrefOf)
        val html = StringBuilder(kf6Book.getSectionText(section))
        while (true) {
            section = section.next ?: break
            if (section.href == nextHref || kf6Book.sectionIdMap[section.index] != null) break
            html.append(kf6Book.getSectionText(section))
        }
        return formatAndStoreImages(Jsoup.parse(html.toString())) { kf6Book.getResourceByHref(it) }
    }

    private fun getContentKF8(kf8Book: KF8Book, chapter: BookChapter): String? {
        val href = hrefOf(chapter.url)
        var section = kf8Book.getSectionByHref(href) ?: return null
        val nextHref = chapter.getVariable("nextUrl")?.let(::hrefOf)
        val nextPos = nextHref?.let(kf8Book::parsePosURI)
        val firstText = if (nextHref == null) {
            kf8Book.getSectionText(section)
        } else {
            kf8Book.getTextByHref(href, nextHref)
        }
        val html = StringBuilder(firstText)
        while (true) {
            if (nextPos != null && section.frags.any { it.index == nextPos.fid }) break
            section = section.next ?: break
            if (!section.linear) continue
            if (section.href == nextHref || kf8Book.sectionIdMap[section.index] != null) break
            html.append(kf8Book.getSectionText(section))
        }
        return formatAndStoreImages(Jsoup.parse(html.toString())) { kf8Book.getResourceByHref(it) }
    }

    private fun formatAndStoreImages(document: Document, resolver: (String) -> ByteArray?): String {
        document.select("title").remove()
        document.select("[style*=display:none]").remove()
        document.select("img[recindex]").forEach { image ->
            image.attr("src", "recindex:${image.attr("recindex")}")
            image.removeAttr("recindex")
        }
        document.select("img[src]").forEach { image ->
            val href = image.attr("src")
            if (href.startsWith("recindex:") || href.startsWith("kindle:embed:")) {
                resolver(href)?.let { bytes -> image.attr("src", storeImage(href, bytes)) }
            }
        }
        return HtmlFormatter.formatKeepImg(document.outerHtml())
            .replace(xmlDeclarationRegex, "")
            .replace(doctypeDeclarationRegex, "")
    }

    private fun getImage(href: String): InputStream? = when (val parsed = parsedBook()) {
        is KF8Book -> parsed.getResourceByHref(href)?.inputStream()
        is KF6Book -> parsed.getResourceByHref(href)?.inputStream()
        else -> null
    }

    private fun updateBookInfo() {
        val parsed = parsedBook()
        val metadata = parsed.metadata
        book.name = metadata.title.ifBlank { book.getLocalFile().nameWithoutExtension }
        metadata.author.firstOrNull()?.takeIf(String::isNotBlank)?.let { book.author = it }
        metadata.subject.takeIf(List<String>::isNotEmpty)?.let { book.kind = it.joinToString(",") }
        metadata.description.takeIf(String::isNotBlank)?.let { book.intro = HtmlFormatter.format(it) }
        updateCover(parsed)
    }

    private fun updateCover(parsed: MobiBook = parsedBook()) {
        val bytes = parsed.getCover() ?: return
        val relativePath = Paths.get(
            "assets",
            userAssetKey(),
            "covers",
            "${hash(bytes)}.${imageExtension(bytes)}"
        ).toString()
        val output = Paths.get(book.workRoot(), "storage", relativePath).toString()
        if (!File(output).exists()) FileUtils.writeBytes(output, bytes)
        book.coverUrl = "/${relativePath.replace(File.separatorChar, '/')}"
    }

    private fun storeImage(href: String, bytes: ByteArray): String {
        val relativePath = Paths.get(
            "assets",
            userAssetKey(),
            "mobi",
            hash(book.bookUrl.toByteArray()),
            "${hash(href.toByteArray())}-${hash(bytes)}.${imageExtension(bytes)}"
        ).toString()
        val output = Paths.get(book.workRoot(), "storage", relativePath).toString()
        if (!File(output).exists()) FileUtils.writeBytes(output, bytes)
        return "/${relativePath.replace(File.separatorChar, '/')}"
    }

    private fun userAssetKey(): String {
        val identity = book.userid.ifBlank { book.getUserNameSpace().ifBlank { "local" } }
        return hash(identity.toByteArray())
    }

    private fun imageExtension(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        ) -> "png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "jpg"
        bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "gif"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "bmp"
        else -> "bin"
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
        .take(32)

    private fun hrefOf(chapterUrl: String): String = chapterUrl.replaceFirst(chapterUrlPrefixRegex, "")

    override fun close() {
        mobiBook?.close()
        mobiBook = null
    }
}
