package book.webBook.localBook

import book.model.Book
import book.model.BookChapter
import book.util.FileUtils
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Paths
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.sqrt

class PdfFile(override var book: Book) : CachedLocalBookParser {

    companion object : BaseLocalBookParse {
        const val PAGE_SIZE = 10

        private const val MAX_PDF_BYTES = 100L * 1024 * 1024
        private const val MAX_PAGES = 10_000
        private const val MAX_CHAPTER_CHARS = 16 * 1024 * 1024
        private const val MAX_RENDER_PIXELS = 16_000_000.0
        private val parserCache = LocalBookParserCache(4, ::PdfFile)

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

    private var document: PDDocument? = null

    private fun parsedDocument(): PDDocument {
        document?.let { return it }
        val file = book.getLocalFile()
        require(file.length() in 1..MAX_PDF_BYTES) { "PDF 文件为空或超过100MB" }
        return Loader.loadPDF(file).also { parsed ->
            try {
                require(!parsed.isEncrypted) { "不支持加密的 PDF 文件" }
                require(parsed.numberOfPages in 1..MAX_PAGES) { "PDF 页数无效或超过10000页" }
                document = parsed
            } catch (e: Exception) {
                parsed.close()
                throw e
            }
        }
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val pageCount = parsedDocument().numberOfPages
        val chapterCount = ceil(pageCount.toDouble() / PAGE_SIZE).toInt()
        val chapters = ArrayList<BookChapter>(chapterCount)
        repeat(chapterCount) { index ->
            val startPage = index * PAGE_SIZE + 1
            val endPage = ((index + 1) * PAGE_SIZE).coerceAtMost(pageCount)
            chapters.add(
                BookChapter(
                    url = "pdf:$startPage-$endPage",
                    title = if (startPage == endPage) "第 $startPage 页" else "第 $startPage-$endPage 页",
                    bookUrl = book.bookUrl,
                    index = index,
                    userid = book.userid
                )
            )
        }
        book.latestChapterTitle = chapters.lastOrNull()?.title
        book.totalChapterNum = chapters.size
        return chapters
    }

    private fun getContent(chapter: BookChapter): String {
        val parsed = parsedDocument()
        val startPage = chapter.index * PAGE_SIZE + 1
        require(startPage in 1..parsed.numberOfPages) { "PDF 章节索引越界" }
        val endPage = (startPage + PAGE_SIZE - 1).coerceAtMost(parsed.numberOfPages)
        val text = PDFTextStripper().apply {
            sortByPosition = true
            this.startPage = startPage
            this.endPage = endPage
        }.getText(parsed).replace("\r\n", "\n").trim()
        require(text.length <= MAX_CHAPTER_CHARS) { "PDF 章节文本超过16MB" }
        return text
    }

    private fun getImage(href: String): InputStream? {
        val pageIndex = if (href.startsWith("pdf:")) {
            href.removePrefix("pdf:").substringBefore('-').toIntOrNull()?.minus(1)
        } else {
            href.toIntOrNull()
        } ?: return null
        if (pageIndex !in 0 until parsedDocument().numberOfPages) return null
        val image = renderPage(pageIndex)
        return ByteArrayOutputStream().use { output ->
            try {
                ImageIO.write(image, "jpg", output)
                ByteArrayInputStream(output.toByteArray())
            } finally {
                image.flush()
            }
        }
    }

    private fun updateBookInfo() {
        val parsed = parsedDocument()
        val information = parsed.documentInformation
        book.name = information.title?.takeIf(String::isNotBlank) ?: book.getLocalFile().nameWithoutExtension
        information.author?.takeIf(String::isNotBlank)?.let { book.author = it }
        information.subject?.takeIf(String::isNotBlank)?.let { book.intro = it }
        information.keywords?.takeIf(String::isNotBlank)?.let { book.kind = it }
        updateCover(parsed)
    }

    private fun updateCover(parsed: PDDocument = parsedDocument()) {
        val file = book.getLocalFile()
        val coverName = "${hash("${book.bookUrl}:${file.length()}:${file.lastModified()}".toByteArray())}.jpg"
        val relativePath = Paths.get("assets", userAssetKey(), "covers", coverName).toString()
        val output = Paths.get(book.workRoot(), "storage", relativePath).toString()
        if (!File(output).exists()) {
            val image = renderPage(0, parsed)
            try {
                val bytes = ByteArrayOutputStream().use { stream ->
                    check(ImageIO.write(image, "jpg", stream)) { "JVM 不支持 JPEG 编码" }
                    stream.toByteArray()
                }
                check(FileUtils.writeBytes(output, bytes)) { "PDF 封面写入失败" }
            } finally {
                image.flush()
            }
        }
        book.coverUrl = "/${relativePath.replace(File.separatorChar, '/')}"
    }

    private fun renderPage(pageIndex: Int, parsed: PDDocument = parsedDocument()) =
        parsed.getPage(pageIndex).mediaBox.let { box ->
            val pagePixels = box.width.toDouble() * box.height.toDouble()
            val scale = if (pagePixels <= 0.0) 1f else sqrt(MAX_RENDER_PIXELS / pagePixels).coerceAtMost(2.0).toFloat()
            PDFRenderer(parsed).renderImage(pageIndex, scale, ImageType.RGB)
        }

    private fun userAssetKey(): String {
        val identity = book.userid.ifBlank { book.getUserNameSpace().ifBlank { "local" } }
        return hash(identity.toByteArray())
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
        .take(32)

    override fun close() {
        document?.close()
        document = null
    }
}
