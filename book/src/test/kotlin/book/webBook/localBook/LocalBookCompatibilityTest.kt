package book.webBook.localBook

import book.model.Book
import book.model.BookType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalBookCompatibilityTest {

    @Test
    fun recognizesEveryServerSideLocalBookFormat() {
        val root = Files.createTempDirectory("local-book-formats-").toFile()
        try {
            listOf("txt", "epub", "umd", "cbz", "mobi", "azw", "azw3", "pdf").forEach { extension ->
                val file = root.resolve("book.$extension").apply { writeText("test") }
                assertTrue(LocalBook.isSupportedFile(file), extension)
            }
            assertFalse(LocalBook.isSupportedFile(root.resolve("book.zip").apply { writeText("test") }))
            assertFalse(LocalBook.isSupportedFile(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reportsLastModifiedForLocalFiles() {
        val file = Files.createTempFile("local-book-modified-", ".txt").toFile()
        try {
            val expected = file.lastModified()
            val book = localBook(file)
            assertEquals(expected, LocalBook.getLastModified(book).getOrThrow())
        } finally {
            file.delete()
        }
    }

    @Test
    fun deletionRemovesOnlyTheRequestedEpubOrCbzFile() {
        listOf("epub", "cbz").forEach { extension ->
            val root = Files.createTempDirectory("local-book-delete-").toFile()
            try {
                val target = root.resolve("target.$extension").apply { writeText("target") }
                val sibling = root.resolve("keep.txt").apply { writeText("keep") }

                LocalBook.deleteBook(localBook(target))

                assertFalse(target.exists(), extension)
                assertTrue(sibling.isFile, extension)
                assertTrue(root.isDirectory, extension)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun localBook(file: java.io.File) = Book(
        bookUrl = file.absolutePath,
        origin = BookType.local,
        originName = file.absolutePath,
        name = file.nameWithoutExtension
    )
}
