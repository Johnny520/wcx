package h.Hchat.hooks.items.payment.detect;

import android.text.TextUtils;

import h.Hchat.utils.XmlValueReader;
import java.util.regex.Pattern;

/**
 * 红包解析工具。
 * 只做字符串/XML/nativeurl 解析，不读配置、不保存状态、不做 Hook。
 */
public final class RedPacketParser {
    private RedPacketParser() {}

    private static final Pattern KEYWORD_SPLIT = Pattern.compile("[|,，\\n\\r]+");
    private static final String[] RED_PACKET_USER_KEYWORD_TAGS = {
            "wishing",
            "memo",
            "remark"
    };
    private static final String[] RED_PACKET_VISIBLE_KEYWORD_TAGS = {
            "sendertitle",
            "description",
            "des",
            "wording"
    };

    public static String getXmlParamByTag(String xml, String tag) {
        return XmlValueReader.getTagValue(xml, tag);
    }

    public static String getNativeUrlParam(String url, String key) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(key)) return null;
        try {
            String prefix = key + "=";
            int start = url.indexOf('?');
            start = start >= 0 ? start + 1 : 0;
            while (start < url.length()) {
                int end = url.indexOf('&', start);
                if (end < 0) end = url.length();
                if (url.startsWith(prefix, start)) {
                    return url.substring(start + prefix.length(), end);
                }
                start = end + 1;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static boolean containsRedBagMarker(String text) {
        return !TextUtils.isEmpty(text)
                && (text.contains("receivehongbao")
                || text.contains("wxhb_personalreceive")
                || text.contains("weixin://weixinunionhongbao/")
                || text.contains("<nativeurl>"));
    }

    public static boolean containsKeyword(String content, String keywords) {
        if (TextUtils.isEmpty(content) || TextUtils.isEmpty(keywords)) return false;
        String text = redPacketKeywordText(content);
        if (TextUtils.isEmpty(text)) return false;
        for (String keyword : KEYWORD_SPLIT.split(keywords)) {
            String word = keyword != null ? keyword.trim() : "";
            if (!TextUtils.isEmpty(word) && text.contains(word)) return true;
        }
        return false;
    }

    public static String redPacketKeywordText(String content) {
        if (TextUtils.isEmpty(content)) return "";
        String source = stripMessagePrefix(content.trim());
        StringBuilder text = new StringBuilder();
        for (String tag : RED_PACKET_USER_KEYWORD_TAGS) {
            appendKeywordPart(text, getXmlParamByTag(source, tag), false);
        }
        for (String tag : RED_PACKET_VISIBLE_KEYWORD_TAGS) {
            appendKeywordPart(text, getXmlParamByTag(source, tag), true);
        }
        if (text.length() > 0) return text.toString();
        return looksLikeXml(source) ? "" : source;
    }

    private static String stripMessagePrefix(String content) {
        int prefixEnd = content.indexOf(":\n");
        if (prefixEnd > 0) {
            int xmlStart = content.indexOf('<', prefixEnd + 2);
            if (xmlStart >= 0) return content.substring(prefixEnd + 2).trim();
        }
        return content;
    }

    private static boolean looksLikeXml(String content) {
        int start = content.indexOf('<');
        int end = content.indexOf('>', start + 1);
        return start >= 0 && end > start;
    }

    private static void appendKeywordPart(StringBuilder text, String value, boolean skipGenericRedPacketTitle) {
        if (TextUtils.isEmpty(value)) return;
        String trimmed = value.trim();
        if (TextUtils.isEmpty(trimmed)) return;
        if (skipGenericRedPacketTitle && isGenericRedPacketTitle(trimmed)) return;
        if (text.indexOf(trimmed) >= 0) return;
        if (text.length() > 0) text.append('\n');
        text.append(trimmed);
    }

    private static boolean isGenericRedPacketTitle(String value) {
        if (TextUtils.isEmpty(value)) return false;
        String compact = value.replace(" ", "")
                .replace("\u00A0", "")
                .replace("\u2005", "")
                .trim();
        return compact.contains("给你发了一个红包")
                || compact.contains("给您发了一个红包")
                || compact.contains("发了一个红包")
                || compact.contains("发来一个红包")
                || "微信红包".equals(compact)
                || "红包".equals(compact);
    }

    public static boolean isGroupTalker(String talker) {
        return !TextUtils.isEmpty(talker)
                && (talker.endsWith("@chatroom")
                || talker.endsWith("@im.chatroom")
                || talker.endsWith("@openim"));
    }

    public static String normalizeUsername(String value) {
        if (TextUtils.isEmpty(value)) return "";
        String v = value.trim();
        while (v.endsWith("]") || v.endsWith(")") || v.endsWith("，")
                || v.endsWith(",") || v.endsWith(";") || v.endsWith("；")) {
            v = v.substring(0, v.length() - 1).trim();
        }
        int newline = v.indexOf('\n');
        if (newline > 0) v = v.substring(0, newline).trim();
        return v;
    }

    public static int getLuckyMoneySceneId(String xmlContent, String talker, String nativeUrl) {
        if (isUnionLuckyMoney(xmlContent, talker, nativeUrl)) return 1005;
        try {
            String scene = null;
            if (!TextUtils.isEmpty(nativeUrl)) {
                scene = getNativeUrlParam(nativeUrl, "sceneid");
                if (TextUtils.isEmpty(scene)) scene = getNativeUrlParam(nativeUrl, "scene_id");
                if (TextUtils.isEmpty(scene)) scene = getNativeUrlParam(nativeUrl, "scene");
            }
            if (TextUtils.isEmpty(scene)) scene = getXmlParamByTag(xmlContent, "sceneid");
            if (TextUtils.isEmpty(scene)) scene = getXmlParamByTag(xmlContent, "scene_id");
            if (!TextUtils.isEmpty(scene)) return safeParseInt(scene, 1002);
        } catch (Throwable ignored) {}
        return 1002;
    }

    public static boolean isUnionLuckyMoney(String xmlContent, String talker, String nativeUrl) {
        try {
            String normalizedTalker = String.valueOf(talker).trim().toLowerCase();
            if (normalizedTalker.endsWith("@openim")
                    || normalizedTalker.endsWith("@im.chatroom")) {
                return true;
            }

            String url = nativeUrl == null ? "" : nativeUrl.trim();
            if (url.startsWith("weixin://weixinunionhongbao/")) return true;
            for (String key : new String[]{"sceneid", "scene_id", "scene"}) {
                if ("1005".equals(getNativeUrlParam(url, key))
                        || "1005".equals(getXmlParamByTag(xmlContent, key).trim())) return true;
            }
            return !TextUtils.isEmpty(getNativeUrlParam(url, "union_source"))
                    || !TextUtils.isEmpty(getXmlParamByTag(xmlContent, "union_source").trim());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int safeParseInt(String value, int def) {
        if (TextUtils.isEmpty(value)) return def;
        try {
            return Integer.parseInt(value);
        } catch (Throwable ignored) {
            return def;
        }
    }
}
