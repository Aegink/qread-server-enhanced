package book.webBook.analyzeRule

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QueryTtfCompatibilityApiTest {

    @Test
    fun exposesNewMapAndMethodNamesWithoutBreakingOldNames() {
        val query = QueryTTF(realFontBytes())
        val unicode = query.codeToGlyph.keys.first()
        val glyph = query.codeToGlyph[unicode]

        assertSame(query.codeToGlyph, query.unicodeToGlyph)
        assertSame(query.glyphToCode, query.glyphToUnicode)
        assertEquals(query.getGlyfByCode(unicode), query.getGlyfByUnicode(unicode))
        assertEquals(query.getCodeByGlyf(glyph), query.getUnicodeByGlyf(glyph))
        assertNull(query.getGlyfByUnicode(Int.MAX_VALUE))
        val glyphId = query.getGlyfIdByUnicode(unicode)
        assertTrue(glyphId > 0)
        assertEquals(query.getGlyfByUnicode(unicode), query.getGlyfById(glyphId))
        assertNull(query.getGlyfById(Int.MAX_VALUE))
    }

    @Test
    fun recognizesTheUpstreamBlankUnicodeSet() {
        val query = QueryTTF(ByteArray(12))
        val blanks = listOf(
            0x0009, 0x0020, 0x00A0, 0x2002, 0x2003, 0x2007,
            0x200A, 0x200B, 0x200C, 0x200D, 0x202F, 0x205F
        )

        blanks.forEach { assertTrue(query.isBlankUnicode(it), "U+${it.toString(16)} should be blank") }
        listOf(0x000A, 0x0041, 0x3000).forEach {
            assertFalse(query.isBlankUnicode(it), "U+${it.toString(16)} should not be blank")
        }
    }

    @Test
    fun keepsCmapFormatsZeroFourSixAndTwelve() {
        val query = QueryTTF(cmapOnlyFont())

        assertEquals(setOf(0, 4, 6, 12), query.supportedCmapFormats)
        assertEquals(1, query.getGlyfIdByUnicode(0x1F600))
    }

    private fun realFontBytes(): ByteArray {
        return File("../src/main/resources/static/static/layui/font/iconfont.ttf").readBytes()
    }

    private fun cmapOnlyFont(): ByteArray {
        val subtables = listOf(format12(), format4(), format0(), format6())
        val records = listOf(3 to 10, 3 to 1, 1 to 0, 0 to 3)
        val cmap = ByteArrayOutputStream()
        DataOutputStream(cmap).use { out ->
            out.writeShort(0)
            out.writeShort(records.size)
            var offset = 4 + records.size * 8
            records.zip(subtables).forEach { (record, table) ->
                out.writeShort(record.first)
                out.writeShort(record.second)
                out.writeInt(offset)
                offset += table.size
            }
            subtables.forEach(out::write)
        }
        val cmapBytes = cmap.toByteArray()
        return ByteArrayOutputStream().also { font ->
            DataOutputStream(font).use { out ->
                out.writeInt(0x00010000)
                out.writeShort(1)
                out.writeShort(16)
                out.writeShort(0)
                out.writeShort(0)
                out.writeBytes("cmap")
                out.writeInt(0)
                out.writeInt(28)
                out.writeInt(cmapBytes.size)
                out.write(cmapBytes)
            }
        }.toByteArray()
    }

    private fun format0(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeShort(0)
            out.writeShort(262)
            out.writeShort(0)
            repeat(256) { out.writeByte(if (it == 65) 1 else 0) }
        }
    }.toByteArray()

    private fun format4(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeShort(4)
            out.writeShort(32)
            out.writeShort(0)
            out.writeShort(4)
            out.writeShort(4)
            out.writeShort(1)
            out.writeShort(0)
            out.writeShort(65)
            out.writeShort(0xFFFF)
            out.writeShort(0)
            out.writeShort(65)
            out.writeShort(0xFFFF)
            out.writeShort(-64)
            out.writeShort(1)
            out.writeShort(0)
            out.writeShort(0)
        }
    }.toByteArray()

    private fun format6(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeShort(6)
            out.writeShort(12)
            out.writeShort(0)
            out.writeShort(65)
            out.writeShort(1)
            out.writeShort(1)
        }
    }.toByteArray()

    private fun format12(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeShort(12)
            out.writeShort(0)
            out.writeInt(28)
            out.writeInt(0)
            out.writeInt(1)
            out.writeInt(0x1F600)
            out.writeInt(0x1F600)
            out.writeInt(1)
        }
    }.toByteArray()
}
