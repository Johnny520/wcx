package h.Hchat.hooks.items.payment.detect;

import android.text.TextUtils;

import h.Hchat.utils.KavaReflector;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 红包功能反射工具。
 * 只负责通用反射读取、构造对象、弱 JSON 字段读取，不保存状态、不读配置、不做 Hook。
 */
public final class RedPacketReflector {
    private RedPacketReflector() {}

    public static Object readObjField(Object obj, String fieldName) {
        if (obj == null || TextUtils.isEmpty(fieldName)) return null;
        return KavaReflector.readField(obj, fieldName);
    }

    public static String readObjFieldString(Object obj, String fieldName) {
        Object value = readObjField(obj, fieldName);
        if (value == null) return null;
        try {
            Object inner = readObjField(value, "d");
            if (inner != null) return String.valueOf(inner);
        } catch (Throwable ignored) {}
        return String.valueOf(value);
    }

    public static boolean hasField(Class<?> clazz, String name) {
        if (clazz == null || TextUtils.isEmpty(name)) return false;
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

    public static boolean isLikelyAddMsgClass(Class<?> clazz) {
        if (clazz == null || clazz.isPrimitive() || clazz.isArray()) return false;
        if (clazz == String.class || Number.class.isAssignableFrom(clazz) || clazz == Boolean.class) {
            return false;
        }
        return hasField(clazz, "e") && hasField(clazz, "f")
                && (hasField(clazz, "h") || hasField(clazz, "i") || hasField(clazz, "m"));
    }

    public static String findAddMsgContent(Object addMsg) {
        for (String fieldName : new String[]{"h", "i", "m"}) {
            String value = readObjFieldString(addMsg, fieldName);
            if (!TextUtils.isEmpty(value) && value.contains("<wcpayinfo>")) return value;
        }
        String h = readObjFieldString(addMsg, "h");
        return !TextUtils.isEmpty(h) && !h.matches("^-?\\d+$") ? h : null;
    }

    public static String readJsonString(Object jsonObj, String key) {
        if (jsonObj == null || TextUtils.isEmpty(key)) return null;
        try {
            java.lang.reflect.Method method = KavaReflector.findMethod(jsonObj.getClass(), "getString", String.class);
            Object value = KavaReflector.invoke(method, jsonObj, key);
            return value != null ? String.valueOf(value) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static int readJsonInt(Object jsonObj, String key, int defaultValue) {
        if (jsonObj == null || TextUtils.isEmpty(key)) return defaultValue;
        try {
            java.lang.reflect.Method method = KavaReflector.findMethod(
                    jsonObj.getClass(), "optInt", String.class, int.class);
            Object value = KavaReflector.invoke(method, jsonObj, key, defaultValue);
            if (value instanceof Number) return ((Number) value).intValue();
        } catch (Throwable ignored) {}
        try {
            String value = readJsonString(jsonObj, key);
            if (!TextUtils.isEmpty(value)) return Integer.parseInt(value);
        } catch (Throwable ignored) {}
        return defaultValue;
    }

    public static String extractReceivedAmount(Object jsonObj) {
        return extractReceivedAmount(jsonObj, 0);
    }

    public static String extractReceivedAmount(Object jsonObj, int errCode) {
        if (jsonObj == null) return null;
        int retcode = readJsonInt(jsonObj, "retcode", 0);
        int isSender = readJsonInt(jsonObj, "isSender", -1);
        int receiveStatus = readJsonInt(jsonObj, "receiveStatus", -1);
        if (errCode != 0 || retcode != 0 || isSender == 1) {
            return null;
        }

        Long fen = readFen(jsonObj, "amount");
        if (fen == null) fen = readFen(jsonObj, "receiveAmount");
        if (fen == null) fen = readFen(jsonObj, "recAmount");
        if (fen == null || fen <= 0L) return null;
        if (receiveStatus >= 0 && receiveStatus != 1 && receiveStatus != 2) return null;
        return formatAmount(fen / 100.0d);
    }

    public static boolean isPositiveAmount(String amount) {
        if (TextUtils.isEmpty(amount)) return false;
        try {
            String normalized = amount.replaceAll("[^0-9.\\-]", "");
            return !TextUtils.isEmpty(normalized) && Double.parseDouble(normalized) > 0.000001d;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int amountToFen(String amount) {
        if (TextUtils.isEmpty(amount)) return 0;
        try {
            String normalized = amount.replaceAll("[^0-9.\\-]", "");
            if (TextUtils.isEmpty(normalized)) return 0;
            return (int) Math.round(Double.parseDouble(normalized) * 100.0d);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Long readFen(Object jsonObj, String key) {
        try {
            String value = readJsonString(jsonObj, key);
            if (!TextUtils.isEmpty(value) && value.matches("^\\d+$")) {
                return Long.parseLong(value);
            }
        } catch (Throwable ignored) {}
        try {
            String raw = String.valueOf(jsonObj);
            Pattern pattern = Pattern.compile("\"" + Pattern.quote(key)
                    + "\"\\s*:\\s*(\"?)(\\d+)\\1");
            Matcher matcher = pattern.matcher(raw);
            if (matcher.find()) return Long.parseLong(matcher.group(2));
        } catch (Throwable ignored) {}
        return null;
    }

    private static String formatAmount(double amount) {
        try {
            java.text.DecimalFormat df = new java.text.DecimalFormat("0.00");
            df.setRoundingMode(java.math.RoundingMode.HALF_UP);
            return df.format(amount);
        } catch (Throwable ignored) {
            return String.valueOf(amount);
        }
    }

    public static Object newInstanceByArgs(Class<?> clazz, Object[] args) {
        if (clazz == null) return null;
        try {
            return KavaReflector.newInstanceByArgs(clazz, args);
        } catch (Throwable ignored) {}
        return null;
    }
}
