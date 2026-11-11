package h.Hchat.hooks.items.payment.detect

import android.text.TextUtils
import h.Hchat.hooks.items.payment.core.RedPacketEffectiveRule
import h.Hchat.hooks.items.payment.core.RedPacketSettings

/**
 * 红包过滤器。
 */
class RedPacketFilter(private val settings: RedPacketSettings) {
    fun getRejectReason(
        sender: String?,
        talker: String?,
        content: String?,
        exclusiveRecvUser: String?,
        myWxid: String?,
        skipSelf: Boolean,
        listMode: Int,
        keywordMode: Int,
        keywords: String?
    ): String? {
        if (TextUtils.isEmpty(exclusiveRecvUser) && !skipSelf && listMode == 0 && keywordMode == 0) {
            return null
        }

        if (!TextUtils.isEmpty(exclusiveRecvUser) && exclusiveRecvUser != myWxid) {
            return "不是发给我的专属红包"
        }

        if (!TextUtils.isEmpty(myWxid) && !TextUtils.isEmpty(sender) && sender == myWxid && skipSelf) {
            return "自己发的红包"
        }

        val isGroup = talker != null &&
            (talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom") || talker.endsWith("@openim"))

        if (listMode == 1) {
            var white = settings.isUserInList(sender, RedPacketSettings.KEY_WHITELIST)
            if (isGroup) white = white || settings.isUserInList(talker, RedPacketSettings.KEY_WHITELIST)
            if (!white) return "非白名单"
        } else if (listMode == 2) {
            var black = settings.isUserInList(sender, RedPacketSettings.KEY_BLACKLIST)
            if (isGroup) black = black || settings.isUserInList(talker, RedPacketSettings.KEY_BLACKLIST)
            if (black) return "黑名单"
        }

        if (keywordMode == 1 && !RedPacketParser.containsKeyword(content, keywords)) {
            return "未包含指定关键词"
        }
        if (keywordMode == 2 && RedPacketParser.containsKeyword(content, keywords)) {
            return "包含屏蔽关键词"
        }

        return null
    }

    fun getRejectReason(
        sender: String?,
        talker: String?,
        content: String?,
        exclusiveRecvUser: String?,
        myWxid: String?,
        rule: RedPacketEffectiveRule
    ): String? {
        if (!rule.enabled) return "规则已关闭"

        if (!TextUtils.isEmpty(exclusiveRecvUser) && exclusiveRecvUser != myWxid) {
            return "不是发给我的专属红包"
        }

        if (!TextUtils.isEmpty(myWxid) && !TextUtils.isEmpty(sender) && sender == myWxid && rule.skipSelf) {
            return "自己发的红包"
        }

        val isGroup = talker != null &&
            (talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom") || talker.endsWith("@openim"))
        if (rule.listMode == 1) {
            var white = listContains(rule.whitelist, sender)
            if (isGroup) white = white || listContains(rule.whitelist, talker)
            if (!white) return "非白名单"
        } else if (rule.listMode == 2) {
            var black = listContains(rule.blacklist, sender)
            if (isGroup) black = black || listContains(rule.blacklist, talker)
            if (black) return "黑名单"
        }

        if (rule.isInQuietTime()) return "当前时间段禁抢"

        if (rule.keywordMode == 1 && !RedPacketParser.containsKeyword(content, rule.keywords)) {
            return "未包含指定关键词"
        }
        if (rule.keywordMode == 2 && RedPacketParser.containsKeyword(content, rule.keywords)) {
            return "包含屏蔽关键词"
        }

        return null
    }

    private fun listContains(list: String?, id: String?): Boolean {
        if (list.isNullOrBlank() || id.isNullOrBlank()) return false
        return list.split("|", ",", "，", "\n", "\r").any { it.trim() == id }
    }
}
