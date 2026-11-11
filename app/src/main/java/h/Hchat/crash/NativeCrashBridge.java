package h.Hchat.crash;

final class NativeCrashBridge {
    private NativeCrashBridge() {
    }

    static boolean install(String reportPath) {
        return reportPath != null && !reportPath.isEmpty() && nativeInstall(reportPath);
    }

    private static native boolean nativeInstall(String reportPath);
}
