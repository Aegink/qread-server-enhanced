package book.webBook.analyzeRule

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.jsoup.parser.Tag
import org.jsoup.parser.TagSet

/**
 * 书源 HTML 解析入口（对齐 LegadoTeam/legado 的 `SourceHtmlParser`，上游 #1182）。
 *
 * 大量老书源按 XHTML 习惯写 `<a/>`、`<div/>` 这类自闭合标签，标准 HTML 解析器会把它们当成
 * "已闭合的开标签"，导致后续兄弟节点全部挂到错误的父节点上，规则取到的元素结构随之错位。
 * 这里显式把 HTML 命名空间下的新标签标成 SelfClose，让 CSS / XPath / JSONPath 走同一份 DOM。
 */
internal fun parseSourceHtml(html: String): Document {
    val tags = TagSet.Html().onNewTag { tag ->
        if (tag.namespace() == Parser.NamespaceHtml) tag.set(Tag.SelfClose)
    }
    return Jsoup.parse(html, Parser.htmlParser().tagSet(tags))
}
