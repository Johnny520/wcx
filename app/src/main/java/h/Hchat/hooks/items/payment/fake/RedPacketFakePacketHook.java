package h.Hchat.hooks.items.payment.fake;

import android.text.TextUtils;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.hooks.items.payment.core.RedPacketSettings;
import h.Hchat.utils.KavaReflector;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 伪造/分裂红包发包与响应修正。
 */
public class RedPacketFakePacketHook {
    public interface Logger {
        void log(String message);
    }

    private final ClassLoader classLoader;
    private final DexFinder dexFinder;
    private final RedPacketSettings settings;
    private final Logger logger;
    private final ConcurrentHashMap<String, Long> recentTamperMap = new ConcurrentHashMap<>();
    private boolean dispatcherHooked;
    private boolean responseHooked;

    public RedPacketFakePacketHook(ClassLoader classLoader, DexFinder dexFinder,
                                   RedPacketSettings settings, Logger logger) {
        this.classLoader = classLoader;
        this.dexFinder = dexFinder;
        this.settings = settings;
        this.logger = logger;
    }

    public void hook() {
        hookPacketDispatcher();
        hookFakePacketResponseFix();
    }

    private void hookPacketDispatcher() {
        if (dispatcherHooked) return;
        try {
            List<Class<?>> sceneBaseClasses = new ArrayList<>();
            addKnownClass(sceneBaseClasses, "com.tencent.mm.modelbase.m1");
            addKnownClass(sceneBaseClasses, "com.tencent.mm.modelbase.k1");
            addKnownClass(sceneBaseClasses, "com.tencent.mm.modelbase.l1");
            addKnownClass(sceneBaseClasses, "com.tencent.mm.modelbase.n1");
            if (sceneBaseClasses.isEmpty()) sceneBaseClasses.addAll(dexFinder.packetBaseClasses);
            collectSceneBaseFromQueue(sceneBaseClasses);

            if (sceneBaseClasses.isEmpty()) {
                log("发包请求Hook失败: 未找到NetSceneBase");
                return;
            }

            int hooked = 0;
            Set<String> hookedDesc = new HashSet<>();
            for (Class<?> sceneBaseClass : sceneBaseClasses) {
                for (Method method : KavaReflector.declaredMethods(sceneBaseClass)) {
                    if (!"dispatch".equals(method.getName())) continue;
                    if (method.getParameterTypes().length != 3) continue;
                    String desc = sceneBaseClass.getName() + "#" + method;
                    if (!hookedDesc.add(desc)) continue;
                    HookRegistry.get().hook(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            handlePacketTamperBeforeDispatch(param);
                        }
                    });
                    hooked++;
                }
            }
            dispatcherHooked = hooked > 0;
            log("发包请求Hook完成, count=" + hooked);
        } catch (Throwable e) {
            log("ERROR 发包请求Hook失败: " + e.getMessage());
        }
    }

    private void addKnownClass(List<Class<?>> out, String name) {
        try {
            Class<?> clazz = KavaReflector.loadClass(name, classLoader);
            if (clazz != null && !out.contains(clazz)) out.add(clazz);
        } catch (Throwable ignored) {}
    }

    private void collectSceneBaseFromQueue(List<Class<?>> out) {
        try {
            for (Class<?> queueClass : dexFinder.packetQueueClasses) {
                for (Method method : KavaReflector.declaredMethods(queueClass)) {
                    Class<?>[] params = method.getParameterTypes();
                    if (params == null) continue;
                    for (Class<?> param : params) {
                        try {
                            for (Method candidate : KavaReflector.declaredMethods(param)) {
                                if ("dispatch".equals(candidate.getName())
                                        && candidate.getParameterTypes().length == 3
                                        && !out.contains(param)) {
                                    out.add(param);
                                    break;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private void handlePacketTamperBeforeDispatch(XC_MethodHook.MethodHookParam param) {
        try {
            if (!settings.getBoolean(RedPacketSettings.KEY_FAKE_PACKET_ENABLE, false)) return;
            if (param == null || param.args == null || param.args.length < 2) return;
            Object reqResp = param.args[1];
            if (reqResp == null) return;
            int cgiId = ((Number) KavaReflector.invoke(KavaReflector.findMethod(reqResp.getClass(), "getType"), reqResp)).intValue();
            String uri = String.valueOf(KavaReflector.invoke(KavaReflector.findMethod(reqResp.getClass(), "getUri"), reqResp));
            boolean isSendRedPacketReq = cgiId == 1575 || (uri != null && uri.contains("requestwxhb"));
            if (!isSendRedPacketReq) return;

            Object reqWrapper = KavaReflector.invoke(KavaReflector.findMethod(reqResp.getClass(), "getReqObj"), reqResp);
            Object reqPbObj = getReqPbObject(reqWrapper);
            if (reqPbObj == null) return;
            byte[] reqBytes = asByteArray(KavaReflector.invoke(KavaReflector.findMethod(reqPbObj.getClass(), "toByteArray"), reqPbObj));
            if (reqBytes == null || reqBytes.length == 0) return;

            String key = cgiId + "|" + uri + "|" + reqPbObj.getClass().getName()
                    + "|" + Arrays.hashCode(reqBytes);
            if (shouldSkipRecentTamper(key)) return;

            byte[] tampered = tamperSendRedPacketRequest(reqBytes);
            if (tampered == null) return;
            KavaReflector.invoke(KavaReflector.findMethod(reqPbObj.getClass(), "parseFrom", byte[].class), reqPbObj, tampered);
            log("发包请求已篡改: uri=" + uri + ", cgi=" + cgiId
                    + ", len=" + reqBytes.length + "->" + tampered.length);
        } catch (Throwable e) {
            log("ERROR 发包请求Hook处理失败: " + e.getMessage());
        }
    }

    private Object getReqPbObject(Object reqWrapper) {
        if (reqWrapper == null) return null;
        try {
            Object pb = KavaReflector.readField(reqWrapper, "a");
            if (pb != null) return pb;
        } catch (Throwable ignored) {}
        try {
            for (Field field : KavaReflector.declaredFields(reqWrapper.getClass())) {
                try {
                    Object value = KavaReflector.readField(field, reqWrapper);
                    if (value == null) continue;
                    if (KavaReflector.findMethod(value.getClass(), "toByteArray") == null) continue;
                    if (KavaReflector.findMethod(value.getClass(), "parseFrom", byte[].class) == null) continue;
                    return value;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean shouldSkipRecentTamper(String key) {
        if (TextUtils.isEmpty(key)) return false;
        long now = System.currentTimeMillis();
        Long last = recentTamperMap.put(key, now);
        if (recentTamperMap.size() > 20) {
            try {
                String first = recentTamperMap.keySet().iterator().next();
                recentTamperMap.remove(first);
            } catch (Throwable ignored) {}
        }
        return last != null && now - last < 500L;
    }

    private byte[] tamperSendRedPacketRequest(byte[] reqBytes) {
        try {
            if (reqBytes == null || reqBytes.length == 0) return null;
            byte[] prefix = new byte[0];
            byte[] body = reqBytes;
            if (hasPacketPrefix(reqBytes)) {
                prefix = Arrays.copyOfRange(reqBytes, 0, 4);
                body = Arrays.copyOfRange(reqBytes, 4, reqBytes.length);
            }
            byte[] nextBody = transformPbPacketBytes(body);
            if (Arrays.equals(body, nextBody)) return null;
            byte[] out = concatBytes(prefix, nextBody);
            return Arrays.equals(reqBytes, out) ? null : out;
        } catch (Throwable e) {
            log("ERROR 发包PB篡改失败: " + e.getMessage());
            return null;
        }
    }

    private byte[] transformPbPacketBytes(byte[] body) {
        try {
            if (body == null || body.length == 0) return body;
            PbTransformResult result = transformPbMessageStreaming(body, 0, body.length, 0);
            if (result == null) return body;
            return result.changed > 0 ? result.output : body;
        } catch (Throwable ignored) {
            return body;
        }
    }

    private PbTransformResult transformPbMessageStreaming(byte[] data, int offset, int limit, int depth) {
        if (data == null || offset < 0 || limit < offset || limit > data.length || depth > 12) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int changed = 0;
        try {
            int pos = offset;
            while (pos < limit) {
                long[] tagRead = readPbVarint(data, pos, limit);
                long tag = tagRead[0];
                pos = (int) tagRead[1];
                if (tag == 0L) return null;
                int fieldNumber = (int) (tag >>> 3);
                int wireType = (int) (tag & 7L);
                if (fieldNumber <= 0 || wireType == 3 || wireType == 4 || wireType > 5) return null;
                writePbVarint(out, tag);
                if (wireType == 0) {
                    long[] value = readPbVarint(data, pos, limit);
                    writePbVarint(out, value[0]);
                    pos = (int) value[1];
                } else if (wireType == 1) {
                    if (pos + 8 > limit) return null;
                    out.write(data, pos, 8);
                    pos += 8;
                } else if (wireType == 2) {
                    long[] lenRead = readPbVarint(data, pos, limit);
                    int len = (int) lenRead[0];
                    pos = (int) lenRead[1];
                    if (len < 0 || pos + len > limit) return null;
                    byte[] raw = Arrays.copyOfRange(data, pos, pos + len);
                    pos += len;

                    byte[] next = raw;
                    int fieldChanged = 0;
                    PbTransformResult nested = raw.length > 0
                            ? transformPbMessageStreaming(raw, 0, raw.length, depth + 1)
                            : null;
                    if (nested != null && nested.changed > 0) {
                        next = nested.output;
                        fieldChanged += nested.changed;
                    }
                    if (fieldChanged == 0) {
                        byte[] replaced = transformUsernameQueryBytes(raw);
                        if (!Arrays.equals(raw, replaced)) {
                            next = replaced;
                            fieldChanged++;
                        }
                    }
                    writePbVarint(out, next.length);
                    out.write(next, 0, next.length);
                    changed += fieldChanged;
                } else if (wireType == 5) {
                    if (pos + 4 > limit) return null;
                    out.write(data, pos, 4);
                    pos += 4;
                }
            }
            return new PbTransformResult(out.toByteArray(), changed);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private byte[] transformUsernameQueryBytes(byte[] raw) {
        if (raw == null || raw.length == 0) return raw;
        try {
            String query = new String(raw, StandardCharsets.UTF_8);
            int start = query.indexOf("username=");
            if (start < 0) return raw;
            int valueStart = start + "username=".length();
            int valueEnd = query.indexOf("&", valueStart);
            if (valueEnd < 0) valueEnd = query.length();
            String before = query.substring(0, valueStart);
            String value = query.substring(valueStart, valueEnd);
            String after = query.substring(valueEnd);
            String nextValue = RedPacketFakePacketCompat.duplicateAt(value);
            if (nextValue.equals(value)) return raw;
            RedPacketFakePacketCompat.rememberGroupFix(value, nextValue);
            return (before + nextValue + after).getBytes(StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return raw;
        }
    }

    private long[] readPbVarint(byte[] data, int pos, int limit) {
        long value = 0L;
        int shift = 0;
        while (pos < limit && shift < 64) {
            int b = data[pos++] & 0xff;
            value |= ((long) (b & 0x7f)) << shift;
            if ((b & 0x80) == 0) return new long[]{value, pos};
            shift += 7;
        }
        throw new IllegalArgumentException("bad varint");
    }

    private void writePbVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0L) {
            out.write((int) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    private boolean hasPacketPrefix(byte[] data) {
        return data != null && data.length >= 4 && (data[0] & 0xff) == 0;
    }

    private byte[] concatBytes(byte[] a, byte[] b) {
        if (a == null || a.length == 0) return b == null ? new byte[0] : b;
        if (b == null || b.length == 0) return a;
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private byte[] asByteArray(Object value) {
        if (value == null) return null;
        try {
            Class<?> clazz = value.getClass();
            if (!clazz.isArray()) return null;
            int len = java.lang.reflect.Array.getLength(value);
            byte[] out = new byte[len];
            for (int i = 0; i < len; i++) {
                Object item = java.lang.reflect.Array.get(value, i);
                if (item instanceof Number) out[i] = ((Number) item).byteValue();
                else return null;
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void hookFakePacketResponseFix() {
        if (responseHooked) return;
        try {
            List<Class<?>> classList = new ArrayList<>(dexFinder.fakePacketClasses);
            addKnownClass(classList, "com.tencent.mm.plugin.luckymoney.model.e6");
            if (classList.isEmpty()) {
                log("假红包响应修正Hook失败: 未找到PrepareLuckyMoney类");
                return;
            }
            int hooked = 0;
            for (Class<?> clazz : classList) {
                for (Method method : KavaReflector.declaredMethods(clazz)) {
                    if (!"onGYNetEnd".equals(method.getName())) continue;
                    if (method.getParameterTypes().length != 3) continue;
                    HookRegistry.get().hook(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            fixFakePacketResponse(param.thisObject);
                        }
                    });
                    hooked++;
                }
            }
            responseHooked = hooked > 0;
            log("假红包响应修正Hook完成, count=" + hooked);
        } catch (Throwable e) {
            log("ERROR 假红包响应修正Hook失败: " + e.getMessage());
        }
    }

    private void fixFakePacketResponse(Object scene) {
        if (!settings.getBoolean(RedPacketSettings.KEY_FAKE_PACKET_ENABLE, false)) return;
        if (!RedPacketFakePacketCompat.hasGroupFix() || scene == null) return;
        try {
            Object xmlObj = null;
            try {
                xmlObj = KavaReflector.readField(scene, "m");
            } catch (Throwable ignored) {}
            if (xmlObj != null) {
                String xml = String.valueOf(xmlObj);
                String fixed = RedPacketFakePacketCompat.restoreGroupIds(xml);
                if (!fixed.equals(xml)) {
                    KavaReflector.writeField(scene, "m", fixed);
                    log("假红包群ID已修正，避免分裂群");
                }
                return;
            }
            for (Field field : KavaReflector.declaredFields(scene.getClass())) {
                try {
                    if (field.getType() != String.class) continue;
                    Object value = KavaReflector.readField(field, scene);
                    if (value == null || !String.valueOf(value).contains("<wcpayinfo>")) continue;
                    String fixed = RedPacketFakePacketCompat.restoreGroupIds(String.valueOf(value));
                    if (!fixed.equals(String.valueOf(value))) {
                        KavaReflector.writeField(field, scene, fixed);
                        log("假红包群ID已修正，避免分裂群");
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            log("ERROR 假红包响应修正失败: " + e.getMessage());
        }
    }

    private void log(String message) {
        if (logger != null) logger.log(message);
    }

    private static final class PbTransformResult {
        final byte[] output;
        final int changed;

        PbTransformResult(byte[] output, int changed) {
            this.output = output;
            this.changed = changed;
        }
    }
}
