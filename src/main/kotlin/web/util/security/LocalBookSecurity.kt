package web.util.security

import org.noear.solon.core.handle.Context
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** 本地书仓和书籍上传的输入校验。 */
object LocalBookSecurity {
    const val MAX_BOOK_SIZE_BYTES: Long = 50L * 1024 * 1024
    private const val MAX_CBZ_ENTRY_BYTES = 20 * 1024 * 1024
    private const val MAX_CBZ_TOTAL_BYTES = 200L * 1024 * 1024
    private const val MAX_CBZ_ENTRIES = 10_000
    private val cbzImageExtensions = setOf("jpg", "jpeg", "gif", "png", "bmp", "webp")

    fun isLocalSocketRequest(context: Context): Boolean = isLoopbackSocketAddress(context.remoteIp())

    /** 只接受数字形式的 IPv4/IPv6 回环地址，绝不解析主机名或转发头。 */
    fun isLoopbackSocketAddress(remoteIp: String?): Boolean {
        val value = remoteIp?.trim() ?: return false
        if (value == "127.0.0.1") return true
        if (!value.contains(':') || !value.matches(Regex("[0-9a-fA-F:]+"))) return false
        return runCatching {
            val address = InetAddress.getByName(value)
            address is Inet6Address && address.isLoopbackAddress
        }.getOrDefault(false)
    }

    /** 返回用户可见的拒绝原因；null 表示文件内容通过校验。 */
    fun validateBookUpload(fileName: String?, declaredSize: Long, bytes: ByteArray): String? {
        if (fileName.isNullOrBlank()) return "文件名不能为空"
        if (declaredSize <= 0L || declaredSize > MAX_BOOK_SIZE_BYTES) return "书籍文件不能超过50MB"
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_BOOK_SIZE_BYTES) return "书籍文件不能超过50MB"
        if (declaredSize != bytes.size.toLong()) return "上传文件大小校验失败"

        return when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "txt" -> if (isText(bytes)) null else "TXT 文件内容无效"
            "epub" -> if (isEpub(bytes)) null else "EPUB 文件内容无效"
            "umd" -> if (isUmd(bytes)) null else "UMD 文件内容无效"
            "cbz" -> if (isCbz(bytes)) null else "CBZ 文件内容无效"
            "mobi", "azw", "azw3" -> if (isMobi(bytes)) null else "MOBI 文件内容无效"
            "pdf" -> if (isPdf(bytes)) null else "PDF 文件内容无效"
            else -> "当前文件格式不支持"
        }
    }

    private fun isText(bytes: ByteArray): Boolean {
        val candidates = buildList {
            when {
                bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) -> add(StandardCharsets.UTF_16LE)
                bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) -> add(StandardCharsets.UTF_16BE)
            }
            add(StandardCharsets.UTF_8)
            add(Charset.forName("GB18030"))
            add(Charset.forName("Big5"))
        }
        val text = candidates.firstNotNullOfOrNull { charset -> decodeStrict(bytes, charset) } ?: return false
        if (text.isBlank() || text.contains('\u0000')) return false
        val controlCount = text.count { character ->
            character.code < 32 && character != '\n' && character != '\r' && character != '\t' && character != '\u000C'
        }
        return controlCount.toDouble() / text.length.coerceAtLeast(1) <= 0.01
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun isEpub(bytes: ByteArray): Boolean {
        if (!bytes.startsWith(byteArrayOf(0x50, 0x4B, 0x03, 0x04))) return false
        var mimetype: ByteArray? = null
        var container: ByteArray? = null
        return runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        "mimetype" -> mimetype = readEntry(zip, 64)
                        "META-INF/container.xml" -> container = readEntry(zip, 64 * 1024)
                    }
                    if (mimetype != null && container != null) break
                    entry = zip.nextEntry
                }
            }
            mimetype?.toString(StandardCharsets.US_ASCII) == "application/epub+zip" &&
                container?.let(::hasValidContainerXml) == true
        }.getOrDefault(false)
    }

    private fun isMobi(bytes: ByteArray): Boolean {
        if (bytes.size < 86 || bytes.copyOfRange(60, 68).toString(StandardCharsets.US_ASCII) != "BOOKMOBI") {
            return false
        }
        val buffer = ByteBuffer.wrap(bytes)
        val recordCount = buffer.getShort(76).toInt() and 0xffff
        if (recordCount <= 0 || 78L + recordCount * 8L > bytes.size) return false
        val offsets = IntArray(recordCount) { index -> buffer.getInt(78 + index * 8) }
        if (offsets.first() < 78 + recordCount * 8 || offsets.last() >= bytes.size) return false
        if ((0 until offsets.lastIndex).any { offsets[it] >= offsets[it + 1] }) return false

        val firstRecord = offsets.first()
        val firstRecordEnd = offsets.getOrElse(1) { bytes.size }
        if (firstRecordEnd - firstRecord < 248) return false
        if (bytes.copyOfRange(firstRecord + 16, firstRecord + 20)
                .toString(StandardCharsets.US_ASCII) != "MOBI") return false

        val compression = buffer.getShort(firstRecord).toInt() and 0xffff
        val textRecordCount = buffer.getShort(firstRecord + 8).toInt() and 0xffff
        val recordSize = buffer.getShort(firstRecord + 10).toInt() and 0xffff
        val encryption = buffer.getShort(firstRecord + 12).toInt() and 0xffff
        if (compression !in setOf(1, 2, 17480) || textRecordCount !in 1 until recordCount) return false
        if (recordSize <= 0 || encryption != 0) return false

        val mobiLength = buffer.getInt(firstRecord + 20)
        val encoding = buffer.getInt(firstRecord + 28)
        val titleOffset = buffer.getInt(firstRecord + 84)
        val titleLength = buffer.getInt(firstRecord + 88)
        val recordLength = firstRecordEnd - firstRecord
        return mobiLength >= 116 && mobiLength + 16 <= recordLength &&
            encoding in setOf(65001, 1252) &&
            titleOffset >= 0 && titleLength >= 0 &&
            titleOffset.toLong() + titleLength <= recordLength
    }

    private fun isUmd(bytes: ByteArray): Boolean {
        if (bytes.size < 9 || !bytes.startsWith(byteArrayOf(0x89.toByte(), 0x9B.toByte(), 0x9A.toByte(), 0xDE.toByte()))) {
            return false
        }
        var offset = 4
        var sectionCount = 0
        while (offset < bytes.size) {
            if (bytes[offset].toInt() and 0xff != '#'.code || offset + 5 > bytes.size) return false
            val sectionLength = bytes[offset + 4].toInt() and 0xff
            if (sectionLength < 5 || offset + sectionLength > bytes.size) return false
            offset += sectionLength
            sectionCount++
            while (offset < bytes.size && bytes[offset].toInt() and 0xff == '$'.code) {
                if (offset + 9 > bytes.size) return false
                val blockLength = Integer.reverseBytes(ByteBuffer.wrap(bytes, offset + 5, 4).int)
                if (blockLength < 9 || offset.toLong() + blockLength > bytes.size) return false
                offset += blockLength
            }
        }
        return sectionCount > 0
    }

    private fun isCbz(bytes: ByteArray): Boolean {
        if (!bytes.startsWith(byteArrayOf(0x50, 0x4B, 0x03, 0x04))) return false
        var entryCount = 0
        var totalBytes = 0L
        var imageCount = 0
        return runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    entryCount++
                    if (entryCount > MAX_CBZ_ENTRIES) return@runCatching false
                    if (!entry.isDirectory) {
                        val extension = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        val maxBytes = if (extension in cbzImageExtensions) MAX_CBZ_ENTRY_BYTES else 1024 * 1024
                        val content = readEntry(zip, maxBytes) ?: return@runCatching false
                        totalBytes += content.size
                        if (totalBytes > MAX_CBZ_TOTAL_BYTES) return@runCatching false
                        if (extension in cbzImageExtensions) {
                            if (!isImage(extension, content)) return@runCatching false
                            imageCount++
                        } else if (entry.name.equals("ComicInfo.xml", true)) {
                            if (content.toString(StandardCharsets.UTF_8).contains("<!DOCTYPE", true)) {
                                return@runCatching false
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            imageCount > 0
        }.getOrDefault(false)
    }

    private fun isImage(extension: String, bytes: ByteArray): Boolean = when (extension) {
        "jpg", "jpeg" -> bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        "png" -> bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        "gif" -> bytes.size >= 6 && bytes.copyOfRange(0, 6).toString(StandardCharsets.US_ASCII) in setOf("GIF87a", "GIF89a")
        "webp" -> bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(StandardCharsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(StandardCharsets.US_ASCII) == "WEBP"
        "bmp" -> bytes.startsWith(byteArrayOf('B'.code.toByte(), 'M'.code.toByte()))
        else -> false
    }

    private fun isPdf(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val header = bytes.copyOfRange(0, 8).toString(StandardCharsets.US_ASCII)
        if (!header.matches(Regex("%PDF-[12]\\.\\d"))) return false
        val tailStart = (bytes.size - 2048).coerceAtLeast(0)
        return bytes.copyOfRange(tailStart, bytes.size)
            .toString(StandardCharsets.ISO_8859_1)
            .contains("%%EOF")
    }

    private fun readEntry(zip: ZipInputStream, maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun hasValidContainerXml(bytes: ByteArray): Boolean = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        val document: Document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val rootFiles = document.getElementsByTagNameNS("*", "rootfile")
        val fallbackRootFiles = document.getElementsByTagName("rootfile")
        val nodes = if (rootFiles.length > 0) rootFiles else fallbackRootFiles
        (0 until nodes.length).any { nodes.item(it).attributes?.getNamedItem("full-path")?.nodeValue?.isNotBlank() == true }
    }.getOrDefault(false)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size &&
        prefix.indices.all { this[it] == prefix[it] }
}
