package book.webBook.localBook

import book.model.Book
import book.model.BookType
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.io.path.readText

class LocalBookConcurrentReadTest {

    @Test
    fun readsSameEpubConcurrentlyWithIsolatedUserSettings() {
        val root = Files.createTempDirectory("local-book-concurrent-").toFile()
        val epub = createEpub(root)
        val userA = createBook(epub, root, "user-a", removeRuby = true)
        val userB = createBook(epub, root, "user-b", removeRuby = false)
        val chapterA = LocalBook.getChapterList(userA).single()
        val chapterB = LocalBook.getChapterList(userB).single()
        val failures = ConcurrentHashMap.newKeySet<String>()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)

        try {
            repeat(100) { index ->
                executor.submit {
                    start.await()
                    val content = LocalBook.getContent(userA, chapterA).orEmpty()
                    if ("annotation" in content || "Shared content" !in content) {
                        failures.add("user-a-$index")
                    }
                }
                executor.submit {
                    start.await()
                    val content = LocalBook.getContent(userB, chapterB).orEmpty()
                    if ("annotation" !in content || "Shared content" !in content) {
                        failures.add("user-b-$index")
                    }
                }
            }

            start.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS))
            assertTrue(failures.isEmpty(), "并发读取发生用户配置串扰：${failures.joinToString()}")
            assertEquals("user-a", userA.userid)
            assertEquals("user-b", userB.userid)
        } finally {
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }

    @Test
    fun parsesDifferentCbzMetadataForUsersSharingLogicalBookUrl() {
        val root = Files.createTempDirectory("local-cbz-concurrent-").toFile()
        val cbzA = createCbz(root, "user-a.cbz", "User A Book")
        val cbzB = createCbz(root, "user-b.cbz", "User B Book")
        val failures = ConcurrentHashMap.newKeySet<String>()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)

        try {
            repeat(100) { index ->
                executor.submit {
                    start.await()
                    val book = Book.initLocalBook("shared-book-url", cbzA.absolutePath, root.absolutePath, "user-a")
                    if (book.name != "User A Book" || book.userid != "user-a") failures.add("user-a-$index")
                }
                executor.submit {
                    start.await()
                    val book = Book.initLocalBook("shared-book-url", cbzB.absolutePath, root.absolutePath, "user-b")
                    if (book.name != "User B Book" || book.userid != "user-b") failures.add("user-b-$index")
                }
            }

            start.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS))
            assertTrue(failures.isEmpty(), "CBZ 元数据发生用户串扰：${failures.joinToString()}")
        } finally {
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }

    @Test
    fun cbzContentExtractsOnlyImageChaptersToVersionedUserAssets() {
        val root = Files.createTempDirectory("local-cbz-content-").toFile()
        try {
            val cbz = createCbz(root, "content.cbz", "Content Book")
            val book = Book.initLocalBook(cbz.absolutePath, cbz.absolutePath, root.absolutePath, "user-a")
            val chapters = LocalBook.getChapterList(book)

            assertEquals(listOf("001.jpg"), chapters.map { it.url })
            val content = LocalBook.getContent(book, chapters.single()).orEmpty()
            val src = Regex("src=\"([^\"]+)\"").find(content)?.groupValues?.get(1).orEmpty()
            assertTrue(src.startsWith("http//assets/"), content)
            val relativePath = src.removePrefix("http/").removePrefix("/")
            val extracted = File(root, "storage/$relativePath")
            assertTrue(extracted.isFile)
            assertEquals(
                listOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()),
                extracted.readBytes().toList()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun epubWithoutCoverDoesNotPublishMissingCoverUrl() {
        val root = Files.createTempDirectory("local-epub-no-cover-").toFile()
        try {
            val epub = createEpub(root, includeCover = false)
            val book = createBook(epub, root, "user-a", removeRuby = false)

            EpubFile.upBookInfo(book, onlyCover = true)

            assertNull(book.coverUrl)
            assertFalse(File(root, "storage").walkTopDown().any { it.isFile })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removedLocalBookDeadMembersStayRemoved() {
        val localBookDir = Path.of("src", "main", "kotlin", "book", "webBook", "localBook")
        val epubSource = localBookDir.resolve("EpubFile.kt").readText()
        val umdSource = localBookDir.resolve("UmdFile.kt").readText()
        val cbzSource = localBookDir.resolve("CbzFile.kt").readText()

        assertFalse(epubSource.contains("getContent2("))
        assertFalse(umdSource.contains("fun getImage("))
        assertFalse(Regex("init\\s*\\{\\s*}").containsMatchIn(epubSource))
        assertFalse(Regex("init\\s*\\{\\s*}").containsMatchIn(umdSource))
        assertFalse(Regex("getContent\\([^)]*\\)[^{]*\\{\\s*return \\\"\\\"").containsMatchIn(cbzSource))
    }

    private fun createBook(
        epub: File,
        root: File,
        userid: String,
        removeRuby: Boolean
    ): Book {
        return Book(
            bookUrl = epub.absolutePath,
            origin = BookType.local,
            originName = epub.absolutePath,
            name = "Concurrent EPUB",
            userid = userid
        ).also {
            it.setRootDir(root.absolutePath)
            if (removeRuby) it.setDelTag(Book.rubyTag)
        }
    }

    private fun createEpub(root: File, includeCover: Boolean = true): File {
        val epub = File(root, if (includeCover) "concurrent.epub" else "no-cover.epub")
        val coverMetadata = if (includeCover) "<meta name=\"cover\" content=\"cover-image\"/>" else ""
        val coverManifest = if (includeCover) {
            "<item id=\"cover-image\" href=\"cover.jpg\" media-type=\"image/jpeg\"/>"
        } else ""
        ZipOutputStream(FileOutputStream(epub)).use { zip ->
            zip.addEntry("mimetype", "application/epub+zip")
            zip.addEntry(
                "META-INF/container.xml",
                """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                """.trimIndent()
            )
            zip.addEntry(
                "OEBPS/content.opf",
                """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:identifier id="BookId">concurrent-test</dc:identifier>
                        <dc:title>Concurrent EPUB</dc:title>
                        <dc:language>en</dc:language>
                        $coverMetadata
                      </metadata>
                      <manifest>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                        $coverManifest
                      </manifest>
                      <spine toc="ncx"><itemref idref="chapter"/></spine>
                    </package>
                """.trimIndent()
            )
            zip.addEntry(
                "OEBPS/toc.ncx",
                """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                      <head><meta name="dtb:uid" content="concurrent-test"/></head>
                      <docTitle><text>Concurrent EPUB</text></docTitle>
                      <navMap>
                        <navPoint id="chapter-1" playOrder="1">
                          <navLabel><text>Chapter 1</text></navLabel>
                          <content src="chapter.xhtml"/>
                        </navPoint>
                      </navMap>
                    </ncx>
                """.trimIndent()
            )
            zip.addEntry(
                "OEBPS/chapter.xhtml",
                """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <head><title>Chapter 1</title></head>
                      <body><p><ruby>Word<rt>annotation</rt></ruby> Shared content</p></body>
                    </html>
                """.trimIndent()
            )
            if (includeCover) {
                zip.addEntry("OEBPS/cover.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
            }
        }
        return epub
    }

    private fun createCbz(root: File, fileName: String, title: String): File {
        val cbz = File(root, fileName)
        ZipOutputStream(FileOutputStream(cbz)).use { zip ->
            zip.addEntry(
                "ComicInfo.xml",
                """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <ComicInfo><Title>$title</Title><Writer>Test Author</Writer></ComicInfo>
                """.trimIndent()
            )
            zip.addEntry("001.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
            zip.addEntry("notes.txt", "not an image")
        }
        return cbz
    }

    private fun ZipOutputStream.addEntry(name: String, content: String) {
        addEntry(name, content.toByteArray(Charsets.UTF_8))
    }

    private fun ZipOutputStream.addEntry(name: String, content: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(content)
        closeEntry()
    }
}
