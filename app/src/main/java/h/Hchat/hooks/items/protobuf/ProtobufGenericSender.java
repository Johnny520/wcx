package h.Hchat.hooks.items.protobuf;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.utils.HLog;
import h.Hchat.utils.KavaReflector;

final class ProtobufGenericSender {
    private static final String TAG = "[Hchat:Protobuf]";
    private final DexFinder dexFinder;
    private final ClassLoader classLoader;

    ProtobufGenericSender(DexFinder dexFinder, ClassLoader classLoader) {
        this.dexFinder = dexFinder;
        this.classLoader = classLoader;
    }

    boolean send(String uri, int cgiId, int funcId, int routeId, byte[] body,
                 ProtobufPacketRuntime.Callback callback) {
        if (!isReady()) return false;
        try {
            Object req = createRequest(cgiId, body);
            Object resp = KavaReflector.newInstance(KavaReflector.findConstructor(dexFinder.protobufGenericRespClass));
            Object builder = KavaReflector.newInstance(KavaReflector.findConstructor(dexFinder.protobufConfigBuilderClass));
            if (req == null || resp == null || builder == null) {
                notify(callback, false, "通用发包失败: 对象创建失败");
                return true;
            }
            writeField(builder, "a", req);
            writeField(builder, "b", resp);
            writeField(builder, "c", uri);
            writeField(builder, "d", cgiId);
            writeField(builder, "e", funcId);
            writeField(builder, "f", routeId);
            writeField(builder, "l", 1);
            writeField(builder, "n", body);

            Object reqResp = buildReqResp(builder);
            if (reqResp == null) {
                notify(callback, false, "通用发包失败: ReqResp构造失败");
                return true;
            }
            return dispatch(cgiId, uri, reqResp, callback);
        } catch (Throwable e) {
            notify(callback, false, "通用发包失败: " + e.getMessage());
            return true;
        }
    }

    boolean supportsSpecificRequest(int cgiId) {
        return cgiId == 522 || cgiId == 681;
    }

    private boolean dispatch(int cgiId, String uri, Object reqResp,
                             ProtobufPacketRuntime.Callback callback) {
        Object cb = Proxy.newProxyInstance(
                classLoader,
                new Class[]{dexFinder.protobufCallbackClass},
                (proxy, method, args) -> {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == (args != null && args.length > 0 ? args[0] : null);
                    if ("toString".equals(method.getName())) return "HchatProtobufCallback";
                    handleCallback(cgiId, uri, method, args, callback);
                    return 0;
                });
        Object scene;
        try {
            scene = KavaReflector.invokeOrThrow(dexFinder.protobufStaticDispatchMethod, null, reqResp, cb, false);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            notify(callback, false, "通用发包失败: Dispatch异常 " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            return true;
        } catch (Throwable e) {
            notify(callback, false, "通用发包失败: Dispatch异常 " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return true;
        }
        if (scene != null) ProtobufPacketRuntime.markGenericScene(scene);
        notify(callback, true, "通用发包已发送: " + uri + " type=" + cgiId);
        return true;
    }

    String readinessReport() {
        if (dexFinder == null) return "DexFinder为空";
        StringBuilder sb = new StringBuilder();
        appendMissing(sb, "RawReq", dexFinder.protobufRawReqClass);
        appendMissing(sb, "GenericResp", dexFinder.protobufGenericRespClass);
        appendMissing(sb, "ConfigBuilder", dexFinder.protobufConfigBuilderClass);
        appendMissing(sb, "ReqResp", dexFinder.protobufReqRespClass);
        appendMissing(sb, "Callback", dexFinder.protobufCallbackClass);
        if (dexFinder.protobufCallbackClass != null && !dexFinder.protobufCallbackClass.isInterface()) {
            appendText(sb, "Callback非接口=" + dexFinder.protobufCallbackClass.getName());
        }
        appendMissing(sb, "Dispatch", dexFinder.protobufStaticDispatchMethod);
        return sb.length() == 0 ? "已就绪" : sb.toString();
    }

    private boolean isReady() {
        return dexFinder != null
                && dexFinder.protobufRawReqClass != null
                && dexFinder.protobufGenericRespClass != null
                && dexFinder.protobufConfigBuilderClass != null
                && dexFinder.protobufReqRespClass != null
                && dexFinder.protobufCallbackClass != null
                && dexFinder.protobufCallbackClass.isInterface()
                && dexFinder.protobufStaticDispatchMethod != null;
    }

    private Object createRequest(int cgiId, byte[] body) {
        Object req = null;
        if (cgiId == 522) req = createSpecificRequest(dexFinder.protobufNewSendMsgReqClass, body);
        if (req == null && cgiId == 681) req = createSpecificRequest(dexFinder.protobufOplogReqClass, body);
        if (req != null) return req;
        return KavaReflector.newInstance(KavaReflector.findConstructor(dexFinder.protobufRawReqClass, byte[].class), body);
    }

    private Object createSpecificRequest(Class<?> clazz, byte[] body) {
        if (clazz == null) return null;
        Object req = KavaReflector.newInstance(KavaReflector.findConstructor(clazz));
        if (req == null) return null;
        Object parsed = KavaReflector.invoke(KavaReflector.findMethodRecursive(req.getClass(), "parseFrom", byte[].class), req, body);
        return parsed != null ? parsed : req;
    }

    private Object buildReqResp(Object builder) {
        try {
            Object value = KavaReflector.invoke(KavaReflector.findMethod(builder.getClass(), "a"), builder);
            if (value != null
                    && (dexFinder.protobufReqRespClass == null
                    || dexFinder.protobufReqRespClass.isAssignableFrom(value.getClass()))) {
                return value;
            }
        } catch (Throwable ignored) {
        }
        for (Method method : KavaReflector.declaredMethods(builder.getClass())) {
            try {
                if (method.getParameterTypes().length != 0) continue;
                if (method.getReturnType() == Void.TYPE || method.getReturnType() == void.class) continue;
                if (dexFinder.protobufReqRespClass != null
                        && !dexFinder.protobufReqRespClass.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                return KavaReflector.invoke(method, builder);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void handleCallback(int cgiId, String uri, Method method, Object[] args, ProtobufPacketRuntime.Callback callback) {
        if (method == null || args == null) return;
        if (!"callback".equals(method.getName())) return;
        try {
            int errType = args.length > 0 && args[0] instanceof Number ? ((Number) args[0]).intValue() : -1;
            int errCode = args.length > 1 && args[1] instanceof Number ? ((Number) args[1]).intValue() : -1;
            String errMsg = args.length > 2 ? String.valueOf(args[2]) : "";
            Object reqResp = args.length > 3 ? args[3] : null;
            byte[] bytes = extractRespBytes(reqResp);
            String json = bytes != null ? ProtoJsonCodec.toJson(bytes).toString() : "{}";
            if (errType == 0 && errCode == 0) {
                notify(callback, true, "响应: " + json);
                return;
            }
            String message = "响应失败: type=" + errType + " code=" + errCode + " msg=" + errMsg;
            if (cgiId == 681) {
                HLog.e(TAG + " Oplog回包非成功但请求已发送: uri=" + uri + " " + message);
                return;
            }
            notify(callback, false, message);
        } catch (Throwable ignored) {
        }
    }

    private byte[] extractRespBytes(Object reqResp) {
        if (reqResp == null) return null;
        try {
            Object wrapper = KavaReflector.readField(reqResp, "b");
            Object pb = KavaReflector.readField(wrapper, "a");
            Object initial = null;
            try {
                initial = KavaReflector.invoke(KavaReflector.findMethod(pb.getClass(), "initialProtobufBytes"), pb);
            } catch (Throwable ignored) {
            }
            if (initial instanceof byte[]) return (byte[]) initial;
            Object bytes = KavaReflector.invoke(KavaReflector.findMethod(pb.getClass(), "toByteArray"), pb);
            return bytes instanceof byte[] ? (byte[]) bytes : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void writeField(Object target, String name, Object value) throws Exception {
        Field field = KavaReflector.findFieldRecursive(target.getClass(), name);
        if (field == null) throw new NoSuchFieldException(name);
        if (!KavaReflector.writeField(field, target, value)) throw new IllegalAccessException(name);
    }

    private void notify(ProtobufPacketRuntime.Callback callback, boolean success, String message) {
        if (!success) HLog.e(TAG + " " + message);
        if (callback == null) return;
        new Handler(Looper.getMainLooper()).post(() -> callback.onResult(success, message));
    }

    private void appendMissing(StringBuilder sb, String name, Object value) {
        if (value != null) return;
        appendText(sb, name);
    }

    private void appendText(StringBuilder sb, String text) {
        if (sb.length() > 0) sb.append(',');
        sb.append(text);
    }
}
