package h.Hchat.hooks.items.payment.notify

import android.text.TextUtils
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.items.payment.core.PaymentTemplateTimeFormatter
import h.Hchat.hooks.items.payment.core.RedPacketSettings
import h.Hchat.hooks.items.payment.core.RedPacketState
import h.Hchat.hooks.items.payment.detect.RedPacketParser

/**
 * 红包通知/回复模板变量替换。
 */
class RedPacketTemplateFormatter(
    private val state: RedPacketState,
    private val settings: RedPacketSettings,
    private val logger: Logger?
) {
    fun interface Logger {
        fun log(message: String)
    }

    fun format(
        template: String?,
        amount: String?,
        talker: String?,
        nativeUrl: String?,
        reason: String?
    ): String {
        val safeTemplate = template ?: ""
        val safeAmount = if (TextUtils.isEmpty(amount)) "未知" else amount!!.replace("元", "")
        var talkerText = displayName(talker)
        if (TextUtils.isEmpty(talkerText)) talkerText = "未知会话"
        val content = if (!TextUtils.isEmpty(nativeUrl)) state.contentMap[nativeUrl] else ""
        val sender = resolveSender(nativeUrl, content)
        var senderText = displayMemberName(talker, sender)
        if (TextUtils.isEmpty(senderText)) senderText = "未知成员"
        val atSenderText = "@$senderText\u2005"
        val timeText = PaymentTemplateTimeFormatter.format(
            settings.getString(RedPacketSettings.KEY_TIME_FORMAT, RedPacketSettings.DEFAULT_TIME_FORMAT),
            System.currentTimeMillis()
        )
        return safeTemplate
            .replace("{amount}", safeAmount)
            .replace("{金额}", safeAmount)
            .replace("{talker}", talkerText)
            .replace("{会话}", talkerText)
            .replace("{@发红包的人}", atSenderText)
            .replace("{@sender}", atSenderText)
            .replace("{@成员}", atSenderText)
            .replace("{sender}", senderText)
            .replace("{成员}", senderText)
            .replace("{time}", timeText)
    }

    fun resolveSenderId(nativeUrl: String?): String {
        val content = if (!TextUtils.isEmpty(nativeUrl)) state.contentMap[nativeUrl] else ""
        return resolveSender(nativeUrl, content)
    }

    private fun resolveSender(nativeUrl: String?, content: String?): String {
        var sender = if (!TextUtils.isEmpty(nativeUrl)) state.senderMap[nativeUrl] else ""
        if (TextUtils.isEmpty(sender) || isGroupId(sender)) {
            val xmlSender = RedPacketParser.getXmlParamByTag(content, "fromusername")
            if (!TextUtils.isEmpty(xmlSender) && !isGroupId(xmlSender)) sender = xmlSender
        }
        return RedPacketParser.normalizeUsername(sender)
    }

    private fun displayName(wxId: String?): String {
        if (TextUtils.isEmpty(wxId)) return ""
        return try {
            val api = WeChatApis.contact().contacts()
            if (api != null && api.isAvailable) {
                api.getDisplayName(wxId)
            } else {
                wxId ?: ""
            }
        } catch (e: Throwable) {
            log("解析会话名失败: ${e.message}")
            wxId ?: ""
        }
    }

    private fun displayMemberName(talker: String?, sender: String?): String {
        if (TextUtils.isEmpty(sender)) return ""
        return try {
            val api = WeChatApis.contact().contacts()
            if (api != null && api.isAvailable) {
                if (!TextUtils.isEmpty(talker) && api.isGroup(talker)) {
                    api.getGroupMemberDisplayName(talker, sender)
                } else {
                    api.getDisplayName(sender)
                }
            } else {
                sender ?: ""
            }
        } catch (e: Throwable) {
            log("解析成员名失败: ${e.message}")
            sender ?: ""
        }
    }

    private fun isGroupId(value: String?): Boolean {
        return !TextUtils.isEmpty(value) &&
            (value!!.endsWith("@chatroom") || value.endsWith("@im.chatroom"))
    }

    private fun log(message: String) {
        logger?.log(message)
    }
}
