package book.util

import book.model.BookSource
import book.webBook.analyzeRule.AnalyzeRule
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveUtilsTest {
    private val entryName = "nested/chapter.txt"
    private val content = "stage17-content".toByteArray()

    @Test
    fun readsAndExtracts7zArchive() {
        withTempDirectory { root ->
            val archive = File(root, "fixture.7z")
            create7z(archive, entryName, content)

            assertContentEquals(content, ArchiveUtils.readEntry(archive.readBytes(), ArchiveType.SEVEN_Z, entryName))
            val destination = File(root, "seven-output")
            val files = ArchiveUtils.extract(archive, destination)

            assertEquals(listOf(entryName), files.map { it.relativeTo(destination).invariantSeparatorsPath })
            assertContentEquals(content, File(destination, entryName).readBytes())
        }
    }

    @Test
    fun readsAndExtractsRarArchive() {
        withTempDirectory { root ->
            val archive = File(root, "fixture.rar")
            archive.writeBytes(createStoredRar(entryName, content))

            assertContentEquals(content, ArchiveUtils.readEntry(archive.readBytes(), ArchiveType.RAR, entryName))
            val destination = File(root, "rar-output")
            val files = ArchiveUtils.extract(archive, destination)

            assertEquals(listOf(entryName), files.map { it.relativeTo(destination).invariantSeparatorsPath })
            assertContentEquals(content, File(destination, entryName).readBytes())
        }
    }

    @Test
    fun exposes7zAndRarFunctionsToJavaScript() {
        withTempDirectory { root ->
            val sevenZ = File(root, "fixture.7z")
            create7z(sevenZ, entryName, content)
            val rar = createStoredRar(entryName, content)
            val rule = AnalyzeRule(
                source = BookSource(
                    bookSourceName = "stage17",
                    bookSourceUrl = "https://stage17.example",
                    userid = "stage17-${System.nanoTime()}"
                )
            )
            val sevenHex = sevenZ.readBytes().toHex()
            val rarHex = rar.toHex()

            assertEquals("stage17-content", rule.get7zStringContent(sevenHex, entryName, "UTF-8"))
            assertContentEquals(content, rule.getRarByteArrayContent(rarHex, entryName))
            assertEquals(
                "stage17-content",
                rule.evalJS("java.getRarStringContent('$rarHex', '$entryName', 'UTF-8')")
            )
        }
    }

    @Test
    fun universalDispatcherExtractsArchiveAndDeletesInput() {
        val userId = "stage17-dispatch-${System.nanoTime()}"
        val cacheRoot = File(FileUtils.getCachePath(userId))
        val archive = File(cacheRoot, "dispatch.7z")
        val rule = AnalyzeRule(
            source = BookSource(
                bookSourceName = "stage17-dispatch",
                bookSourceUrl = "https://stage17.example/dispatch",
                userid = userId
            )
        )

        try {
            create7z(archive, entryName, content)
            val relativeOutput = rule.unArchiveFile(archive.name)
            val output = rule.getFile(relativeOutput)

            assertFalse(archive.exists())
            assertContentEquals(content, File(output, entryName).readBytes())
            assertTrue(JsExtensionsMethodNames.all { name ->
                rule.javaClass.methods.any { it.name == name }
            })
        } finally {
            FileUtils.delete(cacheRoot, true)
        }
    }

    @Test
    fun rejectsArchivePathTraversalAndCleansPartialOutput() {
        withTempDirectory { root ->
            val archive = File(root, "traversal.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("safe.txt"))
                zip.write(content)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("../outside.txt"))
                zip.write("outside".toByteArray())
                zip.closeEntry()
            }
            val destination = File(root, "zip-output")

            assertFailsWith<SecurityException> { ArchiveUtils.extract(archive, destination) }
            assertFalse(File(root, "outside.txt").exists())
            assertFalse(destination.exists())
        }
    }

    private fun create7z(file: File, name: String, bytes: ByteArray) {
        SevenZOutputFile(file).use { archive ->
            val entry = SevenZArchiveEntry().apply {
                this.name = name
                size = bytes.size.toLong()
            }
            archive.putArchiveEntry(entry)
            archive.write(bytes)
            archive.closeArchiveEntry()
        }
    }

    private fun createStoredRar(name: String, bytes: ByteArray): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val fileCrc = CRC32().apply { update(bytes) }.value
        val fileBody = ByteArrayOutputStream().apply {
            writeInt32(bytes.size.toLong())
            writeInt32(bytes.size.toLong())
            write(2)
            writeInt32(fileCrc)
            writeInt32(0)
            write(20)
            write(0x30)
            writeInt16(nameBytes.size)
            writeInt32(0x20)
            write(nameBytes)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00))
            writeRarHeader(0x73, 0, ByteArray(6))
            writeRarHeader(0x74, 0x8000, fileBody)
            write(bytes)
            write(byteArrayOf(0xc4.toByte(), 0x3d, 0x7b, 0x00, 0x40, 0x07, 0x00))
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeRarHeader(type: Int, flags: Int, body: ByteArray) {
        val header = ByteArrayOutputStream().apply {
            write(type)
            writeInt16(flags)
            writeInt16(7 + body.size)
            write(body)
        }.toByteArray()
        val crc = CRC32().apply { update(header) }.value.toInt() and 0xffff
        writeInt16(crc)
        write(header)
    }

    private fun ByteArrayOutputStream.writeInt16(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private fun ByteArrayOutputStream.writeInt32(value: Long) {
        repeat(4) { shift -> write((value ushr (shift * 8)).toInt() and 0xff) }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("qread-stage17-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    companion object {
        private val JsExtensionsMethodNames = setOf(
            "un7zFile",
            "unrarFile",
            "unArchiveFile",
            "get7zStringContent",
            "get7zByteArrayContent",
            "getRarStringContent",
            "getRarByteArrayContent"
        )
    }
}
