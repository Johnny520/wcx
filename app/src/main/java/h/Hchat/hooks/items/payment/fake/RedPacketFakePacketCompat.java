package h.Hchat.hooks.items.payment.fake;

import android.content.ContentValues;
import android.text.TextUtils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 伪造/分裂红包兼容工具。
 */
public final class RedPacketFakePacketCompat {
    private static final Map<String, String> GROUP_FIX_MAP = new ConcurrentHashMap<>();

    private RedPacketFakePacketCompat() {}

    public static String duplicateAt(String value) {
        if (TextUtils.isEmpty(value)) return value;
        return value.replace("%40", "%40%40").replace("@", "@@");
    }

    public static boolean isNormalChatroomUsername(String value) {
        return !TextUtils.isEmpty(value)
                && value.replace("%40", "@").matches("^[0-9]+@chatroom$");
    }

    public static void rememberGroupFix(String original, String tampered) {
        if (TextUtils.isEmpty(original) || TextUtils.isEmpty(tampered)
                || original.equals(tampered)) {
            return;
        }
        if (!original.contains("@chatroom") && !original.contains("%40chatroom")) return;
        GROUP_FIX_MAP.put(tampered, original);
        try {
            String originalEncoded = original.replace("@", "%40");
            String tamperedEncoded = tampered.replace("@", "%40");
            if (!originalEncoded.equals(original) || !tamperedEncoded.equals(tampered)) {
                GROUP_FIX_MAP.put(tamperedEncoded, originalEncoded);
            }
        } catch (Throwable ignored) {}
        if (GROUP_FIX_MAP.size() > 20) {
            try {
                String first = GROUP_FIX_MAP.keySet().iterator().next();
                GROUP_FIX_MAP.remove(first);
            } catch (Throwable ignored) {}
        }
    }

    public static boolean hasGroupFix() {
        return !GROUP_FIX_MAP.isEmpty();
    }

    public static String restoreGroupIds(String text) {
        if (TextUtils.isEmpty(text) || GROUP_FIX_MAP.isEmpty()) return text;
        try {
            String out = text;
            for (Map.Entry<String, String> entry : GROUP_FIX_MAP.entrySet()) {
                String bad = entry.getKey();
                String good = entry.getValue();
                if (!TextUtils.isEmpty(bad) && !TextUtils.isEmpty(good)) {
                    out = out.replace(bad, good);
                }
            }
            return out;
        } catch (Throwable ignored) {
            return text;
        }
    }

    public static void fixContentValues(ContentValues values) {
        if (values == null || GROUP_FIX_MAP.isEmpty()) return;
        try {
            Set<String> keys = new HashSet<>(values.keySet());
            for (String key : keys) {
                Object value = values.get(key);
                if (!(value instanceof String)) continue;
                String oldValue = (String) value;
                String newValue = restoreGroupIds(oldValue);
                if (!newValue.equals(oldValue)) values.put(key, newValue);
            }
        } catch (Throwable ignored) {}
    }

    public static String normalizeNativeUrl(String nativeUrl, String talker) {
        if (TextUtils.isEmpty(nativeUrl) || !isNormalChatroomUsername(talker)) return nativeUrl;
        try {
            StringBuilder out = new StringBuilder(nativeUrl);
            int scan = nativeUrl.indexOf('?');
            scan = scan >= 0 ? scan + 1 : 0;
            boolean changed = false;
            while (scan < out.length()) {
                int pairEnd = out.indexOf("&", scan);
                if (pairEnd < 0) pairEnd = out.length();
                int eq = out.indexOf("=", scan);
                if (eq > scan && eq < pairEnd) {
                    int valueStart = eq + 1;
                    String value = out.substring(valueStart, pairEnd);
                    String decoded = value.replace("%40", "@").toLowerCase();
                    if (decoded.contains("chatroom") && !talker.equals(value.replace("%40", "@"))) {
                        String fixed = value.contains("%40")
                                ? talker.replace("@", "%40")
                                : talker;
                        out.replace(valueStart, pairEnd, fixed);
                        pairEnd += fixed.length() - value.length();
                        changed = true;
                    }
                }
                scan = pairEnd + 1;
            }
            return changed ? out.toString() : nativeUrl;
        } catch (Throwable ignored) {
            return nativeUrl;
        }
    }
}
