package com.Johnny.wcx.features.items.chat

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Forward
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.WeServiceApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.utils.ForwardIcon
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.AudioUtils
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.android.showToastSuspend
import com.Johnny.wcx.utils.serialization.XmlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Feature(
    name = "转发消息",
    categories = ["聊天"],
    description = "在消息长按菜单添加转发按钮, 可向好友或群聊批量转发"
)
object ForwardMessages : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "ForwardMessages"

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777010,
                "转发",
                ForwardIcon,
                MaterialSymbols.Outlined.Forward,
                isSupported = { true },
                // forward every selected message to every chosen contact
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Adapted(
                    isSupported = { true },
                    onClick = { view, _, msgInfos ->
                        showForwardDialog(view) { selectedWxIds ->
                            forwardMessages(msgInfos, selectedWxIds)
                        }
                    }
                )
            ) { view, _, msgInfo ->
                showForwardDialog(view) { selectedWxIds ->
                    forwardMessages(listOf(msgInfo), selectedWxIds)
                }
            }
        )
    }

    // shows the contacts picker once; invokes onConfirm with the chosen wxIds (non-empty)
    private fun showForwardDialog(view: android.view.View, onConfirm: (Set<String>) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

            withContext(Dispatchers.Main) {
                showComposeDialog(view.context) {
                    ContactsSelector(
                        title = "选择转发对象",
                        contacts = contacts,
                        initialSelectedWxIds = emptySet(),
                        onDismiss = onDismiss,
                        onConfirm = { selectedWxIds ->
                            if (selectedWxIds.isEmpty()) {
                                showToast("请选择至少一个联系人")
                                return@ContactsSelector
                            }

                            onDismiss()
                            onConfirm(selectedWxIds)
                        }
                    )
                }
            }
        }
    }

    private fun forwardMessages(msgInfos: List<MessageInfo>, wxIds: Set<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            val total = msgInfos.size * wxIds.size
            showToastSuspend("正在转发 ${msgInfos.size} 条消息到 ${wxIds.size} 个对象...")

            var success = 0
            wxIds.forEach { wxId ->
                msgInfos.forEach { msgInfo ->
                    if (sendTo(wxId, msgInfo)) success++
                }
            }

            showToastSuspend(
                if (success == total) "已转发到 ${wxIds.size} 个对象"
                else "已转发 $success/$total 条 (部分失败)"
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun sendTo(toUser: String, msgInfo: MessageInfo): Boolean {
        return runCatching {
            when (msgInfo.type) {
                MessageType.TEXT -> WeMessageApi.sendText(toUser, msgInfo.actualContent)
                MessageType.IMAGE -> forwardImage(toUser, msgInfo)
                MessageType.VOICE -> forwardVoice(toUser, msgInfo)
                MessageType.VIDEO, MessageType.MICRO_VIDEO -> forwardVideo(toUser, msgInfo)
                MessageType.STICKER, MessageType.SO_GOU_EMOJI -> forwardEmoji(toUser, msgInfo)
                MessageType.APP -> WeMessageApi.sendXmlAppMsg(toUser, msgInfo.actualContent)
                MessageType.QUOTE -> WeMessageApi.sendText(toUser, msgInfo.quoteMsgActualContent!!)
                else -> {
                    showToast("警告: 该消息类型未经过测试, 回退为作为卡片消息发送, 可能失败!")
                    WeMessageApi.sendXmlAppMsg(toUser, msgInfo.actualContent)
                }
            }
        }.getOrElse {
            WeLogger.e(TAG, "failed to forward message to $toUser: type=${msgInfo.typeCode}", it)
            false
        }
    }

    private fun forwardImage(toUser: String, msgInfo: MessageInfo): Boolean {
        val md5 = WeServiceApi.getImageMd5FromMsgInfo(msgInfo)
        WeMessageApi.sendImageByMd5(toUser, md5, null)
        return true
    }

    private fun forwardVoice(toUser: String, msgInfo: MessageInfo): Boolean {
        // 语音消息的源路径取自微信消息对象的 field_imgPath；为空说明该消息没有可用的语音文件引用。
        // 原来的实现直接 return false，失败现场没有任何日志，排查时无从下手。
        val encPath = msgInfo.imagePath
        if (encPath.isNullOrBlank()) {
            WeLogger.w(TAG, "forwardVoice skipped: empty source path (type=${msgInfo.typeCode})")
            return false
        }

        val voicePath = WeMessageApi.getVoiceFullPath(encPath)
        if (voicePath.isBlank()) {
            WeLogger.w(TAG, "forwardVoice failed: cannot resolve voice file path from $encPath")
            return false
        }

        // 时长由 native 解析（AudioUtils.getDurationMs -> lib.rs）。
        // native 侧解析失败时不会抛异常，而是静默返回 0；这里显式记录，
        // 便于区分「路径解析失败」与「时长解析失败」两种不同的失败原因。
        val durationMs = AudioUtils.getDurationMs(voicePath).toInt()
        if (durationMs <= 0) {
            WeLogger.w(TAG, "forwardVoice: duration unresolved for $voicePath (native returned $durationMs)")
        }

        val sent = WeMessageApi.sendVoice(toUser, voicePath, durationMs)
        if (!sent) {
            WeLogger.w(TAG, "forwardVoice: sendVoice returned false (path=$voicePath, duration=$durationMs)")
        }
        return sent
    }

    private fun forwardVideo(toUser: String, msgInfo: MessageInfo): Boolean {
        val mp4Path = WeServiceApi.getVideoMp4PathFromMsgInfo(msgInfo)
        return WeMessageApi.sendVideo(toUser, mp4Path)
    }

    private fun forwardEmoji(toUser: String, msgInfo: MessageInfo): Boolean {
        val md5 = msgInfo.imagePath
            ?: XmlUtils.extractXmlAttr(msgInfo.content, "md5").takeIf { it.isNotBlank() }
            ?: XmlUtils.extractXmlTag(msgInfo.content, "md5").takeIf { it.isNotBlank() }
            ?: return false
        return WeMessageApi.sendEmojiByMd5(toUser, md5)
    }
}
