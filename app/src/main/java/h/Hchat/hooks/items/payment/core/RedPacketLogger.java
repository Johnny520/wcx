package h.Hchat.hooks.items.payment.core;

import h.Hchat.utils.HLog;

public final class RedPacketLogger {
    private static final String TAG = "[Hchat:RedPacket]";

    private RedPacketLogger() {
    }

    public static void log(Object message) {
        if (message == null) return;
        if (message instanceof Throwable) {
            error("异常", (Throwable) message);
            return;
        }
        String text = String.valueOf(message);
        if (text.startsWith("ERROR")
                || text.contains("失败")
                || text.contains("未找到")
                || text.contains("不可用")
                || text.contains("无合适方法")
                || text.contains("异常")) {
            HLog.e(TAG + " " + text);
        }
    }

    public static void error(String message, Throwable error) {
        HLog.e(TAG + " " + message, error);
    }
}
