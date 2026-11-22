package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.beautify.WeChatQuickActions
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.baseActivity
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.android.showToastSuspend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 长按底部「微信」页签 → 未读会话面板 + 一键已读。
 *
 * 锚点策略（三重兜底，任何一层缺失都不影响其它功能）：
 * 1. 主路径：hook 微信自己的「主屏页签长按处理器」
 *    （8.0.80 = com.tencent.mm.ui.w8.onLongClick，日志串 "onMainTabLongClick"）。
 *    这样微信原生的长按行为**完全保留**，我们只是在它之后追加一个面板；
 * 2. 兜底 1：主路径锚点缺失时，hook LauncherUIBottomTabView.setTo(int)（每次切页签都会走），
 *    给「微信」页签 View 自己装 OnLongClickListener（会先调用微信原有的 listener）；
 * 3. 兜底 2：以上都拿不到时，功能只记日志不做事（绝不抛异常，也不占用其它功能的钩子）。
 */
@Feature(
    name = "长按微信页签看未读",
    categories = ["聊天"],
    description = "长按底部「微信」页签弹出未读会话列表，可点进去或一键全部已读"
)
object TabLongPressUnreadPanel : SwitchFeature(), IResolveDex {

    private const val TAG = "TabLongPressUnreadPanel"

    /** 面板同时只允许一个；长按会被连点多次触发。 */
    private val panelVisible = AtomicBoolean(false)

    /** 手抖重复长按的节流。 */
    @Volatile
    private var lastTriggerAt = 0L

    private var scope: CoroutineScope? = null

    /** 已装过长按监听的页签 View（弱引用，页签重建后自动失效，不会泄漏 Activity）。 */
    private val installedTabs: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    // ---------------------------------------------------------------- 锚点

    /**
     * 微信自己的「微信页签长按」处理器（8.0.80 = com.tencent.mm.ui.w8#onLongClick）。
     * 唯一性验证：全 APK 中同时含 "MicroMsg.LauncherUITabView" 与 "onMainTabLongClick" 的方法只有这一个。
     */
    private val methodMainTabLongClick by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.LauncherUITabView", "onMainTabLongClick")
            paramCount = 1
            paramTypes("android.view.View")
        }
    }

    /**
     * 兜底锚点：LauncherUIBottomTabView（8.0.80 类名未被混淆）。
     * 用「只在该类的 updateMainTabUnread 里出现的日志串」定位类，比硬编码类名更抗改版；
     * 拿不到时再退回固定类名。
     */
    private val classBottomTabView by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.LauncherUITabView", "[updateMainTabUnread] unread : ")
        }
    }

    // ------------------------------------------------------------ 生命周期

    override fun onEnable() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        if (!methodMainTabLongClick.isPlaceholder) {
            methodMainTabLongClick.hookAfter {
                val clicked = args.getOrNull(0) as? View ?: return@hookAfter
                if (!isMainTabView(clicked)) return@hookAfter
                handleMainTabLongPress(clicked)
            }
            WeLogger.i(TAG, "已挂载主路径：微信页签长按处理器")
            return
        }

        WeLogger.w(TAG, "主路径锚点缺失，尝试 setTo 兜底")
        installFallbackHook()
    }

    override fun onDisable() {
        installedTabs.clear()
        panelVisible.set(false)
        scope?.cancel()
        scope = null
    }

    // -------------------------------------------------------------- 页签判定

    /**
     * 判断被长按的是不是「微信」页签。
     *
     * 微信自己在 LauncherUIBottomTabView 里给每个页签 View 打了 tag（Integer 下标，微信页签 = 0），
     * 它自己的 onLongClick 就是靠 `getTag() as Int == 0` 区分主屏页签的（见 ANCHORS.md）。
     * tag 拿不到时退化为「父容器里的第 0 个子 View」。
     */
    private fun isMainTabView(view: View): Boolean {
        val tag = view.tag
        if (tag is Int) return tag == 0
        val parent = view.parent as? ViewGroup ?: return false
        return parent.indexOfChild(view) == 0
    }

    private fun handleMainTabLongPress(view: View) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < 600L) return
        lastTriggerAt = now

        val activity = view.context.baseActivity ?: WeChatQuickActions.resolveHost(null)
        if (activity == null || activity.isFinishing) {
            WeLogger.w(TAG, "长按微信页签时没有可用 Activity，跳过")
            return
        }
        showUnreadPanel(activity)
    }

    // --------------------------------------------------------------- 兜底

    private fun installFallbackHook() {
        val methodSetTo = runCatching {
            val clazz = if (classBottomTabView.isPlaceholder) {
                Class.forName("com.tencent.mm.ui.LauncherUIBottomTabView")
            } else {
                classBottomTabView.clazz
            }
            clazz.reflekt().firstMethodOrNull {
                // 8.0.80 实测：LauncherUIBottomTabView 只有一个 setTo 方法（setTo(I)V，见 ANCHORS.md），
                // 用「方法名 + 参数个数」定位足够精确，也不依赖 reflekt 对基本类型的匹配细节。
                name = "setTo"
                parameterCount = 1
            }
        }.onFailure {
            WeLogger.w(TAG, "定位 LauncherUIBottomTabView.setTo 失败", it)
        }.getOrNull()

        if (methodSetTo == null) {
            WeLogger.w(TAG, "兜底锚点也不可用：本版本不提供长按未读面板")
            return
        }

        methodSetTo.hookAfter {
            val root = thisObject as? ViewGroup ?: return@hookAfter
            runCatching { installListenerOnMainTab(root) }
                .onFailure { WeLogger.w(TAG, "安装页签长按监听失败", it) }
        }
        WeLogger.i(TAG, "已挂载兜底路径：LauncherUIBottomTabView.setTo")
    }

    private fun installListenerOnMainTab(root: ViewGroup) {
        val bar = (root.getChildAt(0) as? ViewGroup) ?: root
        val tab = bar.getChildAt(0) ?: return
        if (!installedTabs.add(tab)) return

        // 微信原有长按监听（8.0.80 = com.tencent.mm.ui.w8，公用 OnLongClickListener 接口）要保留：
        // 直接覆盖会把微信自己的长按行为弄丢。
        val previous = runCatching {
            View::class.java.getDeclaredMethod("getOnLongClickListener")
                .apply { isAccessible = true }
                .invoke(tab) as? View.OnLongClickListener
        }.getOrNull()

        tab.isLongClickable = true
        tab.setOnLongClickListener { v ->
            runCatching { previous?.onLongClick(v) }
                .onFailure { WeLogger.w(TAG, "微信原生长按监听执行失败", it) }
            handleMainTabLongPress(v)
            true
        }
    }

    // ---------------------------------------------------------------- 面板

    private data class UnreadConv(
        val username: String,
        val title: String,
        val subtitle: String,
        val unread: Int
    )

    private fun showUnreadPanel(activity: Activity) {
        if (!panelVisible.compareAndSet(false, true)) return
        val sc = scope
        if (sc == null) {
            panelVisible.set(false)
            showToast("功能尚未初始化，请重启微信后重试")
            return
        }

        sc.launch {
            val rows = withContext(Dispatchers.IO) {
                runCatching { loadUnreadConversations() }
                    .onFailure { WeLogger.e(TAG, "读取未读会话失败", it) }
                    .getOrDefault(emptyList())
            }
            withContext(Dispatchers.Main) {
                showComposeDialog(activity) {
                    DisposableEffect(Unit) {
                        onDispose { panelVisible.set(false) }
                    }
                    AlertDialogContent(
                        title = { Text("未读会话（${rows.size}）") },
                        text = {
                            if (rows.isEmpty()) {
                                Text("当前没有未读会话")
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 400.dp)
                                ) {
                                    items(rows, key = { it.username }) { row ->
                                        ListItem(
                                            modifier = Modifier.clickable {
                                                onDismiss()
                                                openChat(activity, row.username)
                                            },
                                            headlineContent = { Text(row.title) },
                                            supportingContent = { Text(row.subtitle) },
                                            trailingContent = { Text("${row.unread}") }
                                        )
                                    }
                                }
                            }
                        },
                        dismissButton = { TextButton(onDismiss) { Text("关闭") } },
                        confirmButton = {
                            if (rows.isNotEmpty()) {
                                Button(onClick = {
                                    onDismiss()
                                    markAllRead()
                                }) { Text("全部已读") }
                            }
                        }
                    )
                }
            }
        }
    }

    /** 读 rconversation（未读数 + 最后时间）并 join rcontact 取昵称/备注。IO 线程调用。 */
    private fun loadUnreadConversations(): List<UnreadConv> {
        val rows = WeDatabaseApi.executeQuery(
            """
            SELECT c.username AS username,
                   IFNULL(c.unReadCount, 0) AS unReadCount,
                   IFNULL(c.conversationTime, 0) AS conversationTime,
                   IFNULL(r.nickname, '') AS nickname,
                   IFNULL(r.conRemark, '') AS conRemark
            FROM rconversation c
            LEFT JOIN rcontact r ON r.username = c.username
            WHERE IFNULL(c.unReadCount, 0) > 0
            ORDER BY c.conversationTime DESC
            LIMIT 100
            """.trimIndent()
        )
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val result = ArrayList<UnreadConv>(rows.size)
        for (row in rows) {
            val username = row["username"]?.toString().orEmpty()
            if (username.isEmpty()) continue
            val nickname = row["nickname"]?.toString().orEmpty()
            val remark = row["conRemark"]?.toString().orEmpty()
            val unread = (row["unReadCount"] as? Number)?.toInt() ?: 0
            val timeSeconds = (row["conversationTime"] as? Number)?.toLong() ?: 0L
            val title = remark.ifBlank { nickname }.ifBlank { username }
            val time = if (timeSeconds > 0) {
                runCatching { formatter.format(Date(timeSeconds * 1000L)) }.getOrDefault("")
            } else {
                ""
            }
            val kind = when {
                username.endsWith("@chatroom") -> "群聊"
                username.startsWith("gh_") -> "公众号"
                else -> "单聊"
            }
            val subtitle = if (time.isEmpty()) kind else "$kind · $time"
            result += UnreadConv(username = username, title = title, subtitle = subtitle, unread = unread)
        }
        return result
    }

    private fun openChat(activity: Activity, username: String) {
        runCatching {
            WeApi.openContact(activity, username, WeApi.OpenContactDestination.CONVERSATION)
        }.onFailure {
            WeLogger.w(TAG, "打开会话失败：$username", it)
            showToast("打开会话失败：${it.javaClass.simpleName}")
        }
    }

    private fun markAllRead() {
        val sc = scope ?: return
        sc.launch {
            try {
                withContext(Dispatchers.IO) {
                    runCatching { WeConversationApi.markAllAsRead() }
                        .onFailure { WeLogger.w(TAG, "标记全部已读失败", it) }
                }
                withContext(Dispatchers.Main) {
                    runCatching { WeConversationApi.reloadConversations() }
                        .onFailure { WeLogger.w(TAG, "刷新会话列表失败", it) }
                }
                showToastSuspend("已全部标为已读")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                WeLogger.e(TAG, "一键已读失败", e)
                showToastSuspend("一键已读失败：${e.javaClass.simpleName}")
            }
        }
    }
}
