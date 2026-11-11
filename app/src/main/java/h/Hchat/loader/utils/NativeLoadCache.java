package h.Hchat.loader.utils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/** 仅复用同一模块加载器内已经成功加载的 SO，失败仍可重试。 */
final class NativeLoadCache {
    private final Map<ClassLoader, Set<String>> loaded = new WeakHashMap<>();

    synchronized boolean load(ClassLoader loader, String library, BooleanSupplier action) {
        Set<String> libraries = loaded.get(loader);
        if (libraries != null && libraries.contains(library)) return true;
        if (!action.getAsBoolean()) return false;
        if (libraries == null) {
            libraries = new HashSet<>();
            loaded.put(loader, libraries);
        }
        libraries.add(library);
        return true;
    }
}
