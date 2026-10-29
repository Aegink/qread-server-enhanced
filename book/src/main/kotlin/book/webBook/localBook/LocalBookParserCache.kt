package book.webBook.localBook

import book.model.Book
import book.util.SynchronizedLruCache

internal interface CachedLocalBookParser {
    var book: Book
    fun close() = Unit
}

internal class LocalBookParserCache<T : CachedLocalBookParser>(
    maxSize: Int,
    private val parserFactory: (Book) -> T
) {
    private data class CacheKey(val userid: String, val bookUrl: String)

    private data class FileStamp(
        val path: String,
        val size: Long,
        val lastModified: Long
    )

    private data class CacheEntry<T>(val fileStamp: FileStamp, val parser: T)

    private val cache = SynchronizedLruCache<CacheKey, CacheEntry<T>>(maxSize) { it.parser.close() }

    fun <R> withParser(book: Book, action: (T) -> R): R {
        val key = CacheKey(book.userid, book.bookUrl)
        val file = book.getLocalFile()
        val fileStamp = FileStamp(file.absolutePath, file.length(), file.lastModified())
        val entry = cache.getOrPut(key, { it.fileStamp == fileStamp }) {
            CacheEntry(fileStamp, parserFactory(book))
        }

        return synchronized(entry.parser) {
            entry.parser.book = book
            action(entry.parser)
        }
    }

    fun remove(book: Book) {
        cache.remove(CacheKey(book.userid, book.bookUrl))
    }

    internal fun size(): Int = cache.size()
}
