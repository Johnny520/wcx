package h.Hchat.hooks.items.musicorder

import android.content.Context
import android.media.MediaMetadataRetriever
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.message.WeChatMessageChangeApi
import h.Hchat.hooks.items.script.ScriptSendButtonHook
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

internal class QQMusicOrderRuntime(
    context: Context,
    private val logError: (String, Throwable?) -> Unit
) {
    private val hostContext = context.applicationContext ?: context
    private val settings = QQMusicOrderSettings(context)
    private val client = QQMusicClient()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HchatQQMusicOrder").apply { isDaemon = true }
    }

    fun install(track: (Any) -> Unit) {
        WeChatApis.message().changes()?.let { changes ->
            runCatching { changes.install() }
                .onFailure { logError("QQ点歌数据库监听安装失败", it) }
            changes.subscribe(::onMessageChanged)?.let(track)
        }
        track(ScriptSendButtonHook.registerHandler(QQMusicOrderFeature.ID, ::onSendButton))
    }

    fun destroy() {
        executor.shutdownNow()
    }

    private fun onSendButton(text: String): Boolean {
        if (!settings.isEnabled()) return false
        val talker = WeChatApis.interaction().chatPage()?.currentTalker().orEmpty()
        if (talker.isBlank()) return false
        when (text.trim()) {
            ENABLE_CURRENT_CHAT_COMMAND -> {
                setCurrentChatAllowed(talker, true)
                return true
            }
            DISABLE_CURRENT_CHAT_COMMAND -> {
                setCurrentChatAllowed(talker, false)
                return true
            }
        }
        if (!settings.interceptOwnCommand()) return false
        val command = parseCommand(text) ?: return false
        submit(talker, 0L, WeChatApis.contact().account()?.selfWxId().orEmpty(), command)
        return true
    }

    private fun setCurrentChatAllowed(talker: String, allowed: Boolean) {
        val next = settings.allowedTalkers().toMutableSet().apply {
            if (allowed) add(talker) else remove(talker)
        }
        settings.saveAllowedTalkers(next)
        val content = if (allowed) {
            "该聊天点歌开关已开启，其他人可以点歌了"
        } else {
            "该聊天点歌开关已关闭，只有你能点歌了"
        }
        WeChatApis.message().local()?.insertSystemMessage(talker, content, System.currentTimeMillis())
    }

    private fun onMessageChanged(change: WeChatMessageChangeApi.MessageChange) {
        if (!change.isInsert) return
        val message = change.message ?: return
        if (!settings.isEnabled() || !message.isText()) return
        if (message.isSystem()) return
        val createTime = message.createTime
        if (createTime > 0L && System.currentTimeMillis() - normalizeTime(createTime) >= MESSAGE_MAX_AGE_MS) return
        val talker = message.talker
        if (talker.isBlank()) return
        if (!message.isOutgoing() && !settings.allowedTalkers().contains(talker)) return
        val content = normalizeContent(message.content, message.isGroupChat())
        val command = parseCommand(content) ?: return
        val sender = if (message.isOutgoing()) {
            WeChatApis.contact().account()?.selfWxId().orEmpty()
        } else {
            message.getSendTalker()
        }
        submit(talker, message.msgId, sender, command)
    }

    private fun submit(talker: String, msgId: Long, sender: String, command: MusicCommand) {
        executor.execute {
            runCatching { process(talker, msgId, sender, command) }
                .onFailure {
                    logError("QQ点歌处理异常", it)
                    sendReply(talker, msgId, "处理失败")
                }
        }
    }

    private fun process(talker: String, msgId: Long, sender: String, command: MusicCommand) {
        val track = when (val result = client.search(command.keyword)) {
            is QQMusicSearchResult.Success -> result.track
            QQMusicSearchResult.NotFound -> {
                sendReply(talker, msgId, "未搜到")
                return
            }
            QQMusicSearchResult.Unavailable -> {
                sendReply(talker, msgId, "获取失败，可能是版权限制或数字专辑")
                return
            }
        }
        val sendCard = settings.sendAsCard()
        val sendVoice = settings.sendAsVoice()
        if (!sendCard && !sendVoice) {
            sendReply(talker, msgId, "请至少开启音乐卡片或歌曲语音发送")
            return
        }
        val sentCard = if (sendCard) {
            val singer = resolveSinger(talker, sender, command.customSinger, track.singer)
            val albumUrl = if (settings.replaceCoverWithAvatar()) {
                WeChatApis.contact().contacts()?.getAvatarUrl(sender, true).orEmpty().ifBlank { track.coverUrl }
            } else {
                track.coverUrl
            }
            val thumbData = downloadThumb(albumUrl.replace("R500x500", "R300x300"))
            WeChatApis.interaction().media()?.shareMusicWithMetadata(
                talker,
                track.title,
                singer,
                track.landingUrl,
                track.playUrl,
                track.lyric.ifBlank { "[99:99.99]暂无歌词" },
                albumUrl,
                thumbData,
                settings.appId()
            ) == true
        } else {
            null
        }
        val sentVoice = if (sendVoice) {
            sendTrackAsVoice(talker, track)
        } else {
            null
        }
        when {
            sentCard != false && sentVoice != false -> Unit
            sentCard == false && sentVoice == false -> sendReply(talker, msgId, "音乐卡片和歌曲语音发送失败")
            sentCard == false -> sendReply(talker, msgId, "音乐卡片发送失败")
            sentVoice == false -> sendReply(talker, msgId, "歌曲语音发送失败")
        }
    }

    private fun sendTrackAsVoice(talker: String, track: QQMusicTrack): Boolean {
        val voiceApi = WeChatApis.media()?.voices()
        if (voiceApi == null || !voiceApi.canSendSilently()) {
            return false
        }
        val cacheDir = File(hostContext.cacheDir, VOICE_CACHE_DIR)
        if ((!cacheDir.isDirectory && !cacheDir.mkdirs()) || !cacheDir.canWrite()) {
            return false
        }
        val target = File(cacheDir, "qq_music_${System.currentTimeMillis()}_${System.nanoTime()}${audioSuffix(track.playUrl)}")
        val part = File(target.absolutePath + ".part")
        return try {
            downloadAudio(track.playUrl, part, target) &&
                voiceApi.send(talker, target.absolutePath)
        } finally {
            deleteFile(part)
            deleteFile(target)
        }
    }

    private fun resolveSinger(talker: String, sender: String, customSinger: String?, sourceSinger: String): String {
        if (!customSinger.isNullOrBlank()) return customSinger
        if (settings.customSingerEnabled() && settings.defaultSinger().isNotBlank()) return settings.defaultSinger()
        if (settings.replaceSingerWithNickname() && sender.isNotBlank()) {
            val contacts = WeChatApis.contact().contacts()
            val contact = contacts?.getContact(sender)
            val nickname = if (isGroup(talker)) {
                WeChatApis.contact().chatrooms()?.getRoomDisplayName(talker, sender).orEmpty()
                    .ifBlank { contacts?.getGroupMemberDisplayName(talker, sender).orEmpty() }
            } else {
                ""
            }.ifBlank { contact?.remarkName.orEmpty() }
                .ifBlank { contact?.nickname.orEmpty() }
            if (nickname.isNotBlank() && nickname != sender) return nickname
        }
        return sourceSinger
    }

    private fun parseCommand(text: String): MusicCommand? {
        val clean = text.trim()
        val trigger = settings.triggers().firstOrNull { clean.startsWith(it) } ?: return null
        var keyword = clean.removePrefix(trigger).trim()
        var customSinger: String? = null
        if (settings.customSingerEnabled()) {
            val separator = keyword.indexOf('&')
            if (separator >= 0) {
                customSinger = keyword.substring(separator + 1).trim().takeIf { it.isNotEmpty() }
                keyword = keyword.substring(0, separator).trim()
            }
        }
        return keyword.takeIf { it.isNotEmpty() }?.let { MusicCommand(it, customSinger) }
    }

    private fun sendReply(talker: String, msgId: Long, content: String) {
        val sender = WeChatApis.message().sender() ?: return
        if (msgId > 0L && sender.sendQuote(talker, msgId, content)) return
        sender.sendText(talker, content)
    }

    private fun normalizeContent(content: String, group: Boolean): String {
        if (!group) return content.trim()
        val marker = when {
            content.contains(":\n") -> ":\n"
            content.contains(":\\n") -> ":\\n"
            else -> return content.trim()
        }
        return content.substringAfter(marker).trim()
    }

    private fun downloadThumb(url: String): ByteArray? {
        if (url.isBlank()) return null
        var connection: HttpURLConnection? = null
        return runCatching {
            connection = URL(url).openConnection() as HttpURLConnection
            connection?.connectTimeout = 10_000
            connection?.readTimeout = 10_000
            connection?.instanceFollowRedirects = true
            connection?.setRequestProperty("User-Agent", "MicroMessenger Client")
            val responseCode = connection?.responseCode ?: return@runCatching null
            if (responseCode !in 200..299) return@runCatching null
            connection?.inputStream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_THUMB_BYTES) return@use null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }.onFailure { logError("QQ点歌封面下载失败", it) }
            .getOrNull()
            .also { connection?.disconnect() }
    }

    private fun downloadAudio(url: String, part: File, target: File): Boolean {
        if (url.isBlank()) return false
        var connection: HttpURLConnection? = null
        return runCatching {
            connection = URL(url).openConnection() as HttpURLConnection
            connection?.connectTimeout = AUDIO_CONNECT_TIMEOUT_MS
            connection?.readTimeout = AUDIO_READ_TIMEOUT_MS
            connection?.instanceFollowRedirects = true
            connection?.setRequestProperty("User-Agent", "MicroMessenger Client")
            connection?.setRequestProperty("Referer", "https://y.qq.com/")
            val responseCode = connection?.responseCode ?: return@runCatching false
            if (responseCode !in 200..299) return@runCatching false
            val contentType = connection?.contentType.orEmpty().substringBefore(';').lowercase()
            if (contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("xml")) {
                return@runCatching false
            }
            val contentLength = connection?.contentLengthLong ?: -1L
            if (contentLength > MAX_AUDIO_BYTES) return@runCatching false
            var total = 0L
            var oversized = false
            connection?.inputStream?.use { input ->
                FileOutputStream(part, false).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_AUDIO_BYTES) {
                            oversized = true
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } ?: return@runCatching false
            if (oversized || !part.isFile || part.length() <= 0L) return@runCatching false
            if (target.exists() && !target.delete()) return@runCatching false
            part.renameTo(target) && target.isFile && target.length() > 0L && isAudioFile(target)
        }.onFailure { logError("QQ点歌歌曲音频下载失败", it) }
            .getOrDefault(false)
            .also { connection?.disconnect() }
    }

    private fun isAudioFile(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.let { it > 0L } == true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun audioSuffix(url: String): String {
        val path = runCatching { URL(url).path.lowercase() }.getOrDefault("")
        return SUPPORTED_AUDIO_SUFFIXES.firstOrNull(path::endsWith) ?: ".audio"
    }

    private fun deleteFile(file: File) {
        if (file.exists() && !file.delete()) file.deleteOnExit()
    }

    private fun normalizeTime(value: Long): Long = if (value < 100_000_000_000L) value * 1000L else value

    private fun isGroup(talker: String): Boolean = talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")

    private data class MusicCommand(val keyword: String, val customSinger: String?)

    companion object {
        private const val ENABLE_CURRENT_CHAT_COMMAND = "开启点歌"
        private const val DISABLE_CURRENT_CHAT_COMMAND = "关闭点歌"
        private const val MESSAGE_MAX_AGE_MS = 30_000L
        private const val MAX_THUMB_BYTES = 128 * 1024
        private const val VOICE_CACHE_DIR = "Hchat_qq_music_order_voice"
        private const val AUDIO_CONNECT_TIMEOUT_MS = 15_000
        private const val AUDIO_READ_TIMEOUT_MS = 30_000
        private const val MAX_AUDIO_BYTES = 128L * 1024L * 1024L
        private val SUPPORTED_AUDIO_SUFFIXES = listOf(".mp3", ".m4a", ".mp4", ".flac", ".ogg", ".wav")
    }
}
