package com.Johnny.wcx.features.items.chat

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Download
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.utils.DownloadIcon
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Feature(name = "文件下载到本地", categories = ["聊天"], description = "在文件消息菜单添加下载按钮, 允许将文件缓存并保存到本地")
object DownloadFilesToLocalStorage : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "DownloadFilesToLocalStorage"

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777022,
                "下载",
                DownloadIcon,
                MaterialSymbols.Outlined.Download,
                { msgInfo -> msgInfo.type == MessageType.FILE }
            ) { _, _, msgInfo ->
                showToast("正在缓存并下载...")
                CoroutineScope(Dispatchers.IO).launch {
                    val path = WeMessageApi.downloadFile(msgInfo.instance) ?: run {
                        WeLogger.e(TAG, "failed to cache & download file")
                        showToastSuspend("文件下载失败! 查看日志以了解错误详情")
                        return@launch
                    }
                    showToastSuspend("已将文件下载到 $path")
                }
            }
        )
    }
}
