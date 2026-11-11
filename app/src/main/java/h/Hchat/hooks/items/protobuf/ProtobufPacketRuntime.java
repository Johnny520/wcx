package h.Hchat.hooks.items.protobuf;

import org.json.JSONObject;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import h.Hchat.utils.HLog;

public final class ProtobufPacketRuntime {
    public static final String DIRECTION_REQUEST = "request";
    public static final String DIRECTION_RESPONSE = "response";

    private static volatile ProtobufPacketHook hook;
    private static final Map<Object, Boolean> GENERIC_SCENES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private ProtobufPacketRuntime() {}

    static void install(ProtobufPacketHook value) {
        hook = value;
    }

    public static boolean send(String uri, int cgiId, String json, Callback callback) {
        return send(uri, cgiId, 0, 0, json, callback);
    }

    public static boolean send(String uri, int cgiId, int funcId, int routeId, String json, Callback callback) {
        ProtobufPacketHook current = hook;
        if (current == null) {
            if (callback != null) callback.onResult(false, "Protobuf API未就绪");
            return false;
        }
        return current.send(uri, cgiId, funcId, routeId, json, callback);
    }

    static void markGenericScene(Object scene) {
        if (scene != null) GENERIC_SCENES.put(scene, Boolean.TRUE);
    }

    static boolean isGenericScene(Object scene) {
        return scene != null && GENERIC_SCENES.containsKey(scene);
    }

    public static boolean registerListener(Listener listener) {
        return listener != null && LISTENERS.addIfAbsent(listener);
    }

    public static boolean unregisterListener(Listener listener) {
        return listener != null && LISTENERS.remove(listener);
    }

    public static boolean hasListeners() {
        return !LISTENERS.isEmpty();
    }

    static void broadcast(Packet packet) {
        if (packet == null) return;
        for (Listener listener : LISTENERS) {
            try {
                listener.onPacket(packet);
            } catch (Throwable e) {
                HLog.e("[Hchat:Protobuf] 数据包监听器处理失败", e);
            }
        }
    }

    public interface Listener {
        void onPacket(Packet packet);
    }

    public static final class Packet {
        private final String direction;
        private final String uri;
        private final int cgiId;
        private final byte[] data;
        private final long timestamp;
        private volatile String json;

        Packet(String direction, String uri, int cgiId, byte[] data, long timestamp) {
            this.direction = direction == null ? "" : direction;
            this.uri = uri == null || "null".equals(uri) ? "" : uri;
            this.cgiId = cgiId;
            this.data = data == null ? new byte[0] : data.clone();
            this.timestamp = timestamp;
        }

        public String getDirection() {
            return direction;
        }

        public String getUri() {
            return uri;
        }

        public int getCgiId() {
            return cgiId;
        }

        public byte[] getData() {
            return data.clone();
        }

        public int getLength() {
            return data.length;
        }

        public String getJson() {
            String value = json;
            if (value != null) return value;
            synchronized (this) {
                value = json;
                if (value == null) {
                    try {
                        value = ProtoJsonCodec.toJson(data).toString();
                    } catch (Throwable ignored) {
                        value = "{}";
                    }
                    json = value;
                }
            }
            return value;
        }

        public JSONObject getJsonObject() {
            try {
                return new JSONObject(getJson());
            } catch (Throwable ignored) {
                return new JSONObject();
            }
        }

        public long getTimestamp() {
            return timestamp;
        }

        public boolean isRequest() {
            return DIRECTION_REQUEST.equals(direction);
        }

        public boolean isResponse() {
            return DIRECTION_RESPONSE.equals(direction);
        }
    }

    public interface Callback {
        void onResult(boolean success, String message);
    }
}
