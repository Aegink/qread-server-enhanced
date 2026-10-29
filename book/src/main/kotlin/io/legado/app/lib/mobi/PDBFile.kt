package io.legado.app.lib.mobi

import io.legado.app.lib.mobi.utils.readString
import io.legado.app.lib.mobi.utils.readUInt16
import io.legado.app.lib.mobi.utils.readUInt32
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class PDBFile(file: File) : AutoCloseable {
    private val randomAccessFile = RandomAccessFile(file, "r")
    private val fc: FileChannel = randomAccessFile.channel
    private val offsets: IntArray
    val name: String
    val type: String
    val creator: String
    val recordCount: Int

    init {
        require(fc.size() in 86..MAX_PDB_BYTES) { "Invalid PDB file" }
        var buffer = ByteBuffer.allocate(79)
        readFully(buffer, 0)
        name = buffer.readString(0, 32)
        type = buffer.readString(60, 4)
        creator = buffer.readString(64, 4)
        recordCount = buffer.readUInt16(76)
        require(recordCount > 0 && 78L + recordCount * 8L <= fc.size()) { "Invalid PDB record table" }

        buffer = ByteBuffer.allocate(recordCount * 8)
        readFully(buffer, 78)
        offsets = IntArray(recordCount) {
            buffer.readUInt32(it * 8)
        }
        require(offsets.first() >= 78 + recordCount * 8 && offsets.last() < fc.size()) {
            "Invalid PDB record offset"
        }
        require((0 until offsets.lastIndex).all { index -> offsets[index] < offsets[index + 1] }) {
            "Invalid PDB record order"
        }
    }

    fun getRecordData(index: Int): ByteBuffer {
        if (index !in 0..<recordCount) {
            throw IndexOutOfBoundsException("Record index out of bounds")
        }
        val len = offsets.getOrElse(index + 1) { fc.size().toInt() } - offsets[index]
        require(len in 1..MAX_RECORD_BYTES) { "Invalid PDB record size" }
        val buffer = ByteBuffer.allocate(len)
        readFully(buffer, offsets[index].toLong())
        return buffer
    }

    private fun readFully(buffer: ByteBuffer, offset: Long) {
        var position = offset
        while (buffer.hasRemaining()) {
            val count = fc.read(buffer, position)
            if (count < 0) error("Unexpected end of PDB file")
            if (count == 0) continue
            position += count
        }
    }

    override fun close() {
        fc.close()
        randomAccessFile.close()
    }

    companion object {
        private const val MAX_RECORD_BYTES = 64 * 1024 * 1024
        private const val MAX_PDB_BYTES = 512L * 1024 * 1024
    }

}
