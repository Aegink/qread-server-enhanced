package web.controller.api

import book.appCtx
import book.model.Book
import book.util.FileUtils
import book.webBook.localBook.LocalBook
import org.noear.solon.annotation.Controller
import org.noear.solon.annotation.Inject
import org.noear.solon.annotation.Mapping
import org.noear.solon.core.handle.UploadedFile
import org.noear.solon.core.util.DataThrowable
import org.noear.solon.web.cors.annotation.CrossOrigin
import web.mapper.BooklistMapper
import web.model.Booklist
import web.response.*
import web.util.cache.getlocalpath
import web.util.hash.Md5
import web.util.security.LocalBookSecurity
import java.io.File
import java.net.URLDecoder
import kotlin.concurrent.thread

@Controller
@Mapping(routepath)
@CrossOrigin(origins = "*")
open class LocalBookController:BaseController() {

    companion object {
        private const val maxImageSize = 10 * 1024 * 1024
        private const val maxUserImageQuota = 100L * 1024 * 1024
        private val imageUploadLock = Any()
    }

    private val imagesDir = FileUtils.createFolderIfNotExist(appCtx.externalFiles, "assets","images")

    @Inject
    lateinit var booklistMapper: BooklistMapper


    @Mapping("/importBookPreview")
    open fun importBookPreview(accessToken:String?, file: UploadedFile?)=run{
        if(file == null) throw DataThrowable().data(JsonResponse(false, NOT_BANK))
        if (file.isEmpty) {
            throw DataThrowable().data(JsonResponse(false, NOT_BANK))
        }
        val user=getuserbytocken(accessToken)
        if(user.AllowUpTxt != true) {
            throw DataThrowable().data(JsonResponse(false,NOT_ALLOW_TXT))
        }
        val contentSize = file.contentSize
        if (contentSize > LocalBookSecurity.MAX_BOOK_SIZE_BYTES) {
            throw DataThrowable().data(JsonResponse(false, "书籍文件不能超过50MB"))
        }
        val bytes = file.content.readNBytes((LocalBookSecurity.MAX_BOOK_SIZE_BYTES + 1).toInt())
        LocalBookSecurity.validateBookUpload(file.name, contentSize, bytes)?.let { reason ->
            throw DataThrowable().data(JsonResponse(false, reason))
        }
        var f1=file.name
        kotlin.runCatching {
            f1= URLDecoder.decode( f1, "UTF-8" )
        }
        val unifiedPath = f1.replace("\\", "/")
        f1= unifiedPath.substringAfterLast('/')
        val  uploadDir = getlocalpath(user.username?:"")
        val ufile= File(uploadDir)
        if(!ufile.exists()){ufile.mkdirs()}
        val localpath= "$uploadDir/$f1"
        val  uploadedFile =  File(localpath)
        uploadedFile.writeBytes(bytes)
        val book = Book.initLocalBook(localpath, localpath, "", user.id!!)
        val chapters = LocalBook.getChapterList(book)
        val booklist= Booklist().create().bookto(book)
        booklistMapper.getbook(user.id!!,book.bookUrl)?.let {
           // booklist.durChapterTime=it.durChapterTime
            booklist.durChapterTitle=it.durChapterTitle
            booklist.durChapterPos=it.durChapterPos
            booklist.durChapterIndex=it.durChapterIndex
            booklistMapper.deleteById(it.id)
        }
        booklist.originName="本地"
        booklist.userid=user.id
        booklist.lastCheckTime=System.currentTimeMillis()
        booklist.lastCheckCount=chapters.size
        booklist.totalChapterNum=chapters.size
        booklist.latestChapterTitle=chapters[chapters.size-1].title
        booklist.latestChapterTime=System.currentTimeMillis()
        booklistMapper.insert(booklist)
        web.notification.Book.sendNotification(user)
        thread {
            ReadController.removeChapterListbycache(book.bookUrl,user.id!!)
            ReadController.removeallBookContentbycache(book.bookUrl,user.id!!)
            ReadController.setChapterListbycache(book.bookUrl,chapters,user.id!!)
        }
        return@run JsonResponse(true,SUCCESS).Data(mapOf("books" to book,"chapters" to chapters))
    }

    @Mapping("/uploadimage")
    open fun uploadimage(accessToken: String?, file: UploadedFile?) = run {
        val user = getuserbytocken(accessToken)
        if (file == null || file.isEmpty) {
            throw DataThrowable().data(JsonResponse(false, NOT_BANK))
        }
        val bytes = file.contentAsBytes
        if (bytes.size > maxImageSize) {
            throw DataThrowable().data(JsonResponse(false, "图片不能超过10MB"))
        }
        val extension = imageExtension(bytes)
            ?: throw DataThrowable().data(JsonResponse(false, "仅允许上传 PNG/JPEG/GIF/WebP 图片"))
        val userImageDir = FileUtils.createFolderIfNotExist(imagesDir, user.id!!)
        val fileName = "${Md5(bytes).joinToString("") { "%02x".format(it) }}.$extension"
        val valueFile = FileUtils.getFile(userImageDir, fileName)
        synchronized(imageUploadLock) {
            if (!valueFile.exists()) {
                val usedBytes = userImageDir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
                if (usedBytes + bytes.size > maxUserImageQuota) {
                    throw DataThrowable().data(JsonResponse(false, "图片空间已满"))
                }
                valueFile.writeBytes(bytes)
            }
        }
        JsonResponse(true).Data("http//assets/images/${user.id}/$fileName")
    }

    private fun imageExtension(bytes: ByteArray): String? {
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))) {
            return "png"
        }
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "jpg"
        }
        if (bytes.size >= 6) {
            val signature = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
            if (signature == "GIF87a" || signature == "GIF89a") return "gif"
        }
        if (bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP") {
            return "webp"
        }
        return null
    }

}
