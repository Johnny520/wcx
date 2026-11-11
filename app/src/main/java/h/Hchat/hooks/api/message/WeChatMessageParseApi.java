package h.Hchat.hooks.api.message;

import android.text.TextUtils;

import h.Hchat.hooks.api.model.WeChatParsedMessage;
import h.Hchat.hooks.api.model.WeChatMessage;
import h.Hchat.utils.KavaReflector;
import h.Hchat.utils.XmlValueReader;

import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信消息解析 API。
 */
public final class WeChatMessageParseApi {
    public boolean isAvailable() {
        return true;
    }

    public WeChatParsedMessage parseAddMsg(Object addMsg, String selfWxId) {
        if (addMsg == null) return null;
        String content = findAddMsgContent(addMsg);
        if (TextUtils.isEmpty(content)) return null;
        String xml = extractXml(content);
        String from = normalizeUsername(readObjFieldString(addMsg, "e"));
        String to = normalizeUsername(readObjFieldString(addMsg, "f"));
        String talker = resolveTalker(from, to, selfWxId);
        if (TextUtils.isEmpty(talker) || isInvalidUsername(talker)) return null;
        String sender = resolveSender(content, xml, from, talker);
        if (isInvalidUsername(sender)) return null;
        String nativeUrl = getXmlParamByTag(xml, "nativeurl");
        if (TextUtils.isEmpty(nativeUrl)) nativeUrl = getXmlParamByTag(content, "nativeurl");
        String exclusiveRecvUser = getXmlParamByTag(xml, "exclusive_recv_username");
        String msgSource = resolveMsgSource(addMsg, content, xml);
        boolean group = isGroupTalker(talker);
        boolean redPacket = containsRedPacketMarker(content);
        int type = resolveMessageType(addMsg, content);
        long createTime = resolveCreateTimeSeconds(addMsg);
        long msgSvrId = resolveMsgSvrId(addMsg);
        return new WeChatParsedMessage(
                content, xml, from, to, sender, talker, nativeUrl, exclusiveRecvUser,
                group, redPacket, type, createTime, msgSvrId, msgSource, selfWxId);
    }

    public String extractXml(String content) {
        if (TextUtils.isEmpty(content)) return "";
        int index = content.indexOf(":\n");
        if (index > 0 && content.indexOf("<") > index) return content.substring(index + 2);
        return content;
    }

    public String getXmlParamByTag(String xml, String tag) {
        return XmlValueReader.getTagValue(xml, tag);
    }

    public String getNativeUrlParam(String url, String key) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(key)) return "";
        try {
            String prefix = key + "=";
            int start = url.indexOf('?');
            start = start >= 0 ? start + 1 : 0;
            while (start < url.length()) {
                int end = url.indexOf('&', start);
                if (end < 0) end = url.length();
                if (url.startsWith(prefix, start)) return url.substring(start + prefix.length(), end);
                start = end + 1;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    public boolean isGroupTalker(String talker) {
        return !TextUtils.isEmpty(talker)
                && (talker.endsWith("@chatroom")
                || talker.endsWith("@im.chatroom")
                || talker.endsWith("@openim"));
    }

    public boolean containsRedPacketMarker(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String lower = text.toLowerCase();
        if (lower.contains("receivehongbao")
                || lower.contains("wxhb_personalreceive")
                || lower.contains("/hongbao/")) {
            return true;
        }
        String nativeUrl = getXmlParamByTag(text, "nativeurl").toLowerCase();
        if (nativeUrl.contains("receivehongbao")
                || nativeUrl.contains("wxhb")
                || nativeUrl.contains("hongbao")) {
            return true;
        }
        return "2001".equals(getXmlParamByTag(text, "type"));
    }

    public String normalizeUsername(String value) {
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

    public boolean isLikelyAddMsgClass(Class<?> clazz) {
        if (clazz == null || clazz.isPrimitive() || clazz.isArray()) return false;
        if (clazz == String.class || Number.class.isAssignableFrom(clazz) || clazz == Boolean.class) {
            return false;
        }
        return hasField(clazz, "e") && hasField(clazz, "f")
                && (hasField(clazz, "h") || hasField(clazz, "m"))
                && (hasField(clazz, "g") || hasField(clazz, "i"));
    }

    public String findAddMsgContent(Object addMsg) {
        for (String fieldName : new String[]{"h", "m", "i"}) {
            String value = readTextContainerString(addMsg, fieldName);
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "";
    }

    private String resolveTalker(String from, String to, String selfWxId) {
        String talker = from;
        if (isGroupTalker(to)) {
            talker = to;
        } else if (isGroupTalker(from)) {
            talker = from;
        } else if (!TextUtils.isEmpty(selfWxId) && selfWxId.equals(from) && !TextUtils.isEmpty(to)) {
            talker = to;
        }
        if (TextUtils.isEmpty(talker)) talker = to;
        return normalizeUsername(talker);
    }

    private String resolveSender(String content, String xml, String from, String talker) {
        String sender = "";
        if (isGroupTalker(talker) && !TextUtils.isEmpty(content)) {
            int prefixEnd = content.indexOf(":\n");
            if (prefixEnd > 0) sender = content.substring(0, prefixEnd);
        }
        if (TextUtils.isEmpty(sender) && !isGroupTalker(from)) sender = from;
        if (TextUtils.isEmpty(sender)) sender = getXmlParamByTag(xml, "fromusername");
        if (TextUtils.isEmpty(sender)) sender = from;
        return normalizeUsername(sender);
    }

    private boolean isInvalidUsername(String value) {
        if (TextUtils.isEmpty(value)) return true;
        return "false".equalsIgnoreCase(value)
                || "true".equalsIgnoreCase(value)
                || "0".equals(value)
                || "1".equals(value);
    }

    private int resolveMessageType(Object addMsg, String content) {
        int typeG = readObjFieldInt(addMsg, "g");
        int typeI = readObjFieldInt(addMsg, "i");
        if (typeG > 0) return typeG;
        if (typeI > 0) return typeI;
        return WeChatMessage.inferType(content);
    }

    private long resolveCreateTimeSeconds(Object addMsg) {
        long value = chooseUnixSeconds(readObjFieldLong(addMsg, "o"), readObjFieldLong(addMsg, "q"));
        return value > 0 ? value : 0L;
    }

    private long resolveMsgSvrId(Object addMsg) {
        long valueR = readObjFieldLong(addMsg, "r");
        long valueU = readObjFieldLong(addMsg, "u");
        if (valueR > 100000L) return valueR;
        if (valueU > 100000L) return valueU;
        if (valueR > 0L) return valueR;
        return Math.max(valueU, 0L);
    }

    private String resolveMsgSource(Object addMsg, String content, String xml) {
        String fromXml = getXmlSection(xml, "msgsource");
        if (TextUtils.isEmpty(fromXml)) fromXml = getXmlSection(content, "msgsource");
        if (!TextUtils.isEmpty(fromXml)) return "<msgsource>" + fromXml + "</msgsource>";
        String source = readTextContainerString(addMsg, "p");
        if (!TextUtils.isEmpty(source)) return normalizeMsgSource(source);
        source = readTextContainerString(addMsg, "q");
        if (!TextUtils.isEmpty(source) && source.contains("msgsource")) return normalizeMsgSource(source);
        for (String fieldName : new String[]{"j", "k", "l", "n", "p", "s", "t"}) {
            String value = readTextContainerString(addMsg, fieldName);
            if (!TextUtils.isEmpty(value) && value.contains("msgsource")) return value;
        }
        return "";
    }

    private String normalizeMsgSource(String value) {
        if (TextUtils.isEmpty(value)) return "";
        String trimmed = value.trim();
        if (trimmed.contains("<msgsource")) return trimmed;
        if (trimmed.contains("<atuserlist")) return "<msgsource>" + trimmed + "</msgsource>";
        if (trimmed.contains("atuserlist")
                || trimmed.contains("notify@all")
                || trimmed.contains("announcement@all")) {
            return "<msgsource><atuserlist>" + trimmed + "</atuserlist></msgsource>";
        }
        return trimmed;
    }

    private String getXmlSection(String xml, String tag) {
        if (TextUtils.isEmpty(xml) || TextUtils.isEmpty(tag)) return "";
        try {
            Matcher section = Pattern.compile("<" + tag + "\\b[^>]*>(.*?)</" + tag + ">",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(xml);
            if (section.find()) return section.group(1);
        } catch (Throwable ignored) {}
        return "";
    }

    private long chooseUnixSeconds(long first, long second) {
        if (isUnixSeconds(first)) return first;
        if (isUnixSeconds(second)) return second;
        return 0L;
    }

    private boolean isUnixSeconds(long value) {
        return value >= 946656000L && value <= 4102444800L;
    }

    private Object readObjField(Object obj, String fieldName) {
        if (obj == null || TextUtils.isEmpty(fieldName)) return null;
        try {
            Field field = KavaReflector.findFieldRecursive(obj.getClass(), fieldName);
            return field != null ? KavaReflector.readField(field, obj) : null;
        } catch (Throwable ignored) {}
        return null;
    }

    private String readObjFieldString(Object obj, String fieldName) {
        Object value = readObjField(obj, fieldName);
        if (value == null || value instanceof Boolean || value instanceof Number) return "";
        if (value instanceof CharSequence) return String.valueOf(value);
        Object inner = readObjField(value, "d");
        return inner != null ? String.valueOf(inner) : String.valueOf(value);
    }

    private String readTextContainerString(Object obj, String fieldName) {
        Object value = readObjField(obj, fieldName);
        if (value == null || value instanceof Boolean || value instanceof Number) return "";
        if (value instanceof CharSequence) return String.valueOf(value);
        Object inner = readObjField(value, "d");
        return inner instanceof CharSequence ? String.valueOf(inner) : "";
    }

    private int readObjFieldInt(Object obj, String fieldName) {
        long value = readObjFieldLong(obj, fieldName);
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) return 0;
        return (int) value;
    }

    private long readObjFieldLong(Object obj, String fieldName) {
        Object value = readObjField(obj, fieldName);
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private boolean hasField(Class<?> clazz, String name) {
        try {
            Class<?> cur = clazz;
            while (cur != null && cur != Object.class) {
                for (Field field : KavaReflector.declaredFields(cur)) {
                    if (name.equals(field.getName())) return true;
                }
                cur = cur.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
