package book.webBook.localBook

import book.model.Book
import book.model.BookType
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentInformation
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfFileTest {

    @Test
    fun extractsMetadataPagedTextAndCoverFromRealPdf() {
        val root = Files.createTempDirectory("stage19-pdf-").toFile()
        val pdf = File(root, "stage19.pdf")
        createPdf(pdf, 12)
        val book = Book(
            bookUrl = pdf.absolutePath,
            origin = BookType.local,
            originName = pdf.absolutePath,
            userid = "stage19-user"
        ).apply { setRootDir(root.absolutePath) }

        try {
            PdfFile.upBookInfo(book)
            val chapters = LocalBook.getChapterList(book)
            val firstContent = LocalBook.getContent(book, chapters.first()).orEmpty()
            val secondContent = LocalBook.getContent(book, chapters.last()).orEmpty()

            assertEquals("Stage 19 PDF", book.name)
            assertEquals("PDF Author", book.author)
            assertEquals("PDF Subject", book.intro)
            assertEquals("novel,pdf", book.kind)
            assertEquals(2, chapters.size)
            assertEquals("第 1-10 页", chapters.first().title)
            assertEquals("第 11-12 页", chapters.last().title)
            assertEquals(chapters.last().title, book.latestChapterTitle)
            assertEquals(2, book.totalChapterNum)
            assertTrue(firstContent.contains("PAGE 1 CONTENT"))
            assertTrue(firstContent.contains("PAGE 10 CONTENT"))
            assertTrue(!firstContent.contains("PAGE 11 CONTENT"))
            assertTrue(secondContent.contains("PAGE 11 CONTENT"))
            assertTrue(secondContent.contains("PAGE 12 CONTENT"))

            val coverUrl = requireNotNull(book.coverUrl)
            assertTrue(coverUrl.matches(Regex("/assets/[a-f0-9]{32}/covers/[a-f0-9]{32}\\.jpg")))
            val cover = File(root, "storage/${coverUrl.removePrefix("/")}")
            assertTrue(cover.isFile)
            assertTrue(cover.readBytes().take(3) == listOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))

            val renderedPage = PdfFile.getImage(book, "pdf:1-10")!!.readBytes()
            assertTrue(renderedPage.take(3) == listOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        } finally {
            PdfFile.remove(book)
            assertTrue(pdf.delete(), "关闭 PDFBox 文档后应释放 PDF 文件")
            root.deleteRecursively()
        }
    }

    private fun createPdf(file: File, pageCount: Int) {
        PDDocument().use { document ->
            document.documentInformation = PDDocumentInformation().apply {
                title = "Stage 19 PDF"
                author = "PDF Author"
                subject = "PDF Subject"
                keywords = "novel,pdf"
            }
            repeat(pageCount) { index ->
                val page = PDPage(PDRectangle.LETTER)
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    content.newLineAtOffset(72f, 720f)
                    content.showText("PAGE ${index + 1} CONTENT")
                    content.endText()
                }
            }
            document.save(file)
        }
    }
}
