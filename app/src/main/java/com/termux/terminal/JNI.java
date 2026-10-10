package com.termux.terminal;

// Termux PTY 的 JNI 入口：方法与 libtermux.so 的符号严格对应，勿改名/改签名
final class JNI {

    static final String LIB_PATH_PROPERTY = "hchat.termux.lib.path";

    static {
        boolean loaded = false;
        try {
            String path = System.getProperty(LIB_PATH_PROPERTY);
            if (path != null && path.length() > 0) {
                System.load(path);
                loaded = true;
            }
        } catch (Throwable ignored) {
        }
        if (!loaded) {
            System.loadLibrary("termux");
        }
    }

    JNI() {
    }

    public static native int createSubprocess(String cmd, String cwd, String[] args,
                                              String[] env, int[] pid, int rows, int cols);

    public static native void setPtyWindowSize(int fd, int rows, int cols);

    public static native int waitFor(int pid);

    public static native void close(int fd);
}
