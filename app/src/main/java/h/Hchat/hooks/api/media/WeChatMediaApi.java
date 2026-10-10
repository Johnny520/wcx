package h.Hchat.hooks.api.media;

import android.content.Context;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.api.ui.WeChatCurrentActivityApi;
import org.luckypray.dexkit.DexKitBridge;

/**
 * 微信媒体 API 聚合入口。
 *
 * 每种媒体独立一个子 API，避免图片、语音、视频、表情、文件发送逻辑混在一起。
 */
public final class WeChatMediaApi {
    public interface Logger {
        void log(String message);
    }

    private final WeChatImageApi imageApi;
    private final WeChatVoiceApi voiceApi;
    private final WeChatVideoApi videoApi;
    private final WeChatEmojiApi emojiApi;
    private final WeChatFileApi fileApi;
    private final WeChatFavoriteApi favoriteApi;

    public WeChatMediaApi(Context hostContext,
                          DexFinder dexFinder,
                          ClassLoader hostClassLoader,
                          DexKitBridge dexKitBridge,
                          WeChatCurrentActivityApi currentActivityApi,
                          Logger logger) {
        this.imageApi = new WeChatImageApi(hostContext, dexFinder,
                message -> log(logger, message));
        this.voiceApi = new WeChatVoiceApi(hostContext, dexFinder, message -> log(logger, message));
        this.videoApi = new WeChatVideoApi(hostContext, dexFinder, currentActivityApi, imageApi,
                message -> log(logger, message));
        this.emojiApi = new WeChatEmojiApi(hostContext, dexFinder, message -> log(logger, message));
        this.fileApi = new WeChatFileApi(dexFinder, message -> log(logger, message));
        this.favoriteApi = new WeChatFavoriteApi(hostContext, dexFinder, hostClassLoader,
                dexKitBridge, currentActivityApi, message -> log(logger, message));
    }

    public boolean isAvailable() {
        return true;
    }

    public WeChatImageApi images() {
        return imageApi;
    }

    public WeChatVoiceApi voices() {
        return voiceApi;
    }

    public WeChatVideoApi videos() {
        return videoApi;
    }

    public WeChatEmojiApi emojis() {
        return emojiApi;
    }

    public WeChatFileApi files() {
        return fileApi;
    }

    public WeChatFavoriteApi favorites() {
        return favoriteApi;
    }

    /** Compatibility wrapper. New code should use images().canSendSilently(). */
    public boolean canSendImageSilently() {
        return imageApi.canSendSilently();
    }

    /** Compatibility wrapper. New code should use voices().canSendSilently(). */
    public boolean canSendVoiceSilently() {
        return voiceApi.canSendSilently();
    }

    /** Compatibility wrapper. New code should use files().canSendSilently(). */
    public boolean canSendFileSilently() {
        return fileApi.canSendSilently();
    }

    /** Compatibility wrapper. New code should use favorites().canSendSilently(). */
    public boolean canSendFavoriteSilently() {
        return favoriteApi.canSendSilently();
    }

    /** Compatibility wrapper. New code should use emojis().canSendSilently(). */
    public boolean canSendEmojiSilently() {
        return emojiApi.canSendSilently();
    }

    /** Compatibility wrapper. New code should use images().send(...). */
    public boolean sendImage(String talker, String imagePath) {
        return imageApi.send(talker, imagePath);
    }

    /** Compatibility wrapper. New code should use images().send(...). */
    public boolean sendImage(String talker, String imagePath, String appId) {
        return imageApi.send(talker, imagePath, appId);
    }

    /** Compatibility wrapper. New code should use images().sendOriginal(...). */
    public boolean sendOriginalImage(String talker, String imagePath) {
        return imageApi.sendOriginal(talker, imagePath);
    }

    /** Compatibility wrapper. New code should use voices().send(...). */
    public boolean sendVoice(String talker, String voicePath) {
        return voiceApi.send(talker, voicePath);
    }

    /** Compatibility wrapper. New code should use voices().send(...). */
    public boolean sendVoice(String talker, String voicePath, int durationMillis) {
        return voiceApi.send(talker, voicePath, durationMillis);
    }

    /** Compatibility wrapper. New code should use files().send(...). */
    public boolean sendFile(String talker, String filePath) {
        return fileApi.send(talker, filePath);
    }

    /** Compatibility wrapper. New code should use files().send(...). */
    public boolean sendFile(String talker, String filePath, String title) {
        return fileApi.send(talker, filePath, title);
    }

    /** Compatibility wrapper. New code should use favorites().send(...). */
    public boolean sendFavorite(String talker, long localId) {
        return favoriteApi.send(talker, localId);
    }

    /** Compatibility wrapper. New code should use favorites().send(...). */
    public boolean sendFavorite(String talker, String localId) {
        return favoriteApi.send(talker, localId);
    }

    /** Compatibility wrapper. New code should use emojis().send(...). */
    public boolean sendEmoji(String talker, String emojiPathOrMd5) {
        return emojiApi.send(talker, emojiPathOrMd5);
    }

    /** WA compatibility wrapper for WXMediaMessage high-level send. */
    public boolean sendMediaMessage(String talker, Object mediaMessage, String appId) {
        return fileApi.sendMediaMessage(talker, mediaMessage, appId);
    }

    /** WA compatibility wrapper. */
    public boolean shareFile(String talker, String title, String filePath, String appId) {
        return fileApi.shareFile(talker, title, filePath, appId);
    }

    /** WA compatibility wrapper. */
    public boolean shareText(String talker, String text, String appId) {
        return fileApi.shareText(talker, text, appId);
    }

    /** WA compatibility wrapper. */
    public boolean shareWebpage(String talker, String title, String description, String webpageUrl, byte[] thumbData, String appId) {
        return fileApi.shareWebpage(talker, title, description, webpageUrl, thumbData, appId);
    }

    /** WA compatibility wrapper. */
    public boolean shareVideo(String talker, String title, String description, String videoUrl, byte[] thumbData, String appId) {
        return fileApi.shareVideo(talker, title, description, videoUrl, thumbData, appId);
    }

    /** WA compatibility wrapper. */
    public boolean shareMusic(String talker, String title, String description, String musicUrl, String musicDataUrl, byte[] thumbData, String appId) {
        return fileApi.shareMusic(talker, title, description, musicUrl, musicDataUrl, thumbData, appId);
    }

    public boolean shareMusicWithMetadata(String talker,
                                          String title,
                                          String description,
                                          String musicUrl,
                                          String musicDataUrl,
                                          String songLyric,
                                          String songAlbumUrl,
                                          byte[] thumbData,
                                          String appId) {
        return fileApi.shareMusicWithMetadata(
                talker, title, description, musicUrl, musicDataUrl,
                songLyric, songAlbumUrl, thumbData, appId);
    }

    /** WA compatibility wrapper. */
    public boolean shareMusicVideo(String talker,
                                   String title,
                                   String description,
                                   String musicUrl,
                                   String musicDataUrl,
                                   String singerName,
                                   int duration,
                                   String songLyric,
                                   byte[] thumbData,
                                   String appId) {
        return fileApi.shareMusicVideo(
                talker, title, description, musicUrl, musicDataUrl, singerName, duration, songLyric, thumbData, appId);
    }

    /** WA compatibility wrapper. */
    public boolean shareMiniProgram(String talker,
                                    String title,
                                    String description,
                                    String userName,
                                    String path,
                                    byte[] thumbData,
                                    String appId) {
        return fileApi.shareMiniProgram(talker, title, description, userName, path, thumbData, appId);
    }

    private static void log(Logger logger, String message) {
        if (logger != null) logger.log(message);
    }
}
