package book.util

import com.github.junrar.Archive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipFile

enum class ArchiveType {
    ZIP,
    SEVEN_Z,
    RAR;

    companion object {
        fun fromName(name: String): ArchiveType = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "zip" -> ZIP
            "7z" -> SEVEN_Z
            "rar" -> RAR
            else -> throw IllegalArgumentException("仅支持 zip、7z 和 rar 压缩文件")
        }
    }
}

object ArchiveUtils {
    const val MAX_ARCHIVE_BYTES = 64L * FileUtils.MB
    const val MAX_ENTRY_BYTES = 64L * FileUtils.MB
    const val MAX_EXTRACTED_BYTES = 256L * FileUtils.MB
    const val MAX_ENTRIES = 4096

    fun extract(archiveFile: File, destination: File): List<File> {
        require(archiveFile.isFile) { "压缩文件不存在" }
        require(archiveFile.length() <= MAX_ARCHIVE_BYTES) { "压缩文件不能超过 64MB" }
        val type = ArchiveType.fromName(archiveFile.name)
        val root = destination.canonicalFile
        FileUtils.createFolderIfNotExist(root.absolutePath)
        val budget = ExtractionBudget()

        return try {
            when (type) {
                ArchiveType.ZIP -> extractZip(archiveFile, root, budget)
                ArchiveType.SEVEN_Z -> extract7z(archiveFile, root, budget)
                ArchiveType.RAR -> extractRar(archiveFile, root, budget)
            }
        } catch (e: Exception) {
            FileUtils.delete(root, true)
            throw e
        }
    }

    fun readEntry(bytes: ByteArray, type: ArchiveType, path: String): ByteArray? {
        require(bytes.size.toLong() <= MAX_ARCHIVE_BYTES) { "压缩文件不能超过 64MB" }
        val expected = normalizeEntryName(path)
        val budget = ExtractionBudget(MAX_ENTRY_BYTES)
        return when (type) {
            ArchiveType.ZIP -> readZipEntry(bytes, expected, budget)
            ArchiveType.SEVEN_Z -> read7zEntry(bytes, expected, budget)
            ArchiveType.RAR -> readRarEntry(bytes, expected, budget)
        }
    }

    private fun extractZip(file: File, root: File, budget: ExtractionBudget): List<File> {
        val files = mutableListOf<File>()
        ZipFile(file).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                budget.nextEntry()
                val target = safeTarget(root, entry.name)
                if (entry.isDirectory) {
                    FileUtils.createFolderIfNotExist(target.absolutePath)
                } else {
                    budget.validateDeclaredSize(entry.size)
                    target.parentFile?.let { FileUtils.createFolderIfNotExist(it.absolutePath) }
                    archive.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { output -> budget.copy(input, output) }
                    }
                    files.add(target)
                }
            }
        }
        return files
    }

    private fun extract7z(file: File, root: File, budget: ExtractionBudget): List<File> {
        val files = mutableListOf<File>()
        SevenZFile.builder()
            .setFile(file)
            .setMaxMemoryLimitKb((MAX_ENTRY_BYTES / FileUtils.KB).toInt())
            .get().use { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                budget.nextEntry()
                val target = safeTarget(root, entry.name)
                if (entry.isDirectory) {
                    FileUtils.createFolderIfNotExist(target.absolutePath)
                } else {
                    budget.validateDeclaredSize(entry.size)
                    target.parentFile?.let { FileUtils.createFolderIfNotExist(it.absolutePath) }
                    archive.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { output -> budget.copy(input, output) }
                    }
                    files.add(target)
                }
                entry = archive.nextEntry
            }
        }
        return files
    }

    private fun extractRar(file: File, root: File, budget: ExtractionBudget): List<File> {
        val files = mutableListOf<File>()
        Archive(file).use { archive ->
            archive.fileHeaders.forEach { entry ->
                budget.nextEntry()
                val target = safeTarget(root, entry.fileName)
                if (entry.isDirectory) {
                    FileUtils.createFolderIfNotExist(target.absolutePath)
                } else {
                    budget.validateDeclaredSize(entry.fullUnpackSize)
                    target.parentFile?.let { FileUtils.createFolderIfNotExist(it.absolutePath) }
                    archive.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { output -> budget.copy(input, output) }
                    }
                    files.add(target)
                }
            }
        }
        return files
    }

    private fun readZipEntry(bytes: ByteArray, path: String, budget: ExtractionBudget): ByteArray? {
        java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                budget.nextEntry()
                if (!entry.isDirectory && normalizeEntryName(entry.name) == path) {
                    budget.validateDeclaredSize(entry.size)
                    return readBytes(archive, budget)
                }
                entry = archive.nextEntry
            }
        }
        return null
    }

    private fun read7zEntry(bytes: ByteArray, path: String, budget: ExtractionBudget): ByteArray? {
        SeekableInMemoryByteChannel(bytes).use { channel ->
            SevenZFile.builder()
                .setSeekableByteChannel(channel)
                .setDefaultName("memory.7z")
                .setMaxMemoryLimitKb((MAX_ENTRY_BYTES / FileUtils.KB).toInt())
                .get().use { archive ->
                var entry = archive.nextEntry
                while (entry != null) {
                    budget.nextEntry()
                    if (!entry.isDirectory && normalizeEntryName(entry.name) == path) {
                        budget.validateDeclaredSize(entry.size)
                        archive.getInputStream(entry).use { return readBytes(it, budget) }
                    }
                    entry = archive.nextEntry
                }
            }
        }
        return null
    }

    private fun readRarEntry(bytes: ByteArray, path: String, budget: ExtractionBudget): ByteArray? {
        Archive(ByteArrayInputStream(bytes)).use { archive ->
            archive.fileHeaders.forEach { entry ->
                budget.nextEntry()
                if (!entry.isDirectory && normalizeEntryName(entry.fileName) == path) {
                    budget.validateDeclaredSize(entry.fullUnpackSize)
                    archive.getInputStream(entry).use { return readBytes(it, budget) }
                }
            }
        }
        return null
    }

    private fun readBytes(input: InputStream, budget: ExtractionBudget): ByteArray {
        val output = ByteArrayOutputStream()
        budget.copy(input, output)
        return output.toByteArray()
    }

    private fun safeTarget(root: File, entryName: String): File {
        require(entryName.isNotBlank()) { "压缩条目名称为空" }
        val target = File(root, normalizeEntryName(entryName)).canonicalFile
        val rootPath = root.canonicalPath
        val insideRoot = target.path == rootPath || target.path.startsWith(rootPath + File.separator)
        if (!insideRoot) throw SecurityException("压缩文件包含越界路径：$entryName")
        return target
    }

    private fun normalizeEntryName(name: String): String = name.replace('\\', '/')

    private class ExtractionBudget(private val totalLimit: Long = MAX_EXTRACTED_BYTES) {
        private var entries = 0
        private var totalBytes = 0L

        fun nextEntry() {
            entries++
            require(entries <= MAX_ENTRIES) { "压缩文件条目数超过 $MAX_ENTRIES" }
        }

        fun validateDeclaredSize(size: Long) {
            if (size < 0) return
            require(size <= MAX_ENTRY_BYTES) { "单个压缩条目不能超过 64MB" }
            require(totalBytes + size <= totalLimit) { "解压后总大小超过限制" }
        }

        fun copy(input: InputStream, output: OutputStream) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var entryBytes = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                entryBytes += count
                totalBytes += count
                require(entryBytes <= MAX_ENTRY_BYTES) { "单个压缩条目不能超过 64MB" }
                require(totalBytes <= totalLimit) { "解压后总大小超过限制" }
                output.write(buffer, 0, count)
            }
        }
    }
}
