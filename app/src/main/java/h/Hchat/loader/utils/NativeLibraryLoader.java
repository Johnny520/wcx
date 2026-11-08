package h.Hchat.loader.utils;

import android.content.Context;
import android.os.Process;

import h.Hchat.utils.KavaReflector;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XposedBridge;

/**
 * Native 库加载器。
 * 负责从模块 APK 中提取并加载 Native so，避免模块入口混入底层加载细节。
 */
public class NativeLibraryLoader {
    private static final String TAG = "[Hchat:NativeLoader]";
    private static final Object EXTRACTION_LOCK = new Object();
    private static final NativeLoadCache LOADED_LIBRARIES = new NativeLoadCache();
    private static final String DEXKIT_SO = "libdexkit.so";
    private static final String DEXKIT_LIB = "dexkit";
    private static final String SILK_CODEC_SO = "libsilk_codec.so";
    private static final String SILK_CODEC_LIB = "silk_codec";
    private static final String CRASH_GUARD_SO = "libhchat_crash.so";
    private static final String CRASH_GUARD_LIB = "hchat_crash";
    private static final String TERMUX_SO = "libtermux.so";
    private static final String TERMUX_LIB_PATH_PROPERTY = "hchat.termux.lib.path";

    public void loadDexKit(Context ctx, ClassLoader moduleClassLoader) {
        loadLibrary(ctx, moduleClassLoader, DEXKIT_SO, DEXKIT_LIB, false);
    }

    public boolean loadSilkCodec(Context ctx, ClassLoader moduleClassLoader) {
        return loadLibrary(ctx, moduleClassLoader, SILK_CODEC_SO, SILK_CODEC_LIB, true);
    }

    public boolean loadCrashGuard(Context ctx, ClassLoader moduleClassLoader) {
        return loadLibrary(ctx, moduleClassLoader, CRASH_GUARD_SO, CRASH_GUARD_LIB, true);
    }

    public boolean loadTermux(Context ctx, ClassLoader moduleClassLoader) {
        try {
            String path = resolveTermuxSoPath(ctx, moduleClassLoader);
            if (path == null) {
                h.Hchat.utils.HLog.e(TAG + " loadTermux 找不到 " + TERMUX_SO);
                return false;
            }
            System.setProperty(TERMUX_LIB_PATH_PROPERTY, path);
            try {
                System.load(path);
            } catch (Throwable t) {
                h.Hchat.utils.HLog.e(TAG + " 预加载 " + TERMUX_SO + " 失败: " + t.getMessage());
            }
            return true;
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " loadTermux 失败: " + e.getMessage(), e);
            return false;
        }
    }

    private String resolveTermuxSoPath(Context ctx, ClassLoader moduleClassLoader) {
        String moduleApk = getModuleApkPath(moduleClassLoader);
        if (moduleApk != null) {
            File parent = new File(moduleApk).getParentFile();
            if (parent != null) {
                File[] archDirs = new File(parent, "lib").listFiles();
                if (archDirs != null) {
                    for (File archDir : archDirs) {
                        File so = new File(archDir, TERMUX_SO);
                        if (so.isFile() && so.length() > 0) return so.getAbsolutePath();
                    }
                }
            }
        }
        try {
            File dir = prepareTermuxNativeDir(ctx, moduleClassLoader);
            if (dir != null) {
                File so = new File(dir, TERMUX_SO);
                if (so.isFile() && so.length() > 0) return so.getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private File prepareTermuxNativeDir(Context ctx, ClassLoader moduleClassLoader) throws Exception {
        String moduleApk = getModuleApkPath(moduleClassLoader);
        if (moduleApk == null || moduleApk.length() == 0) return null;
        String abi = "arm64-v8a";
        File dir = new File(new File(ctx.getCacheDir(), "Hchat_termux"), abi);
        dir.mkdirs();
        File dest = new File(dir, TERMUX_SO);
        try (ZipFile apk = new ZipFile(moduleApk)) {
            ZipEntry entry = apk.getEntry("lib/" + abi + "/" + TERMUX_SO);
            if (entry == null) return null;
            boolean valid = dest.isFile() && dest.length() == entry.getSize() && dest.length() > 0;
            if (!valid) {
                extractLibrary(apk, entry, dir, TERMUX_SO, dest);
            }
        }
        dest.setReadable(true, false);
        dest.setExecutable(true, false);
        return dir;
    }

    /**
     * 从 LSPosed 模块 ClassLoader 中获取模块 APK 真实路径。
     */
    private String getModuleApkPath(ClassLoader moduleClassLoader) {
        try {
            String clStr = moduleClassLoader.toString();
            int idx = clStr.indexOf("module=");
            if (idx >= 0) {
                int start = idx + 7;
                int end = clStr.indexOf(",", start);
                if (end < 0) end = clStr.indexOf("]", start);
                if (end > start) {
                    String path = clStr.substring(start, end).trim();
                    if (new File(path).exists()) {
                        return path;
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> clz = moduleClassLoader.getClass();
            while (clz != null) {
                for (java.lang.reflect.Field f : KavaReflector.declaredFields(clz)) {
                    Object val = KavaReflector.readField(f, moduleClassLoader);
                    if (val instanceof String) {
                        String s = (String) val;
                        if (s.endsWith(".apk") && s.contains("h.Hchat") && new File(s).exists()) {
                            return s;
                        }
                    }
                }
                clz = clz.getSuperclass();
            }
        } catch (Throwable ignored) {}

        try {
            File dataApp = new File("/data/app");
            File[] dirs = dataApp.listFiles();
            if (dirs != null) {
                for (File dir : dirs) {
                    if (dir != null && dir.isDirectory() && dir.getName().contains("h.Hchat")) {
                        File apk = new File(dir, "base.apk");
                        if (apk.exists()) return apk.getAbsolutePath();
                    }
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private boolean loadLibrary(Context ctx, ClassLoader moduleClassLoader, String soName,
                                String libName, boolean optional) {
        if (!Process.is64Bit()) {
            String message = "当前版本仅支持 ARM64 微信进程，无法加载 " + soName;
            h.Hchat.utils.HLog.e(TAG + " " + message);
            if (optional) return false;
            throw new IllegalStateException(message);
        }
        return LOADED_LIBRARIES.load(moduleClassLoader, soName,
                () -> loadLibraryUncached(ctx, moduleClassLoader, soName, libName, optional));
    }

    private boolean loadLibraryUncached(Context ctx, ClassLoader moduleClassLoader, String soName,
                                        String libName, boolean optional) {
        String moduleApk = getModuleApkPath(moduleClassLoader);
        String abi = "arm64-v8a";
        String entryPath = "lib/" + abi + "/" + soName;
        File cacheDir = new File(new File(ctx.getCacheDir(), "Hchat_native"), abi);

        try {
            if (moduleApk == null || moduleApk.length() == 0) {
                File cached = findCachedLibrary(cacheDir, soName);
                if (cached != null) {
                    System.load(cached.getAbsolutePath());
                    return true;
                }
                System.loadLibrary(libName);
                return true;
            }

            try (ZipFile apk = new ZipFile(moduleApk)) {
                java.util.zip.ZipEntry entry = apk.getEntry(entryPath);
                if (entry == null) {
                    System.loadLibrary(libName);
                    return true;
                }

                cacheDir.mkdirs();
                String cacheName = versionedLibraryName(soName, entry.getCrc());
                File destSo = new File(cacheDir, cacheName);
                synchronized (EXTRACTION_LOCK) {
                    File lockFile = new File(cacheDir, cacheName + ".lock");
                    try (RandomAccessFile lockAccess = new RandomAccessFile(lockFile, "rw");
                         java.nio.channels.FileLock ignored = lockAccess.getChannel().lock()) {
                        boolean reusedCache = isValidCachedLibrary(destSo, entry.getSize());
                        if (!reusedCache) {
                            extractLibrary(apk, entry, cacheDir, cacheName, destSo);
                        }
                        try {
                            System.load(destSo.getAbsolutePath());
                        } catch (UnsatisfiedLinkError firstLoadError) {
                            if (!reusedCache) throw firstLoadError;
                            if (!destSo.delete()) {
                                throw new IllegalStateException("无法删除损坏的 Native 缓存: " + destSo, firstLoadError);
                            }
                            extractLibrary(apk, entry, cacheDir, cacheName, destSo);
                            try {
                                System.load(destSo.getAbsolutePath());
                            } catch (Throwable retryError) {
                                retryError.addSuppressed(firstLoadError);
                                throw retryError;
                            }
                        }
                        deleteOldVersions(cacheDir, soName, destSo);
                        return true;
                    }
                }
            }
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " " + soName + " 加载失败: " + e.getMessage(), e);
            try {
                System.loadLibrary(libName);
                return true;
            } catch (Throwable e2) {
                h.Hchat.utils.HLog.e(TAG + " " + libName + " loadLibrary 也失败: " + e2.getMessage(), e2);
                if (optional) return false;
                throw new RuntimeException("无法加载 " + soName, e2);
            }
        }
    }

    private String versionedLibraryName(String soName, long crc) {
        int dot = soName.lastIndexOf('.');
        String suffix = "-" + Long.toHexString(crc);
        if (dot <= 0) return soName + suffix;
        return soName.substring(0, dot) + suffix + soName.substring(dot);
    }

    private File findCachedLibrary(File cacheDir, String soName) {
        File exact = new File(cacheDir, soName);
        File newest = exact.isFile() && exact.length() > 0 ? exact : null;
        String prefix = soName.endsWith(".so") ? soName.substring(0, soName.length() - 3) + "-" : soName + "-";
        File[] files = cacheDir.listFiles();
        if (files == null) return newest;
        for (File file : files) {
            if (file == null || !file.isFile() || file.length() <= 0) continue;
            String name = file.getName();
            if (!name.startsWith(prefix) || !name.endsWith(".so")) continue;
            if (newest == null || file.lastModified() > newest.lastModified()) newest = file;
        }
        return newest;
    }

    private boolean isValidCachedLibrary(File file, long expectedSize) {
        return file.isFile() && file.length() > 0 && (expectedSize < 0 || file.length() == expectedSize);
    }

    private void extractLibrary(ZipFile apk, java.util.zip.ZipEntry entry, File cacheDir,
                                String cacheName, File destination) throws Exception {
        File temp = new File(
                cacheDir,
                cacheName + ".tmp-" + Process.myPid() + "-" + Thread.currentThread().getId()
        );
        try {
            CRC32 crc = new CRC32();
            try (InputStream input = apk.getInputStream(entry);
                 FileOutputStream output = new FileOutputStream(temp, false)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) > 0) {
                    output.write(buffer, 0, length);
                    crc.update(buffer, 0, length);
                }
                output.getFD().sync();
            }
            if (entry.getCrc() >= 0 && crc.getValue() != entry.getCrc()) {
                throw new IllegalStateException("Native 缓存 CRC 校验失败: " + cacheName);
            }
            try {
                Files.move(
                        temp.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            temp.delete();
        }
    }

    private void deleteOldVersions(File cacheDir, String soName, File current) {
        String prefix = soName.endsWith(".so") ? soName.substring(0, soName.length() - 3) + "-" : soName + "-";
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file == null || file.equals(current)) continue;
            String name = file.getName();
            if (name.equals(soName) || (name.startsWith(prefix) && name.endsWith(".so"))) {
                file.delete();
            }
        }
    }
}
