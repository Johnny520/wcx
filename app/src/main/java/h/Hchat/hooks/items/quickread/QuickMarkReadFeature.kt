package h.Hchat.hooks.items.quickread

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.custombottombar.CustomBottomBarFeature
import h.Hchat.hooks.items.custombottombar.FloatingBottomBarTouchState
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Method
import kotlin.math.abs

class QuickMarkReadFeature : BaseFeature() {
    private var dragController: QuickMarkReadDragController? = null

    override fun featureId(): String = ID

    override fun name(): String = "快捷已读"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(QuickMarkReadSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        QuickMarkReadRuntime.install(context)
        val controller = QuickMarkReadDragController(context.hostClassLoader(), ::logError)
        dragController = controller
        controller.install()
    }

    companion object {
        const val ID = "quick_mark_read"
    }
}

private class QuickMarkReadDragController(
    private val classLoader: ClassLoader,
    private val logger: (String, Throwable?) -> Unit
) {
    @Volatile private var installed = false
    private var pending: PendingDragSession? = null
    private var session: DragSession? = null

    fun install(): Boolean {
        if (installed) return true
        val methods = collectDispatchMethods()
        if (methods.isEmpty()) return false
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                val event = param.args?.getOrNull(0) as? MotionEvent ?: return
                if (handleTouch(activity, event)) {
                    param.result = true
                }
            }
        }
        var hookedCount = 0
        var lastError: Throwable? = null
        for (method in methods) {
            runCatching {
                HookRegistry.get().hook(method, callback)
                hookedCount++
            }.onFailure {
                lastError = it
            }
        }
        installed = hookedCount > 0
        if (!installed) {
            logger("快捷已读拖拽Hook安装失败", lastError)
        }
        return installed
    }

    private fun collectDispatchMethods(): Set<Method> {
        val methods = LinkedHashSet<Method>()
        val launcherClass = KavaReflector.loadClass(LAUNCHER_UI, classLoader)
        var current: Class<*>? = launcherClass
        while (current != null && Activity::class.java.isAssignableFrom(current)) {
            KavaReflector.findDeclaredMethod(
                current,
                DISPATCH_TOUCH_EVENT,
                MotionEvent::class.java
            )?.let { methods.add(it) }
            if (current == Activity::class.java) break
            current = current.superclass
        }
        if (methods.isEmpty()) {
            KavaReflector.findDeclaredMethod(
                Activity::class.java,
                DISPATCH_TOUCH_EVENT,
                MotionEvent::class.java
            )?.let { methods.add(it) }
        }
        return methods
    }

    private fun handleTouch(activity: Activity, event: MotionEvent): Boolean {
        if (!isLauncherUi(activity)) {
            clearGesture(resetActive = true)
            return false
        }
        if (!QuickMarkReadRuntime.isDragEnabled(activity)) {
            clearGesture(resetActive = true)
            return false
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startPendingSession(activity, event)
            MotionEvent.ACTION_MOVE -> moveSession(event)
            MotionEvent.ACTION_UP -> finishSession(event)
            MotionEvent.ACTION_CANCEL -> cancelSession()
            else -> session != null
        }
    }

    private fun startPendingSession(activity: Activity, event: MotionEvent): Boolean {
        val root = activity.window?.decorView as? ViewGroup ?: return false
        if (root.findViewWithTag<View>(HCHAT_SETTINGS_PAGE_TAG) != null) {
            clearGesture(resetActive = true)
            return false
        }
        val target = findUnreadTargetAt(root, event.rawX, event.rawY) ?: return false
        clearGesture(resetActive = true)
        pending = PendingDragSession(root, target, event.rawX, event.rawY)
        return false
    }

    private fun moveSession(event: MotionEvent): Boolean {
        val current = session
        if (current == null) {
            val pendingSession = pending ?: return false
            if (!pendingSession.shouldStart(event.rawX, event.rawY)) return false
            pending = null
            val started = DragSession(
                pendingSession.root,
                pendingSession.target,
                pendingSession.downRawX,
                pendingSession.downRawY
            )
            session = started
            pendingSession.target.source?.parent?.requestDisallowInterceptTouchEvent(true)
            pendingSession.target.source?.bringToFront()
            started.moveTo(event.rawX, event.rawY)
            return true
        }
        current.moveTo(event.rawX, event.rawY)
        return true
    }

    private fun finishSession(event: MotionEvent): Boolean {
        val current = session
        if (current == null) {
            pending = null
            return false
        }
        current.moveTo(event.rawX, event.rawY)
        val shouldMarkRead = current.shouldMarkRead()
        if (shouldMarkRead) {
            var success = false
            runCatching {
                val changed = QuickMarkReadRuntime.markAllRead(current.target.contextView.context, true)
                success = changed >= 0
                if (success) {
                    current.restoreImmediately()
                }
            }.onFailure {
                logger("快捷已读触发失败", it)
            }
            if (!success) {
                current.reset()
            }
        } else {
            current.reset()
        }
        session = null
        return true
    }

    private fun cancelSession(): Boolean {
        pending = null
        val current = session ?: return false
        current.reset()
        session = null
        return true
    }

    private fun clearGesture(resetActive: Boolean) {
        pending = null
        val current = session ?: return
        if (resetActive) current.reset()
        session = null
    }

    private fun findUnreadTargetAt(root: ViewGroup, rawX: Float, rawY: Float): DragTarget? {
        findFloatingBottomBar(root)?.let { floatingBar ->
            findFloatingHomeRegion(root, floatingBar, rawX, rawY)?.let { homeRegion ->
                return syntheticDotTarget(root, homeRegion, rawX, rawY)
            }
        }
        val bottomNav = findBottomNavContainer(root) ?: return null
        val homeRegion = findBottomTabRegion(bottomNav, HOME_TAB_LABEL) ?: return null
        if (!homeRegion.contains(rawX, rawY)) return null
        val matches = ArrayList<BadgeHit>()
        traverse(bottomNav) { badge ->
            if (!isUnreadBadgeCandidate(badge)) return@traverse
            if (!hitTest(badge, rawX, rawY)) return@traverse
            val width = viewWidth(badge)
            val height = viewHeight(badge)
            val location = IntArray(2)
            badge.getLocationOnScreen(location)
            val dx = rawX - (location[0] + width / 2f)
            val dy = rawY - (location[1] + height / 2f)
            val distance = dx * dx + dy * dy
            val area = width * height
            matches += BadgeHit(badge, distance, area)
        }
        if (matches.isNotEmpty()) {
            return matches.minWithOrNull(
                compareBy<BadgeHit> { it.distance }
                    .thenBy { it.area.takeIf { area -> area > 0 } ?: Int.MAX_VALUE }
            )?.view?.let { dragTargetFromView(it) }
        }
        return syntheticDotTarget(root, homeRegion, rawX, rawY)
    }

    private fun findFloatingBottomBar(root: ViewGroup): ViewGroup? {
        return root.findViewWithTag<View>(CustomBottomBarFeature.FLOATING_BOTTOM_BAR_TAG)
            ?.takeIf { it.isShown && it.visibility == View.VISIBLE }
            ?.let { it as? ViewGroup }
    }

    private fun findFloatingHomeRegion(
        root: ViewGroup,
        floatingBar: ViewGroup,
        rawX: Float,
        rawY: Float
    ): ScreenRect? {
        val touchState = (0 until floatingBar.childCount)
            .asSequence()
            .mapNotNull { floatingBar.getChildAt(it).tag as? FloatingBottomBarTouchState }
            .firstOrNull()
            ?: return null
        val bounds = touchState.snapshot() ?: return null
        val rootScreenLocation = IntArray(2)
        val rootWindowLocation = IntArray(2)
        root.getLocationOnScreen(rootScreenLocation)
        root.getLocationInWindow(rootWindowLocation)
        val rawWindowX = rawX - rootScreenLocation[0] + rootWindowLocation[0]
        val rawWindowY = rawY - rootScreenLocation[1] + rootWindowLocation[1]
        val barRect = ScreenRect(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom
        )
        if (!barRect.contains(rawWindowX, rawWindowY)) return null
        return ScreenRect(
            left = barRect.left,
            top = barRect.top,
            right = barRect.left + barRect.width() / BOTTOM_TAB_LABELS.size,
            bottom = barRect.bottom
        )
    }

    private fun isUnreadBadgeCandidate(view: View): Boolean {
        if (!view.isShown || view.visibility != View.VISIBLE) return false
        val width = viewWidth(view)
        val height = viewHeight(view)
        if (width <= 0 || height <= 0) return false
        val textView = view as? TextView
        val text = textView?.text?.toString()?.trim().orEmpty()
        val hasUnreadText = textView != null && UNREAD_TEXT.matches(text)
        val isPlainDot = text.isEmpty() &&
            view.background != null &&
            width in dp(view, 4f)..dp(view, 24f) &&
            height in dp(view, 4f)..dp(view, 24f) &&
            width <= height * 2 &&
            height <= width * 2
        if (!hasUnreadText && !isPlainDot) return false
        if (hasUnreadText) {
            if (width !in dp(view, 6f)..dp(view, 72f)) return false
            if (height !in dp(view, 6f)..dp(view, 36f)) return false
            if (!isLightText(textView.currentTextColor)) return false
        }
        return true
    }

    private fun hitTest(view: View, rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val extra = dp(view, 28f)
        val left = location[0] - extra
        val top = location[1] - extra
        val right = location[0] + viewWidth(view) + extra
        val bottom = location[1] + viewHeight(view) + extra
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
    }

    private fun syntheticDotTarget(root: ViewGroup, homeRegion: ScreenRect, rawX: Float, rawY: Float): DragTarget? {
        if (!homeRegion.contains(rawX, rawY)) return null
        val size = dp(root, 10f).coerceAtLeast(1)
        return DragTarget(
            contextView = root,
            source = null,
            screenLeft = (rawX - size / 2f).toInt(),
            screenTop = (rawY - size / 2f).toInt(),
            width = size,
            height = size,
            text = null,
            textColor = Color.WHITE,
            textSizePx = 0f,
            typeface = null,
            gravity = 0,
            includeFontPadding = false,
            paddingLeft = 0,
            paddingTop = 0,
            paddingRight = 0,
            paddingBottom = 0,
            background = syntheticDotDrawable()
        )
    }

    private fun findBottomTabRegion(bottomNav: ViewGroup, label: String): ScreenRect? {
        val location = IntArray(2)
        bottomNav.getLocationOnScreen(location)
        val navLeft = location[0]
        val navTop = location[1]
        val navWidth = viewWidth(bottomNav)
        val navHeight = viewHeight(bottomNav)
        if (navWidth <= 0 || navHeight <= 0) return null
        val labelView = findTextView(bottomNav, label) ?: return null
        val labelLocation = IntArray(2)
        labelView.getLocationOnScreen(labelLocation)
        val centerX = labelLocation[0] + viewWidth(labelView) / 2f
        val tabWidth = (navWidth / BOTTOM_TAB_LABELS.size.toFloat()).coerceAtLeast(dp(bottomNav, 48f).toFloat())
        return ScreenRect(
            left = centerX - tabWidth / 2f,
            top = navTop.toFloat(),
            right = centerX + tabWidth / 2f,
            bottom = navTop + navHeight * 0.72f
        ).intersect(
            ScreenRect(
                left = navLeft.toFloat(),
                top = navTop.toFloat(),
                right = navLeft + navWidth.toFloat(),
                bottom = navTop + navHeight.toFloat()
            )
        )
    }

    private fun findTextView(view: View, text: String): TextView? {
        if (view is TextView && view.text?.toString()?.trim() == text) return view
        val group = view as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findTextView(group.getChildAt(index), text)?.let { return it }
        }
        return null
    }

    private fun dragTargetFromView(view: View): DragTarget {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val textView = view as? TextView
        return DragTarget(
            contextView = view,
            source = view,
            screenLeft = location[0],
            screenTop = location[1],
            width = viewWidth(view).coerceAtLeast(1),
            height = viewHeight(view).coerceAtLeast(1),
            text = textView?.text,
            textColor = textView?.currentTextColor ?: Color.WHITE,
            textSizePx = textView?.textSize ?: 0f,
            typeface = textView?.typeface,
            gravity = textView?.gravity ?: 0,
            includeFontPadding = textView?.includeFontPadding ?: false,
            paddingLeft = view.paddingLeft,
            paddingTop = view.paddingTop,
            paddingRight = view.paddingRight,
            paddingBottom = view.paddingBottom,
            background = view.background?.constantState?.newDrawable()?.mutate()
        )
    }

    private fun syntheticDotDrawable(): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(250, 81, 81))
        }
    }

    private fun hitTest(view: View, rawX: Float, rawY: Float, extra: Int): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val left = location[0] - extra
        val top = location[1] - extra
        val right = location[0] + viewWidth(view) + extra
        val bottom = location[1] + viewHeight(view) + extra
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
    }

    private fun findBottomNavContainer(root: ViewGroup): ViewGroup? {
        val rootHeight = root.height.takeIf { it > 0 }
            ?: root.resources.displayMetrics.heightPixels
        val candidates = ArrayList<ViewGroup>()
        traverse(root) { view ->
            val group = view as? ViewGroup ?: return@traverse
            if (!isBottomContainer(group, rootHeight)) return@traverse
            val labels = LinkedHashSet<String>()
            collectTexts(group, depth = 0, labels)
            if (BOTTOM_TAB_LABELS.count { label -> labels.any { it == label } } >= 2) {
                candidates += group
            }
        }
        return candidates.minWithOrNull(
            compareBy<ViewGroup> { viewHeight(it).takeIf { height -> height > 0 } ?: Int.MAX_VALUE }
                .thenBy { viewWidth(it).takeIf { width -> width > 0 } ?: Int.MAX_VALUE }
        )
    }

    private fun isBottomContainer(group: ViewGroup, rootHeight: Int): Boolean {
        val location = IntArray(2)
        group.getLocationOnScreen(location)
        val top = location[1]
        val bottom = top + viewHeight(group)
        val height = viewHeight(group)
        return top >= rootHeight * 0.62f &&
            bottom >= rootHeight * 0.88f &&
            height <= rootHeight * 0.28f
    }

    private fun collectTexts(view: View, depth: Int, out: MutableSet<String>) {
        if (depth > 4) return
        if (view is TextView) {
            view.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            view.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        }
        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            collectTexts(group.getChildAt(index), depth + 1, out)
        }
    }

    private fun traverse(view: View, block: (View) -> Unit) {
        block(view)
        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            traverse(group.getChildAt(index), block)
        }
    }

    private fun isLightText(color: Int): Boolean {
        return Color.red(color) >= 180 && Color.green(color) >= 180 && Color.blue(color) >= 180
    }

    private fun isLauncherUi(activity: Activity): Boolean {
        return activity.javaClass.name == LAUNCHER_UI
    }

    companion object {
        private const val LAUNCHER_UI = "com.tencent.mm.ui.LauncherUI"
        private const val DISPATCH_TOUCH_EVENT = "dispatchTouchEvent"
        private const val HCHAT_SETTINGS_PAGE_TAG = "Hchat:MiuixSettingsPage"
        private const val HOME_TAB_LABEL = "微信"
        private val UNREAD_TEXT = Regex("""\d+\+?|…|\.{2,3}""")
        private val BOTTOM_TAB_LABELS = setOf("微信", "通讯录", "发现", "我")

        private fun viewWidth(view: View): Int {
            return view.width.takeIf { it > 0 } ?: view.measuredWidth
        }

        private fun viewHeight(view: View): Int {
            return view.height.takeIf { it > 0 } ?: view.measuredHeight
        }

        private fun dp(view: View, value: Float): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                view.resources.displayMetrics
            ).toInt()
        }
    }

    private data class BadgeHit(
        val view: View,
        val distance: Float,
        val area: Int
    )

    private data class ScreenRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        fun width(): Float = (right - left).coerceAtLeast(0f)

        fun contains(x: Float, y: Float): Boolean {
            return x >= left && x <= right && y >= top && y <= bottom
        }

        fun intersect(other: ScreenRect): ScreenRect {
            return ScreenRect(
                left = maxOf(left, other.left),
                top = maxOf(top, other.top),
                right = minOf(right, other.right),
                bottom = minOf(bottom, other.bottom)
            )
        }
    }
}

private class PendingDragSession(
    val root: ViewGroup,
    val target: DragTarget,
    val downRawX: Float,
    val downRawY: Float
) {
    fun shouldStart(rawX: Float, rawY: Float): Boolean {
        val dx = rawX - downRawX
        val dy = rawY - downRawY
        return dy <= -dp(target.contextView, 18f) && abs(dy) > abs(dx) * 0.8f
    }

    private fun dp(view: View, value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            view.resources.displayMetrics
        ).toInt()
    }
}

private class DragSession(
    val root: ViewGroup,
    val target: DragTarget,
    private val downRawX: Float,
    private val downRawY: Float
) {
    private val dragView = createDragView(root, target)
    private val originalAlpha = target.source?.alpha
    private val originalTranslationX = dragView.translationX
    private val originalTranslationY = dragView.translationY
    private var hapticSent = false
    private var cleaned = false

    init {
        target.source?.alpha = 0f
        root.overlay.add(dragView)
    }

    fun moveTo(rawX: Float, rawY: Float) {
        val dx = rawX - downRawX
        val dy = rawY - downRawY
        dragView.translationX = originalTranslationX + dx
        dragView.translationY = originalTranslationY + dy
        if (shouldMarkRead() && !hapticSent) {
            target.contextView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            hapticSent = true
        }
    }

    fun shouldMarkRead(): Boolean {
        val dy = dragView.translationY - originalTranslationY
        val dx = dragView.translationX - originalTranslationX
        return dy <= -dp(target.contextView, 42f) && abs(dy) > abs(dx) * 0.45f
    }

    fun reset() {
        dragView.animate()
            .translationX(originalTranslationX)
            .translationY(originalTranslationY)
            .setDuration(120L)
            .withEndAction { cleanup() }
            .start()
        target.source?.parent?.requestDisallowInterceptTouchEvent(false)
    }

    fun restoreImmediately() {
        dragView.animate().cancel()
        cleanup()
        target.source?.parent?.requestDisallowInterceptTouchEvent(false)
    }

    companion object {
        private fun createDragView(root: ViewGroup, target: DragTarget): View {
            val rootLocation = IntArray(2)
            root.getLocationOnScreen(rootLocation)
            val width = target.width
            val height = target.height
            val left = target.screenLeft - rootLocation[0]
            val top = target.screenTop - rootLocation[1]
            val ghost = if (target.text != null) {
                TextView(target.contextView.context).apply {
                    text = target.text
                    setTextColor(target.textColor)
                    textSize = target.textSizePx / resources.displayMetrics.scaledDensity
                    typeface = target.typeface
                    gravity = target.gravity
                    includeFontPadding = target.includeFontPadding
                    setPadding(target.paddingLeft, target.paddingTop, target.paddingRight, target.paddingBottom)
                    background = target.background
                }
            } else {
                View(target.contextView.context).apply {
                    background = target.background
                }
            }
            ghost.alpha = target.source?.alpha ?: 1f
            ghost.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            ghost.layout(left, top, left + width, top + height)
            ghost.translationX = target.source?.translationX ?: 0f
            ghost.translationY = target.source?.translationY ?: 0f
            ghost.elevation = (target.source?.elevation ?: 0f) + dp(target.contextView, 8f)
            return ghost
        }

        private fun dp(view: View, value: Float): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                view.resources.displayMetrics
            ).toInt()
        }
    }

    private fun cleanup() {
        if (cleaned) return
        cleaned = true
        runCatching { root.overlay.remove(dragView) }
        if (originalAlpha != null) {
            target.source?.alpha = originalAlpha
        }
    }
}

private data class DragTarget(
    val contextView: View,
    val source: View?,
    val screenLeft: Int,
    val screenTop: Int,
    val width: Int,
    val height: Int,
    val text: CharSequence?,
    val textColor: Int,
    val textSizePx: Float,
    val typeface: Typeface?,
    val gravity: Int,
    val includeFontPadding: Boolean,
    val paddingLeft: Int,
    val paddingTop: Int,
    val paddingRight: Int,
    val paddingBottom: Int,
    val background: Drawable?
)
