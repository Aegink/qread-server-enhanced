package book.model.rule

import book.util.ruleJsonDeserializer

data class ContentRule(
    var content: String? = null,
    var subContent: String? = null, // 副文规则，可用于歌词、弹幕等附加内容
    var title: String? = null, //有些网站只能在正文中获取标题
    var nextContentUrl: String? = null,
    var webJs: String? = null,
    var sourceRegex: String? = null,
    var replaceRegex: String? = null, //替换规则
    var imageStyle: String? = null,   //默认大小居中,FULL最大宽度
    var imageDecode: String? = null, //图片bytes二次解密js, 返回解密后的bytes
    var payAction: String? = null,    //购买操作,js或者包含{{js}}的url
    var callBackJs: String? = null,   // 监听事件后执行的回调脚本
) {
    companion object {
        val jsonDeserializer = ruleJsonDeserializer(ContentRule::class.java) {
            ContentRule(content = it)
        }
    }
}
