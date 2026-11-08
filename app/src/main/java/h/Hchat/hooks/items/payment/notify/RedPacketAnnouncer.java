package h.Hchat.hooks.items.payment.notify;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import h.Hchat.hooks.items.payment.core.RedPacketEffectiveRule;
import h.Hchat.hooks.items.payment.core.RedPacketRuleResolver;
import h.Hchat.hooks.items.payment.core.RedPacketSettings;
import h.Hchat.utils.HLog;

/**
 * 红包领取成功后的本机 TTS 播报。
 */
public final class RedPacketAnnouncer {
    private static final String TAG = "[Hchat:RedPacketAnnouncer]";
    private static final String DEFAULT_TEXT = "抢到红包 {amount} 元";
    private static final int MAX_TTS_RETRY = 1;
    private static final long TTS_RETRY_DELAY_MS = 800L;

    private final Context hostContext;
    private final RedPacketSettings settings;
    private final RedPacketTemplateFormatter formatter;
    private final RedPacketTemplateFormatter.Logger logger;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Queue<PendingSpeech> pendingSpeeches = new ArrayDeque<>();
    private final Set<String> pendingKeys = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());
    private final Set<String> announcedKeys = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean ttsInitializing;

    public RedPacketAnnouncer(Context hostContext,
                              RedPacketSettings settings,
                              RedPacketTemplateFormatter formatter,
                              RedPacketTemplateFormatter.Logger logger) {
        this.hostContext = hostContext;
        this.settings = settings;
        this.formatter = formatter;
        this.logger = logger;
    }

    public void announceReceived(String amount, String talker, String nativeUrl) {
        announceReceived(amount, talker, nativeUrl, null);
    }

    public void announceReceived(String amount, String talker, String nativeUrl, RedPacketEffectiveRule rule) {
        if (rule == null) rule = RedPacketRuleResolver.legacyRule(settings);
        if (!rule.getAnnounceEnabled()) return;
        String template = rule.getAnnounceText();
        if (TextUtils.isEmpty(template)) template = DEFAULT_TEXT;
        String text = formatter.format(template, amount, talker, nativeUrl, "").trim();
        if (TextUtils.isEmpty(text)) return;
        String key = announceKey(nativeUrl);
        if (!markPending(key)) return;
        mainHandler.post(() -> speakOnMain(new PendingSpeech(key, text, 0)));
    }

    private boolean markPending(String key) {
        if (TextUtils.isEmpty(key)) return true;
        if (announcedKeys.contains(key)) return false;
        return pendingKeys.add(key);
    }

    private void markSpoken(PendingSpeech speech) {
        if (speech == null || TextUtils.isEmpty(speech.key)) return;
        announcedKeys.add(speech.key);
        pendingKeys.remove(speech.key);
    }

    private void releasePending(PendingSpeech speech) {
        if (speech == null || TextUtils.isEmpty(speech.key)) return;
        pendingKeys.remove(speech.key);
    }

    private void speakOnMain(PendingSpeech speech) {
        if (speech == null || TextUtils.isEmpty(speech.text)) {
            releasePending(speech);
            return;
        }
        if (ttsReady && tts != null) {
            if (!speakNow(speech)) retrySpeech(speech, "speak 返回错误");
            return;
        }
        pendingSpeeches.offer(speech);
        ensureTtsOnMain();
    }

    private void ensureTtsOnMain() {
        if (ttsReady && tts != null) {
            drainPendingSpeeches();
            return;
        }
        if (ttsInitializing) return;
        ttsInitializing = true;
        try {
            Context context = hostContext.getApplicationContext();
            if (context == null) context = hostContext;
            tts = new TextToSpeech(context, status ->
                    mainHandler.post(() -> onTtsInit(status)));
        } catch (Throwable e) {
            ttsInitializing = false;
            logError("红包播报初始化失败: " + e.getMessage(), e);
            retryPendingSpeeches("初始化异常");
        }
    }

    private void onTtsInit(int status) {
        ttsInitializing = false;
        if (status != TextToSpeech.SUCCESS || tts == null) {
            safeShutdown();
            logError("红包播报初始化失败: status=" + status, null);
            retryPendingSpeeches("初始化失败");
            return;
        }
        ttsReady = true;
        configureTts();
        drainPendingSpeeches();
    }

    private void configureTts() {
        try {
            int result = tts.setLanguage(Locale.CHINA);
            if (result == TextToSpeech.LANG_MISSING_DATA
                    || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                log("ERROR 红包播报中文语音不可用: " + result);
            }
        } catch (Throwable e) {
            log("ERROR 红包播报语言设置失败: " + e.getMessage());
        }
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            } catch (Throwable e) {
                log("ERROR 红包播报音频通道设置失败: " + e.getMessage());
            }
        }
    }

    private void drainPendingSpeeches() {
        PendingSpeech speech;
        while (ttsReady && tts != null && (speech = pendingSpeeches.poll()) != null) {
            if (!speakNow(speech)) {
                retrySpeech(speech, "speak 返回错误");
                return;
            }
        }
    }

    private boolean speakNow(PendingSpeech speech) {
        if (speech == null || tts == null || TextUtils.isEmpty(speech.text)) {
            releasePending(speech);
            return true;
        }
        try {
            int result;
            if (Build.VERSION.SDK_INT >= 21) {
                Bundle params = new Bundle();
                params.putString(TextToSpeech.Engine.KEY_PARAM_STREAM,
                        String.valueOf(AudioManager.STREAM_MUSIC));
                result = tts.speak(
                        speech.text,
                        TextToSpeech.QUEUE_ADD,
                        params,
                        "hchat_redpacket_" + System.currentTimeMillis());
            } else {
                HashMap<String, String> params = new HashMap<>();
                params.put(TextToSpeech.Engine.KEY_PARAM_STREAM,
                        String.valueOf(AudioManager.STREAM_MUSIC));
                result = tts.speak(speech.text, TextToSpeech.QUEUE_ADD, params);
            }
            if (result == TextToSpeech.ERROR) {
                logError("红包播报失败", null);
                return false;
            }
            markSpoken(speech);
            return true;
        } catch (Throwable e) {
            logError("红包播报失败: " + e.getMessage(), e);
            return false;
        }
    }

    private void retrySpeech(PendingSpeech speech, String reason) {
        safeShutdown();
        if (speech == null) return;
        if (speech.retryCount >= MAX_TTS_RETRY) {
            releasePending(speech);
            logError("红包播报放弃: " + reason, null);
            return;
        }
        pendingSpeeches.offer(new PendingSpeech(speech.key, speech.text, speech.retryCount + 1));
        mainHandler.postDelayed(this::ensureTtsOnMain, TTS_RETRY_DELAY_MS);
    }

    private void retryPendingSpeeches(String reason) {
        if (pendingSpeeches.isEmpty()) return;
        int count = pendingSpeeches.size();
        for (int i = 0; i < count; i++) {
            PendingSpeech speech = pendingSpeeches.poll();
            if (speech == null) continue;
            if (speech.retryCount >= MAX_TTS_RETRY) {
                releasePending(speech);
                logError("红包播报放弃: " + reason, null);
                continue;
            }
            pendingSpeeches.offer(new PendingSpeech(speech.key, speech.text, speech.retryCount + 1));
        }
        if (!pendingSpeeches.isEmpty()) {
            mainHandler.postDelayed(this::ensureTtsOnMain, TTS_RETRY_DELAY_MS);
        }
    }

    private void safeShutdown() {
        try {
            if (tts != null) tts.shutdown();
        } catch (Throwable ignored) {
        }
        tts = null;
        ttsReady = false;
    }

    private void log(String message) {
        if (logger != null) logger.log(message);
    }

    private void logError(String message, Throwable throwable) {
        log("ERROR " + message);
        HLog.e(TAG + " " + message, throwable);
    }

    private static String announceKey(String nativeUrl) {
        if (TextUtils.isEmpty(nativeUrl)) return "";
        String sendId = firstParam(nativeUrl, "sendid", "sendId");
        if (!TextUtils.isEmpty(sendId)) return "sendid:" + sendId;
        String msgId = firstParam(nativeUrl, "msgid", "msgId");
        String channelId = firstParam(nativeUrl, "channelid", "channelId");
        if (!TextUtils.isEmpty(msgId) || !TextUtils.isEmpty(channelId)) {
            return "msg:" + msgId + "|channel:" + channelId;
        }
        return "url:" + nativeUrl;
    }

    private static String firstParam(String url, String... keys) {
        if (TextUtils.isEmpty(url) || keys == null) return "";
        for (String key : keys) {
            String value = nativeUrlParam(url, key);
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "";
    }

    private static String nativeUrlParam(String url, String key) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(key)) return "";
        try {
            String prefix = key + "=";
            int start = url.indexOf('?');
            start = start >= 0 ? start + 1 : 0;
            while (start < url.length()) {
                int end = url.indexOf('&', start);
                if (end < 0) end = url.length();
                if (url.startsWith(prefix, start)) {
                    return url.substring(start + prefix.length(), end);
                }
                start = end + 1;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static final class PendingSpeech {
        final String key;
        final String text;
        final int retryCount;

        PendingSpeech(String key, String text, int retryCount) {
            this.key = key;
            this.text = text;
            this.retryCount = retryCount;
        }
    }
}
