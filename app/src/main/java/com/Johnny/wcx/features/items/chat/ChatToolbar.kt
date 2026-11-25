package com.Johnny.wcx.features.items.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.children
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Account_box
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Arrow_drop_down
import com.composables.icons.materialsymbols.outlined.Attach_file
import com.composables.icons.materialsymbols.outlined.Attach_money
import com.composables.icons.materialsymbols.outlined.Camera
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Drag_handle
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Favorite
import com.composables.icons.materialsymbols.outlined.Format_list_numbered
import com.composables.icons.materialsymbols.outlined.Location_on
import com.composables.icons.materialsymbols.outlined.Mail
import com.composables.icons.materialsymbols.outlined.Mic
import com.composables.icons.materialsymbols.outlined.Music_note
import com.composables.icons.materialsymbols.outlined.Photo_library
import com.composables.icons.materialsymbols.outlined.Redeem
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Video_chat
import com.composables.icons.materialsymbols.outlined.Voice_chat
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.InjectedUiTheme
import com.Johnny.wcx.ui.utils.LifecycleOwnerProvider
import com.Johnny.wcx.ui.utils.findViewByChildIndexes
import com.Johnny.wcx.ui.utils.findViewWhich
import com.Johnny.wcx.ui.utils.iterable
import com.Johnny.wcx.ui.utils.setLifecycleOwner
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.android.getTopMostActivity
import com.Johnny.wcx.utils.now
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private enum class ToolbarDisplayMode(val preferenceValue: String, val label: String) {
    ICON_AND_TEXT("icon_and_text", "图标+文字"),
    ICON_ONLY("icon_only", "仅图标"),
    TEXT_ONLY("text_only", "仅文字");

    companion object {
        fun fromPreference(value: String): ToolbarDisplayMode =
            entries.firstOrNull { it.preferenceValue == value } ?: ICON_AND_TEXT
    }
}

@SuppressLint("StaticFieldLeak")
@Feature(name = "聊天工具栏", categories = ["聊天"], description = "在输入框上方添加工具栏")
object ChatToolbar : ClickableFeature(), IResolveDex {

    private const val TAG = "ChatToolbar"

    /** 我们注入的那层 ComposeView 的标记：判断「有没有注入过」只认它。 */
    private const val TOOLBAR_TAG = "WCX_CHAT_TOOLBAR"

    private val NAME_TO_ICON_MAP = mapOf(
        "相册" to MaterialSymbols.Outlined.Photo_library,
        "拍摄" to MaterialSymbols.Outlined.Camera,
        "系统拍摄" to MaterialSymbols.Outlined.Camera,
        "视频通话" to MaterialSymbols.Outlined.Video_chat,
        "语音通话" to MaterialSymbols.Outlined.Voice_chat,
        "位置" to MaterialSymbols.Outlined.Location_on,
        "红包" to MaterialSymbols.Outlined.Mail,
        "礼物" to MaterialSymbols.Outlined.Redeem,
        "转账" to MaterialSymbols.Outlined.Attach_money,
        "语音输入" to MaterialSymbols.Outlined.Mic,
        "收藏" to MaterialSymbols.Outlined.Favorite,
        "接龙" to MaterialSymbols.Outlined.Format_list_numbered,
        "文件" to MaterialSymbols.Outlined.Attach_file,
        "个人名片" to MaterialSymbols.Outlined.Account_box,
        "音乐" to MaterialSymbols.Outlined.Music_note
    )

    // 快捷回复 is a wekit-injected item (not backed by a WeChat grid tool), so it lives
    // outside NAME_TO_ICON_MAP. Its icon is resolved via iconFor().
    private const val QUICK_REPLY_NAME = "快捷回复"

    private val methodAppPanelInitAppGrid by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.pluginsdk.ui.chat.AppPanel"
            usingEqStrings("MicroMsg.AppPanel", "initAppGrid()")
        }
    }
    private val methodAppPanelOnMeasure by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings(
                "MicroMsg.AppPanel",
                "onMeasure width: %d, heigth:%d, isMeasured:%b, gridWidth:%d, gridHeight:%d"
            )
        }
    }

    /**
     * 一个原生工具项。
     *
     * 8.0.80 实测：只调 `GridView.performItemClick` 不会触发微信的处理（adapter 层没有走
     * ItemClick 分发），所以点击做成多级兜底，并且每级都留日志 —— 用户报「点了没反应」时
     * 能直接看出走的是哪条路径、卡在哪一级：
     * ① AdapterView 私有字段 `mOnItemClickListener`（系统类字段，不会被混淆）→ 直调
     * ② `GridView.performItemClick`
     * ③ 真实挂载 child 的 `performClick`
     * ④ 采集时那份 item view 的 `performClick`
     * ⑤ 给真实 child 派发一对 DOWN/UP（和手指点完全同一条路径）
     */
    private data class MenuItem(
        val name: String,
        val gridView: WeakReference<GridView>,
        val itemView: WeakReference<View>,
        val indexInGrid: Int,
    ) {
        fun click() {
            val grid = gridView.get() ?: return fail("原生面板已重建，请重新展开工具面板再点")
            val item = itemView.get() ?: return fail("工具项 View 已失效，请重新展开工具面板再点")
            val position = indexInGrid
            val id = runCatching { grid.adapter?.getItemId(position) }.getOrNull() ?: position.toLong()

            // ① 系统类字段里的 OnItemClickListener
            val listener = runCatching {
                AdapterView::class.java
                    .getDeclaredField("mOnItemClickListener")
                    .apply { isAccessible = true }
                    .get(grid) as? AdapterView.OnItemClickListener
            }.getOrNull()
            if (listener != null) {
                val ok = runCatching { listener.onItemClick(grid, item, position, id) }
                    .onFailure { WeLogger.w(TAG, "mOnItemClickListener 调用异常：$name", it) }
                    .isSuccess
                if (ok) {
                    WeLogger.i(TAG, "tap '$name' via mOnItemClickListener")
                    return
                }
            }

            // ② GridView.performItemClick
            if (runCatching { grid.performItemClick(item, position, id) }.getOrDefault(false)) {
                WeLogger.i(TAG, "tap '$name' via performItemClick")
                return
            }

            // ③ 真实挂载的 child
            val liveIndex = position - grid.firstVisiblePosition
            val live = if (liveIndex in 0 until grid.childCount) grid.getChildAt(liveIndex) else null
            if (live != null && runCatching { live.performClick() }.getOrDefault(false)) {
                WeLogger.i(TAG, "tap '$name' via live performClick")
                return
            }

            // ④ 给 GridView 派发一对 DOWN/UP（坐标换算到格子中心）：和手指点完全一致，
            //    微信无论用 OnItemClickListener 还是自己的触摸处理都能吃下
            if (dispatchTapToGrid(grid, position)) {
                WeLogger.i(TAG, "tap '$name' via grid synthetic touch")
                return
            }

            // ⑤ 采集时的 item view
            if (runCatching { item.performClick() }.getOrDefault(false)) {
                WeLogger.i(TAG, "tap '$name' via item performClick")
                return
            }

            // ⑥ 直接给某个 View 派发触摸
            if (dispatchTap(live ?: item)) {
                WeLogger.i(TAG, "tap '$name' via view synthetic touch")
                return
            }

            fail("五种点击方式都没触发微信的处理")
        }

        fun longClick() {
            val grid = gridView.get()
            val item = itemView.get() ?: return fail("工具项 View 已失效，请重新展开工具面板再长按")

            val listener = runCatching {
                AdapterView::class.java
                    .getDeclaredField("mOnItemLongClickListener")
                    .apply { isAccessible = true }
                    .get(grid) as? AdapterView.OnItemLongClickListener
            }.getOrNull()
            if (listener != null && grid != null) {
                val ok = runCatching {
                    listener.onItemLongClick(grid, item, indexInGrid, indexInGrid.toLong())
                }.onFailure { WeLogger.w(TAG, "mOnItemLongClickListener 调用异常：$name", it) }.isSuccess
                if (ok) {
                    WeLogger.i(TAG, "long-press '$name' via mOnItemLongClickListener")
                    return
                }
            }
            if (runCatching { item.performLongClick() }.getOrDefault(false)) {
                WeLogger.i(TAG, "long-press '$name' via item performLongClick")
                return
            }
            fail("长按没触发微信的处理")
        }

        /** 给 GridView 派发一对 DOWN/UP（坐标换成该格子的中心点），等价于手指点一下。 */
        private fun dispatchTapToGrid(grid: GridView, position: Int): Boolean = runCatching {
            val liveIndex = position - grid.firstVisiblePosition
            val child = if (liveIndex in 0 until grid.childCount) grid.getChildAt(liveIndex) else null
                ?: return@runCatching false
            val childLoc = IntArray(2)
            val gridLoc = IntArray(2)
            child.getLocationInWindow(childLoc)
            grid.getLocationInWindow(gridLoc)
            val x = (childLoc[0] - gridLoc[0]) + child.width / 2f
            val y = (childLoc[1] - gridLoc[1]) + child.height / 2f
            if (x <= 0f || y <= 0f) return@runCatching false
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(downTime, downTime + 80, MotionEvent.ACTION_UP, x, y, 0)
            grid.dispatchTouchEvent(down)
            val handled = grid.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
            handled
        }.getOrDefault(false)

        /** 给真实挂载的 View 派发一对 DOWN/UP，等价于手指点一下。 */
        private fun dispatchTap(target: View): Boolean = runCatching {
            if (target.width <= 0 || target.height <= 0) return@runCatching false
            val downTime = SystemClock.uptimeMillis()
            val x = target.width / 2f
            val y = target.height / 2f
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(downTime, downTime + 60, MotionEvent.ACTION_UP, x, y, 0)
            val handled = target.dispatchTouchEvent(down) and target.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
            handled
        }.getOrDefault(false)

        private fun fail(reason: String) {
            WeLogger.w(TAG, "触发工具项失败：$name（$reason）")
            healthIssue = "工具项「$name」点击无效：$reason"
            runCatching { showToast("聊天工具栏：$name 点击无效，请重新展开工具面板后重试") }
        }
    }

    private data class QuickReplyDraft(
        val id: String = UUID.randomUUID().toString(),
        val text: String,
    )

    private val toolsState = MutableStateFlow<List<Pair<String, MenuItem>>>(emptyList())

    /**
     * 自检回执：注入/采集环节的**实际结果**。null = 正常，或还没进过聊天页（无法判断）。
     *
     * Hook 装上 ≠ 界面生效。工具栏是「注入型」功能，最容易出现「开关开着、日志里 hook 也装了，
     * 但聊天页什么都没有」，所以这里把注入/采集的真实结论回报给模块自检。
     */
    @Volatile
    private var healthIssue: String? = null

    override fun runtimeIssue(): String? = if (isEnabled) healthIssue else null

    private var itemsOrder by WePrefs.prefOption("chat_toolbar_order", NAME_TO_ICON_MAP.keys.joinToString(","))
    private var enabledItems by WePrefs.prefOption("chat_toolbar_enabled_items", NAME_TO_ICON_MAP.keys)
    private var displayModeValue by WePrefs.prefOption(
        "chat_toolbar_display_mode",
        ToolbarDisplayMode.ICON_AND_TEXT.preferenceValue,
    )

    // quick replies are stored as a JSON string array so individual replies may safely
    // contain commas, newlines or any other character
    private var quickRepliesRaw by WePrefs.prefOption("chat_toolbar_quick_replies", "")

    private val quickRepliesSerializer = ListSerializer(String.serializer())

    private fun loadQuickReplies(): List<String> {
        val raw = quickRepliesRaw
        if (raw.isEmpty()) return emptyList()
        return runCatching { Json.decodeFromString(quickRepliesSerializer, raw) }
            .getOrElse {
                WeLogger.w(TAG, "failed to parse quick replies, resetting: ${it.message}")
                emptyList()
            }
    }

    private fun saveQuickReplies(replies: List<String>) {
        quickRepliesRaw = Json.encodeToString(quickRepliesSerializer, replies)
    }

    private fun iconFor(name: String): ImageVector =
        if (name == QUICK_REPLY_NAME) MaterialSymbols.Outlined.Chat else NAME_TO_ICON_MAP.getValue(name)

    // Ensures every supported item is present while preserving the user's saved order. Legacy
    // configs that predate quick replies get that item inserted first.
    private fun normalizeOrder(order: List<String>): List<String> {
        val supportedItems = setOf(QUICK_REPLY_NAME) + NAME_TO_ICON_MAP.keys
        val result = order.filter { it in supportedItems }.distinct().toMutableList()
        if (QUICK_REPLY_NAME !in result) result.add(0, QUICK_REPLY_NAME)
        NAME_TO_ICON_MAP.keys.forEach { if (it !in result) result.add(it) }
        return result
    }

    private fun insertQuickReply(text: String) {
        WeMessageApi.sendText(WeCurrentConversationApi.value, text)
    }

    private var lastToolListUpdateTime = now()

    /** 补注入的限流：AppPanel 初始化会频繁触发，没必要每次都走一遍 View 树。 */
    private var lastInjectAttemptTime = now()

    override fun onEnable() {
        try {
            registerHooks()
            // footer 复用 / 开关在聊天页里打开时，构造器不会再跑，靠这个回调补注入。
            ChatFooterHooks.addFooterListener(footerListener)
            // 从设置页打开开关再退回会话时，footer 早已初始化；这里主动找一次。
            scheduleSelfHeal()
        } catch (t: Throwable) {
            // 绝不静默：工具栏不显示时必须能从日志看出是「安装失败」还是「注入被跳过」
            WeLogger.e(TAG, "聊天工具栏安装失败（工具栏将不显示）：${t.message}", t)
        }
    }

    /**
     * 开启后的自愈窗口：每 1.5 秒在当前页面树里找一次 ChatFooter，最多 15 秒。
     *
     * 场景是「在设置页打开开关 → 按返回回到刚才的会话」：那个 footer 早就构造完了，
     * 构造器不会再跑；AppPanel 也可能因为面板已初始化而不再触发。找不到就静默退出，
     * 不常驻、不轮询。
     */
    private fun scheduleSelfHeal() {
        val handler = Handler(Looper.getMainLooper())
        val started = SystemClock.uptimeMillis()
        handler.postDelayed(object : Runnable {
            override fun run() {
                val injected = runCatching { injectIntoVisibleChat() }.getOrDefault(false)
                if (injected) return
                if (SystemClock.uptimeMillis() - started < 15_000L) handler.postDelayed(this, 1_500L)
            }
        }, 800L)
    }

    private fun injectIntoVisibleChat(): Boolean {
        val activity = getTopMostActivity() ?: return false
        val decor = activity.window?.decorView as? ViewGroup ?: return false
        val footer = findChatFooter(decor) ?: return false
        injectToolbar(footer)
        return true
    }

    /** 广度优先找页面里的 ChatFooter，限层数与节点数，避免在大页面上扫太久。 */
    private fun findChatFooter(root: ViewGroup): ChatFooter? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 400) {
            val view = queue.removeFirst()
            if (view is ChatFooter) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) queue.add(view.getChildAt(index))
            }
        }
        return null
    }

    private val footerListener: (ChatFooter) -> Unit = { footer ->
        runCatching { injectToolbar(footer) }
            .onFailure {
                healthIssue = "工具栏注入异常：${it.message}"
                WeLogger.e(TAG, "工具栏注入异常（工具栏将不显示）：${it.message}", it)
            }
    }

    /** 递归收集面板内所有 GridView：微信 8.x 把工具格放在自定义 MMFlipper 里，层级会随版本变。 */
    private fun collectGrids(root: View): List<GridView> {
        val out = mutableListOf<GridView>()

        fun walk(v: View) {
            if (v is GridView) {
                out.add(v)
                return
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }

        walk(root)
        return out
    }

    /**
     * 采集原生工具项，填充 [toolsState]。返回采到的条数。
     *
     * 从任意容器出发（AppPanel 或 ChatFooter 本身）都用同一套：递归找 GridView、
     * 枚举 adapter。注入时先采一次，避免「已注入但工具列表为空 → 行高 0 → 看起来没生效」。
     */
    private fun collectTools(root: View): Int {
        val tools = mutableListOf<Pair<String, MenuItem>>()
        collectGrids(root).forEach { grid ->
            val adapter = grid.adapter ?: return@forEach
            for (index in 0 until adapter.count) {
                val itemView = runCatching { adapter.getView(index, null, grid) }.getOrNull()
                    ?: continue
                val name = toolNameOf(itemView) ?: continue
                tools.add(name to MenuItem(name, WeakReference(grid), WeakReference(itemView), index))
            }
        }
        WeLogger.i(TAG, "采集到 ${tools.size} 个原生工具项")
        toolsState.value = tools
        return tools.size
    }

    /** 工具项显示名：tag 里挂着持有名字 TextView 的对象；tag 结构变了就退回在 item 里直接找 TextView。 */
    private fun toolNameOf(itemView: View): String? {
        val tag = itemView.tag
        val byTag = if (tag != null) {
            runCatching { tag.reflekt().firstField { type = TextView::class }.get() as? TextView }
                .getOrNull()?.text?.toString()?.takeIf { it.isNotBlank() }
        } else null
        if (byTag != null) return byTag
        return runCatching {
            (itemView as ViewGroup).findViewWhich<TextView> { v -> (v as? TextView)?.text?.isNotBlank() == true }?.text?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 打印 View 层级（诊断「注入容器找不到」用，只在失败时调用）。 */
    private fun describeChildren(root: ViewGroup): String {
        val sb = StringBuilder()

        fun walk(v: ViewGroup, level: Int) {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i)
                sb.append('\n').append("  ".repeat(level)).append('[').append(i).append("] ")
                    .append(child.javaClass.simpleName)
                if (child is ViewGroup && level < 2) walk(child, level + 1)
            }
        }

        walk(root, 0)
        return sb.toString()
    }

    /** 真正的 hook 安装（失败要留痕，否则「工具栏失效」无从定位）。 */
    private fun registerHooks() {
        methodAppPanelInitAppGrid.apply {
            hookBefore {
                // 尺寸预估只是为了让微信按真实高度排版；失败也必须吞掉，
                // 否则 hookBefore 抛异常会让用户感觉「点了没反应」，且不好定位。
                runCatching {
                val appPanel = args[0] as LinearLayout
                // WeChat normally lets MMFlipper.onMeasure feed the real measured size into the
                // measurer (g.a). We have to invoke initAppGrid before the panel is laid out, so we
                // reproduce WeChat's own natural dimensions instead of hardcoding pixels.
                //   width  = screen width (initAppGrid derives column count as gridWidth / dp(82))
                //   height = the MMFlipper height. initAppGrid spreads any height left over after
                //            the icon rows into grid spacing/top-padding, so overshooting here shows
                //            up as extra padding at the bottom of the panel.
                // The panel's port height is NOT a fixed 215dp: getPortHeightPX() returns a value
                // set to match the soft-keyboard height (setPortHeighPx), which is device/IME
                // dependent. The container LinearLayout (a1r, child path 0,0) already has that
                // resolved height in its layoutParams (set in AppPanel.y()), so read it at runtime
                // and only fall back to the 215dp portrait / 158dp landscape default. The flipper
                // is that container minus the MMDotView strip below it (6dp dot + 16dp paddingBottom
                // = 22dp, see layout hy.xml), which is fixed in dp.
                val metrics = appPanel.resources.displayMetrics
                val width = metrics.widthPixels
                val fallbackDp = if (metrics.widthPixels < metrics.heightPixels) 215 else 158
                val containerHeight = appPanel.findViewByChildIndexes<View>(0, 0)
                    ?.layoutParams?.height?.takeIf { it > 0 }
                    ?: (fallbackDp * metrics.density).toInt()
                val dotStrip = (22 * metrics.density).toInt()
                val height = (containerHeight - dotStrip).coerceAtLeast(1)
                val measurer = methodAppPanelOnMeasure.method.declaringClass.createInstance(appPanel)
                methodAppPanelOnMeasure.method.invoke(measurer, width, height)
                }.onFailure { WeLogger.w(TAG, "AppPanel 尺寸预估失败（不影响工具栏显示）：${it.message}") }
            }

            hookAfter {
                // 补注入放在限流之前：ChatFooter 会被复用，只靠构造器注入的话，
                // 「在聊天页里打开开关」与「切回已初始化过的会话」都拿不到工具栏。
                // AppPanel 就在 footer 内部，往上找到 footer 补一次，幂等由 tag 保证。
                runCatching { ensureInjectedFromPanel(args[0] as? View) }
                    .onFailure { WeLogger.w(TAG, "补注入失败（不影响已有工具栏）：${it.message}") }

                val now = now()
                if (now - lastToolListUpdateTime < 2.seconds) return@hookAfter

                // 采集失败必须留痕：工具栏「什么都不显示」时要能从日志看出是没找到 GridView、
                // adapter 拿不到项，还是取不到工具名——而不是一律静默成空白。
                runCatching {
                    val appPanel = args[0] as? View ?: return@runCatching
                    collectTools(appPanel)
                    healthIssue = if (toolsState.value.isEmpty()) {
                        "没采集到任何原生工具项（AppPanel 结构变化），工具栏是空的"
                    } else null
                }.onFailure {
                    healthIssue = "采集原生工具项失败：${it.message}（工具栏是空的）"
                    WeLogger.e(TAG, "采集聊天工具项失败（工具栏可能为空）：${it.message}", it)
                }

                // rate limit this since this method is called REALLY frequently
                lastToolListUpdateTime = now()
            }
        }

        // ChatFooter 只有 (Context, AttributeSet) / (Context, AttributeSet, int) 两个构造器，
        // 没有无参构造器。reflekt 的 constructor() 扩展按默认条件匹配不到时会抛
        // "No constructor matching conditions"，整个 hook 安装直接失败、工具栏不显示
        //（自检页「功能回执」已能捕获该错误）。这里显式接管全部构造器，不再依赖条件匹配。
        var hookedConstructors = 0
        ChatFooter::class.java.declaredConstructors.forEach { ctor ->
            ctor.hookAfter {
                val chatFooter = thisObject as? FrameLayout ?: return@hookAfter
                runCatching { injectToolbar(chatFooter) }
                    .onFailure {
                        healthIssue = "工具栏注入异常：${it.message}"
                        WeLogger.e(TAG, "工具栏注入异常（工具栏将不显示）：${it.message}", it)
                    }
            }
            hookedConstructors++
        }
        if (hookedConstructors == 0) {
            error("ChatFooter 没有可 Hook 的构造器（微信类结构变化），工具栏不会显示")
        }
        WeLogger.i(TAG, "聊天工具栏 hook 已安装（$hookedConstructors 个构造器，聊天页创建时注入）")
    }

    /**
     * 往 ChatFooter 里注入工具栏，**可重复调用**。
     *
     * 两条入口：构造器（新会话）与 AppPanel 初始化（footer 被复用、开关在会话里打开）。
     * 幂等靠 [TOOLBAR_TAG]：不能用「容器里有 ComposeView」判断，微信自己也可能在容器里放
     * ComposeView，那样会把我们的注入当成已存在而静默跳过。
     */
    private fun injectToolbar(chatFooter: FrameLayout) {
        val activity = chatFooter.context.findActivity() ?: run {
            healthIssue = "聊天页取不到 Activity，工具栏没有显示"
            WeLogger.w(TAG, "取不到 Activity：context=${chatFooter.context.javaClass.name}")
            return
        }
        val lifecycleOwner = LifecycleOwnerProvider.getOrCreate(activity)
        chatFooter.setLifecycleOwner(lifecycleOwner)

        // 主路径：ChatFooter 第 0 个子节点的第 1 层容器；失败再退化到「直接子节点里的第一个 LinearLayout」。
        val linearLayout = chatFooter.findViewByChildIndexes<LinearLayout>(0, 1)
            ?: (0 until chatFooter.childCount).firstNotNullOfOrNull { chatFooter.getChildAt(it) as? LinearLayout }
        if (linearLayout == null) {
            // 微信调整了 ChatFooter 内部层级：跳过本次注入（不再 NPE 中断整个 hook），并打印层级便于适配
            healthIssue = "聊天页找不到注入容器（ChatFooter 层级变化），工具栏没有显示"
            WeLogger.w(
                TAG,
                "ChatFooter 层级变化，找不到注入容器（子节点数=${chatFooter.childCount}）：${describeChildren(chatFooter)}"
            )
            return
        }
        linearLayout.setLifecycleOwner(lifecycleOwner)
        if (linearLayout.findViewWhich<View> { it.tag == TOOLBAR_TAG } != null) return
        activity.window.decorView.setLifecycleOwner(lifecycleOwner)

        linearLayout.addView(ComposeView(activity).apply {
            tag = TOOLBAR_TAG
            setLifecycleOwner(lifecycleOwner)

            setContent {
                InjectedUiTheme {
                    val tools by toolsState.collectAsStateWithLifecycle()
                    val itemsOrder = remember { itemsOrder }
                    val enabledItems = remember { enabledItems }
                    val displayMode = remember { ToolbarDisplayMode.fromPreference(displayModeValue) }

                    val sortedVisibleItems = remember(tools) {
                        if (tools.isEmpty()) return@remember emptyList()

                        val firstTool = tools[0].second
                        val orderList = normalizeOrder(itemsOrder.split(",").filter { it.isNotEmpty() })
                        val list = mutableListOf<Pair<String, () -> Unit>>()

                        list.add(QUICK_REPLY_NAME to {
                            showQuickReplyPicker(activity)
                        })

                        list.add("相册" to { firstTool.click() })
                        list.add("系统拍摄" to { firstTool.longClick() })

                        tools.forEach { (name, menuItem) ->
                            if (name in NAME_TO_ICON_MAP && name != "相册" && name != "系统拍摄") {
                                list.add(name to { menuItem.click() })
                            }
                        }

                        list.distinctBy { it.first }
                            .filter { it.first in enabledItems }
                            .sortedBy { item ->
                                val idx = orderList.indexOf(item.first)
                                if (idx == -1) Int.MAX_VALUE else idx
                            }
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        items(sortedVisibleItems, key = { it.first }) { (name, onClick) ->
                            val icon = iconFor(name)
                            FeatureChip(name, icon, displayMode, onClick)
                        }
                    }
                }
            }
        }, 0)
        // 注入完立刻采一次：工具列表为空时 LazyRow 高度是 0，用户会以为注入失败。
        // 采集失败不影响注入本身，交给 AppPanel 回调重试。
        if (toolsState.value.isEmpty()) {
            runCatching { collectTools(chatFooter) }
        }
        // 注入成功：采集结果由 AppPanel 的 hookAfter 决定（那里会把 0 项也回报上来）
        if (toolsState.value.isEmpty()) {
            healthIssue = "工具栏已注入聊天页，但当前没采集到原生工具项（AppPanel 结构变化）"
        } else {
            healthIssue = null
        }
        WeLogger.i(TAG, "聊天工具栏已注入聊天页（容器子 View 数=${linearLayout.childCount}）")
    }

    /**
     * 从 AppPanel 往上找 ChatFooter 并补一次注入。
     *
     * 微信会复用 ChatFooter：同一个 ChattingUI 里切换会话不会再走构造器，
     * 开关又是在设置页打开的（那一刻页面上没有 footer）。只靠构造器注入，
     * 表现就是「开关开着、日志里 hook 也装了，但当前会话什么都没有」。
     */
    private fun ensureInjectedFromPanel(panel: View?) {
        // 1 秒一次：兜底路径，不需要比这更灵敏；真正的入口仍是构造器。
        val now = now()
        if (now - lastInjectAttemptTime < 1.seconds) return
        lastInjectAttemptTime = now

        var view: View? = panel
        var guard = 0
        while (view != null && guard++ < 16) {
            if (view is ChatFooter) {
                injectToolbar(view)
                return
            }
            view = view.parent as? View
        }
    }

    /** Context 可能被包了一层（ContextThemeWrapper），逐层解包到 Activity。 */
    private fun Context.findActivity(): Activity? {
        var context: Context? = this
        var guard = 0
        while (context != null && guard++ < 8) {
            if (context is Activity) return context
            context = (context as? ContextWrapper)?.baseContext
        }
        return null
    }

    override fun onDisable() {
        ChatFooterHooks.removeFooterListener(footerListener)
        toolsState.value = emptyList()
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            val currentOrder = remember {
                normalizeOrder(itemsOrder.split(",").filter { it.isNotEmpty() }).toMutableStateList()
            }
            val currentEnabled = remember { enabledItems.toMutableStateList() }
            var currentDisplayMode by remember {
                mutableStateOf(ToolbarDisplayMode.fromPreference(displayModeValue))
            }
            var displayModeMenuExpanded by remember { mutableStateOf(false) }

            AlertDialogContent(
                modifier = Modifier.fillMaxWidth(),
                title = { Text("聊天工具栏") },
                text = {
                    DefaultColumn {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            ListItem(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { displayModeMenuExpanded = true },
                                headlineContent = { Text("显示样式") },
                                supportingContent = { Text(currentDisplayMode.label) },
                                trailingContent = {
                                    Icon(
                                        MaterialSymbols.Outlined.Arrow_drop_down,
                                        contentDescription = "选择显示样式",
                                    )
                                },
                            )
                            DropdownMenu(
                                expanded = displayModeMenuExpanded,
                                onDismissRequest = { displayModeMenuExpanded = false },
                            ) {
                                ToolbarDisplayMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.label) },
                                        trailingIcon = if (mode == currentDisplayMode) ({
                                            Icon(
                                                MaterialSymbols.Outlined.Check,
                                                contentDescription = null,
                                            )
                                        }) else null,
                                        onClick = {
                                            currentDisplayMode = mode
                                            displayModeMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        Column {
                            Text("显示与顺序", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "长按拖动手柄调整顺序，使用开关控制是否显示",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ReorderableList(
                            items = currentOrder,
                            itemKey = { it },
                            onMove = { from, to ->
                                currentOrder.add(to, currentOrder.removeAt(from))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 480.dp),
                        ) { name, dragHandleModifier ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 60.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .then(dragHandleModifier),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        MaterialSymbols.Outlined.Drag_handle,
                                        contentDescription = "拖动 $name",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Box(
                                    modifier = Modifier.size(36.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        iconFor(name),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(
                                    text = name,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (name == QUICK_REPLY_NAME) {
                                    IconButton(onClick = { showQuickReplyConfig(context) }) {
                                        Icon(
                                            MaterialSymbols.Outlined.Settings,
                                            contentDescription = "配置快捷回复",
                                        )
                                    }
                                }
                                Switch(
                                    checked = name in currentEnabled,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            if (name !in currentEnabled) currentEnabled.add(name)
                                        } else {
                                            currentEnabled.remove(name)
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        itemsOrder = currentOrder.joinToString(",")
                        enabledItems = currentEnabled.toSet()
                        displayModeValue = currentDisplayMode.preferenceValue
                        onDismiss()
                    }) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                }
            )
        }
    }

    // shown when the user taps the 快捷回复 chip in the chat toolbar: pick a reply to insert
    private fun showQuickReplyPicker(context: Context) {
        showComposeDialog(context) {
            val replies = remember { loadQuickReplies() }

            AlertDialogContent(
                modifier = Modifier.fillMaxWidth(),
                title = { Text(QUICK_REPLY_NAME) },
                text = {
                    if (replies.isEmpty()) {
                        Text("暂无快捷回复, 请在「聊天工具栏」设置中配置")
                    } else {
                        LazyColumn {
                            items(replies) { reply ->
                                ListItem(
                                    modifier = Modifier.clickable {
                                        insertQuickReply(reply)
                                        onDismiss()
                                    },
                                    headlineContent = { Text(reply) },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            )
        }
    }

    private fun showQuickReplyEditor(
        context: Context,
        title: String,
        initialValue: String = "",
        onSave: (String) -> Unit,
    ) {
        showComposeDialog(context) {
            var value by remember { mutableStateOf(initialValue) }

            AlertDialogContent(
                title = { Text(title) },
                text = {
                    TextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("输入回复内容") },
                        minLines = 3,
                        maxLines = 8,
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(
                        onClick = {
                            onSave(value.trim())
                            onDismiss()
                        },
                        enabled = value.isNotBlank(),
                    ) { Text("保存") }
                },
            )
        }
    }

    // Shown from the settings button in the quick-reply row.
    @OptIn(ExperimentalFoundationApi::class)
    private fun showQuickReplyConfig(context: Context) {
        showComposeDialog(context) {
            val replies = remember {
                loadQuickReplies().map { QuickReplyDraft(text = it) }.toMutableStateList()
            }

            AlertDialogContent(
                modifier = Modifier.fillMaxWidth(),
                title = { Text(QUICK_REPLY_NAME) },
                text = {
                    DefaultColumn {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("回复内容", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "点击编辑，长按手柄调整顺序",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    showQuickReplyEditor(context, "添加快捷回复") { text ->
                                        replies.add(QuickReplyDraft(text = text))
                                    }
                                }
                            ) {
                                Icon(MaterialSymbols.Outlined.Add, contentDescription = null)
                                Text("添加")
                            }
                        }

                        if (replies.isEmpty()) {
                            Text(
                                "暂无快捷回复，点击右上角“添加”创建。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 28.dp),
                            )
                        } else {
                            ReorderableList(
                                items = replies,
                                itemKey = { it.id },
                                onMove = { from, to ->
                                    replies.add(to, replies.removeAt(from))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp),
                            ) { reply, dragHandleModifier ->
                                val editReply = {
                                    showQuickReplyEditor(
                                        context = context,
                                        title = "编辑快捷回复",
                                        initialValue = reply.text,
                                    ) { text ->
                                        val index = replies.indexOfFirst { it.id == reply.id }
                                        if (index >= 0) replies[index] = reply.copy(text = text)
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 60.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .then(dragHandleModifier),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            MaterialSymbols.Outlined.Drag_handle,
                                            contentDescription = "拖动快捷回复",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = reply.text,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable(onClick = editReply)
                                            .padding(horizontal = 8.dp, vertical = 12.dp),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconButton(onClick = editReply) {
                                        Icon(
                                            MaterialSymbols.Outlined.Edit,
                                            contentDescription = "编辑快捷回复",
                                        )
                                    }
                                    IconButton(onClick = { replies.removeAll { it.id == reply.id } }) {
                                        Icon(
                                            MaterialSymbols.Outlined.Delete,
                                            contentDescription = "删除快捷回复",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        saveQuickReplies(replies.map { it.text.trim() }.filter { it.isNotEmpty() })
                        onDismiss()
                    }) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> ReorderableList(
    items: List<T>,
    itemKey: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (item: T, dragHandleModifier: Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    LazyColumn(
        state = listState,
        modifier = modifier,
        userScrollEnabled = draggingKey == null,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(
            items = items,
            key = { _, item -> itemKey(item) },
        ) { _, item ->
            val key = itemKey(item)
            val isDragging = draggingKey == key
            val dragHandleModifier = Modifier.pointerInput(key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        if (listState.layoutInfo.visibleItemsInfo.any { it.key == key }) {
                            draggingKey = key
                            dragOffset = 0f
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                        }
                    },
                    onDragCancel = {
                        draggingKey = null
                        dragOffset = 0f
                    },
                    onDragEnd = {
                        draggingKey = null
                        dragOffset = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        if (draggingKey != key) return@detectDragGesturesAfterLongPress
                        dragOffset += amount.y

                        val currentInfo = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == key }
                            ?: return@detectDragGesturesAfterLongPress
                        val currentIndex = currentInfo.index
                        val start = currentInfo.offset + dragOffset
                        val end = start + currentInfo.size
                        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { targetInfo ->
                            if (targetInfo.index == currentIndex) {
                                false
                            } else if (dragOffset > 0f) {
                                targetInfo.index > currentIndex &&
                                        end > targetInfo.offset + targetInfo.size / 2
                            } else {
                                targetInfo.index < currentIndex &&
                                        start < targetInfo.offset + targetInfo.size / 2
                            }
                        }
                        if (target != null) {
                            onMove(currentIndex, target.index)
                            dragOffset -= target.offset - currentInfo.offset
                        }

                        val viewport = listState.layoutInfo
                        val center = currentInfo.offset + dragOffset + currentInfo.size / 2
                        when {
                            center < viewport.viewportStartOffset + 56 && listState.canScrollBackward ->
                                coroutineScope.launch { listState.scrollBy(-12f) }

                            center > viewport.viewportEndOffset - 56 && listState.canScrollForward ->
                                coroutineScope.launch { listState.scrollBy(12f) }
                        }
                    },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragging) dragOffset else 0f
                        scaleX = if (isDragging) 1.02f else 1f
                        scaleY = if (isDragging) 1.02f else 1f
                        shadowElevation = if (isDragging) 8.dp.toPx() else 0f
                    }
                    .then(if (isDragging) Modifier else Modifier.animateItem())
            ) {
                itemContent(item, dragHandleModifier)
            }
        }
    }
}

@Composable
private fun FeatureChip(
    text: String,
    icon: ImageVector,
    displayMode: ToolbarDisplayMode,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = {
            when (displayMode) {
                ToolbarDisplayMode.ICON_ONLY -> Icon(
                    icon,
                    contentDescription = text,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                    tint = MaterialTheme.colorScheme.primary,
                )

                ToolbarDisplayMode.ICON_AND_TEXT,
                ToolbarDisplayMode.TEXT_ONLY -> Text(text)
            }
        },
        leadingIcon = if (displayMode == ToolbarDisplayMode.ICON_AND_TEXT) ({
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        }) else null,
    )
}
