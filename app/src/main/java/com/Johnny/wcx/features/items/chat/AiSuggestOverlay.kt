package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.Johnny.wcx.ai.AiSuggestEngine
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.ui.style.WxPalette
import com.Johnny.wcx.ui.utils.LifecycleOwnerProvider
import com.Johnny.wcx.ui.utils.XposedLifecycleOwner
import com.Johnny.wcx.ui.utils.setLifecycleOwner
import com.Johnny.wcx.ui.utils.theme.ModuleTheme
import com.Johnny.wcx.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 智能建议浮层。
 *
 * 形态是「胶囊 → 卡片」两步：双击后先在标题栏下方出现一枚小胶囊（对方名字 + 三点动画 +
 * 当前动作），模型返回后原地展开成卡片。胶囊阶段就把会话名字显示出来 —— 这是确认
 * 「手势绑定到了哪个会话」最直接的方式。
 *
 * 位置**贴标题栏下沿**，不再贴输入框上沿：聊天内容是从下往上读的，卡片放在底部正好压住
 * 最新的消息；而且放在顶部就不必跟着键盘上移，省掉了一套逐帧对齐的 pre-draw 逻辑。
 *
 * 为什么不用 Dialog / Scrim：这两个都是模态的。用户要求「自己不点建议、直接发消息时卡片收起」，
 * 前提是**输入栏仍然可以正常用** —— 模态遮罩会把输入栏挡住。所以只有这一层 `WRAP_CONTENT`
 * 的 ComposeView 挂在 decorView 上，点别处会穿透到聊天页，退出页面时移除。
 */
object AiSuggestOverlay {

    private const val TAG = "AiSuggestOverlay"

    /** 聊天页根布局与标题栏：用来把卡片顶到标题下方。类名不会被混淆（库类 / 微信稳定类）。 */
    private const val CHATTING_LAYOUT_CLASS = "com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout"
    private const val ACTION_BAR_CONTAINER_CLASS = "androidx.appcompat.widget.ActionBarContainer"

    /**
     * 下面四个字段的读写全部走这把锁。
     *
     * `show()` 在主线程，`dismissFor()` 在消息落库线程。不加锁时存在一个中间态：
     * `show()` 已经换好了 `currentTalker` / `lifecycleOwner`，但 `cardView` 还没写，
     * 此时落库线程收到同一会话的文字消息 → `dismiss()` 会把**刚建好的** owner 销毁，
     * 而卡片已经挂在 decor 上。下一次 measure 时 Compose 拿不到可用 owner
     * （ChattingMainUI 的 decor 本身没有 owner），直接抛
     * `ViewTreeLifecycleOwner not found from DecorView@…[ChattingMainUI]`。
     *
     * 所以「挂载 + 发布字段」必须在同一段临界区里，外面只能看到「没有浮层」或「完整浮层」。
     */
    private val lock = Any()

    private var cardView: ComposeView? = null
    private var host: Activity? = null
    private var observer: LifecycleEventObserver? = null
    private var lifecycleOwner: XposedLifecycleOwner? = null

    /**
     * 当前浮层属于哪个会话。
     *
     * 三个地方会拿它比对：自己发消息时收起（[dismissFor]）、切换会话时收起
     * （`AiSuggestGesture` 里输入栏重新附着），以及双击新会话时收起旧卡片。
     * 不比对会话的话，在 A 会话看建议时 B 会话来一条消息，浮层就被关掉了。
     */
    private var currentTalker: String? = null

    /**
     * 浮层代号：每次挂载 / 收起都 +1。
     *
     * 提示胶囊会延时自动收起，延时到点时如果用户已经双击出了新卡片（或换了会话），
     * 这次回调必须作废 —— 否则它会把刚弹出的卡片一起关掉。
     */
    private var viewToken = 0

    /** 只收起属于 [talker] 的浮层；别的会话来消息时保持不动。 */
    fun dismissFor(talker: String) {
        if (synchronized(lock) { currentTalker } == talker) dismiss()
    }

    /**
     * 是否正在给 [talker] 显示浮层。
     *
     * 自动回复据此让路：用户正在挑建议时不要抢在他前面把消息发出去。
     */
    fun isShowingFor(talker: String): Boolean = synchronized(lock) { currentTalker } == talker

    /**
     * 正在显示浮层，但显示的**不是** [talker]。
     *
     * 聊天页是同一个 Activity 里的 Fragment，切换会话不会触发 Activity 的 pause，
     * 浮层自己不会消失 —— 不收起的话，换到下一个会话看到的就是上一个人的卡片。
     */
    fun isShowingOtherThan(talker: String): Boolean {
        val current = synchronized(lock) { currentTalker } ?: return false
        return current != talker
    }

    /**
     * 收起浮层。**必须主线程移除 View**。
     *
     * `dismiss()` 有两个调用方：界面上的 ✕ / 选中建议（主线程），以及
     * `AiAutoReplyFeature.onInsert`（消息落库线程）。从非主线程直接 `removeView`
     * 会抛 `CalledFromWrongThreadException`，被 `runCatching` 吞掉后 View 还在屏幕上，
     * 但下面的引用已经置空 —— 之后任何一次 `dismiss()` 都找不到它，浮层就再也关不掉。
     */
    fun dismiss() {
        // 字段在锁里一次性取走并清空：只可能拿到某一对完整的 (card, owner)，
        // 不会出现「销毁了新卡的 owner、留下新卡」的错配。
        val snapshot = synchronized(lock) {
            viewToken++
            val current = Snapshot(cardView, lifecycleOwner, observer, host)
            cardView = null
            lifecycleOwner = null
            observer = null
            host = null
            currentTalker = null
            current
        }

        val action = Runnable {
            runCatching { snapshot.card?.let { (it.parent as? ViewGroup)?.removeView(it) } }
            runCatching {
                val lifecycleOwner = snapshot.activity as? LifecycleOwner
                if (lifecycleOwner != null && snapshot.observer != null) {
                    lifecycleOwner.lifecycle.removeObserver(snapshot.observer)
                }
            }
            runCatching { snapshot.owner?.onDestroy() }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action.run()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(action)
        }
    }

    /** [dismiss] 在锁里取到的一组引用，拿到之后就不再读字段。 */
    private class Snapshot(
        val card: ComposeView?,
        val owner: XposedLifecycleOwner?,
        val observer: LifecycleEventObserver?,
        val activity: Activity?,
    )

    /**
     * 卡片顶边距：贴着标题栏下沿。
     *
     * 三个候选取最大值 —— 标题栏下沿（`ActionBarContainer`）、消息列表上沿、
     * 状态栏高度兜底。取最大值是为了兼容「标题栏浮在列表之上」的布局：
     * 那时列表上沿更靠下，卡片自然落在标题下方而不是压在标题上。
     */
    private fun topInset(activity: Activity, decor: ViewGroup): Int {
        val page = findByClassName(decor, CHATTING_LAYOUT_CLASS) ?: decor
        val titleBottom = bottomInWindow(page, decor, ACTION_BAR_CONTAINER_CLASS)
        val listTop = topInWindow(page, decor) { isMessageList(it) }
        val statusBar = statusBarHeight(activity)
        val base = maxOf(titleBottom, listTop, statusBar)
        return base + dip(activity, 8)
    }

    private fun findByClassName(root: ViewGroup, className: String): View? {
        var found: View? = null
        fun walk(view: View, depth: Int) {
            if (found != null || depth > 24 || view.visibility != View.VISIBLE) return
            if (view.javaClass.name == className && view.isShown) {
                found = view
                return
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index), depth + 1)
            }
        }
        walk(root, 0)
        return found
    }

    /** 页面里第一个该类视图的下沿（相对 decor 顶部）。找不到返回 0。 */
    private fun bottomInWindow(root: View, decor: ViewGroup, className: String): Int {
        val decorLocation = IntArray(2)
        decor.getLocationInWindow(decorLocation)
        var best = 0
        fun walk(view: View, depth: Int) {
            if (depth > 24 || view.visibility != View.VISIBLE) return
            if (view.javaClass.name == className && view.isShown && view.height > 0) {
                val location = IntArray(2)
                view.getLocationInWindow(location)
                val bottom = location[1] - decorLocation[1] + view.height
                if (bottom > best) best = bottom
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index), depth + 1)
            }
        }
        walk(root, 0)
        return best
    }

    /**
     * 页面里最靠上的一个满足条件的视图的上沿（相对 decor 顶部）。找不到返回 0。
     *
     * 取最靠上而不是最靠下：页面里除了消息列表，表情面板、文件列表也是 RecyclerView，
     * 那些都在底部，取最靠下的会把卡片推到屏幕下半屏。
     */
    private fun topInWindow(root: View, decor: ViewGroup, predicate: (View) -> Boolean): Int {
        val decorLocation = IntArray(2)
        decor.getLocationInWindow(decorLocation)
        var best = 0
        fun walk(view: View, depth: Int) {
            if (depth > 24 || view.visibility != View.VISIBLE) return
            if (predicate(view) && view.isShown && view.height > 0) {
                val location = IntArray(2)
                view.getLocationInWindow(location)
                val top = location[1] - decorLocation[1]
                if (top > 0 && (best == 0 || top < best)) best = top
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index), depth + 1)
            }
        }
        walk(root, 0)
        return best
    }

    /** 消息列表：类名链里含 RecyclerView（库类名不会被混淆）。 */
    private fun isMessageList(view: View): Boolean {
        var clazz: Class<*>? = view.javaClass
        var depth = 0
        while (clazz != null && depth < 6) {
            if (clazz.name.contains("RecyclerView")) return true
            clazz = clazz.superclass
            depth++
        }
        return false
    }

    private fun statusBarHeight(activity: Activity): Int {
        val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) activity.resources.getDimensionPixelSize(id) else dip(activity, 28)
    }

    /**
     * @param title 对方显示名，显示在胶囊里用来确认绑定的是哪个会话。
     */
    fun show(
        activity: Activity,
        talker: String,
        title: String,
    ) {
        dismiss()

        val decor = activity.window?.decorView as? ViewGroup ?: return
        // 兜底：聊天页的 decor 默认没有 ViewTreeLifecycleOwner（只有 LauncherUI 由
        // WeViewTreeLifecycleProvider 设过，聊天页是 ChatToolbar 顺手设的，功能关掉就没有）。
        // 给 decor 挂一个随 Activity 走的 owner，卡片的 owner 万一失效时 Compose 还能退到它，
        // 不会直接撞上 "ViewTreeLifecycleOwner not found from DecorView" 那条崩溃路径。
        runCatching { decor.setLifecycleOwner(LifecycleOwnerProvider.getOrCreate(activity)) }
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            // 离开聊天页／切后台就收起：用户看不到时留着没有意义，还会在下次进页面时闪一下。
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) dismiss()
        }.also { lifecycle?.addObserver(it) }

        // decorView 上的 ComposeView 必须自带生命周期所有者：Activity 的 owner 只挂在内容视图
        // 那条链上，直接塞进 decorView 时拿不到 ViewTreeLifecycleOwner，组合会停在没有 RESUMED
        // 的状态（表现为「浮层是空的」或直接抛 IllegalStateException）。
        val owner = XposedLifecycleOwner.create()
        val card = ComposeView(activity).apply {
            isSaveEnabled = false
            setLifecycleOwner(owner)
            setContent {
                ModuleTheme {
                    SuggestCard(
                        talker = talker,
                        title = title,
                        onDismiss = { dismiss() },
                    )
                }
            }
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        ).apply {
            topMargin = topInset(activity, decor)
        }

        // 挂载与字段发布在同一段临界区：并发的 dismissFor 要么看不到浮层、要么看到完整的一份。
        synchronized(lock) {
            viewToken++
            decor.addView(card, params)
            host = activity
            currentTalker = talker
            lifecycleOwner = owner
            this.observer = observer
            cardView = card
        }
    }

    /**
     * 只弹一枚提示胶囊（不请求模型），说清楚「现在为什么用不了」，2.6 秒后自动收起。
     *
     * 位置与建议卡片相同 —— 用户双击后第一眼就在那个位置找反馈；返回一句 Toast 会
     * 出现在屏幕底部，离视线焦点太远，也容易被当成系统提示。
     */
    fun showNotice(activity: Activity, text: String) {
        dismiss()
        val decor = activity.window?.decorView as? ViewGroup ?: return
        runCatching { decor.setLifecycleOwner(LifecycleOwnerProvider.getOrCreate(activity)) }
        val owner = XposedLifecycleOwner.create()
        val view = ComposeView(activity).apply {
            isSaveEnabled = false
            setLifecycleOwner(owner)
            setContent {
                ModuleTheme { NoticeCapsule(text) }
            }
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        ).apply {
            topMargin = topInset(activity, decor)
        }
        val token = synchronized(lock) {
            viewToken++
            decor.addView(view, params)
            host = activity
            // 提示不属于任何会话：换会话时的自动收起不该把它当成某个人的卡片。
            currentTalker = null
            lifecycleOwner = owner
            observer = null
            cardView = view
            viewToken
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { if (synchronized(lock) { viewToken } == token) dismiss() },
            2_600L,
        )
    }

    private fun dip(activity: Activity, value: Int): Int =
        (activity.resources.displayMetrics.density * value).toInt()
}

/**
 * 提示胶囊：一句话，居中，位置与建议卡片相同。
 *
 * 刻意不做动画、不带关闭键：它是「双击后的即时反馈」，2.6 秒后自己消失。
 */
@Composable
private fun NoticeCapsule(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = WxPalette.textPrimary(),
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(WxPalette.cardBackground())
                .padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

/**
 * 建议卡片本体：加载时是一枚胶囊（名字 + 三点 + 当前动作），拿到结果后原地展开成卡片。
 *
 * 名字在胶囊里就显示 —— 双击后第一眼就能确认手势绑到了哪个会话；绑定错了立刻能看出来。
 */
@Composable
private fun SuggestCard(
    talker: String,
    title: String,
    onDismiss: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var suggestion by remember { mutableStateOf(AiSuggestEngine.Suggestion()) }
    var attempt by remember { mutableStateOf(0) }

    // 每次生成一枚「还需要结果」的标记：卡片被关掉、或点了刷新换一批时置 false。
    // 引擎在每轮开始与收口前各查一次 —— 请求本身是阻塞调用，取消协程只在它返回后才生效，
    // 没有这个标记的话，关掉卡片之后模型仍会把剩下几轮跑满（单轮 45s、总共 8 分钟）。
    val alive = remember(attempt) { AtomicBoolean(true) }
    DisposableEffect(alive) {
        onDispose { alive.set(false) }
    }

    LaunchedEffect(attempt) {
        // 每次重新生成（含点刷新）都先收回胶囊，出结果再展开：动作和「在想」是一回事。
        expanded = false
        val result = withContext(Dispatchers.IO) {
            // 过程提示不显示（胶囊只留名字与动画）；每轮做了什么在展开后的「思考过程」里看。
            AiSuggestEngine.suggest(talker) { !alive.get() }
        }
        suggestion = result
        expanded = true
    }

    // 展开 = 一次从胶囊中心向四周的放大，而不是「宽度先拉满、再往下长高」。
    // animateContentSize 只补尺寸变化，宽度瞬间铺满就看起来像从左边拉开；
    // 这里改成排版直接到位、只对绘制层做缩放，起点正好是胶囊那块矩形。
    val open = expanded
    var pillSize by remember { mutableStateOf(IntSize.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    val reveal by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(380, easing = FastOutSlowInEasing),
        label = "suggest-bloom",
    )
    // 起点缩放比：把整张卡片缩到胶囊大小。纵向以胶囊中心为原点，四周一起扩散。
    val scaleFromX = if (cardSize.width > 0) pillSize.width.toFloat() / cardSize.width else 0f
    val scaleFromY = if (cardSize.height > 0) pillSize.height.toFloat() / cardSize.height else 0f
    val originY = if (cardSize.height > 0) {
        (pillSize.height / 2f) / cardSize.height
    } else {
        0f
    }
    val bloomReady = open && cardSize.width > 0 && cardSize.height > 0 && pillSize.width > 0

    val maxContentHeight = (LocalConfiguration.current.screenHeightDp * 0.42f).dp
    val corner by animateDpAsState(if (open) 16.dp else 22.dp, label = "suggest-corner")
    val shape = RoundedCornerShape(corner)
    // 胶囊的彩色跑马灯：只在收起时创建，展开后连动画一起销毁，不会一直占着帧。
    val border = if (open) {
        Modifier
    } else {
        Modifier.border(1.6.dp, marqueeBrush(), shape)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                // 收起态不加宽度约束，由「三点 + 左右内边距」自己撑出胶囊；
                // 展开态直接铺满，尺寸变化交给下面的缩放。
                .then(if (open) Modifier.fillMaxWidth() else Modifier)
                .onSizeChanged { if (open) cardSize = it else pillSize = it }
                .graphicsLayer {
                    if (bloomReady) {
                        val eased = reveal
                        scaleX = scaleFromX + (1f - scaleFromX) * eased
                        scaleY = scaleFromY + (1f - scaleFromY) * eased
                        transformOrigin = TransformOrigin(0.5f, originY)
                        // 一起淡入：一开始缩得极小，文字本来就不可读，淡一点更柔和。
                        alpha = 0.25f + 0.75f * eased
                    } else if (open) {
                        // 首帧还没量到卡片尺寸：先不画，避免闪一下整张卡片。
                        alpha = 0f
                    }
                }
                .then(border)
                .clip(shape)
                .background(WxPalette.cardBackground())
                // 胶囊阶段没有关闭键（只留名字与动画），点一下整枚收起。
                .then(if (open) Modifier else Modifier.clickable { onDismiss() })
                .padding(
                    horizontal = if (open) 14.dp else 16.dp,
                    vertical = if (open) 12.dp else 10.dp,
                ),
        ) {
            // 不用 Crossfade：它会把「卡片头部」多留 160ms，而收起态是按内容定宽的，
            // 留着一条 fillMaxWidth 的旧内容会让胶囊瞬间被撑成整行。展开那一下由上面的
            // 缩放负责，两帧之间的硬切换被 0.25 的起始透明度盖住，看不出来。
            if (open) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = WxPalette.textPrimary(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                    Text(
                        text = "· 建议",
                        fontSize = 11.sp,
                        color = WxPalette.textTertiary(),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "↻",
                        fontSize = 14.sp,
                        color = WxPalette.textSecondary(),
                        modifier = Modifier
                            .clickable { attempt++ }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    // 与关闭键拉开距离：两个图标挨在一起时很容易点错。
                    Spacer(Modifier.width(18.dp))
                    Text(
                        text = "✕",
                        fontSize = 13.sp,
                        color = WxPalette.textTertiary(),
                        modifier = Modifier
                            .clickable { onDismiss() }
                            .padding(horizontal = 2.dp, vertical = 2.dp),
                    )
                }
            } else {
                // 思考中只留三点动画，名字等展开后再出现：胶囊越干净越小。
                Box(contentAlignment = Alignment.Center) {
                    InlineDots()
                }
            }

            if (open) {
                Spacer(Modifier.height(10.dp))
                SuggestionBody(
                    talker = talker,
                    suggestion = suggestion,
                    maxHeight = maxContentHeight,
                    onRetry = { attempt++ },
                    onSent = onDismiss,
                )
            }
        }
    }
}

/**
 * 展开后的内容区：解读 + 三条回复（或失败原因）。
 *
 * 单独抽出来是为了让「胶囊 / 卡片」的切换只留一个 `if (expanded)` —— 在同一个 Column 里
 * 用提前 return 跳过一半内容会切断组合分组，不值得为省几行冒这个险。
 */
@Composable
private fun SuggestionBody(
    talker: String,
    suggestion: AiSuggestEngine.Suggestion,
    maxHeight: Dp,
    onRetry: () -> Unit,
    onSent: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            suggestion.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "生成失败：" + suggestion.error,
                    fontSize = 12.sp,
                    color = WxPalette.Danger,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "重试",
                    fontSize = 12.sp,
                    color = WxPalette.Primary,
                    modifier = Modifier
                        .clickable { onRetry() }
                        .padding(start = 10.dp),
                )
            }

            else -> {
                if (suggestion.read.isNotBlank()) {
                    ReadBlock(suggestion.read)
                }
                suggestion.replies.forEach { reply ->
                    ReplyRow(reply = reply, talker = talker, onSent = onSent)
                }
                if (suggestion.replies.isEmpty()) {
                    Text(
                        "没有生成可用回复，换一批试试。",
                        fontSize = 12.sp,
                        color = WxPalette.textSecondary(),
                    )
                }
            }
        }

        if (suggestion.steps.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(WxPalette.divider())
            )
            TraceSection(suggestion.steps)
        }
    }
}

/**
 * 思考过程：默认收起，展开后逐轮列出「想了什么、调了哪些工具」。
 *
 * 用 `remember(steps)` 绑定这一批结果 —— 点刷新换一批时自动收回去，不会把上一批的过程留在眼前。
 */
@Composable
private fun TraceSection(steps: List<AiSuggestEngine.Step>) {
    var open by remember(steps) { mutableStateOf(false) }
    // 只给这一段挂尺寸动画：展开/收起思考过程时高度平滑变化；
    // 卡片本身的展开由外层缩放负责，两者互不干扰。
    Column(Modifier.fillMaxWidth().animateContentSize(tween(220))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "思考过程 · ${steps.size} 轮",
                fontSize = 11.sp,
                color = WxPalette.textSecondary(),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (open) "收起" else "展开",
                fontSize = 11.sp,
                color = WxPalette.Primary,
            )
        }
        if (open) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                steps.forEach { step ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "第 ${step.round} 轮",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = WxPalette.textPrimary(),
                        )
                        if (step.actions.isNotEmpty()) {
                            Text(
                                text = "· " + step.actions.joinToString("；"),
                                fontSize = 11.sp,
                                color = WxPalette.textSecondary(),
                                lineHeight = 16.sp,
                            )
                        }
                        if (step.thinking.isNotBlank()) {
                            Text(
                                text = step.thinking,
                                fontSize = 11.sp,
                                color = WxPalette.textSecondary(),
                                lineHeight = 17.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 解读块：左侧一条 2dp 竖线 + 小字号灰字，视觉上贴着「翻译」的感觉。 */
@Composable
private fun ReadBlock(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .width(2.dp)
                .height(if (text.length > 40) 32.dp else 16.dp)
                .background(WxPalette.Primary.copy(alpha = 0.6f))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            color = WxPalette.textSecondary(),
            lineHeight = 17.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReplyRow(reply: String, talker: String, onSent: () -> Unit) {
    var sending by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(WxPalette.insetSurface())
            .clickable(enabled = !sending) {
                sending = true
                CoroutineScope(Dispatchers.Main).launch {
                    val ok = withContext(Dispatchers.IO) {
                        // 发送前再压一次换行：微信自己会折行，带 \n 发出去会多一个空行。
                        runCatching { WeMessageApi.sendText(talker, AiSuggestEngine.oneLine(reply)) }
                            .getOrDefault(false)
                    }
                    if (ok) {
                        onSent()
                    } else {
                        sending = false
                        WeLogger.w("AiSuggestOverlay", "发送建议失败")
                    }
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = reply,
            fontSize = 14.sp,
            color = WxPalette.textPrimary(),
            modifier = Modifier.alpha(if (sending) 0.5f else 1f),
        )
    }
}

/**
 * 跑马灯边框：扫掠渐变的颜色每帧往前推一格，看起来就是一圈彩带在绕着胶囊跑。
 *
 * 不旋转画布 —— 那样会把圆角矩形（宽高不等）一起转歪；改颜色则只动渐变、不动形状。
 */
@Composable
private fun marqueeBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "marquee")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "marquee-phase",
    )
    val colors = List(12) { index ->
        val hue = ((index / 12f + phase) % 1f) * 360f
        Color.hsv(hue, 0.55f, 1f)
    }
    return Brush.sweepGradient(colors)
}

/** 三点跳动：三个圆点错峰缩放，替代转圈（转圈看不出「正在等模型」）。 */
@Composable
private fun InlineDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val scale by transition.animateFloat(
                initialValue = 0.55f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(460, delayMillis = index * 120),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .size(6.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(CircleShape)
                    .background(WxPalette.Primary.copy(alpha = 0.85f))
            )
            // 间距加在中间，最后一个点后面不留空白 —— 胶囊宽度要按它精确算。
            if (index < 2) Spacer(Modifier.width(5.dp))
        }
    }
}
