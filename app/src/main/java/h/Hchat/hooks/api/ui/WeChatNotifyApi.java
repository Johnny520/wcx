package h.Hchat.hooks.api.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

/**
 * 微信内通知/跳转 API。
 */
public final class WeChatNotifyApi {
    public interface Logger {
        void log(String message);
    }

    private static final String CHANNEL_ID = "Hchat_wechat_api_notify_high";
    private static final String CHANNEL_NAME = "Hchat 通知";

    private final Context hostContext;
    private final Logger logger;

    public WeChatNotifyApi(Context hostContext, Logger logger) {
        this.hostContext = hostContext;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return hostContext != null;
    }

    public void showToast(final String text) {
        if (TextUtils.isEmpty(text) || hostContext == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(hostContext, text, Toast.LENGTH_SHORT).show();
            } catch (Throwable e) {
                log("Toast失败: " + e.getMessage());
            }
        });
    }

    public void sendNotice(String title, String text, String toastText, boolean system, boolean toast) {
        sendNotice(title, text, toastText, null, null, 0, system, toast);
    }

    public void sendNotice(String title,
                           String text,
                           String toastText,
                           String talker,
                           String idKey,
                           int idSalt,
                           boolean system,
                           boolean toast) {
        try {
            if (toast) showToast(toastText);
            if (!system || hostContext == null) return;

            NotificationManager nm = (NotificationManager)
                    hostContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH));
            }

            int icon = hostContext.getApplicationInfo().icon;
            if (icon == 0) icon = android.R.drawable.ic_dialog_info;
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(hostContext, CHANNEL_ID)
                    : new Notification.Builder(hostContext);
            builder.setSmallIcon(icon)
                    .setContentTitle(safe(title))
                    .setContentText(safe(text))
                    .setTicker(safe(text))
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL);

            PendingIntent intent = buildChatPendingIntent(talker, idKey, idSalt);
            if (intent != null) builder.setContentIntent(intent);
            android.graphics.Bitmap customAvatar =
                    h.Hchat.hooks.items.customfriendavatar.CustomFriendAvatarStore
                            .loadNotificationBitmap(hostContext, talker);
            if (customAvatar != null) builder.setLargeIcon(customAvatar);
            nm.notify(nextNotifyId(idKey, idSalt), builder.build());
        } catch (Throwable e) {
            log("通知失败: " + e.getMessage());
        }
    }

    public boolean openChat(String talker) {
        if (hostContext == null) return false;
        Intent[] intents = buildChatOpenIntents(talker);
        if (intents == null || intents.length == 0) return false;
        try {
            hostContext.startActivities(intents);
            return true;
        } catch (Throwable first) {
            try {
                hostContext.startActivity(intents[intents.length - 1]);
                return true;
            } catch (Throwable e) {
                log("打开聊天失败: " + e.getMessage());
                return false;
            }
        }
    }

    public Intent[] buildChatOpenIntents(String talker) {
        Intent home = null;
        Intent chat = null;
        try {
            home = new Intent();
            home.setComponent(new ComponentName(hostContext.getPackageName(),
                    "com.tencent.mm.ui.LauncherUI"));
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        } catch (Throwable ignored) {}
        if (home == null) {
            try {
                home = hostContext.getPackageManager()
                        .getLaunchIntentForPackage(hostContext.getPackageName());
                if (home != null) {
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                }
            } catch (Throwable ignored) {}
        }
        if (!TextUtils.isEmpty(talker)) {
            try {
                chat = new Intent();
                chat.setComponent(new ComponentName(hostContext.getPackageName(),
                        "com.tencent.mm.ui.chatting.ChattingUI"));
                chat.putExtra("Chat_User", talker);
                chat.putExtra("Chat_Mode", 1);
                chat.putExtra("finish_direct", true);
                chat.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            } catch (Throwable ignored) {}
        }
        if (home != null && chat != null) return new Intent[]{home, chat};
        if (chat != null) return new Intent[]{chat};
        if (home != null) return new Intent[]{home};
        return null;
    }

    private PendingIntent buildChatPendingIntent(String talker, String idKey, int idSalt) {
        Intent[] intents = buildChatOpenIntents(talker);
        if (intents == null || intents.length == 0) return null;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivities(
                hostContext,
                nextPendingIntentRequestCode(talker, idKey, idSalt),
                intents,
                flags);
    }

    private int nextNotifyId(String idKey, int idSalt) {
        long base = TextUtils.isEmpty(idKey) ? System.currentTimeMillis() : idKey.hashCode();
        long raw = 0x4D000000L | ((base & 0xffffL) << 4) | (idSalt & 0xfL);
        return (int) (raw & 0x7fffffffL);
    }

    private int nextPendingIntentRequestCode(String talker, String idKey, int idSalt) {
        long base = TextUtils.isEmpty(idKey)
                ? (TextUtils.isEmpty(talker) ? System.currentTimeMillis() : talker.hashCode())
                : idKey.hashCode();
        long raw = 0x4E000000L | ((base & 0xfffffL) << 1) | (idSalt == 0 ? 0 : 1);
        return (int) (raw & 0x7fffffffL);
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatNotifyApi] " + message);
    }
}
