package book.webBook.localBook

import book.model.Book
import book.model.BookChapter
import book.util.FileUtils
import book.util.HtmlFormatter
import book.util.MD5Utils
import book.util.getFileExtetion
import book.util.xml2map
import java.io.File
import java.io.IOException
import java.util.*
import java.nio.file.Paths
import java.util.zip.ZipFile
import java.util.zip.ZipEntry

class CbzFile(override var book: Book) : CachedLocalBookParser {
    var info: MutableMap<String, Any>? = null
    var cover: ByteArray? = null

    companion object {
        private const val maxImageBytes = 20 * 1024 * 1024
        private val imageExtensions = setOf("jpg", "jpeg", "gif", "png", "bmp", "webp")
        private val parserCache = LocalBookParserCache(8, ::CbzFile)

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

    private fun parseBookInfo(): Pair<MutableMap<String, Any>?, ByteArray?> {
        if (cover != null || info != null) {
            return Pair(info, cover)
        }
        ZipFile(book.getLocalFile()).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val zipEntry: ZipEntry = entries.nextElement() as ZipEntry
                if (!zipEntry.isDirectory) {
                    val name = zipEntry.name
                    if (name == "ComicInfo.xml") {
                        zf.getInputStream(zipEntry).use { inputStream ->
                            info = xml2map(inputStream)
                        }
                    } else if (cover == null) {
                        val ext = getFileExtetion(name).lowercase()
                        if (imageExtensions.contains(ext)) {
                            cover = zf.getInputStream(zipEntry).use { it.readBytes() }
                        }
                    }
                }
                if (cover != null && info != null) {
                    break
                }
            }
        }
        return Pair(info, cover)
    }

    private fun upBookInfo() {
        val result = parseBookInfo()
        if (result.first != null) {
            val bookInfo = result.first as Map<String, Any>
            val info = bookInfo.get("ComicInfo") as Map<String, Any>? ?: null
            book.name = (info?.get("Title") ?: book.name) as String
            book.author = (info?.get("Writer") ?: book.author) as String
        }
        updateCover()
    }

    private fun updateCover() {
        val coverFile = "${MD5Utils.md5Encode16(book.bookUrl)}.jpg"
        val relativeCoverUrl = Paths.get("assets", book.getUserNameSpace(), "covers", coverFile).toString()
        book.coverUrl = "/" + relativeCoverUrl
        val coverUrl = Paths.get(book.workRoot(), "storage", relativeCoverUrl).toString()
        if (!File(coverUrl).exists()) {
            val result = parseBookInfo()
            result.second?.let { coverBytes ->
                FileUtils.writeBytes(coverUrl, coverBytes)
            }
        }
    }

    private fun getContent(chapter: BookChapter): String? {
        val extension = getFileExtetion(chapter.url).lowercase()
        if (extension !in imageExtensions) return null
        val localFile = book.getLocalFile()
        val bytes = ZipFile(localFile).use { zip ->
            val entry = zip.getEntry(chapter.url)?.takeUnless { it.isDirectory } ?: return null
            if (entry.size > maxImageBytes) throw IOException("CBZ 图片超过20MB: ${entry.name}")
            zip.getInputStream(entry).use { input ->
                input.readNBytes(maxImageBytes + 1).also {
                    if (it.size > maxImageBytes) throw IOException("CBZ 图片超过20MB: ${entry.name}")
                }
            }
        }
        val userKey = MD5Utils.md5Encode16(
            book.userid.ifBlank { book.getUserNameSpace().ifBlank { "local" } }
        )
        val archiveKey = MD5Utils.md5Encode16(
            "${book.bookUrl}:${localFile.length()}:${localFile.lastModified()}"
        )
        val imageName = "${MD5Utils.md5Encode16(chapter.url)}.$extension"
        val relativePath = Paths.get("assets", userKey, "cbz", archiveKey, imageName).toString()
        val output = Paths.get(book.workRoot(), "storage", relativePath).toString()
        if (!File(output).exists() && !FileUtils.writeBytes(output, bytes)) {
            throw IOException("CBZ 图片写入失败: ${chapter.url}")
        }
        val contentUrl = "/${relativePath.replace(File.separatorChar, '/')}"
        return HtmlFormatter.formatKeepImg("<img src=\"http/$contentUrl\">")
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val chapterList = ArrayList<BookChapter>()
        val imageFileList = arrayListOf<String>()
        ZipFile(book.getLocalFile()).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val zipEntry: ZipEntry = entries.nextElement() as ZipEntry
                if (!zipEntry.isDirectory) {
                    val name = zipEntry.name
                    if (getFileExtetion(name).lowercase() in imageExtensions) {
                        imageFileList.add(name)
                    }
                }
            }
        }

        // 排序
        imageFileList.sort()
        for (i in 0 until imageFileList.size) {
            val name = imageFileList.get(i)
            val chapter = BookChapter()
            chapter.title = name
            chapter.index = i
            chapter.bookUrl = book.bookUrl
            chapter.url = name
            chapterList.add(chapter)
        }
        book.latestChapterTitle = chapterList.lastOrNull()?.title
        book.totalChapterNum = chapterList.size
        return chapterList
    }
}
