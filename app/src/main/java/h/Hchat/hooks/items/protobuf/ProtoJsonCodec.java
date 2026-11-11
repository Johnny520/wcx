package h.Hchat.hooks.items.protobuf;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class ProtoJsonCodec {
    private ProtoJsonCodec() {}

    static byte[] fromJson(String json) throws Exception {
        return fromJsonObject(new JSONObject(json == null || json.trim().isEmpty() ? "{}" : json));
    }

    static byte[] fromJsonObject(JSONObject json) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONArray names = json.names();
        if (names == null) return new byte[0];
        for (int i = 0; i < names.length(); i++) {
            String key = names.getString(i);
            int field = Integer.parseInt(key);
            writeValue(out, field, json.get(key));
        }
        return out.toByteArray();
    }

    static JSONObject toJson(byte[] bytes) {
        JSONObject out = new JSONObject();
        try {
            byte[] body = stripPrefix(bytes);
            parseInto(out, body, 0, body.length, 0);
        } catch (Throwable ignored) {
        }
        return out;
    }

    static boolean hasPacketPrefix(byte[] bytes) {
        return bytes != null && bytes.length >= 4 && (bytes[0] & 0xff) == 0;
    }

    static byte[] withPrefixFrom(byte[] original, byte[] body) {
        if (!hasPacketPrefix(original)) return body;
        byte[] out = new byte[4 + (body == null ? 0 : body.length)];
        System.arraycopy(original, 0, out, 0, 4);
        if (body != null) System.arraycopy(body, 0, out, 4, body.length);
        return out;
    }

    private static byte[] stripPrefix(byte[] bytes) {
        if (bytes == null) return new byte[0];
        if (!hasPacketPrefix(bytes)) return bytes;
        byte[] out = new byte[bytes.length - 4];
        System.arraycopy(bytes, 4, out, 0, out.length);
        return out;
    }

    private static void writeValue(ByteArrayOutputStream out, int field, Object value) throws Exception {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) writeValue(out, field, array.get(i));
        } else if (value instanceof JSONObject) {
            byte[] nested = fromJsonObject((JSONObject) value);
            writeLength(out, field, nested);
        } else if (value instanceof Number) {
            writeVarint(out, (field << 3));
            writeVarint(out, ((Number) value).longValue());
        } else if (value instanceof Boolean) {
            writeVarint(out, (field << 3));
            writeVarint(out, (Boolean) value ? 1L : 0L);
        } else if (value != null && value.toString().startsWith("hex->")) {
            writeLength(out, field, hexToBytes(value.toString().substring(5)));
        } else if (value != null) {
            writeLength(out, field, value.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeLength(ByteArrayOutputStream out, int field, byte[] bytes) {
        writeVarint(out, (field << 3) | 2);
        writeVarint(out, bytes == null ? 0 : bytes.length);
        if (bytes != null) out.write(bytes, 0, bytes.length);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0L) {
            out.write((int) ((value & 0x7fL) | 0x80L));
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static void parseInto(JSONObject out, byte[] data, int offset, int limit, int depth) throws Exception {
        int pos = offset;
        while (pos < limit && depth < 12) {
            Varint tag = readVarint(data, pos, limit);
            pos = tag.next;
            if (tag.value == 0L) return;
            int field = (int) (tag.value >>> 3);
            int wire = (int) (tag.value & 7L);
            Object value;
            if (wire == 0) {
                Varint v = readVarint(data, pos, limit);
                pos = v.next;
                value = v.value;
            } else if (wire == 1) {
                if (pos + 8 > limit) return;
                value = readFixed64(data, pos);
                pos += 8;
            } else if (wire == 2) {
                Varint len = readVarint(data, pos, limit);
                pos = len.next;
                int size = (int) len.value;
                if (size < 0 || pos + size > limit) return;
                byte[] raw = new byte[size];
                System.arraycopy(data, pos, raw, 0, size);
                pos += size;
                value = decodeLengthValue(raw, depth + 1);
            } else if (wire == 5) {
                if (pos + 4 > limit) return;
                value = readFixed32(data, pos);
                pos += 4;
            } else {
                return;
            }
            putValue(out, String.valueOf(field), value);
        }
    }

    private static ParseResult parseMessage(byte[] data, int depth) {
        try {
            JSONObject json = new JSONObject();
            parseInto(json, data, 0, data == null ? 0 : data.length, depth);
            byte[] rebuilt = fromJsonObject(json);
            if (java.util.Arrays.equals(rebuilt, data)) return new ParseResult(json, rebuilt);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object decodeLengthValue(byte[] raw, int depth) {
        ParseResult nested = depth < 12 ? parseMessage(raw, depth) : null;
        if (nested != null && nested.json.length() > 0) return nested.json;
        try {
            String text = new String(raw, StandardCharsets.UTF_8);
            if (java.util.Arrays.equals(text.getBytes(StandardCharsets.UTF_8), raw)) return text;
        } catch (Throwable ignored) {
        }
        return "hex->" + bytesToHex(raw);
    }

    private static void putValue(JSONObject out, String key, Object value) throws Exception {
        if (!out.has(key)) {
            out.put(key, value);
            return;
        }
        Object old = out.get(key);
        JSONArray array;
        if (old instanceof JSONArray) {
            array = (JSONArray) old;
        } else {
            array = new JSONArray();
            array.put(old);
            out.put(key, array);
        }
        array.put(value);
    }

    private static Varint readVarint(byte[] data, int pos, int limit) throws Exception {
        long value = 0L;
        int shift = 0;
        while (pos < limit && shift < 64) {
            int b = data[pos++] & 0xff;
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return new Varint(value, pos);
            shift += 7;
        }
        throw new IllegalArgumentException("bad varint");
    }

    private static long readFixed64(byte[] data, int pos) {
        long value = 0L;
        for (int i = 0; i < 8; i++) value |= (long) (data[pos + i] & 0xff) << (i * 8);
        return value;
    }

    private static int readFixed32(byte[] data, int pos) {
        int value = 0;
        for (int i = 0; i < 4; i++) value |= (data[pos + i] & 0xff) << (i * 8);
        return value;
    }

    private static byte[] hexToBytes(String hex) {
        String clean = hex == null ? "" : hex.replaceAll("[^0-9A-Fa-f]", "");
        int len = clean.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        if (bytes != null) {
            for (byte b : bytes) sb.append(String.format("%02X", b & 0xff));
        }
        return sb.toString();
    }

    private static final class Varint {
        final long value;
        final int next;

        Varint(long value, int next) {
            this.value = value;
            this.next = next;
        }
    }

    private static final class ParseResult {
        final JSONObject json;
        final byte[] bytes;

        ParseResult(JSONObject json, byte[] bytes) {
            this.json = json;
            this.bytes = bytes;
        }
    }
}
