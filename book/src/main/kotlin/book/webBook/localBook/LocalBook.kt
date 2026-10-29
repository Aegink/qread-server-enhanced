package book.webBook.localBook

import book.model.Book
import book.model.BookChapter
import book.util.help.BookHelp
import book.webBook.exception.TocEmptyException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.regex.Pattern

object LocalBook {

    private val supportedExtensions = setOf("txt", "epub", "umd", "cbz", "mobi", "azw", "azw3", "pdf")

    private val nameAuthorPatterns = arrayOf(
        Pattern.compile("(.*?)《([^《》]+)》.*?作者：(.*)"),
        Pattern.compile("(.*?)《([^《》]+)》(.*)"),
        Pattern.compile("(^)(.+) 作者：(.+)$"),
        Pattern.compile("(^)(.+) by (.+)$")
    )

    @Throws(FileNotFoundException::class, SecurityException::class)
    fun getBookInputStream(book: Book): InputStream {
        val file = book.getLocalFile()
        if (file.exists()) {
            return FileInputStream(file)
        }
        throw FileNotFoundException(book.name + " 文件不存在")
    }

    fun isSupportedFile(file: File): Boolean =
        file.isFile && file.extension.lowercase() in supportedExtensions

    fun getLastModified(book: Book): Result<Long> = runCatching {
        val file = book.getLocalFile()
        if (!file.isFile) throw FileNotFoundException("${book.name} 文件不存在")
        file.lastModified()
    }

    fun upBookInfo(book: Book, onlyCover: Boolean = false) {
        when {
            book.isEpub() -> EpubFile.upBookInfo(book, onlyCover)
            book.isUmd() -> UmdFile.upBookInfo(book, onlyCover)
            book.isCbz() -> CbzFile.upBookInfo(book, onlyCover)
            book.isMobi() -> MobiFile.upBookInfo(book, onlyCover)
            book.isPdf() -> PdfFile.upBookInfo(book, onlyCover)
        }
    }

    @Throws(Exception::class)
    fun getChapterList(book: Book): ArrayList<BookChapter> {
        val chapters = when {
            book.isEpub() -> {
                EpubFile.getChapterList(book)
            }
            book.isUmd() -> {
                UmdFile.getChapterList(book)
            }
            book.isCbz() -> {
                CbzFile.getChapterList(book)
            }
            book.isMobi() -> {
                MobiFile.getChapterList(book)
            }
            book.isPdf() -> {
                PdfFile.getChapterList(book)
            }
            else -> {
                TextFile.getChapterList(book)
            }
        }
        if (chapters.isEmpty()) {
            throw TocEmptyException("Chapterlist is empty  " + book.getLocalFile())
        }
        return chapters
    }

    fun getContent(book: Book, chapter: BookChapter): String? {
        return when {
            book.isEpub() -> {
                EpubFile.getContent(book, chapter)
            }
            book.isUmd() -> {
                UmdFile.getContent(book, chapter)
            }
            book.isCbz() -> {
                CbzFile.getContent(book, chapter)
            }
            book.isMobi() -> {
                MobiFile.getContent(book, chapter)
            }
            book.isPdf() -> {
                PdfFile.getContent(book, chapter)
            }
            else -> {
                TextFile.getContent(book, chapter)
            }
        }
    }

    fun analyzeNameAuthor(fileName: String): Pair<String, String> {
        val tempFileName = fileName.substringBeforeLast(".")
        var name: String
        var author: String

        for (pattern in nameAuthorPatterns) {
            pattern.matcher(tempFileName).takeIf { it.find() }?.run {
                name = group(2)!!
                val group1 = group(1) ?: ""
                val group3 = group(3) ?: ""
                author = BookHelp.formatBookAuthor(group1 + group3)
                return Pair(name, author)
            }
        }

        name = BookHelp.formatBookName(tempFileName)
        author = BookHelp.formatBookAuthor(tempFileName.replace(name, ""))
            .takeIf { it.length != tempFileName.length } ?: ""

        return Pair(name, author)
    }

    fun deleteBook(book: Book) {
        kotlin.runCatching {
            when {
                book.isEpub() -> EpubFile.remove(book)
                book.isUmd() -> UmdFile.remove(book)
                book.isCbz() -> CbzFile.remove(book)
                book.isMobi() -> MobiFile.remove(book)
                book.isPdf() -> PdfFile.remove(book)
            }
            val bookFile = book.getLocalFile()
            if (bookFile.isFile) {
                bookFile.delete()
            }
        }
    }
}
