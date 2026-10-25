package h.Hchat.utils;

import com.Johnny.wcx.utils.WeLogger;

import de.robv.android.xposed.XposedBridge;

/**
 * LSPosed treats Throwable logs as error logs. Use this only for real failures.
 *
 * 同时镜像到 WCX 运行日志（WeLogger），否则 Hchat 侧日志只出现在 LSPosed 日志里，
 * 用户在模块「日志」页看不到，无法定位脚本运行时问题。
 */
public final class HLog {
    private HLog() {
    }

    private static final String TAG = "WCX";

    public static void e(String message) {
        XposedBridge.log(new RuntimeException(message));
        WeLogger.INSTANCE.e(TAG, message);
    }

    public static void e(String message, Throwable throwable) {
        if (throwable == null) {
            e(message);
            return;
        }
        XposedBridge.log(new RuntimeException(message, throwable));
        WeLogger.INSTANCE.e(TAG, message, throwable);
    }

    /**
     * 诊断流水：只写 WCX 运行日志（I 级，会落盘，可在「日志」页查看 / 导出），
     * 不进 LSPosed 日志、不构造异常对象，因此可以放心用于定位过程记录。
     */
    public static void i(String message) {
        WeLogger.INSTANCE.i(TAG, message);
    }

    public static void i(String message, Throwable throwable) {
        WeLogger.INSTANCE.i(TAG, message, throwable);
    }

    /** 需要提醒但不属于失败的情形（例如「已回落到兜底路径」）。 */
    public static void w(String message) {
        WeLogger.INSTANCE.w(TAG, message);
    }
}
