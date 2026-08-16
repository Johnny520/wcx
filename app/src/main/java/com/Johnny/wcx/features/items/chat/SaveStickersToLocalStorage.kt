package com.Johnny.wcx.features.items.chat

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Download
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.utils.DownloadIcon
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Feature(name = "贴纸保存到本地", categories = ["聊天"], description = "在贴纸消息菜单添加保存按钮, 允许将图片保存到本地")
object SaveStickersToLocalStorage : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "SaveStickersToLocalStorage"

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            @Suppress("UNCHECKED_CAST")
            WeChatMessageContextMenuApi.MenuItem(
                777001,
                "存本地",
                DownloadIcon,
                MaterialSymbols.Outlined.Download,
                { msgInfo -> msgInfo.type?.isSticker ?: false },
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Adapted(
                    isSupported = { msgs ->
                        msgs.isNotEmpty() && msgs.all { it.type?.isSticker ?: false }
                    },
                    onClick = { view, _, msgs ->
                        CoroutineScope(Dispatchers.IO).launch {
                            var succeeded = 0
                            msgs.forEach { if (saveSticker(it) != null) succeeded++ }
                            showToastSuspend(
                                view.context,
                                "已保存 $succeeded/${msgs.size} 条贴纸到本地",
                            )
                        }
                    },
                )
            ) { view, _, msgInfo ->
                CoroutineScope(Dispatchers.IO).launch {
                    val path = saveSticker(msgInfo)
                    showToastSuspend(
                        view.context,
                        path?.let { "已将贴纸保存到 $it" }
                            ?: "贴纸保存失败! 查看日志以了解错误详情",
                    )
                }
            }
        )
    }

    private suspend fun saveSticker(msgInfo: MessageInfo): String? {
        val md5 = msgInfo.imagePath ?: run {
            WeLogger.e(TAG, "sticker imagePath is null")
            return null
        }
        return WeMessageApi.saveStickerByMd5(md5) ?: run {
            WeLogger.e(TAG, "failed to save sticker md5=$md5")
            null
        }
    }
}
