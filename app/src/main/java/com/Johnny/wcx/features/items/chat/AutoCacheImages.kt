package com.Johnny.wcx.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Feature(name = "自动缓存图片", categories = ["聊天"], description = "监听接收到的图片消息, 自动触发微信从 CDN 下载, 将原图缓存到本地")
object AutoCacheImages : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "AutoCacheImages"

    private var useWhitelist by WePrefs.prefOption("autocache_images_use_whitelist", false)
    private var whitelist by WePrefs.prefOption("autocache_images_whitelist", emptySet())
    private var blacklist by WePrefs.prefOption("autocache_images_blacklist", emptySet())

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type") ?: return
        if (type != MessageType.IMAGE.code) return

        // 自己发出的图片本身就在本地, 无需缓存
        if (values.getAsInteger("isSend") == 1) return

        val talker = values.getAsString("talker") ?: return

        if (useWhitelist) {
            if (talker !in whitelist) return
        } else {
            if (talker in blacklist) return
        }

        val msgSvrId = values.getAsLong("msgSvrId") ?: return
        if (msgSvrId == 0L) return

        WeLogger.i(TAG, "detected image message; msgSvrId=$msgSvrId, auto caching")
        CoroutineScope(Dispatchers.IO).launch {
            val path = WeMessageApi.cacheImage(msgSvrId)
            if (path != null) {
                WeLogger.i(TAG, "cached image to $path")
            } else {
                WeLogger.e(TAG, "failed to auto-cache image msgSvrId=$msgSvrId")
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var useWhitelistState by remember { mutableStateOf(useWhitelist) }

            AlertDialogContent(
                title = { Text("自动缓存图片") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable { useWhitelistState = !useWhitelistState },
                            trailingContent = { Switch(checked = useWhitelistState, onCheckedChange = null) },
                            supportingContent = { Text(if (useWhitelistState) "仅对选中联系人缓存图片" else "对选中联系人跳过缓存图片") },
                            headlineContent = { Text(if (useWhitelistState) "黑名单 [> 白名单 <]" else "[> 黑名单 <] 白名单") },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
                                val currentList = if (useWhitelistState) whitelist else blacklist

                                showComposeDialog(context) {
                                    ContactsSelector(
                                        title = if (useWhitelistState) "选择白名单" else "选择黑名单",
                                        contacts = contacts,
                                        initialSelectedWxIds = currentList,
                                        onDismiss = onDismiss
                                    ) { selectedIds ->
                                        if (useWhitelistState) {
                                            whitelist = selectedIds
                                        } else {
                                            blacklist = selectedIds
                                        }
                                        showToast("已保存 ${selectedIds.size} 个联系人, 重启微信以使更改生效")
                                        onDismiss()
                                    }
                                }
                            },
                            supportingContent = { Text("点击选择联系人") },
                            headlineContent = { Text(if (useWhitelistState) "配置白名单" else "配置黑名单") },
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        useWhitelist = useWhitelistState
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }
}
