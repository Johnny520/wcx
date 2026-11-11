package h.Hchat.hooks.api.sns;

import java.lang.reflect.Method;

/** Builds arguments for the verified legacy and five-String SNS video helpers. */
public final class SnsVideoRequestArguments {
    private SnsVideoRequestArguments() {}

    public static boolean isCompatibleMethod(Class<?> helperClass, Method method) {
        if (helperClass == null || method == null
                || method.getDeclaringClass() != helperClass
                || method.getReturnType() != boolean.class) return false;
        Class<?>[] types = method.getParameterTypes();
        if (types.length != 4 && types.length != 5) return false;
        for (Class<?> type : types) if (type != String.class) return false;
        return true;
    }

    public static Object[] create(Method method, String path, String thumbPath,
                                  String description, String fileMd5) {
        if (method == null || !isCompatibleMethod(method.getDeclaringClass(), method)) return null;
        Object[] args = new Object[]{path, thumbPath, description, fileMd5};
        if (method.getParameterTypes().length == 5) {
            // The ordinary WeChat SightWidget.commitDone caller supplies an empty fifth String.
            return new Object[]{path, thumbPath, description, fileMd5, ""};
        }
        return args;
    }
}
