package h.Hchat.hooks.items.protobuf;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.api.core.WeChatApis;
import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.utils.HLog;
import h.Hchat.utils.KavaReflector;

public class ProtobufPacketHook {
    private static final String TAG = "[Hchat:Protobuf]";
    private static final long SNAPSHOT_TTL_MS = 10 * 60 * 1000L;

    private final ClassLoader classLoader;
    private final DexFinder dexFinder;
    private final SharedPreferences prefs;
    private final ProtobufPacketFileLogger fileLogger;
    private final ProtobufGenericSender genericSender;
    private final ConcurrentHashMap<String, PacketSnapshot> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recentLogs = new ConcurrentHashMap<>();
    private volatile boolean installed;
    private volatile boolean sceneEndHooked;

    public ProtobufPacketHook(ClassLoader classLoader,
                              DexFinder dexFinder,
                              SharedPreferences prefs,
                              ProtobufPacketFileLogger fileLogger) {
        this.classLoader = classLoader;
        this.dexFinder = dexFinder;
        this.prefs = prefs;
        this.fileLogger = fileLogger;
        this.genericSender = new ProtobufGenericSender(dexFinder, classLoader);
    }

    public boolean install() {
        if (installed) return true;
        try {
            List<Class<?>> bases = collectBaseClasses();
            int hooked = 0;
            Set<String> hookedDesc = new HashSet<>();
            for (Class<?> base : bases) {
                for (Method method : KavaReflector.declaredMethods(base)) {
                    if (!"dispatch".equals(method.getName())) continue;
                    if (method.getParameterTypes().length != 3) continue;
                    String desc = base.getName() + "#" + method.toString();
                    if (!hookedDesc.add(desc)) continue;
                    HookRegistry.get().hook(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            handleDispatch(param);
                        }
                    });
                    hooked++;
                }
            }
            hookSceneEndCallbacks();
            installed = hooked > 0;
            if (hooked <= 0) error("Hook失败: 未找到dispatch");
            return installed;
        } catch (Throwable e) {
            error("Hook失败: " + e.getMessage());
            return false;
        }
    }

    public boolean send(String uri, int cgiId, int funcId, int routeId, String json,
                        ProtobufPacketRuntime.Callback callback) {
        try {
            JSONObject signedJson = ProtobufPacketSigner.sign(cgiId,
                    new JSONObject(json == null || json.trim().isEmpty() ? "{}" : json));
            byte[] body = ProtoJsonCodec.fromJsonObject(signedJson);
            if (trySendNativeScene(cgiId, uri, signedJson, callback)) {
                return true;
            }
            PacketSnapshot snapshot = findSnapshot(uri, cgiId);
            if (snapshot != null && !genericSender.supportsSpecificRequest(cgiId)) {
                if (replaySnapshot(snapshot, body, uri, cgiId, callback)) {
                    return true;
                }
            }
            if (genericSender.send(uri, cgiId, funcId, routeId, body, callback)) {
                return true;
            }
            if (snapshot == null || snapshot.request == null || snapshot.reqPbObj == null) {
                notify(callback, false, "通用发包未就绪(" + genericSender.readinessReport() + ")，且未找到同类已抓请求");
                return false;
            }
            return replaySnapshot(snapshot, body, uri, cgiId, callback);
        } catch (Throwable e) {
            notify(callback, false, "发送失败: " + e.getMessage());
            return false;
        }
    }

    private boolean trySendNativeScene(int cgiId, String uri, JSONObject payload,
                                       ProtobufPacketRuntime.Callback callback) {
        Object scene = buildNativeScene(cgiId, uri, payload);
        if (scene == null) return false;
        boolean sent = WeChatApis.network() != null && WeChatApis.network().sendRequest(scene);
        if (sent) {
            notify(callback, true, "已用原生场景发送: " + uri + " type=" + cgiId);
            return true;
        }
        error("原生场景发送失败: type=" + cgiId + " uri=" + uri + " scene=" + scene.getClass().getName());
        return false;
    }

    private Object buildNativeScene(int cgiId, String uri, JSONObject payload) {
        if (dexFinder == null || payload == null) return null;
        try {
            Class<?> sceneClass = dexFinder.findNativeNetSceneClass(uri, cgiId);
            if (sceneClass == null) return null;
            return buildSceneByClass(sceneClass, payload, cgiId);
        } catch (Throwable e) {
            HLog.e(TAG + " 原生场景构造失败: type=" + cgiId + " uri=" + uri, e);
            return null;
        }
    }

    private Object buildSceneByClass(Class<?> sceneClass, JSONObject payload, int cgiId) {
        NativeArgs args = NativeArgs.from(payload);
        if (!args.hasUsefulQuery()) return null;
        for (Constructor<?> ctor : KavaReflector.declaredConstructors(sceneClass)) {
            Object[] values = buildNativeArgs(ctor.getParameterTypes(), args);
            if (values == null) continue;
            Object scene = KavaReflector.newInstance(ctor, values);
            if (scene == null) continue;
            if (sceneType(scene) == cgiId) {
                return scene;
            }
        }
        return null;
    }

    private Object[] buildNativeArgs(Class<?>[] types, NativeArgs nativeArgs) {
        if (types == null || types.length == 0) return null;
        Object[] out = new Object[types.length];
        int stringIndex = 0;
        int intIndex = 0;
        int stringCount = 0;
        for (Class<?> type : types) {
            if (type == String.class) stringCount++;
            if (!isSupportedNativeCtorType(type)) return null;
        }
        if (stringCount > 0 && !nativeArgs.hasStringValue()) return null;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (type == String.class) {
                out[i] = nativeArgs.stringAt(stringIndex++);
            } else if (type == int.class || type == Integer.class) {
                out[i] = nativeArgs.intAt(intIndex++);
            } else if (type == long.class || type == Long.class) {
                out[i] = 0L;
            } else if (type == boolean.class || type == Boolean.class) {
                out[i] = false;
            }
        }
        return out;
    }

    private boolean isSupportedNativeCtorType(Class<?> type) {
        return type == String.class
                || type == int.class
                || type == Integer.class
                || type == long.class
                || type == Long.class
                || type == boolean.class
                || type == Boolean.class;
    }

    private int sceneType(Object scene) {
        try {
            Object value = call(scene, "getType");
            return value instanceof Number ? ((Number) value).intValue() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private boolean replaySnapshot(PacketSnapshot snapshot, byte[] body, String uri, int cgiId,
                                   ProtobufPacketRuntime.Callback callback) {
        try {
            if (snapshot == null || snapshot.request == null || snapshot.reqPbObj == null) return false;
            byte[] out = ProtoJsonCodec.withPrefixFrom(snapshot.bytes, body);
            KavaReflector.invoke(KavaReflector.findMethodRecursive(snapshot.reqPbObj.getClass(), "parseFrom", byte[].class), snapshot.reqPbObj, out);
            boolean sent = WeChatApis.network() != null && WeChatApis.network().sendRequest(snapshot.request);
            notify(callback, sent, sent ? "已用同类请求重放: " + uri + " type=" + cgiId : "发送失败: 网络API未就绪");
            return sent;
        } catch (Throwable e) {
            notify(callback, false, "重放发送失败: " + e.getMessage());
            return false;
        }
    }

    private List<Class<?>> collectBaseClasses() {
        List<Class<?>> out = new ArrayList<>();
        addClass(out, "com.tencent.mm.modelbase.m1");
        addClass(out, "com.tencent.mm.modelbase.k1");
        addClass(out, "com.tencent.mm.modelbase.l1");
        addClass(out, "com.tencent.mm.modelbase.n1");
        if (dexFinder != null && dexFinder.packetBaseClasses != null) {
            for (Class<?> cl : dexFinder.packetBaseClasses) {
                if (cl != null && !out.contains(cl)) out.add(cl);
            }
        }
        return out;
    }

    private void addClass(List<Class<?>> out, String name) {
        try {
            Class<?> cl = KavaReflector.loadClass(name, classLoader);
            if (cl != null && !out.contains(cl)) out.add(cl);
        } catch (Throwable ignored) {
        }
    }

    private void handleDispatch(XC_MethodHook.MethodHookParam param) {
        boolean logEnabled = enabled();
        boolean listenerEnabled = ProtobufPacketRuntime.hasListeners();
        if (!logEnabled && !listenerEnabled) return;
        try {
            if (param.args == null || param.args.length < 3) return;
            Object reqResp = param.args[1];
            Object callback = param.args[2];
            if (reqResp == null) return;
            String uri = safeString(call(reqResp, "getUri"));
            if (uri.length() == 0) uri = "null";
            int cgiId = safeInt(call(reqResp, "getType"), -1);
            boolean blocked = logEnabled && isBlocked(cgiId);
            if (blocked && !listenerEnabled) return;
            final String finalUri = uri;
            final int finalCgiId = cgiId;
            final Object finalReqResp = reqResp;

            PacketSnapshot snapshot = captureRequest(param.thisObject, reqResp, uri, cgiId);
            if (snapshot != null) {
                snapshots.put(snapshot.key(), snapshot);
                trimSnapshots();
                if (listenerEnabled) {
                    broadcastPacket(ProtobufPacketRuntime.DIRECTION_REQUEST, uri, cgiId, snapshot.bytes);
                }
                if (logEnabled && !blocked && captureRequestEnabled()
                        && shouldLog("req|" + snapshot.key(), snapshot.bytes)) {
                    logPacket("请求", uri, cgiId, snapshot.bytes);
                }
            }

            if ((listenerEnabled || (logEnabled && !blocked && captureResponseEnabled()))
                    && callback != null
                    && dexFinder != null
                    && dexFinder.protobufOnGYNetEndClass != null
                    && dexFinder.protobufOnGYNetEndClass.isInterface()
                    && !Proxy.isProxyClass(callback.getClass())) {
                    Object proxy = Proxy.newProxyInstance(
                            dexFinder.protobufOnGYNetEndClass.getClassLoader(),
                            new Class[]{dexFinder.protobufOnGYNetEndClass},
                            (proxyObj, method, args) -> {
                                if ("hashCode".equals(method.getName())) return callback.hashCode();
                                if ("toString".equals(method.getName())) return callback.toString();
                                if ("equals".equals(method.getName())) return callback == (args != null && args.length > 0 ? args[0] : null);
                                if ("onGYNetEnd".equals(method.getName())) {
                                    captureResponse(finalUri, finalCgiId, finalReqResp, args);
                                }
                                return KavaReflector.invoke(method, callback, args == null ? new Object[0] : args);
                            });
                    param.args[2] = proxy;
            }
        } catch (Throwable e) {
            error("dispatch处理失败: " + e.getMessage());
        }
    }

    private void hookSceneEndCallbacks() {
        if (sceneEndHooked || dexFinder == null || dexFinder.protobufNetSceneBaseClass == null) return;
        try {
            int hooked = 0;
            Set<String> hookedDesc = new HashSet<>();
            if (dexFinder.protobufSceneEndMethods != null) {
                for (Method method : dexFinder.protobufSceneEndMethods) {
                    hooked += hookSceneEndMethod(method, hookedDesc);
                }
            }
            List<Class<?>> candidates = new ArrayList<>();
            collectSceneEndClasses(candidates, dexFinder.protobufOnGYNetEndClass);
            if (dexFinder.packetBaseClasses != null) {
                for (Class<?> base : dexFinder.packetBaseClasses) collectSceneEndClasses(candidates, base);
            }
            for (Class<?> clazz : candidates) {
                for (Method method : KavaReflector.declaredMethods(clazz)) {
                    hooked += hookSceneEndMethod(method, hookedDesc);
                }
            }
            sceneEndHooked = hooked > 0;
            if (hooked <= 0) error("Hook通用发包回调隔离失败: 未找到onSceneEnd");
        } catch (Throwable e) {
            error("Hook通用发包回调隔离失败: " + e.getMessage());
        }
    }

    private int hookSceneEndMethod(Method method, Set<String> hookedDesc) {
        if (!isSceneEndMethod(method)) return 0;
        String desc = method.getDeclaringClass().getName() + "#" + method.toString();
        if (!hookedDesc.add(desc)) return 0;
        HookRegistry.get().hook(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 4) return;
                Object scene = param.args[3];
                if (!ProtobufPacketRuntime.isGenericScene(scene)) return;
                param.setResult(null);
            }
        });
        return 1;
    }

    private void collectSceneEndClasses(List<Class<?>> out, Class<?> seed) {
        if (seed == null) return;
        if (seed.isInterface()) {
            collectInterfaceImplementors(out, seed);
            return;
        }
        addSceneEndClass(out, seed);
        Class<?> cur = seed.getSuperclass();
        while (cur != null && cur != Object.class) {
            addSceneEndClass(out, cur);
            cur = cur.getSuperclass();
        }
    }

    private void collectInterfaceImplementors(List<Class<?>> out, Class<?> iface) {
        if (iface == null || dexFinder == null) return;
        List<Class<?>> bases = new ArrayList<>();
        if (dexFinder.packetBaseClasses != null) bases.addAll(dexFinder.packetBaseClasses);
        if (dexFinder.netQueueCandidateClasses != null) bases.addAll(dexFinder.netQueueCandidateClasses);
        for (Class<?> candidate : bases) {
            if (candidate == null) continue;
            if (iface.isAssignableFrom(candidate)) addSceneEndClass(out, candidate);
            for (Class<?> nested : candidate.getDeclaredClasses()) {
                if (iface.isAssignableFrom(nested)) addSceneEndClass(out, nested);
            }
        }
    }

    private void addSceneEndClass(List<Class<?>> out, Class<?> clazz) {
        if (clazz != null && !clazz.isInterface() && !out.contains(clazz)) out.add(clazz);
    }

    private boolean isSceneEndMethod(Method method) {
        if (method == null || !"onSceneEnd".equals(method.getName())) return false;
        Class<?>[] params = method.getParameterTypes();
        return params != null
                && params.length == 4
                && params[0] == int.class
                && params[1] == int.class
                && params[2] == String.class
                && dexFinder != null
                && dexFinder.protobufNetSceneBaseClass != null
                && dexFinder.protobufNetSceneBaseClass.isAssignableFrom(params[3]);
    }

    private PacketSnapshot captureRequest(Object scene, Object reqResp, String uri, int cgiId) {
        try {
            Object wrapper = call(reqResp, "getReqObj");
            Object pb = findPbObject(wrapper);
            byte[] bytes = toBytes(pb);
            if (pb == null || bytes == null) return null;
            return new PacketSnapshot(uri, cgiId, scene, pb, bytes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void captureResponse(String uri, int cgiId, Object reqResp, Object[] args) {
        boolean listenerEnabled = ProtobufPacketRuntime.hasListeners();
        boolean logEnabled = enabled() && captureResponseEnabled() && !isBlocked(cgiId);
        if (!listenerEnabled && !logEnabled) return;
        try {
            Object respSource = null;
            if (args != null && args.length > 4) respSource = args[4];
            if (respSource == null) respSource = reqResp;
            byte[] bytes = extractResponseBytes(respSource);
            if (bytes != null) {
                if (listenerEnabled) {
                    broadcastPacket(ProtobufPacketRuntime.DIRECTION_RESPONSE, uri, cgiId, bytes);
                }
                if (logEnabled && shouldLog("resp|" + cgiId + "|" + uri, bytes)) {
                    logPacket("响应", uri, cgiId, bytes);
                }
            }
        } catch (Throwable e) {
            error("响应抓包失败: " + e.getMessage());
        }
    }

    private byte[] extractResponseBytes(Object source) {
        if (source == null) return null;
        try {
            Object direct = call(source, "getRespObj");
            Object pb = findPbObject(direct);
            byte[] bytes = toBytes(pb);
            if (bytes != null) return bytes;
        } catch (Throwable ignored) {
        }
        try {
            Object wrapper = KavaReflector.readField(source, "b");
            Object pb = findPbObject(wrapper);
            return toBytes(pb);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object findPbObject(Object wrapper) {
        if (wrapper == null) return null;
        try {
            Object value = KavaReflector.readField(wrapper, "a");
            if (isPbObject(value)) return value;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> cur = wrapper.getClass();
            while (cur != null && cur != Object.class) {
                for (Field field : KavaReflector.declaredFields(cur)) {
                    try {
                        Object value = KavaReflector.readField(field, wrapper);
                        if (isPbObject(value)) return value;
                    } catch (Throwable ignored) {
                    }
                }
                cur = cur.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean isPbObject(Object value) {
        return value != null
                && KavaReflector.findMethod(value.getClass(), "toByteArray") != null
                && KavaReflector.findMethod(value.getClass(), "parseFrom", byte[].class) != null;
    }

    private byte[] toBytes(Object pb) {
        if (pb == null) return null;
        try {
            Object value = call(pb, "toByteArray");
            if (value instanceof byte[]) return (byte[]) value;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private PacketSnapshot findSnapshot(String uri, int cgiId) {
        String targetUri = uri == null ? "" : uri.trim();
        long now = System.currentTimeMillis();
        PacketSnapshot exactLatest = null;
        for (PacketSnapshot snapshot : snapshots.values()) {
            if (snapshot.cgiId != cgiId) continue;
            if (!targetUri.equals(snapshot.uri)) continue;
            if (now - snapshot.time > SNAPSHOT_TTL_MS) continue;
            if (exactLatest == null || snapshot.time > exactLatest.time) exactLatest = snapshot;
        }
        return exactLatest;
    }

    private void trimSnapshots() {
        if (snapshots.size() <= 60) return;
        try {
            String first = snapshots.keySet().iterator().next();
            snapshots.remove(first);
        } catch (Throwable ignored) {
        }
    }

    private boolean shouldLog(String prefix, byte[] bytes) {
        String key = prefix + "|" + java.util.Arrays.hashCode(bytes);
        long now = System.currentTimeMillis();
        Long last = recentLogs.put(key, now);
        if (recentLogs.size() > 80) {
            try {
                recentLogs.remove(recentLogs.keySet().iterator().next());
            } catch (Throwable ignored) {
            }
        }
        return last == null || now - last > 500L;
    }

    private void logPacket(String direction, String uri, int cgiId, byte[] bytes) {
        if (fileLogger != null) {
            fileLogger.append(direction, uri, cgiId, bytes == null ? 0 : bytes.length, packetJson(bytes));
        }
    }

    private void broadcastPacket(String direction, String uri, int cgiId, byte[] bytes) {
        if (!ProtobufPacketRuntime.hasListeners()) return;
        long timestamp = System.currentTimeMillis();
        ProtobufPacketRuntime.broadcast(new ProtobufPacketRuntime.Packet(
                direction,
                uri,
                cgiId,
                bytes,
                timestamp
        ));
    }

    private String packetJson(byte[] bytes) {
        try {
            return ProtoJsonCodec.toJson(bytes).toString();
        } catch (Throwable ignored) {
            return "{}";
        }
    }

    private boolean enabled() {
        return prefs != null && prefs.getBoolean(ProtobufPacketSettings.KEY_ENABLE, ProtobufPacketSettings.DEFAULT_ENABLE);
    }

    private boolean captureRequestEnabled() {
        return prefs == null || prefs.getBoolean(ProtobufPacketSettings.KEY_CAPTURE_REQUEST, ProtobufPacketSettings.DEFAULT_CAPTURE_REQUEST);
    }

    private boolean captureResponseEnabled() {
        return prefs == null || prefs.getBoolean(ProtobufPacketSettings.KEY_CAPTURE_RESPONSE, ProtobufPacketSettings.DEFAULT_CAPTURE_RESPONSE);
    }

    private boolean isBlocked(int cgiId) {
        String raw = prefs == null
                ? ProtobufPacketSettings.DEFAULT_BLOCK_TYPES
                : prefs.getString(ProtobufPacketSettings.KEY_BLOCK_TYPES, ProtobufPacketSettings.DEFAULT_BLOCK_TYPES);
        if (TextUtils.isEmpty(raw)) return false;
        String[] parts = raw.split("[,，|\\s]+");
        for (String part : parts) {
            if (part.length() == 0) continue;
            try {
                if (Integer.parseInt(part.trim()) == cgiId) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private Object call(Object target, String method) {
        return KavaReflector.invoke(KavaReflector.findMethod(target != null ? target.getClass() : null, method), target);
    }

    private int safeInt(Object value, int defValue) {
        return value instanceof Number ? ((Number) value).intValue() : defValue;
    }

    private String safeString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void notify(ProtobufPacketRuntime.Callback callback, boolean success, String message) {
        if (callback != null) callback.onResult(success, message);
        if (!success) error(message);
    }

    private void error(String message) {
        HLog.e(TAG + " " + message);
    }

    private static final class PacketSnapshot {
        final String uri;
        final int cgiId;
        final Object request;
        final Object reqPbObj;
        final byte[] bytes;
        final long time;

        PacketSnapshot(String uri, int cgiId, Object request, Object reqPbObj, byte[] bytes) {
            this.uri = uri == null ? "" : uri;
            this.cgiId = cgiId;
            this.request = request;
            this.reqPbObj = reqPbObj;
            this.bytes = bytes;
            this.time = System.currentTimeMillis();
        }

        String key() {
            return cgiId + "|" + uri + "|" + time;
        }
    }

    private static final class NativeArgs {
        private static final String[][] STRING_SLOTS = {
                {"sendId", "sendid"},
                {"nativeUrl", "nativeurl"},
                {"headImg", "headimg"},
                {"nickName", "nickname"},
                {"sessionUserName", "sessionUser"},
                {"ver"},
                {"timingIdentifier"},
                {"left_button_continue", "leftButtonContinue"}
        };
        private static final String[] INT_PRIORITY = {
                "msgType",
                "msgtype",
                "channelId",
                "channelid"
        };

        private final LinkedHashMap<String, String> query = new LinkedHashMap<>();
        private final List<String> plainStrings = new ArrayList<>();

        static NativeArgs from(JSONObject payload) {
            NativeArgs args = new NativeArgs();
            args.collect(payload);
            return args;
        }

        boolean hasUsefulQuery() {
            return !query.isEmpty();
        }

        boolean hasStringValue() {
            for (String value : orderedStrings()) {
                if (!TextUtils.isEmpty(value)) return true;
            }
            return false;
        }

        List<String> orderedStrings() {
            List<String> out = new ArrayList<>();
            for (String[] keys : STRING_SLOTS) {
                out.add(firstValue(keys));
            }
            for (String value : query.values()) {
                if (!TextUtils.isEmpty(value) && !out.contains(value)) out.add(value);
            }
            for (String value : plainStrings) {
                if (!TextUtils.isEmpty(value) && !out.contains(value)) out.add(value);
            }
            return out;
        }

        String stringAt(int index) {
            List<String> values = orderedStrings();
            return index >= 0 && index < values.size() ? values.get(index) : "";
        }

        int intAt(int index) {
            List<Integer> values = orderedInts();
            return index >= 0 && index < values.size() ? values.get(index) : 0;
        }

        private List<Integer> orderedInts() {
            List<Integer> out = new ArrayList<>();
            for (String key : INT_PRIORITY) {
                Integer value = intValue(key);
                if (value != null) out.add(value);
            }
            for (String value : query.values()) {
                Integer parsed = parseInt(value);
                if (parsed != null && !out.contains(parsed)) out.add(parsed);
            }
            return out;
        }

        private String value(String key) {
            if (TextUtils.isEmpty(key)) return "";
            String direct = query.get(key);
            if (!TextUtils.isEmpty(direct)) return direct;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (key.equalsIgnoreCase(entry.getKey())) return entry.getValue();
            }
            return "";
        }

        private String firstValue(String... keys) {
            if (keys == null) return "";
            for (String key : keys) {
                String found = value(key);
                if (!TextUtils.isEmpty(found)) return found;
            }
            return "";
        }

        private Integer intValue(String key) {
            return parseInt(value(key));
        }

        private Integer parseInt(String value) {
            if (TextUtils.isEmpty(value)) return null;
            try {
                return Integer.parseInt(value.trim());
            } catch (Throwable ignored) {
                return null;
            }
        }

        private void collect(Object value) {
            if (value == null || value == JSONObject.NULL) return;
            if (value instanceof JSONObject) {
                JSONObject object = (JSONObject) value;
                Iterator<String> keys = object.keys();
                while (keys.hasNext()) {
                    collect(object.opt(keys.next()));
                }
                return;
            }
            if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < array.length(); i++) collect(array.opt(i));
                return;
            }
            if (value instanceof Number || value instanceof Boolean) return;
            String text = String.valueOf(value);
            if (TextUtils.isEmpty(text)) return;
            if (text.indexOf('=') >= 0 && text.indexOf('&') >= 0) {
                parseQuery(text);
            } else {
                plainStrings.add(text);
            }
        }

        private void parseQuery(String text) {
            String[] pairs = text.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf('=');
                if (idx <= 0) continue;
                String key = decode(pair.substring(0, idx));
                String value = decode(pair.substring(idx + 1));
                if (!TextUtils.isEmpty(key) && !query.containsKey(key)) {
                    query.put(key, value == null ? "" : value);
                }
            }
        }

        private String decode(String value) {
            if (value == null) return "";
            try {
                return URLDecoder.decode(value, "UTF-8");
            } catch (Throwable ignored) {
                return value;
            }
        }
    }
}
