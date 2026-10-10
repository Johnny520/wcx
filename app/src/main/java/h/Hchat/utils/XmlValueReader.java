package h.Hchat.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class XmlValueReader {
    private static final Pattern PARTS = Pattern.compile("<!\\[CDATA\\[(.*?)\\]\\]>|([^<]+)", Pattern.DOTALL);
    private static final Pattern ENTITIES = Pattern.compile("&(?:amp|lt|gt|quot|apos|#[0-9]+|#x[0-9a-fA-F]+);");

    private XmlValueReader() {}

    public static String getTagValue(String xml, String tag) {
        if (xml == null || xml.isEmpty() || tag == null || tag.isEmpty()) return "";
        String name = Pattern.quote(tag);
        // 闭合标签以 < 为边界，占有量词避免长文本及多片段的逐字符递归回溯。
        Matcher element = Pattern.compile("<!\\[CDATA\\[.*?\\]\\]>|<!--.*?-->|<" + name
                + ">((?:<!\\[CDATA\\[.*?\\]\\]>|[^<])*+)</" + name + ">", Pattern.DOTALL).matcher(xml);
        while (element.find()) {
            if (element.group(1) == null) continue;
            Matcher parts = PARTS.matcher(element.group(1));
            StringBuilder value = new StringBuilder();
            while (parts.find()) value.append(parts.group(1) != null
                    ? parts.group(1) : decodeEntities(parts.group(2)));
            return value.toString();
        }
        return "";
    }

    private static String decodeEntities(String text) {
        Matcher entity = ENTITIES.matcher(text);
        StringBuffer result = new StringBuffer();
        while (entity.find()) {
            String token = entity.group();
            String value = token;
            switch (token) {
                case "&amp;": value = "&"; break;
                case "&lt;": value = "<"; break;
                case "&gt;": value = ">"; break;
                case "&quot;": value = "\""; break;
                case "&apos;": value = "'"; break;
                default:
                    try {
                        boolean hex = token.startsWith("&#x");
                        int code = Integer.parseInt(token.substring(hex ? 3 : 2, token.length() - 1), hex ? 16 : 10);
                        if (code == 9 || code == 10 || code == 13
                                || code >= 0x20 && code <= 0xD7FF
                                || code >= 0xE000 && code <= 0xFFFD
                                || code >= 0x10000 && code <= 0x10FFFF) {
                            value = new String(Character.toChars(code));
                        }
                    } catch (IllegalArgumentException ignored) {}
            }
            entity.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        entity.appendTail(result);
        return result.toString();
    }
}
