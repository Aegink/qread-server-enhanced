package web.controller

import book.model.Book
import book.webBook.localBook.LocalBook
import org.noear.solon.annotation.Controller
import org.noear.solon.annotation.Inject
import org.noear.solon.annotation.Mapping
import org.noear.solon.core.handle.Context
import org.noear.solon.core.util.DataThrowable
import org.noear.solon.data.cache.CacheService
import web.response.JsonResponse
import web.response.NOT_TXT
import web.util.security.LocalBookSecurity
import java.io.File

@Controller
open class LocalBookController {

    @Mapping("/searchlocalbook")
    fun search(ctx: Context, path: String?,key:String?): JsonResponse = run{
        requireLocalRequest(ctx)
        if(key.isNullOrBlank()){
            throw DataThrowable().data(JsonResponse(false, "关键字不能为空"))
        }
        if(path.isNullOrBlank()){
            throw DataThrowable().data(JsonResponse(false, "路径不能为空"))
        }
        val file= File(path)
        if(!file.exists()){
            throw DataThrowable().data(JsonResponse(false, "文件夹不存在"))
        }
        if(!file.isDirectory){
            throw DataThrowable().data(JsonResponse(false, "路径不是文件夹"))
        }
        val books = mutableListOf<Book>()
        getname(file).forEach { k,v->
             if(k.contains(key)){
                 if(books.size < 10){
                     val book = Book.initLocalBook(v, v, "")
                     books.add(book)
                 }
             }
        }
        JsonResponse(true).Data(books)
    }

    @Inject
    lateinit var cacheService: CacheService

    val cachetime=60

    @Mapping("/searchlocalbook2")
    fun search2(ctx: Context, path: String?,key:String?,page: Int?): JsonResponse = run{
        requireLocalRequest(ctx)
        if(key.isNullOrBlank()){
            throw DataThrowable().data(JsonResponse(false, "关键字不能为空"))
        }
        if(path.isNullOrBlank()){
            throw DataThrowable().data(JsonResponse(false, "路径不能为空"))
        }
        val file= File(path)
        if(!file.exists()){
            throw DataThrowable().data(JsonResponse(false, "文件夹不存在"))
        }
        if(!file.isDirectory){
            throw DataThrowable().data(JsonResponse(false, "路径不是文件夹"))
        }
        val p=if (page ==null) 1 else page
        val cacheKey = "searchlocalbook2:${path}${key}${p}"
        if (p > 1) {
            return@run cacheService.get(cacheKey, JsonResponse::class.java)
                ?: JsonResponse(true).Data(mutableListOf<Book>())
        }
        val books = mutableListOf<Book>()
        if(p == 1){
            getname(file).forEach { k,v->
                if(k.contains(key)){
                    val book = Book.initLocalBook(v, v, "")
                    books.add(book)
                }
            }
            var books2 = mutableListOf<Book>()
            if(books.size < 10){
                books2=books;
            }else{
                books2=books.subList(0,10)
                for (i in 2..10){
                    if (i * 10 > books.size){
                        val b=books.subList(i * 10-10,books.size)
                        cacheService.store("searchlocalbook2:${path}${key}${i}",JsonResponse(true).Data(b),cachetime)
                        break;
                    }else{
                        val b=books.subList(i * 10-10,i * 10)
                        cacheService.store("searchlocalbook2:${path}${key}${i}",JsonResponse(true).Data(b),cachetime)
                    }

                }
            }
            val result = JsonResponse(true).Data(books2)
            cacheService.store(cacheKey, result, cachetime)
            result
        }else{
            JsonResponse(true).Data(books)
        }
    }

    @Mapping("/getlocalbookinfo")
    fun info(ctx: Context,path: String?): JsonResponse = run{
        requireLocalRequest(ctx)
        if(path.isNullOrBlank()){
            throw DataThrowable().data(JsonResponse(false, "路径不能为空"))
        }
        val file= File(path)
        if(!file.exists()){
            throw DataThrowable().data(JsonResponse(false, "文件夹不存在"))
        }
        if(!isSupportedLocalBook(file)){
            throw DataThrowable().data(JsonResponse(false,NOT_TXT))
        }
        val book = Book.initLocalBook(file.path, file.path, "")
        JsonResponse(true).Data(book)
    }

    @Mapping("/getlocalbookinfoChapterList")
    fun getChapterList(ctx: Context,path: String?) = run{
        requireLocalRequest(ctx)
        if(path.isNullOrBlank()){
            throw DataThrowable().data(JsonResponse(false, "路径不能为空"))
        }
        val file= File(path)
        if(!file.exists()){
            throw DataThrowable().data(JsonResponse(false, "文件夹不存在"))
        }
        if(!isSupportedLocalBook(file)){
            throw DataThrowable().data(JsonResponse(false,NOT_TXT))
        }
        val book = Book.initLocalBook(file.path, file.path, "")
        val chapters = LocalBook.getChapterList(book)
        JsonResponse(true).Data(chapters)
    }

    @Mapping("/getlocalbookinfoContent")
    fun getContent(ctx: Context,path: String?,index: Int?) = run{
        requireLocalRequest(ctx)
        if(path.isNullOrBlank()){
            throw DataThrowable().data(JsonResponse(false, "路径不能为空"))
        }
        val file= File(path)
        if(!file.exists()){
            throw DataThrowable().data(JsonResponse(false, "文件夹不存在"))
        }
        if(!isSupportedLocalBook(file)){
            throw DataThrowable().data(JsonResponse(false,NOT_TXT))
        }
        val book = Book.initLocalBook(file.path, file.path, "")
        val chapters = LocalBook.getChapterList(book)
        if((index ?: 0) > chapters.size - 1){
            throw DataThrowable().data(JsonResponse(false, "index 错误"))
        }
        val content = LocalBook.getContent(book,chapters[index?:0])
        JsonResponse(true).Data(content)
    }


    fun getname(file: File): MutableMap<String,String> {
        val books=mutableMapOf<String, String>()
        file.walk().maxDepth(1).forEach {
            if(it.isDirectory && it.name != file.name){
                books.putAll(getname(it))
            }else{
                if(isSupportedLocalBook(it)){
                    books[it.nameWithoutExtension] = it.path
                }
            }
        }
        return books
    }

    private fun requireLocalRequest(ctx: Context) {
        if (!LocalBookSecurity.isLocalSocketRequest(ctx)) {
            throw DataThrowable().data(JsonResponse(false, "非法请求"))
        }
    }

    private fun isSupportedLocalBook(file: File): Boolean =
        LocalBook.isSupportedFile(file)

}
