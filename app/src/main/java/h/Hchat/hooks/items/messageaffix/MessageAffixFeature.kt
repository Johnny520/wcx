package h.Hchat.hooks.items.messageaffix

import h.Hchat.event.Events
import h.Hchat.hooks.api.message.OutgoingTextDecoratorRegistry
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.items.inputhint.InputHintStats
import h.Hchat.hooks.items.inputhint.OutgoingMessageStatsRepository
import h.Hchat.hooks.items.script.ScriptSendButtonHook
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAffixFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "发送文本格式"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MessageAffixSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        val prefs = MessageAffixSettings.preferences(context.hostContext())
        trackSubscription(
            OutgoingTextDecoratorRegistry.register(ID) { decorationContext ->
                val text = decorationContext.text
                if (!prefs.getBoolean(MessageAffixSettings.KEY_ENABLE, MessageAffixSettings.DEFAULT_ENABLE) ||
                    text.isBlank()
                ) {
                    return@register null
                }
                val format = MessageAffixSettings.textFormat(prefs)
                if (MessageAffixSettings.originalTextVariableCount(format) != 1) {
                    return@register null
                }
                val markerIndex = format.indexOf(MessageAffixSettings.VAR_SEND_TEXT)
                val sendTime = formatSendTime(
                    prefs.getString(
                        MessageAffixSettings.KEY_TIME_FORMAT,
                        MessageAffixSettings.DEFAULT_TIME_FORMAT
                    )
                )
                val stats = OutgoingMessageStatsRepository.current()
                val sendDuration = formatSendDuration(decorationContext.inputDurationMs)
                val prefix = renderFormatPart(
                    format.substring(0, markerIndex),
                    sendTime,
                    sendDuration,
                    stats
                )
                val suffix = renderFormatPart(
                    format.substring(markerIndex + MessageAffixSettings.VAR_SEND_TEXT.length),
                    sendTime,
                    sendDuration,
                    stats
                )
                if (prefix.isEmpty() && suffix.isEmpty()) {
                    null
                } else {
                    OutgoingTextDecoratorRegistry.TextDecoration(prefix, suffix)
                }
            }
        )
        installStats()
        scheduleSendButtonHook(context)
        subscribe(Events.DexReady::class.java) {
            installStats()
            scheduleSendButtonHook(context)
        }
    }

    private fun renderFormatPart(
        part: String,
        sendTime: String,
        sendDuration: String,
        stats: InputHintStats
    ): String {
        return part
            .replace(MessageAffixSettings.VAR_LINE, "\n")
            .replace(MessageAffixSettings.VAR_SEND_TIME, sendTime)
            .replace(MessageAffixSettings.VAR_TOTAL_MESSAGES, stats.totalMessages.toString())
            .replace(MessageAffixSettings.VAR_TEXT_MESSAGES, stats.textMessages.toString())
            .replace(MessageAffixSettings.VAR_TEXT_CHARACTERS, stats.textCharacters.toString())
            .replace(MessageAffixSettings.VAR_EMOJI_MESSAGES, stats.emojiMessages.toString())
            .replace(MessageAffixSettings.VAR_TRANSFER_MESSAGES, stats.transferMessages.toString())
            .replace(MessageAffixSettings.VAR_RED_PACKET_MESSAGES, stats.redPacketMessages.toString())
            .replace(MessageAffixSettings.VAR_FILE_MESSAGES, stats.fileMessages.toString())
            .replace(MessageAffixSettings.VAR_SEND_DURATION, sendDuration)
    }

    private fun installStats() {
        OutgoingMessageStatsRepository.install(::logError)
    }

    private fun formatSendDuration(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
        if (totalSeconds < 60L) return "${totalSeconds}秒"
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        if (minutes < 60L) return "${minutes}分${seconds}秒"
        val hours = minutes / 60L
        return "${hours}小时${minutes % 60L}分${seconds}秒"
    }

    private fun formatSendTime(pattern: String?): String {
        val normalized = MessageAffixSettings.normalizeTimeFormat(pattern)
        return runCatching {
            SimpleDateFormat(normalized, Locale.getDefault()).format(Date())
        }.getOrElse {
            SimpleDateFormat(MessageAffixSettings.DEFAULT_TIME_FORMAT, Locale.getDefault()).format(Date())
        }
    }

    private fun scheduleSendButtonHook(context: FeatureContext) {
        DexInstallScheduler.schedule(
            "shared:send_button",
            "聊天发送按钮",
            stage = DexInstallScheduler.Stage.WARMUP
        ) {
            ScriptSendButtonHook.install(context)
        }
    }

    companion object {
        const val ID = "message_affix"
    }
}
