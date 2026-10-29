package book.webBook.localBook

import book.model.Book
import book.model.BookChapter
import book.util.FileUtils
import book.util.MD5Utils
import me.ag2s.umdlib.domain.UmdBook
import me.ag2s.umdlib.umd.UmdReader
import java.io.File
import java.util.*
import java.nio.file.Paths

class UmdFile(override var book: Book) : CachedLocalBookParser {
    companion object {
        private val parserCache = LocalBookParserCache(8, ::UmdFile)

        fun getChapterList(book: Book): ArrayList<BookChapter> {
            return parserCache.withParser(book) { it.getChapterList() }
        }

        fun getContent(book: Book, chapter: BookChapter): String? {
            return parserCache.withParser(book) { it.getContent(chapter) }
        }

        fun upBookInfo(book: Book, onlyCover: Boolean = false) {
            parserCache.withParser(book) {
                if (onlyCover) {
                    it.updateCover()
                } else {
                    it.upBookInfo()
                }
            }
        }

        fun remove(book: Book) {
            parserCache.remove(book)
        }
    }


    private var umdBook: UmdBook? = null
        get() {
            if (field != null) {
                return field
            }
            field = readUmd()
            return field
        }


    private fun readUmd(): UmdBook? {
        return LocalBook.getBookInputStream(book).use { input ->
            UmdReader().read(input)
        }
    }

    private fun upBookInfo() {
        if (umdBook == null) {
            parserCache.remove(book)
            book.intro = "书籍导入异常"
        } else {
            val hd = umdBook!!.header
            book.name = hd.title
            book.author = hd.author
            book.kind = hd.bookType

            updateCover()
        }
    }

    private fun updateCover() {
        if (umdBook == null) {
            parserCache.remove(book)
            return
        }
        val coverFile = "${MD5Utils.md5Encode16(book.bookUrl)}.jpg"
        val relativeCoverUrl = Paths.get("assets", book.getUserNameSpace(), "covers", coverFile).toString()
        book.coverUrl = "/" + relativeCoverUrl
        val coverUrl = Paths.get(book.workRoot(), "storage", relativeCoverUrl).toString()
        if (!File(coverUrl).exists()) {
            FileUtils.writeBytes(coverUrl, umdBook!!.cover.coverData)
        }
    }

    private fun getContent(chapter: BookChapter): String? {
        return umdBook?.chapters?.getContentString(chapter.index)
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val chapterList = ArrayList<BookChapter>()
        umdBook?.chapters?.titles?.forEachIndexed { index, _ ->
            val title = umdBook!!.chapters.getTitle(index)
            val chapter = BookChapter()
            chapter.title = title
            chapter.index = index
            chapter.bookUrl = book.bookUrl
            chapter.url = index.toString()
            chapterList.add(chapter)
        }
        book.latestChapterTitle = chapterList.lastOrNull()?.title
        book.totalChapterNum = chapterList.size
        return chapterList
    }

}
