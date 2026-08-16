package com.Johnny.wcx.features.items.chat

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Download
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.utils.DownloadIcon
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Feature(name = "语音保存到本地", categories = ["聊天"], description = "在语音消息菜单添加保存按钮, 允许将语音文件保存到本地")
object SaveVoicesToLocalStorage : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "SaveVoicesToLocalStorage"

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777003,
                "存本地",
                DownloadIcon,
                MaterialSymbols.Outlined.Download,
                { msgInfo -> msgInfo.typeCode == MessageType.VOICE.code },
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Adapted(
                    isSupported = { msgs ->
                        msgs.isNotEmpty() && msgs.all { it.typeCode == MessageType.VOICE.code }
                    },
                    onClick = { view, _, msgs ->
                        CoroutineScope(Dispatchers.IO).launch {
                            var succeeded = 0
                            msgs.forEach { if (saveVoice(it) != null) succeeded++ }
                            showToastSuspend(
                                view.context,
                                "已保存 $succeeded/${msgs.size} 条语音到本地",
                            )
                        }
                    },
                )
            ) { view, _, msgInfo ->
                CoroutineScope(Dispatchers.IO).launch {
                    val path = saveVoice(msgInfo)
                    showToastSuspend(
                        view.context,
                        path?.let { "已将语音保存到 $it" }
                            ?: "语音保存失败! 查看日志以了解错误详情",
                    )
                }
            }
        )
    }

    private suspend fun saveVoice(msgInfo: MessageInfo): String? {
        val encPath = msgInfo.imagePath ?: run {
            WeLogger.e(TAG, "voice imagePath is null")
            return null
        }
        return WeMessageApi.saveVoiceByEncPath(encPath) ?: run {
            WeLogger.e(TAG, "failed to save voice encPath=$encPath")
            null
        }
    }
}
