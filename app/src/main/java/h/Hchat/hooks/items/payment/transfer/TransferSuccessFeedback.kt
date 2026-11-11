package h.Hchat.hooks.items.payment.transfer

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.items.payment.core.PaymentTemplateTimeFormatter
import h.Hchat.hooks.items.payment.core.RedPacketReplyStep
import h.Hchat.hooks.items.payment.core.RedPacketRuleConfig
import h.Hchat.hooks.items.payment.notify.RedPacketNotifier
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class TransferSuccessFeedback(
    private val context: Context,
    private val logger: (String, Throwable?) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notifier = RedPacketNotifier(
        context,
        "[Hchat:TransferNotifier]",
        "Hchat_transfer_notify_manual_v1",
        "Hchat 自动收款提醒"
    )
    private val handled = ConcurrentHashMap.newKeySet<String>()
    private val settings = AutoTransferSettings(context)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = ArrayDeque<String>()

    fun onReceived(
        key: String,
        message: h.Hchat.hooks.api.message.WeChatMessageObserveApi.ObservedMessage,
        info: TransferMessageInfo,
        rule: TransferEffectiveRule
    ) {
        if (key.isNotBlank() && !handled.add(key)) return
        val receivedAt = System.currentTimeMillis()
        val title = "自动收款"
        val text = format(rule.notifyText, message, info, receivedAt)
        val toastText = format(rule.notifyToastText, message, info, receivedAt)
        if (rule.notifySystemEnabled || rule.notifyToastEnabled) {
            notifier.sendNotice(
                title,
                text,
                toastText,
                message.talker,
                key,
                300000,
                rule.notifySystemEnabled,
                rule.notifyToastEnabled,
                rule.notifySoundEnabled,
                rule.notifyVibrateEnabled,
                rule.notifySoundUri
            )
        }
        if (rule.announceEnabled) announce(format(rule.announceText, message, info, receivedAt))
        sendReplySteps(
            message.talker,
            info.payerUsername.ifBlank { message.sender },
            message,
            info,
            rule.replyStepsFor(message.group || message.talker.endsWith("@chatroom")),
            receivedAt,
            0
        )
    }

    private fun sendReplySteps(
        talker: String,
        sender: String,
        message: h.Hchat.hooks.api.message.WeChatMessageObserveApi.ObservedMessage,
        info: TransferMessageInfo,
        steps: List<RedPacketReplyStep>,
        receivedAt: Long,
        index: Int
    ) {
        if (talker.isBlank() || index !in steps.indices) return
        val step = steps[index]
        val delay = step.nextDelayMillis().coerceAtLeast(0L)
        mainHandler.postDelayed({
            runCatching { sendStep(talker, sender, message, info, step, receivedAt) }
                .onFailure { logger("自动收款回复失败", it) }
            sendReplySteps(talker, sender, message, info, steps, receivedAt, index + 1)
        }, delay)
    }

    private fun sendStep(
        talker: String,
        sender: String,
        message: h.Hchat.hooks.api.message.WeChatMessageObserveApi.ObservedMessage,
        info: TransferMessageInfo,
        step: RedPacketReplyStep,
        receivedAt: Long
    ): Boolean {
        val selected = pick(step.content)
        val formatted = if (step.mode == RedPacketRuleConfig.REPLY_TEXT ||
            step.mode == RedPacketRuleConfig.REPLY_AT_SENDER ||
            step.mode == RedPacketRuleConfig.REPLY_XML
        ) format(selected, message, info, receivedAt) else selected
        val atSender = step.mode == RedPacketRuleConfig.REPLY_AT_SENDER || AT_TOKENS.any { selected.contains(it) }
        val cleanText = AT_TOKENS.fold(formatted) { value, token -> value.replace(token, "") }.trim()
        val messageApi = WeChatApis.message().sender()
        val mediaApi = WeChatApis.media()
        return when (step.mode) {
            RedPacketRuleConfig.REPLY_TEXT,
            RedPacketRuleConfig.REPLY_AT_SENDER -> if (atSender && sender.isNotBlank()) {
                messageApi?.sendTextWithAtList(talker, cleanText, listOf(sender)) == true
            } else messageApi?.sendText(talker, cleanText) == true
            RedPacketRuleConfig.REPLY_IMAGE -> mediaApi?.sendImage(talker, selected) == true
            RedPacketRuleConfig.REPLY_VOICE -> mediaApi?.sendVoice(talker, selected) == true
            RedPacketRuleConfig.REPLY_VIDEO -> mediaApi?.videos()?.send(talker, selected) == true
            RedPacketRuleConfig.REPLY_EMOJI -> mediaApi?.sendEmoji(talker, selected) == true
            RedPacketRuleConfig.REPLY_FILE -> mediaApi?.sendFile(talker, selected) == true
            RedPacketRuleConfig.REPLY_XML -> messageApi?.sendXml(talker, formatted) == true
            RedPacketRuleConfig.REPLY_FAVORITE -> mediaApi?.favorites()?.send(talker, selected) == true
            else -> false
        }
    }

    private fun format(
        template: String,
        message: h.Hchat.hooks.api.message.WeChatMessageObserveApi.ObservedMessage,
        info: TransferMessageInfo,
        receivedAt: Long
    ): String {
        val senderId = info.payerUsername.ifBlank { message.sender }
        val talkerName = displayName(message.talker)
        val senderName = displayMemberName(message.talker, senderId)
        val amount = String.format(Locale.US, "%.2f", info.amount)
        val timeText = PaymentTemplateTimeFormatter.format(
            settings.getString(AutoTransferSettings.KEY_TIME_FORMAT, AutoTransferSettings.DEFAULT_TIME_FORMAT),
            receivedAt
        )
        return template
            .replace("{amount}", amount).replace("{金额}", amount)
            .replace("{talker}", talkerName).replace("{会话}", talkerName)
            .replace("{sender}", senderName).replace("{成员}", senderName)
            .replace("{@sender}", "@$senderName\u2005")
            .replace("{@成员}", "@$senderName\u2005")
            .replace("{@转账的人}", "@$senderName\u2005")
            .replace("{time}", timeText)
    }

    private fun displayName(id: String): String = runCatching {
        WeChatApis.contact().contacts()?.getDisplayName(id).orEmpty().ifBlank { id }
    }.getOrDefault(id)

    private fun displayMemberName(talker: String, sender: String): String = runCatching {
        val contacts = WeChatApis.contact().contacts()
        if (talker.endsWith("@chatroom")) contacts?.getGroupMemberDisplayName(talker, sender) else contacts?.getDisplayName(sender)
    }.getOrNull().orEmpty().ifBlank { sender }

    private fun pick(value: String): String {
        val choices = value.split('|').map { it.trim() }.filter { it.isNotBlank() }
        return if (choices.isEmpty()) value.trim() else choices[Random.nextInt(choices.size)]
    }

    private fun announce(text: String) {
        if (text.isBlank()) return
        mainHandler.post {
            if (ttsReady) {
                speak(text)
                return@post
            }
            pendingSpeech.addLast(text)
            if (tts != null) return@post
            tts = TextToSpeech(context.applicationContext) { status ->
                mainHandler.post {
                    ttsReady = status == TextToSpeech.SUCCESS
                    if (!ttsReady) {
                        logger("自动收款播报初始化失败: $status", null)
                        pendingSpeech.clear()
                        return@post
                    }
                    tts?.language = Locale.CHINA
                    while (pendingSpeech.isNotEmpty()) speak(pendingSpeech.removeFirst())
                }
            }
        }
    }

    private fun speak(text: String) {
        runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "hchat_transfer_${System.currentTimeMillis()}")
        }.onFailure { logger("自动收款播报失败", it) }
    }

    companion object {
        private val AT_TOKENS = listOf("{@转账的人}", "{@sender}", "{@成员}")
    }
}
