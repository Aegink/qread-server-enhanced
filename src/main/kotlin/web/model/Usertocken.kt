package web.model

import com.baomidou.mybatisplus.annotation.FieldFill
import com.baomidou.mybatisplus.annotation.TableField
import com.baomidou.mybatisplus.annotation.TableId
import org.dromara.autotable.annotation.AutoTable
import org.dromara.autotable.annotation.PrimaryKey
import org.noear.snack.annotation.ONodeAttr
import web.util.auth.createUserAccessToken
import web.util.auth.userTokenStorageId
import java.time.LocalDateTime

@AutoTable(value = "usertocken")
class Usertocken {
    @TableId
    @PrimaryKey
    var id : String? =null
    var model: String? =null
    var userid : String? =null                  //用户id

    @TableField(exist = false)
    var accessToken: String? = null

    @ONodeAttr(format = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @TableField(fill = FieldFill.INSERT,value = "createtime")
    var createtime: LocalDateTime? = null

    fun create():Usertocken{
        this.accessToken = createUserAccessToken()
        this.id = userTokenStorageId(this.accessToken!!)
        this.createtime = LocalDateTime.now()
        return this
    }
}
