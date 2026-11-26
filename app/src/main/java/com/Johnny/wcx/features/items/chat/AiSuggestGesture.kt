package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.Johnny.wcx.ai.AiProfileAudit
import com.Johnny.wcx.ai.AiProfileState
import com.Johnny.wcx.ai.AiProfileStore
import com.Johnny.wcx.ai.AiRunState
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookBeforeDirectly
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import java.lang.reflect.Field
import java.util.Optional
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * 聊天页双击打开 AI 建议。
 *
 * ## 会话 id 从哪来
 *
 * 微信 8.0.79 的聊天页是 LauncherUI 里的 Fragment（任务栈里没有独立聊天 Activity，
 * LauncherUI 的 Intent 也没有 `Chat_User`），所以「当前是哪个会话」只有两个可信来源：
 *
 * 1. **页面上正在显示的消息**：`MsgInfo.field_talker` 就是该消息所属会话，切会话必然跟着变。
 *    绑定关系用 [WeChatMessageViewApi] 维护的表（在 `onBindView` 里记「这一行现在显示哪条
 *    消息」），取最靠下且在屏幕上的一行 —— 聊天记录停在最新那头，底部一定是当前会话。
 * 2. **独立 Activity 打开的聊天页**（通知、搜索等）：Intent 里的 `Chat_User`，实例内固定。
 *
 * 两者都取不到就什么都不做。**不要**再从 `ChattingUIFragment` 的字段、`ChatFooter` 的字段或
 * 任何进程级缓存里取会话 id：前两者是复用后未更新的旧值，后者没有任何事件保证刷新，
 * 三种写法都出现过「切到别人还弹上一个人内容」。
 *
 * ## 手势
 *
 * 只观察不消费（不设置 `param.result`），微信自己的滚动、长按选词照常。
 * 触摸入口挂 `Activity` 与 `MMFragmentActivity` 两个声明：不同页面覆写的层级不同，只挂一处
 * 会漏；两处同时命中时用「同一事件只喂一次」去重，否则一次点击被识别两遍，单击就成了双击。
 */
@Feature(
    name = "AI 建议双击",
    categories = ["API"],
    description = "聊天页双击屏幕打开 AI 建议；群聊、未建档案或档案未就绪的会话不响应",
)
object AiSuggestGesture : ApiFeature() {

    private const val TAG = "AiSuggestGesture"

    /** 独立聊天 Activity 打开时，Intent 里带会话 id 的键。 */
    private const val CHAT_USER = "Chat_User"

    /** 聊天页根布局：输入栏与消息列表都在它下面，用它把「当前这一页」圈出来。 */
    private const val CHATTING_LAYOUT_CLASS = "com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout"

    /** 微信页面的公共基类之一，`dispatchTouchEvent` 可能声明在这里。 */
    private const val MMF_ACTIVITY_CLASS = "com.tencent.mm.ui.MMFragmentActivity"

    /** 两次触发之间的冷却：同一位置连点两轮双击时不重复发请求。 */
    private const val COOLDOWN_MS = 600L

    /** 双击判定窗口：比系统的 300ms 短，滑动时「点一下停住、再点一下」不算。 */
    private const val DOUBLE_TAP_GAP_MS = 250L

    /** 单次点击的最长时长：按住超过它算长按，不参与双击。 */
    private const val TAP_MAX_MS = 250L

    /** 每个 Activity 一个识别器；页面销毁后随之回收。 */
    private val watchers = WeakHashMap<Activity, TapWatcher>()

    @Volatile
    private var lastTriggerAt = 0L

    /** 上一个已处理事件的 (时间, 动作)：两个 hook 声明常常拿到同一个事件。 */
    private var lastEventKey = 0L
    private var lastEventAt = 0L

    /** 读档案会碰磁盘，放到单线程队列里，不在触摸分发里做 IO。 */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wcx-ai-suggest").apply { isDaemon = true }
    }

    /** View 类 → 存放消息对象的字段（没有则缓存 null）。双击时才用，主线程独占。 */
    private val messageFields = HashMap<Class<*>, Optional<Field>>()

    /** 消息类型 → 是否带 `field_talker`。 */
    private val messageTypes = HashMap<Class<*>, Boolean>()

    override fun onEnable() {
        hookTouch("android.app.Activity")
        hookTouch(MMF_ACTIVITY_CLASS)

        // 切换会话时收起上一张卡片：聊天页是同一个 Activity 里的 Fragment，换会话不触发
        // Activity 的 pause，浮层不会自己消失，否则会一直盖在新会话上。
        // 输入栏每次进会话都会重新附着（QuickBackToBottom 依赖同一行为），用它做信号。
        runCatching {
            ChatFooter::class.reflekt().firstMethodOrNull { name = "onAttachedToWindow" }?.hookAfter {
                if (thisObject is ChatFooter) AiSuggestOverlay.dismiss()
            }
        }.onFailure { WeLogger.w(TAG, "ChatFooter.onAttachedToWindow hook 失败", it) }
    }

    private fun hookTouch(className: String) {
        // 总开关关掉就不挂 hook：分发路径上每条触摸都要跑，不值得挂着再判断。
        if (!AiConfig.isMasterEnabled || !AiConfig.isSuggestGestureEnabled) {
            WeLogger.i(TAG, "双击建议已关闭（或 AI 助手总开关已关），跳过手势挂载（$className）")
            return
        }
        val method = runCatching {
            className.toClass().getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
        }.getOrNull() ?: return
        runCatching {
            registerUnhook(method.hookBeforeDirectly { handleTouch(this) })
            WeLogger.d(TAG, "手势挂载：${method.declaringClass.name}.dispatchTouchEvent")
        }.onFailure { WeLogger.e(TAG, "手势挂载失败：$className", it) }
    }

    private fun handleTouch(param: XC_MethodHook.MethodHookParam) {
        // 设置里关掉后立即生效：hook 已挂上也不必等重启，读一次内存里的开关即可。
        if (!AiConfig.isMasterEnabled || !AiConfig.isSuggestGestureEnabled) return
        val activity = param.thisObject as? Activity ?: return
        val event = param.args.getOrNull(0) as? MotionEvent ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        if (isSameEvent(event)) return
        val watcher = watchers[activity] ?: TapWatcher(
            slop = ViewConfiguration.get(activity).scaledTouchSlop,
        ) { onDoubleTap(activity, it) }.also { watchers[activity] = it }
        // 只观察，不看返回值：事件照常往微信的视图树里走。
        watcher.onTouch(event)
    }

    /**
     * 这个事件是否已经在另一个 hook 声明里处理过。
     *
     * 按「事件时间 + 动作」判定而不是对象身份：MotionEvent 会被系统回收复用，
     * 用身份可能在几十毫秒后把新事件误判成旧的。两个不同事件不可能落在同一毫秒的同一动作上。
     */
    private fun isSameEvent(event: MotionEvent): Boolean {
        val key = event.eventTime * 31 + event.actionMasked
        val now = System.currentTimeMillis()
        val same = key == lastEventKey && now - lastEventAt < 40
        lastEventKey = key
        lastEventAt = now
        return same
    }

    private fun onDoubleTap(activity: Activity, event: MotionEvent) {
        if (!isActive) return
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < COOLDOWN_MS) return
        val decor = activity.window?.decorView as? ViewGroup ?: return

        val footer = visibleFooter(decor)
        // 输入栏范围内的双击是选词，抢过来会很难受。
        if (footer != null && insideInputBar(footer, event)) return

        val talker = resolveTalker(activity, footer, decor)
        if (talker == null) {
            WeLogger.d(TAG, "双击：没有解析到会话（输入栏=${footer != null}）")
            return
        }
        if (AiSuggestOverlay.isShowingFor(talker)) return
        // 切换会话后上一张卡片还挂在同一个 Activity 上，先收起，避免串到别人身上。
        if (AiSuggestOverlay.isShowingOtherThan(talker)) AiSuggestOverlay.dismiss()

        lastTriggerAt = now
        WeLogger.d(TAG, "双击：$talker")

        worker.execute(
            Runnable {
                val profile = AiProfileStore.readProfile(talker)
                if (profile == null) {
                    // 没有档案就不响应：这是「对已有档案的好友补一句话」的快捷入口，不是建档入口。
                    WeLogger.d(TAG, "$talker 没有 AI 档案，忽略")
                    return@Runnable
                }
                // 整理 / 审查期间档案只读，建议与解读都会读一份写了一半的档案，必须拒绝。
                // 但双击不能「毫无反应」—— 在那个位置弹一枚胶囊，说清楚现在为什么不可用。
                //
                // 判定走 AiRunState：它额外看内存里的 running 集合。写流水线是「先占位、
                // 再落盘 auditing/state」，只看档案字段会漏掉中间那几毫秒 —— 那时双击会先
                // 弹一个加载胶囊，随后才被建议引擎拒掉。
                if (AiRunState.isBusy(talker, profile)) {
                    val auditing = profile.auditing || AiProfileAudit.isRunning(talker)
                    val text = if (auditing) "正在审查档案，暂不可用" else "正在整理档案，暂不可用"
                    WeLogger.d(TAG, "双击：$talker 忙碌中（$text）")
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        AiSuggestOverlay.showNotice(activity, text)
                    }
                    return@Runnable
                }
                if (profile.state != AiProfileState.READY) {
                    WeLogger.d(TAG, "$talker 档案未就绪（${profile.state}）")
                    toast(activity, "档案还没整理好，先在 AI 页开始整理")
                    return@Runnable
                }
                // 智能建议是按好友开启的：没开就什么都不做（连胶囊都不弹，避免变相提醒）。
                if (!profile.smartSuggest) {
                    WeLogger.d(TAG, "$talker 未开启智能建议，忽略")
                    return@Runnable
                }
                // 胶囊里显示对方名字：双击后第一眼就能确认绑到了哪个会话。
                val title = profile.remark.ifBlank { profile.nickname }.ifBlank { talker }
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (AiSuggestOverlay.isShowingFor(talker)) return@runOnUiThread
                    // 提示音放在确认要弹卡片之后：没档案、档案未就绪时不该响。
                    AiCueSound.play()
                    AiSuggestOverlay.show(
                        activity = activity,
                        talker = talker,
                        title = title,
                    )
                }
            },
        )
    }

    // ── 会话 id ─────────────────────────────────────────────────────────────

    private fun resolveTalker(activity: Activity, footer: ChatFooter?, decor: ViewGroup): String? {
        val page = footer?.let { chattingPageOf(it) } ?: decor
        talkerOfVisibleMessages(page, decor)?.let { return it }
        // 独立 Activity 打开的聊天页（通知、搜索等）：Intent 里带 Chat_User，实例内不变。
        val fromIntent = activity.intent?.getStringExtra(CHAT_USER)
        if (fromIntent != null && isDirectChat(fromIntent)) return fromIntent
        return null
    }

    /** 页面里**最靠下且在屏幕上**的那条消息属于哪个会话。 */
    private fun talkerOfVisibleMessages(page: View, window: ViewGroup): String? {
        val bounds = WindowBounds.of(window)
        var bestTalker: String? = null
        var bestY = Int.MIN_VALUE

        val bound = runCatching {
            WeChatMessageViewApi.findBoundViews { message ->
                runCatching { isDirectChat(message.talker) }.getOrDefault(false)
            }
        }.getOrDefault(emptyList())

        for ((view, message) in bound) {
            if (!isOnScreen(view, bounds) || !isInside(view, page)) continue
            val y = screenY(view)
            if (y >= bestY) {
                bestY = y
                bestTalker = runCatching { message.talker }.getOrNull()
            }
        }
        if (bestTalker != null) return bestTalker
        // 绑定表为空（消息 View 服务的锚点没命中）时，退回直接读行 View 上的消息字段。
        return bottomMostTalkerInTree(page, bounds)?.first
    }

    private fun bottomMostTalkerInTree(
        view: View,
        bounds: WindowBounds,
        depth: Int = 0,
    ): Pair<String, Int>? {
        if (depth > 32 || view.visibility != View.VISIBLE) return null
        var best: Pair<String, Int>? = null
        if (isOnScreen(view, bounds)) {
            messageOf(view)?.let { best = it to screenY(view) }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val candidate = bottomMostTalkerInTree(view.getChildAt(index), bounds, depth + 1)
                    ?: continue
                val current = best
                if (current == null || candidate.second >= current.second) best = candidate
            }
        }
        return best
    }

    /** 这个 View 上绑定的消息属于哪个会话。 */
    private fun messageOf(view: View): String? {
        WeChatMessageViewApi.getBoundMessage(view)?.let { message ->
            runCatching { message.talker }.getOrNull()?.let { if (isDirectChat(it)) return it }
        }
        val field = messageField(view.javaClass) ?: return null
        val value = runCatching { field.get(view) }.getOrNull() ?: return null
        val talker = runCatching { MessageInfo(value).talker }.getOrNull() ?: return null
        return talker.takeIf { isDirectChat(it) }
    }

    private fun messageField(clazz: Class<*>): Field? {
        messageFields[clazz]?.let { return it.orElse(null) }
        var current: Class<*>? = clazz
        var depth = 0
        var found: Field? = null
        while (current != null && depth < 4 && found == null) {
            for (field in current.declaredFields) {
                val type = field.type
                if (type.isPrimitive || type == String::class.java) continue
                if (!hasTalkerField(type)) continue
                runCatching { field.isAccessible = true }
                found = field
                break
            }
            current = current.superclass
            depth++
        }
        messageFields[clazz] = Optional.ofNullable(found)
        return found
    }

    private fun hasTalkerField(clazz: Class<*>): Boolean = messageTypes.getOrPut(clazz) {
        var current: Class<*>? = clazz
        var depth = 0
        while (current != null && depth < 4) {
            if (current.declaredFields.any { it.name == "field_talker" }) return@getOrPut true
            current = current.superclass
            depth++
        }
        false
    }

    /** 只认单聊的 wxid：群聊（@chatroom）与公众号（gh_）都没有档案可以查。 */
    private fun isDirectChat(value: String): Boolean =
        value.startsWith("wxid_") && !value.endsWith("@chatroom")

    // ── 页面结构 ────────────────────────────────────────────────────────────

    /** 当前页面上可见的聊天输入栏；不在聊天页时返回 null。 */
    private fun visibleFooter(window: ViewGroup): ChatFooter? {
        val all = ArrayList<ChatFooter>(2)
        collectFooters(window, all)
        // 可能同时挂着几份（复用的会话、预取布局），取最靠下的那个。
        val bounds = WindowBounds.of(window)
        return all.filter { isOnScreen(it, bounds) }.maxByOrNull { screenY(it) }
    }

    private fun collectFooters(view: View, out: MutableList<ChatFooter>) {
        val footer = view as? ChatFooter
        if (footer != null) {
            out += footer
            return
        }
        if (view !is ViewGroup) return
        for (index in 0 until view.childCount) {
            collectFooters(view.getChildAt(index), out)
        }
    }

    /** 输入栏所在的那一页（`ChattingUILayout`）；找不到就退回它所在的窗口根。 */
    private fun chattingPageOf(footer: ChatFooter): View {
        var current: View? = footer.parent as? View
        var depth = 0
        while (current != null && depth < 12) {
            if (current.javaClass.name == CHATTING_LAYOUT_CLASS) return current
            current = current.parent as? View
            depth++
        }
        return footer.rootView
    }

    /**
     * 是否落在当前窗口的可见范围内：已附着、有尺寸，且竖直方向与窗口有交集。
     *
     * 不用「可见面积 ≥ 60%」这种口径：底部那条最新消息经常只露出半行，按比例会被判成
     * 不可见，于是「解析不到会话」。
     */
    private fun isOnScreen(view: View, bounds: WindowBounds): Boolean {
        if (!view.isShown || !view.isAttachedToWindow) return false
        if (view.width <= 0 || view.height <= 0) return false
        val top = screenY(view)
        return top < bounds.bottom && top + view.height > bounds.top
    }

    private fun isInside(view: View, page: View): Boolean {
        var current: View? = view
        var depth = 0
        while (current != null && depth < 24) {
            if (current === page) return true
            current = current.parent as? View
            depth++
        }
        return false
    }

    private fun screenY(view: View): Int {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return loc[1]
    }

    /** 窗口的竖直范围，算一次给整趟查找用。 */
    private class WindowBounds(val top: Int, val bottom: Int) {
        companion object {
            fun of(window: ViewGroup): WindowBounds {
                val loc = IntArray(2)
                window.getLocationOnScreen(loc)
                return WindowBounds(loc[1], loc[1] + window.height)
            }
        }
    }

    /** 双击是否落在输入栏上（含表情面板）。 */
    private fun insideInputBar(footer: ChatFooter, event: MotionEvent): Boolean {
        val loc = IntArray(2)
        footer.getLocationOnScreen(loc)
        return event.rawX >= loc[0] && event.rawX <= loc[0] + footer.width &&
            event.rawY >= loc[1] && event.rawY <= loc[1] + footer.height
    }

    private fun toast(activity: Activity, text: String) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 简易双击识别：短按 → 短间隔 → 短按，两次落点必须接近，中间不能有拖动。
     *
     * 不用系统 `GestureDetector`：它只看时间、不看落点，滑动列表里「点一下停住、再点一下」
     * 会被判成双击。距离一律用平方比较，避免每次 `ACTION_MOVE` 都算一次开方。
     */
    private class TapWatcher(
        slop: Int,
        private val onDoubleTap: (MotionEvent) -> Unit,
    ) {
        private val slop2 = slop.toFloat() * slop
        private val doubleTapSlop2 = slop2 * 9f

        private var downAt = 0L
        private var downX = 0f
        private var downY = 0f
        private var dragged = false
        private var lastUpAt = 0L
        private var lastX = 0f
        private var lastY = 0f

        fun onTouch(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = event.eventTime
                    downX = event.rawX
                    downY = event.rawY
                    dragged = false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragged && distanceSquared(event.rawX, event.rawY, downX, downY) > slop2) {
                        dragged = true
                    }
                }

                MotionEvent.ACTION_UP -> {
                    // 拖动过、或按住太久（长按菜单）都不算点击，并清掉上一跳。
                    if (dragged || event.eventTime - downAt > TAP_MAX_MS) {
                        lastUpAt = 0L
                        return
                    }
                    val near = lastUpAt != 0L &&
                        event.eventTime - lastUpAt <= DOUBLE_TAP_GAP_MS &&
                        distanceSquared(event.rawX, event.rawY, lastX, lastY) <= doubleTapSlop2
                    if (near) {
                        lastUpAt = 0L
                        onDoubleTap(event)
                    } else {
                        lastUpAt = event.eventTime
                        lastX = event.rawX
                        lastY = event.rawY
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    lastUpAt = 0L
                    dragged = true
                }
            }
        }

        private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x1 - x2
            val dy = y1 - y2
            return dx * dx + dy * dy
        }
    }
}
