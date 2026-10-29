package book.webBook.localBook

import book.model.Book
import book.model.BookType
import io.legado.app.lib.mobi.KF6Book
import io.legado.app.lib.mobi.MobiReader
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MobiFileTest {

    @Test
    fun readsRealKf6HeaderMetadataAndSections() {
        val file = writeMobi("reader", ".mobi", htmlWithPageBreak())
        try {
            MobiReader().readMobi(file).use { parsed ->
                val kf6 = assertIs<KF6Book>(parsed)
                assertEquals("阶段18测试书", kf6.metadata.title)
                assertEquals(2, kf6.sections.size)
                assertTrue(kf6.getSectionText(kf6.sections.first()).contains("第一段正文"))
                assertTrue(kf6.getSectionText(kf6.sections.last()).contains("第二段正文"))
            }
            assertTrue(file.delete(), "关闭 MobiBook 后应释放 PDB 文件通道")
        } finally {
            file.delete()
        }
    }

    @Test
    fun localBookDispatchesMobiMetadataChaptersAndContent() {
        val file = writeMobi("local", ".azw3", htmlWithPageBreak())
        val book = Book(
            bookUrl = file.absolutePath,
            origin = BookType.local,
            originName = file.absolutePath,
            userid = "stage18-user"
        )
        try {
            MobiFile.upBookInfo(book)
            val chapters = LocalBook.getChapterList(book)
            val content = LocalBook.getContent(book, chapters.first()).orEmpty()

            assertEquals("阶段18测试书", book.name)
            assertEquals(1, chapters.size)
            assertEquals(0, chapters.first().index)
            assertEquals(book.bookUrl, chapters.first().bookUrl)
            assertEquals(1, book.totalChapterNum)
            assertEquals(chapters.first().title, book.latestChapterTitle)
            assertTrue(content.contains("第一段正文"))
            assertTrue(content.contains("第二段正文"))
        } finally {
            MobiFile.remove(book)
            file.delete()
        }
    }

    @Test
    fun createsOneSectionWhenKf6HasNoPageBreak() {
        val html = "<html><head><title>无分页</title></head><body>完整正文</body></html>"
        val file = writeMobi("no-page-break", ".azw", html)
        try {
            MobiReader().readMobi(file).use { parsed ->
                val kf6 = assertIs<KF6Book>(parsed)
                assertEquals(1, kf6.sections.size)
                assertTrue(kf6.getSectionText(kf6.sections.single()).contains("完整正文"))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun storesKf6ImagesInUserIsolatedAssetsAndRewritesContentUrl() {
        val image = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
        val html = "<html><head><title>图片章节</title></head><body>正文<img recindex=\"1\"></body></html>"
        val file = writeMobi("image", ".mobi", html, image)
        val root = Files.createTempDirectory("stage18-assets-").toFile()
        val book = Book(
            bookUrl = file.absolutePath,
            origin = BookType.local,
            originName = file.absolutePath,
            userid = "isolated-user"
        ).apply { setRootDir(root.absolutePath) }
        try {
            val chapter = LocalBook.getChapterList(book).single()
            val content = LocalBook.getContent(book, chapter).orEmpty()
            val assetUrl = Regex("/assets/[a-f0-9]{32}/mobi/[a-f0-9]{32}/[a-f0-9-]+\\.png")
                .find(content)?.value ?: error("正文没有改写为用户隔离的 PNG 地址：$content")
            val storedImage = File(root, "storage/${assetUrl.removePrefix("/")}")

            assertTrue(storedImage.isFile)
            assertTrue(storedImage.readBytes().contentEquals(image))
            assertTrue(!content.contains("recindex:"))
        } finally {
            MobiFile.remove(book)
            file.delete()
            root.deleteRecursively()
        }
    }

    private fun htmlWithPageBreak(): String =
        "<html><head><title>第一章</title></head><body>第一段正文<mbp:pagebreak/>第二段正文</body></html>"

    private fun writeMobi(
        prefix: String,
        extension: String,
        html: String,
        resource: ByteArray? = null
    ): File =
        Files.createTempFile("stage18-$prefix-", extension).toFile().apply {
            writeBytes(minimalKf6Mobi("阶段18测试书", html, resource))
        }

    private fun minimalKf6Mobi(title: String, html: String, resource: ByteArray?): ByteArray {
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        val textBytes = html.toByteArray(Charsets.UTF_8)
        val record0 = ByteArray(MOBI_HEADER_BYTES + titleBytes.size)
        val mobi = ByteBuffer.wrap(record0)
        mobi.putShort(0, 1)
        mobi.putInt(4, textBytes.size)
        mobi.putShort(8, 1)
        mobi.putShort(10, 4096)
        mobi.putShort(12, 0)
        putAscii(record0, 16, "MOBI")
        mobi.putInt(20, MOBI_HEADER_BYTES - 16)
        mobi.putInt(24, 2)
        mobi.putInt(28, 65001)
        mobi.putInt(32, 18)
        mobi.putInt(36, 6)
        mobi.putInt(84, MOBI_HEADER_BYTES)
        mobi.putInt(88, titleBytes.size)
        mobi.put(95, 9)
        mobi.putInt(108, 2)
        mobi.putInt(128, 0)
        mobi.putInt(240, 0)
        mobi.putInt(244, -1)
        titleBytes.copyInto(record0, MOBI_HEADER_BYTES)

        val recordCount = if (resource == null) 2 else 3
        val firstRecordOffset = 78 + recordCount * 8
        val secondRecordOffset = firstRecordOffset + record0.size
        val thirdRecordOffset = secondRecordOffset + textBytes.size
        val output = ByteArray(thirdRecordOffset + (resource?.size ?: 0))
        putAscii(output, 0, "Stage 18 KF6")
        putAscii(output, 60, "BOOKMOBI")
        val pdb = ByteBuffer.wrap(output)
        pdb.putShort(76, recordCount.toShort())
        pdb.putInt(78, firstRecordOffset)
        pdb.putInt(86, secondRecordOffset)
        if (resource != null) pdb.putInt(94, thirdRecordOffset)
        record0.copyInto(output, firstRecordOffset)
        textBytes.copyInto(output, secondRecordOffset)
        resource?.copyInto(output, thirdRecordOffset)
        return output
    }

    private fun putAscii(target: ByteArray, offset: Int, value: String) {
        value.toByteArray(Charsets.US_ASCII).copyInto(target, offset)
    }

    companion object {
        private const val MOBI_HEADER_BYTES = 248
    }
}
