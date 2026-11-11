package h.Hchat.hooks.api.message;

import java.lang.reflect.Constructor;

/** Builds only constructor shapes whose argument semantics were verified from WeChat callers. */
public final class SendTextRequestArguments {
    private SendTextRequestArguments() {}

    public static boolean isSupported(Constructor<?> constructor) {
        if (constructor == null) return false;
        Class<?>[] types = constructor.getParameterTypes();
        if (types.length != 5 && types.length != 6) return false;
        if (types[0] != String.class || types[1] != String.class
                || types[2] != int.class || types[3] != int.class) return false;
        if (types[4] != Object.class && types[4] != long.class) return false;
        return types.length == 5 || types[5] == String.class;
    }

    public static Object[] create(Constructor<?> constructor,
                                  String talker,
                                  String content,
                                  int messageType,
                                  int sendFlag,
                                  Object sourceArgs) {
        if (!isSupported(constructor)) return null;
        Class<?>[] types = constructor.getParameterTypes();
        if (types[4] == Object.class) {
            return types.length == 6
                    ? new Object[]{talker, content, messageType, sendFlag, sourceArgs, null}
                    : new Object[]{talker, content, messageType, sendFlag, sourceArgs};
        }
        if (sourceArgs != null) return null;
        return types.length == 6
                ? new Object[]{talker, content, messageType, sendFlag, 0L, null}
                : new Object[]{talker, content, messageType, sendFlag, 0L};
    }
}
