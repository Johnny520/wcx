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
import com.Johnny.wcx.features.core.ClickableFeature
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 「自动缓存文件」和「自动缓存图片」共用的骨架。
 *
 * 这两个功能只差三件事：**消息类型**、**要不要等对方上传完成**、**调哪个缓存 API**。
 * 其余部分（白/黑名单、去重、协程生命周期、设置界面）原本是两份逐字相同的拷贝，
 * 已经因此漂移过一次（文件版关闭时会取消下载，图片版不会）。放在这里只有一份。
 *
 * 子类需要提供各自的偏好键：`xxx_use_whitelist` / `xxx_whitelist` / `xxx_blacklist`。
 */
abstract class AutoCacheFeature : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    /** 平台消息类型，见 [com.Johnny.wcx.features.api.core.models.MessageType]。 */
    protected abstract val typeCode: Int

    /** 只用于文案与日志：「自动缓存**文件**」。 */
    protected abstract val label: String

    /** 日志 TAG。 */
    protected abstract val logTag: String

    protected abstract var useWhitelist: Boolean
    protected abstract var whitelist: Set<String>
    protected abstract var blacklist: Set<String>

    /**
     * 已处理过的 msgSvrId。
     *
     * **成功后保留**（同一条消息可能收到多次数据库回调，重复下载没有意义），
     * **失败时移除**（让后续回调能再试一次）。关闭功能时整个清空。
     */
    private val handled = ConcurrentHashMap.newKeySet<Long>()

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    final override fun onEnable() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        WeDatabaseListenerApi.addListener(this)
    }

    final override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope.cancel()
        handled.clear()
    }

    final override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        handleMessage(values)
    }

    /**
     * 通用过滤 + 去重 + 发起缓存。
     *
     * 子类可以在 `onUpdate` 里直接调它（文件消息要等对方上传完成后，更新事件才算就绪）。
     *
     * 数据库监听回调在主线程，这里只做取值与判断，真正的下载在 [launchCache] 的 IO 作用域里。
     */
    protected fun handleMessage(values: ContentValues) {
        val type = values.getAsInteger("type") ?: return
        if (type != typeCode) return

        // 自己发出的消息本身就在本地，无需缓存。
        if (values.getAsInteger("isSend") == 1) return

        val talker = values.getAsString("talker") ?: return
        val allowed = if (useWhitelist) talker in whitelist else talker !in blacklist
        if (!allowed) return

        val msgSvrId = values.getAsLong("msgSvrId") ?: return
        if (msgSvrId == 0L) return

        if (!shouldCache(values)) return
        if (!handled.add(msgSvrId)) return
        if (!cache(values, msgSvrId)) {
            // 同步阶段就失败了（例如文件消息解析不出来），允许后续事件重试。
            handled.remove(msgSvrId)
        }
    }

    /**
     * 在通用过滤之后调用：这条消息是否已经可以下载。
     *
     * 只给有中间态的类型用（文件消息刚到达时对方可能还在上传），图片不需要覆盖。
     */
    protected open fun shouldCache(values: ContentValues): Boolean = true

    /**
     * 发起缓存。
     *
     * @return 是否已受理。返回 false 时基类会清掉去重标记，让后续回调重试。
     */
    protected abstract fun cache(values: ContentValues, msgSvrId: Long): Boolean

    /**
     * 在功能自己的作用域里执行缓存，统一处理日志与失败重试。
     *
     * @param block 返回缓存到本地的路径；返回 null 表示微信侧下载失败。
     */
    protected fun launchCache(msgSvrId: Long, block: suspend () -> String?) {
        scope.launch {
            runCatching { block() }
                .onSuccess { path ->
                    if (path != null) {
                        WeLogger.d(logTag, "cached $label to $path")
                    } else {
                        WeLogger.w(logTag, "failed to auto-cache $label msgSvrId=$msgSvrId")
                        handled.remove(msgSvrId)
                    }
                }
                .onFailure { error ->
                    WeLogger.w(logTag, "failed to auto-cache $label msgSvrId=$msgSvrId", error)
                    handled.remove(msgSvrId)
                }
        }
    }

    final override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var useWhitelistState by remember { mutableStateOf(useWhitelist) }

            AlertDialogContent(
                title = { Text("自动缓存$label") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable { useWhitelistState = !useWhitelistState },
                            trailingContent = { Switch(checked = useWhitelistState, onCheckedChange = null) },
                            supportingContent = {
                                Text(
                                    if (useWhitelistState) "仅对选中联系人缓存$label"
                                    else "对选中联系人跳过缓存$label"
                                )
                            },
                            headlineContent = {
                                Text(if (useWhitelistState) "黑名单 [> 白名单 <]" else "[> 黑名单 <] 白名单")
                            },
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
