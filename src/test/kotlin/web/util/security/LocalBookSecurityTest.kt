package web.util.security

import org.junit.jupiter.api.Test
import org.noear.solon.core.handle.ContextEmpty
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalBookSecurityTest {
    @Test
    fun onlySocketLoopbackAddressesAreAccepted() {
        assertTrue(LocalBookSecurity.isLoopbackSocketAddress("127.0.0.1"))
        assertTrue(LocalBookSecurity.isLoopbackSocketAddress("::1"))
        assertTrue(LocalBookSecurity.isLoopbackSocketAddress("0:0:0:0:0:0:0:1"))
        assertFalse(LocalBookSecurity.isLoopbackSocketAddress("192.168.1.10"))
        assertFalse(LocalBookSecurity.isLoopbackSocketAddress("localhost"))
        assertFalse(LocalBookSecurity.isLoopbackSocketAddress(null))
    }

    @Test
    fun forwardedLoopbackHeaderCannotOverrideSocketAddress() {
        val context = object : ContextEmpty() {
            override fun remoteIp(): String = "192.168.1.10"
        }
        context.headerMap().put("X-Forwarded-For", "127.0.0.1")
        context.headerMap().put("X-Real-IP", "127.0.0.1")
        assertFalse(LocalBookSecurity.isLocalSocketRequest(context))
    }

    @Test
    fun validatesTextAndRejectsBinaryOrOversizedContent() {
        val text = "第一章\n这是正文。\n".toByteArray(Charsets.UTF_8)
        assertNull(LocalBookSecurity.validateBookUpload("book.TXT", text.size.toLong(), text))
        assertEquals("TXT 文件内容无效", LocalBookSecurity.validateBookUpload("book.txt", 4, byteArrayOf(0, 1, 2, 3)))
        assertEquals(
            "书籍文件不能超过50MB",
            LocalBookSecurity.validateBookUpload("book.txt", LocalBookSecurity.MAX_BOOK_SIZE_BYTES + 1, text)
        )
        assertEquals("PDF 文件内容无效", LocalBookSecurity.validateBookUpload("book.pdf", text.size.toLong(), text))
    }

    @Test
    fun validatesEpubStructure() {
        val epub = zipOf(
            "mimetype" to "application/epub+zip".toByteArray(Charsets.US_ASCII),
            "META-INF/container.xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent().toByteArray(Charsets.UTF_8)
        )
        assertNull(LocalBookSecurity.validateBookUpload("book.epub", epub.size.toLong(), epub))
        val missingContainer = zipOf("mimetype" to "application/epub+zip".toByteArray(Charsets.US_ASCII))
        assertEquals(
            "EPUB 文件内容无效",
            LocalBookSecurity.validateBookUpload("book.epub", missingContainer.size.toLong(), missingContainer)
        )
        assertEquals(
            "EPUB 文件内容无效",
            LocalBookSecurity.validateBookUpload("book.epub", 4, byteArrayOf(0x50, 0x4B, 3, 4))
        )
    }

    @Test
    fun validatesUmdAndCbzStructures() {
        val umd = ByteBuffer.allocate(9).apply {
            put(byteArrayOf(0x89.toByte(), 0x9B.toByte(), 0x9A.toByte(), 0xDE.toByte()))
            put('#'.code.toByte())
            putShort(1)
            put(0)
            put(5)
        }.array()
        assertNull(LocalBookSecurity.validateBookUpload("book.umd", umd.size.toLong(), umd))
        assertEquals(
            "UMD 文件内容无效",
            LocalBookSecurity.validateBookUpload("book.umd", 5, umd.copyOf(5))
        )

        val cbz = zipOf(
            "ComicInfo.xml" to "<ComicInfo><Title>Test</Title></ComicInfo>".toByteArray(),
            "001.jpg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        )
        assertNull(LocalBookSecurity.validateBookUpload("book.cbz", cbz.size.toLong(), cbz))
        val noImage = zipOf("ComicInfo.xml" to "<ComicInfo/>".toByteArray())
        assertEquals(
            "CBZ 文件内容无效",
            LocalBookSecurity.validateBookUpload("book.cbz", noImage.size.toLong(), noImage)
        )
    }

    @Test
    fun validatesMobiFamilyByPdbStructureAndRejectsSpoofedExtensions() {
        val mobi = minimalMobiHeader()
        listOf("mobi", "azw", "azw3").forEach { extension ->
            assertNull(LocalBookSecurity.validateBookUpload("book.$extension", mobi.size.toLong(), mobi))
        }

        val spoofed = "not a mobi file".toByteArray()
        assertEquals(
            "MOBI 文件内容无效",
            LocalBookSecurity.validateBookUpload("book.azw3", spoofed.size.toLong(), spoofed)
        )
        val invalidOffset = mobi.copyOf().also { ByteBuffer.wrap(it).putInt(78, it.size + 1) }
        assertEquals(
            "MOBI 文件内容无效",
            LocalBookSecurity.validateBookUpload("book.mobi", invalidOffset.size.toLong(), invalidOffset)
        )
        val encrypted = mobi.copyOf().also { ByteBuffer.wrap(it).putShort(94 + 12, 1) }
        assertEquals(
            "MOBI 文件内容无效",
            LocalBookSecurity.validateBookUpload("book.azw", encrypted.size.toLong(), encrypted)
        )
    }

    @Test
    fun validatesPdfSignatureAndEndMarker() {
        val pdf = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\nstartxref\n0\n%%EOF\n".toByteArray(Charsets.US_ASCII)
        assertNull(LocalBookSecurity.validateBookUpload("book.PDF", pdf.size.toLong(), pdf))

        val missingEnd = "%PDF-1.7\nnot complete".toByteArray(Charsets.US_ASCII)
        assertEquals(
            "PDF 文件内容无效",
            LocalBookSecurity.validateBookUpload("book.pdf", missingEnd.size.toLong(), missingEnd)
        )
        val spoofed = "not a pdf\n%%EOF".toByteArray(Charsets.US_ASCII)
        assertEquals(
            "PDF 文件内容无效",
            LocalBookSecurity.validateBookUpload("book.pdf", spoofed.size.toLong(), spoofed)
        )
    }

    private fun minimalMobiHeader(): ByteArray {
        val bytes = ByteArray(360)
        "BOOKMOBI".toByteArray(Charsets.US_ASCII).copyInto(bytes, 60)
        val buffer = ByteBuffer.wrap(bytes)
        buffer.putShort(76, 2)
        buffer.putInt(78, 94)
        buffer.putInt(86, 350)
        "MOBI".toByteArray(Charsets.US_ASCII).copyInto(bytes, 110)
        buffer.putShort(94, 1)
        buffer.putShort(102, 1)
        buffer.putShort(104, 4096)
        buffer.putInt(114, 232)
        buffer.putInt(122, 65001)
        buffer.putInt(178, 248)
        buffer.putInt(182, 0)
        return bytes
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
