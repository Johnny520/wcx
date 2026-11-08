package h.Hchat.hooks.items.protobuf;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import h.Hchat.hooks.api.core.WeChatApis;

final class ProtobufPacketSigner {
    private ProtobufPacketSigner() {}

    static JSONObject sign(int cgiId, JSONObject json) {
        if (json == null) return new JSONObject();
        try {
            if (cgiId == 522) return signNewSendMsg(json);
            if (cgiId == 222) return signAppMsg(json);
            if (cgiId == 175) return signEmoji(json);
        } catch (Throwable ignored) {
        }
        return json;
    }

    private static JSONObject signNewSendMsg(JSONObject json) throws Exception {
        JSONArray list = json.optJSONArray("2");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item != null) applyNewSendMsgSign(item);
            }
        } else {
            JSONObject item = json.optJSONObject("2");
            if (item != null) applyNewSendMsgSign(item);
        }
        return json;
    }

    private static void applyNewSendMsgSign(JSONObject item) throws Exception {
        long now = System.currentTimeMillis();
        item.put("4", (int) (now / 1000L));
        item.put("5", generateClientMsgId(selfWxId(), now));
    }

    private static JSONObject signAppMsg(JSONObject json) throws Exception {
        JSONObject inner = json.optJSONObject("2");
        if (inner == null) return json;
        String toUser = inner.optString("4");
        long nextId = previewNextId("message");
        long now = System.currentTimeMillis();
        String signature = toUser + nextId + "T" + now;
        inner.put("8", signature);
        inner.put("7", (int) (now / 1000L));
        json.put("7", signature);
        json.put("4", (int) (now / 1000L));
        return json;
    }

    private static JSONObject signEmoji(JSONObject json) throws Exception {
        JSONObject tag3 = json.optJSONObject("3");
        if (tag3 != null) tag3.put("9", String.valueOf(System.currentTimeMillis()));
        return json;
    }

    private static String selfWxId() {
        try {
            return WeChatApis.account() != null ? WeChatApis.account().selfWxId() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int generateClientMsgId(String wxId, long timeMs) {
        return generateClientMsgIdString(wxId, timeMs).hashCode();
    }

    private static String generateClientMsgIdString(String wxId, long timeMs) {
        String time = new SimpleDateFormat("ssHHmmMMddyy", Locale.US).format(new Date(timeMs));
        String prefix;
        if (wxId == null || wxId.length() <= 1) {
            prefix = time + "fffffff";
        } else {
            String md5 = md5(wxId.getBytes());
            prefix = time + md5.substring(0, Math.min(7, md5.length()));
        }
        String suffixHex = String.format(Locale.US, "%04x", timeMs % 65535L);
        long suffixNum = (timeMs % 7L) + 100L;
        return prefix + suffixHex + suffixNum;
    }

    private static String md5(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] out = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : out) {
                String hex = Integer.toHexString(b & 0xff);
                if (hex.length() < 2) sb.append('0');
                sb.append(hex);
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static long previewNextId(String tableName) {
        try {
            Class<?> mmkv = Class.forName("com.tencent.mmkv.MMKV");
            Object instance = mmkv.getDeclaredMethod("mmkvWithID", String.class, int.class)
                    .invoke(null, "db_max_id_record", 2);
            Object value = mmkv.getDeclaredMethod("decodeLong", String.class, long.class)
                    .invoke(instance, "msg." + tableName, 0L);
            long current = value instanceof Number ? ((Number) value).longValue() : 0L;
            return current == 0L ? initialId(tableName) : nextId(tableName, current);
        } catch (Throwable ignored) {
            return System.currentTimeMillis();
        }
    }

    private static long nextId(String tableName, long current) {
        if ("message".equals(tableName)) {
            if (current == 1_000_000L) return 10_000_000L;
            if (current == 90_000_000L) return 500_000_001L;
        }
        return current + 1L;
    }

    private static long initialId(String tableName) {
        return "message".equals(tableName) ? 1L : 1L;
    }
}
