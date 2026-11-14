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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Feature(name = "自动缓存图片", categories = ["聊天"], description = "监听接收到的图片消息, 自动触发微信从 CDN 下载, 将原图缓存到本地")
object AutoCacheImages : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "AutoCacheImages"

    private var useWhitelist by WePrefs.prefOption("autocache_images_use_whitelist", false)
    private var whitelist by WePrefs.prefOption("autocache_images_whitelist", emptySet())
    private var blacklist by WePrefs.prefOption("autocache_images_blacklist", emptySet())

    /** 正在缓存中的 msgSvrId，避免同一张图被重复触发下载。 */
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** 缓存任务作用域：随功能开关一起取消，避免关闭功能后仍有下载在跑。 */
    private var scope: CoroutineScope? = null

    /**
     * 全局并发闸门。
     *
     * 旧实现「一张图一个协程」无上限并发：Dispatchers.IO 最多 64 个线程，群相册、
     * 连发图片时几十个原图下载同时阻塞会把 IO 线程池占满 —— 微信自己的数据库写入、
     * 消息收取同样要 IO 线程，于是整个进程卡住（用户感知就是「突然卡死、划任务栏也卡、
     * 只能强杀」）。这里限制同时最多 2 个下载，其余排队。
     */
    private val downloadGate = Semaphore(2)

    override fun onEnable() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope?.cancel()
        scope = null
        inFlight.clear()
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

        // 高频 INFO 日志（每张图 2~3 条）会淹没真实报错，这里只留在 logcat，不落盘。
        WeLogger.d(TAG, "detected image message; msgSvrId=$msgSvrId, auto caching")
        // 同一条消息可能被数据库回调触发多次，去重避免重复触发原图下载。
        if (!inFlight.add(msgSvrId)) return
        val sc = scope
        if (sc == null) {
            inFlight.remove(msgSvrId)
            return
        }
        sc.launch {
            try {
                downloadGate.withPermit {
                    val path = WeMessageApi.cacheImage(msgSvrId)
                    if (path != null) {
                        WeLogger.d(TAG, "cached image to $path")
                    } else {
                        WeLogger.w(TAG, "failed to auto-cache image msgSvrId=$msgSvrId")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 后台协程未捕获异常会结束整个宿主进程，必须就地吃掉并留痕。
                WeLogger.w(TAG, "auto cache image failed msgSvrId=$msgSvrId: ${e.message}", e)
            } finally {
                inFlight.remove(msgSvrId)
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
