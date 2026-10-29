package io.legado.app.lib.mobi.decompress

class Lz77Decompressor(private val textRecordSize: Int) : Decompressor {

    override fun decompress(data: ByteArray): ByteArray {
        var out = ByteArray(textRecordSize.coerceAtLeast(16))

        fun ensureCapacity(required: Int) {
            require(required <= MAX_DECOMPRESSED_RECORD_BYTES) { "Mobi text record is too large" }
            if (required > out.size) {
                out = out.copyOf((out.size * 2).coerceAtLeast(required))
            }
        }

        var i = 0
        var o = 0
        while (i < data.size) {
            var c = data[i++].toInt() and 0x00FF
            if (c in 0x01..0x08) {
                var j = 0
                while (j < c && i + j < data.size) {
                    ensureCapacity(o + 1)
                    out[o++] = data[i + j]
                    j++
                }
                i += c
            } else if (c <= 0x7f) {
                ensureCapacity(o + 1)
                out[o++] = c.toByte()
            } else if (c >= 0xC0) {
                ensureCapacity(o + 2)
                out[o++] = ' '.code.toByte()
                out[o++] = (c xor 0x80).toByte()
            } else {
                if (i < data.size) {
                    c = c shl 8 or (data[i++].toInt() and 0xFF)
                    val length = (c and 0x0007) + 3
                    val location = (c shr 3) and 0x7FF

                    if (location in 1..o) {
                        ensureCapacity(o + length)
                        for (j in 0 until length) {
                            val idx = o - location
                            out[o++] = out[idx]
                        }
                    }
                }
            }
        }

        return out.copyOf(o)
    }

    companion object {
        private const val MAX_DECOMPRESSED_RECORD_BYTES = 16 * 1024 * 1024
    }

}
