package h.Hchat.hooks.items.payment.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import java.io.File;

import de.robv.android.xposed.XposedBridge;

/**
 * 红包通知层。
 * 只负责 Toast / 系统通知，不负责判断是否该通知，也不负责 Hook。
 */
public class RedPacketNotifier {
    private static final String DEFAULT_TAG = "[Hchat:RedPacketNotifier]";
    private static final String DEFAULT_CHANNEL_ID = "Hchat_redpacket_notify_manual_v2";
    private static final String DEFAULT_CHANNEL_NAME = "Hchat 红包提醒";
    private static final long MANUAL_SOUND_DEBOUNCE_MS = 1200L;
    private static final long MANUAL_SOUND_STOP_DELAY_MS = 3500L;

    private final Context hostContext;
    private final String tag;
    private final String channelId;
    private final String channelName;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile long lastManualSoundAt;

    public RedPacketNotifier(Context hostContext) {
        this(hostContext, DEFAULT_TAG, DEFAULT_CHANNEL_ID, DEFAULT_CHANNEL_NAME);
    }

    public RedPacketNotifier(Context hostContext, String tag, String channelId, String channelName) {
        this.hostContext = hostContext;
        this.tag = TextUtils.isEmpty(tag) ? DEFAULT_TAG : tag;
        this.channelId = TextUtils.isEmpty(channelId) ? DEFAULT_CHANNEL_ID : channelId;
        this.channelName = TextUtils.isEmpty(channelName) ? DEFAULT_CHANNEL_NAME : channelName;
    }

    public void showToast(final String text) {
        if (TextUtils.isEmpty(text)) return;
        mainHandler.post(() -> {
            try {
                Toast.makeText(hostContext, text, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });
    }

    public void sendNotice(String title, String text, String toastText, boolean system, boolean toast) {
        sendNotice(title, text, toastText, null, null, 0, system, toast);
    }

    public void sendNotice(String title, String text, String toastText, String talker,
                           String nativeUrl, int idSalt, boolean system, boolean toast) {
        sendNotice(title, text, toastText, talker, nativeUrl, idSalt, system, toast, true, true, "");
    }

    public void sendNotice(String title, String text, String toastText, String talker,
                           String nativeUrl, int idSalt, boolean system, boolean toast,
                           boolean sound, boolean vibrate, String soundUri) {
        try {
            if (toast) showToast(toastText);
            if (!system) return;

            NotificationManager nm = (NotificationManager)
                    hostContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) {
                h.Hchat.utils.HLog.e(tag + " 通知失败: 微信通知权限已关闭");
                return;
            }

            String channelId = buildChannelId(vibrate);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(
                        channelId,
                        channelName,
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.enableVibration(vibrate);
                channel.setVibrationPattern(vibrate ? new long[]{0, 180, 80, 180} : null);
                channel.setSound(null, null);
                nm.createNotificationChannel(channel);
                channel = nm.getNotificationChannel(channelId);
                if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                    h.Hchat.utils.HLog.e(tag + " 通知失败: " + channelName + "通知渠道已关闭");
                    return;
                }
            }

            int icon = hostContext.getApplicationInfo().icon;
            if (icon == 0) icon = android.R.drawable.ic_dialog_info;

            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(hostContext, channelId)
                    : new Notification.Builder(hostContext);

            PendingIntent contentIntent = buildContentIntent(talker, nativeUrl, idSalt);
            int defaults = 0;
            if (vibrate) defaults |= Notification.DEFAULT_VIBRATE;
            builder.setSmallIcon(icon)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setTicker(text)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(defaults);
            if (Build.VERSION.SDK_INT < 26) {
                if (sound) builder.setSound(resolveSoundUri(soundUri));
                else builder.setSound(null);
                if (vibrate) builder.setVibrate(new long[]{0, 180, 80, 180});
            }
            if (contentIntent != null) builder.setContentIntent(contentIntent);
            android.graphics.Bitmap customAvatar =
                    h.Hchat.hooks.items.customfriendavatar.CustomFriendAvatarStore
                            .loadNotificationBitmap(hostContext, talker);
            if (customAvatar != null) builder.setLargeIcon(customAvatar);

            nm.notify(nextNotifyId(nativeUrl, idSalt), builder.build());
            if (Build.VERSION.SDK_INT >= 26 && sound) {
                playManualNotificationSound(resolveSoundUri(soundUri));
            }
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(tag + " 通知失败: " + e.getMessage(), e);
        }
    }

    private String buildChannelId(boolean vibrate) {
        return channelId + "_v" + (vibrate ? "1" : "0");
    }

    private Uri resolveSoundUri(String soundUri) {
        try {
            if (TextUtils.isEmpty(soundUri)) {
                return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            String value = soundUri.trim();
            if (value.contains("://")) return Uri.parse(value);
            return Uri.fromFile(new File(value));
        } catch (Throwable ignored) {
            return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }
    }

    private void playManualNotificationSound(Uri uri) {
        if (uri == null) return;
        long now = System.currentTimeMillis();
        if (now - lastManualSoundAt < MANUAL_SOUND_DEBOUNCE_MS) return;
        lastManualSoundAt = now;
        mainHandler.post(() -> {
            Ringtone ringtone;
            try {
                ringtone = RingtoneManager.getRingtone(hostContext, uri);
            } catch (Throwable e) {
                h.Hchat.utils.HLog.e(tag + " 铃声加载失败: " + e.getMessage(), e);
                return;
            }
            if (ringtone == null) return;
            try {
                ringtone.setStreamType(android.media.AudioManager.STREAM_NOTIFICATION);
            } catch (Throwable ignored) {}
            try {
                ringtone.play();
                mainHandler.postDelayed(() -> stopRingtone(ringtone), MANUAL_SOUND_STOP_DELAY_MS);
            } catch (Throwable e) {
                h.Hchat.utils.HLog.e(tag + " 铃声播放失败: " + e.getMessage(), e);
            }
        });
    }

    private void stopRingtone(Ringtone ringtone) {
        try {
            if (ringtone.isPlaying()) ringtone.stop();
        } catch (Throwable ignored) {}
    }

    private PendingIntent buildContentIntent(String talker, String nativeUrl, int idSalt) {
        Intent[] intents = buildChatOpenIntents(talker);
        if (intents == null || intents.length == 0) return null;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivities(
                hostContext,
                nextPendingIntentRequestCode(talker, nativeUrl, idSalt),
                intents,
                flags);
    }

    private Intent[] buildChatOpenIntents(String talker) {
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

    private int nextNotifyId(String nativeUrl, int idSalt) {
        try {
            long base = TextUtils.isEmpty(nativeUrl) ? System.currentTimeMillis() : nativeUrl.hashCode();
            long salt = idSalt & 0x3ffL;
            long raw = 0x4B000000L | ((base & 0x3ffL) << 10) | ((salt & 0x3fL) << 20)
                    | (System.currentTimeMillis() & 0xfffffL);
            return (int) (raw & 0x7fffffffL);
        } catch (Throwable ignored) {
            return (int) (System.currentTimeMillis() & 0x7fffffffL);
        }
    }

    private int nextPendingIntentRequestCode(String talker, String nativeUrl, int idSalt) {
        try {
            long base = TextUtils.isEmpty(nativeUrl)
                    ? (TextUtils.isEmpty(talker) ? System.currentTimeMillis() : talker.hashCode())
                    : nativeUrl.hashCode();
            long raw = 0x4C000000L | ((base & 0xfffffL) << 1) | (idSalt == 0 ? 0 : 1);
            return (int) (raw & 0x7fffffffL);
        } catch (Throwable ignored) {
            return (int) (System.currentTimeMillis() & 0x7fffffffL);
        }
    }
}
