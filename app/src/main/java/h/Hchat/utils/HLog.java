package h.Hchat.utils;

import de.robv.android.xposed.XposedBridge;

/**
 * LSPosed treats Throwable logs as error logs. Use this only for real failures.
 */
public final class HLog {
    private HLog() {
    }

    public static void e(String message) {
        XposedBridge.log(new RuntimeException(message));
    }

    public static void e(String message, Throwable throwable) {
        if (throwable == null) {
            e(message);
            return;
        }
        XposedBridge.log(new RuntimeException(message, throwable));
    }
}
