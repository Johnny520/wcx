package com.Johnny.wcx.utils.serialization

object XmlUtils {

    /**
     * 从 XML 提取属性值 (e.g. appid="xxx")
     */
    fun extractXmlAttr(xml: String, attrName: String): String {
        runCatching {
            val match = Regex("""$attrName="([^"]*)"""").find(xml)
            return match?.groupValues?.get(1) ?: ""
        }
        return ""
    }

    /**
     * 从 XML 提取标签内容 (e.g. <title>xxx</title>)
     */
    fun extractXmlTag(xml: String, tagName: String): String {
        runCatching {
            Regex("""<$tagName><!\[CDATA\[(.*?)]]></$tagName>""").find(xml)?.let {
                return it.groupValues[1]
            }
            Regex("""<$tagName>(.*?)</$tagName>""").find(xml)?.let {
                return it.groupValues[1]
            }
        }
        return ""
    }
}
