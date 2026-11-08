package h.Hchat.hooks.items.floatingshortcut

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.ui.HchatAgentIconDrawable
import h.Hchat.preferences.HchatStorage
import h.Hchat.ui.SettingsUI
import h.Hchat.ui.miuix.MiuixSettingsPage
import h.Hchat.utils.HLog
import java.lang.ref.WeakReference
import kotlin.math.abs

object FloatingShortcutRuntime {
    private const val TAG = "[Hchat:FloatingShortcut]"
    private const val BUBBLE_TAG = "Hchat:FloatingShortcut:Bubble"
    private const val ACTION_LABEL_TAG = "Hchat:FloatingShortcut:Label"
    private const val ACTION_ICON_TAG = "Hchat:FloatingShortcut:Icon"
    private const val LAUNCHER_ACTIVITY = "com.tencent.mm.ui.LauncherUI"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var prefs: SharedPreferences? = null
    @Volatile
    private var enabled = false
    @Volatile
    private var installed = false
    @Volatile
    private var chatPageVisible = false
    private var currentActivity = WeakReference<Activity>(null)
    private var currentBubble = WeakReference<View>(null)
    private var currentMenu = WeakReference<View>(null)
    private var currentDismissLayer = WeakReference<View>(null)
    private var lifecycleApplication = WeakReference<Application>(null)
    private var resumedActivity = WeakReference<Activity>(null)
    private val pendingAttachCallbacks = mutableListOf<Runnable>()
    @Volatile
    private var enabledItems: List<FloatingShortcutItem> = emptyList()
    private var pendingAction: Runnable? = null

    private val activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) {
            runOnMain {
                resumedActivity = WeakReference(activity)
                attach(activity)
                scheduleAttachRetry(activity)
            }
        }

        override fun onActivityPaused(activity: Activity) {
            runOnMain {
                if (resumedActivity.get() === activity) {
                    resumedActivity.clear()
                    cancelAttachRetries()
                    cancelPendingAction()
                }
            }
        }

        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            onActivityPaused(activity)
            detach(activity)
        }
    }

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            FloatingShortcutSettings.KEY_ENABLE -> refreshEnabledState()
            FloatingShortcutSettings.KEY_POSITION_X,
            FloatingShortcutSettings.KEY_POSITION_Y -> Unit
            else -> {
                if (key == FloatingShortcutSettings.KEY_ITEMS || key == null) {
                    lifecycleApplication.get()?.let { application ->
                        enabledItems = FloatingShortcutSettings.loadItems(application).filter { it.enabled }
                    }
                }
                prefetchIcons()
                refreshCurrentActivity()
            }
        }
    }

    @Synchronized
    fun install(hostContext: Context) {
        if (installed) return
        val application = (hostContext as? Application)
            ?: (hostContext.applicationContext as? Application)
            ?: return
        enabledItems = FloatingShortcutSettings.loadItems(hostContext).filter { it.enabled }
        prefs = HchatStorage.preferences(hostContext, FloatingShortcutSettings.PREFS_NAME).also {
            it.registerOnSharedPreferenceChangeListener(preferenceListener)
            enabled = it.getBoolean(
                FloatingShortcutSettings.KEY_ENABLE,
                FloatingShortcutSettings.DEFAULT_ENABLE
            )
        }
        application.registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
        lifecycleApplication = WeakReference(application)
        installed = true
        if (enabled) prefetchIcons()
    }

    fun setEnabled(context: Context, value: Boolean) {
        HchatStorage.preferences(context, FloatingShortcutSettings.PREFS_NAME)
            .edit()
            .putBoolean(FloatingShortcutSettings.KEY_ENABLE, value)
            .apply()
        applyEnabledState(value)
    }

    fun isEnabled(context: Context): Boolean {
        return HchatStorage.preferences(context, FloatingShortcutSettings.PREFS_NAME)
            .getBoolean(
                FloatingShortcutSettings.KEY_ENABLE,
                FloatingShortcutSettings.DEFAULT_ENABLE
            )
    }

    private fun scheduleAttachRetry(activity: Activity) {
        cancelAttachRetries()
        if (!installed || !enabled || !shouldShow(activity)) return
        // 对齐 QEchat：补偿冷启动窗口尚未布局的情况；页面切走后立即取消。
        val activityRef = WeakReference(activity)
        for (delay in longArrayOf(0L, 80L, 250L)) {
            val retry = Runnable {
                val target = activityRef.get() ?: return@Runnable
                if (!installed || !enabled || resumedActivity.get() !== target) return@Runnable
                if (shouldShow(target)) attach(target)
            }
            pendingAttachCallbacks.add(retry)
            mainHandler.postDelayed(retry, delay)
        }
    }

    private fun cancelAttachRetries() {
        pendingAttachCallbacks.forEach(mainHandler::removeCallbacks)
        pendingAttachCallbacks.clear()
    }

    fun onChatPageChanged(inChatPage: Boolean) {
        chatPageVisible = inChatPage
        if (!installed || currentScope() != FloatingShortcutSettings.SCOPE_HOME) return
        runOnMain {
            if (inChatPage) {
                currentActivity.get()?.let(MiuixSettingsPage::collapseFloatingScriptPluginAgent)
                detachNow(null)
                return@runOnMain
            }
            mainHandler.postDelayed({
                if (!installed || !enabled || chatPageVisible) return@postDelayed
                val activity = resumedActivity.get()
                    ?: WeChatApis.currentActivity()?.currentActivity() ?: return@postDelayed
                if (shouldShow(activity)) attach(activity)
            }, 180L)
        }
    }

    fun attach(activity: Activity) {
        runOnMain {
            if (!installed || !enabled || !shouldShow(activity)) {
                if (currentActivity.get() !== activity || !shouldShow(activity)) {
                    currentActivity.get()?.let(MiuixSettingsPage::collapseFloatingScriptPluginAgent)
                    detachNow(null)
                }
                return@runOnMain
            }
            val previous = currentActivity.get()
            if (previous != null && previous !== activity) {
                MiuixSettingsPage.collapseFloatingScriptPluginAgent(previous)
                detachNow(previous)
            }
            attachNow(activity)
        }
    }

    @Synchronized
    fun destroy() {
        lifecycleApplication.get()?.unregisterActivityLifecycleCallbacks(activityLifecycleCallbacks)
        lifecycleApplication.clear()
        prefs?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        prefs = null
        enabled = false
        installed = false
        enabledItems = emptyList()
        chatPageVisible = false
        runOnMain {
            cancelAttachRetries()
            cancelPendingAction()
            FloatingShortcutIconLoader.clear()
            resumedActivity.clear()
            currentActivity.get()?.let(MiuixSettingsPage::collapseFloatingScriptPluginAgent)
            detachNow(null)
        }
    }

    private fun refreshEnabledState() {
        val value = prefs?.getBoolean(
            FloatingShortcutSettings.KEY_ENABLE,
            FloatingShortcutSettings.DEFAULT_ENABLE
        ) ?: false
        applyEnabledState(value)
    }

    private fun applyEnabledState(value: Boolean) {
        enabled = value
        if (value) prefetchIcons()
        runOnMain {
            if (!value) {
                cancelAttachRetries()
                cancelPendingAction()
                FloatingShortcutIconLoader.clear()
                currentActivity.get()?.let(MiuixSettingsPage::collapseFloatingScriptPluginAgent)
                detachNow(null)
                return@runOnMain
            }
            val activity = resumedActivity.get()
                ?: WeChatApis.currentActivity()?.currentActivity() ?: currentActivity.get()
            if (activity != null) {
                attach(activity)
                scheduleAttachRetry(activity)
            }
        }
    }

    private fun refreshCurrentActivity() {
        runOnMain {
            val activity = resumedActivity.get()
                ?: WeChatApis.currentActivity()?.currentActivity() ?: currentActivity.get()
            val shouldReattach = activity != null && enabled && shouldShow(activity)
            val previous = currentActivity.get()
            if (previous != null && (!shouldReattach || previous !== activity)) {
                MiuixSettingsPage.collapseFloatingScriptPluginAgent(previous)
            }
            detachNow(null)
            if (shouldReattach && activity != null) attachNow(activity)
        }
    }

    private fun detach(activity: Activity) {
        runOnMain {
            if (currentActivity.get() === activity) {
                MiuixSettingsPage.collapseFloatingScriptPluginAgent(activity)
                detachNow(activity)
            }
        }
    }

    private fun attachNow(activity: Activity) {
        if (!enabled || !shouldShow(activity)) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val existing = currentBubble.get()
        if (currentActivity.get() === activity && existing?.parent === decor) {
            existing.bringToFront()
            return
        }
        detachNow(null)

        val bubble = createBubble(activity, decor)
        val size = dp(activity, bubbleSizeDp())
        val params = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START)
        val positionedBeforeAttach = applyStoredPosition(activity, params, decor.width, decor.height, size, size)
        if (!positionedBeforeAttach) bubble.visibility = View.INVISIBLE
        val positionListener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                runOnMain {
                    if (revealPositionedBubble(bubble, decor)) {
                        bubble.removeOnLayoutChangeListener(this)
                    }
                }
            }
        }
        bubble.addOnLayoutChangeListener(positionListener)
        decor.addView(
            bubble,
            params
        )
        currentActivity = WeakReference(activity)
        currentBubble = WeakReference(bubble)
        bubble.post {
            runOnMain {
                if (revealPositionedBubble(bubble, decor)) {
                    bubble.removeOnLayoutChangeListener(positionListener)
                }
            }
        }
    }

    private fun revealPositionedBubble(bubble: View, decor: ViewGroup): Boolean {
        if (currentBubble.get() !== bubble || bubble.parent !== decor) return false
        if (!restorePosition(bubble, decor)) return false
        bubble.visibility = View.VISIBLE
        bubble.bringToFront()
        return true
    }

    private fun detachNow(expectedActivity: Activity?) {
        val activity = currentActivity.get()
        if (expectedActivity != null && activity !== expectedActivity) return
        cancelPendingAction()
        closeMenu()
        val bubble = currentBubble.get()
        (bubble?.parent as? ViewGroup)?.removeView(bubble)
        currentBubble.clear()
        currentActivity.clear()
    }

    private fun createBubble(activity: Activity, decor: ViewGroup): View {
        val bubbleSize = bubbleSizeDp()
        val bubbleColors = bubbleColors()
        val bubbleTone = representativeColor(bubbleColors)
        val bubble = FrameLayout(activity).apply {
            tag = BUBBLE_TAG
            contentDescription = "展开或收起悬浮快捷菜单"
            isClickable = true
            isFocusable = true
            elevation = dp(activity, 8).toFloat()
            background = ovalSurface(activity, bubbleColors)
            foreground = RippleDrawable(
                ColorStateList.valueOf(rippleColor(bubbleTone)),
                null,
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
            )
            clipToOutline = true
        }
        val icon = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = null
            imageTintList = null
            clearColorFilter()
            val padding = dp(activity, (bubbleSize * 0.16f).toInt().coerceAtLeast(5))
            setPadding(padding, padding, padding, padding)
            bindBubbleIcon(this, contrastColor(bubbleTone))
        }
        bubble.addView(
            icon,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        bubble.setOnClickListener {
            if (currentMenu.get()?.parent != null) {
                closeMenu(animated = true)
            } else {
                showMenu(activity, decor, bubble)
            }
        }
        installDragGesture(bubble, decor)
        return bubble
    }

    private fun showMenu(activity: Activity, decor: ViewGroup, bubble: View) {
        cancelPendingAction()
        closeMenu()
        val items = enabledItems
        if (items.isEmpty()) {
            Toast.makeText(activity, "请先添加并启用快捷项", Toast.LENGTH_SHORT).show()
            return
        }
        val displayMode = prefs?.getString(
            FloatingShortcutSettings.KEY_DISPLAY_MODE,
            FloatingShortcutSettings.DEFAULT_DISPLAY_MODE
        ) ?: FloatingShortcutSettings.DEFAULT_DISPLAY_MODE
        val horizontal = isHorizontalDirection()
        val list = LinearLayout(activity).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            // Left/right remain physical directions even in an RTL host locale.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4))
        }
        items.forEachIndexed { index, item ->
            val action = createActionView(activity, item, displayMode)
            list.addView(
                action,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) {
                        if (horizontal) leftMargin = dp(activity, 5) else topMargin = dp(activity, 5)
                    }
                }
            )
        }
        val menu: FrameLayout = if (horizontal) {
            object : HorizontalScrollView(activity) {
                override fun shouldDelayChildPressedState(): Boolean = false
            }
        } else {
            object : ScrollView(activity) {
                override fun shouldDelayChildPressedState(): Boolean = false
            }
        }
        menu.apply {
            visibility = View.INVISIBLE
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            elevation = dp(activity, 10).toFloat()
            addView(
                list,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val dismissLayer = View(activity).apply {
            isClickable = true
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { closeMenu(animated = true) }
        }
        decor.addView(
            dismissLayer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP or Gravity.START
            )
        )
        decor.addView(
            menu,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )
        currentDismissLayer = WeakReference(dismissLayer)
        currentMenu = WeakReference(menu)
        bubble.bringToFront()
        // The visible bubble already has bounds. Place now so the next traversal shows
        // the menu without two extra main-loop/frame callbacks.
        positionMenu(activity, decor, bubble, menu, list)
        val translation = menuAnimationTranslation(activity)
        menu.animate().cancel()
        menu.alpha = 0f
        menu.scaleX = 0.88f
        menu.scaleY = 0.88f
        menu.translationX = if (horizontal) translation else 0f
        menu.translationY = if (horizontal) 0f else translation
        menu.visibility = View.VISIBLE
        menu.bringToFront()
        bubble.bringToFront()
        menu.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(180L)
            .start()
    }

    private fun createActionView(
        activity: Activity,
        item: FloatingShortcutItem,
        displayMode: String
    ): View {
        val showIcon = displayMode != FloatingShortcutSettings.DISPLAY_TEXT
        val showText = displayMode != FloatingShortcutSettings.DISPLAY_ICON
        val actionSize = actionSizeDp()
        val actionColors = actionColors(activity)
        val labelColors = labelColors(activity)
        val iconColor = contrastColor(representativeColor(actionColors))
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            minimumHeight = dp(activity, actionSize)
            contentDescription = item.title
            if (showText) {
                addView(
                    TextView(activity).apply {
                        tag = ACTION_LABEL_TAG
                        text = item.title
                        gravity = Gravity.CENTER
                        textSize = labelTextSizeSp().toFloat()
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        maxWidth = dp(activity, 180)
                        minHeight = dp(activity, actionSize)
                        setPadding(dp(activity, 11), dp(activity, 6), dp(activity, 11), dp(activity, 6))
                        applyLabelColors(this, labelColors)
                        background = actionSurface(activity, circle = false)
                        isDuplicateParentStateEnabled = true
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            if (showIcon) {
                addView(
                    FrameLayout(activity).apply {
                        tag = ACTION_ICON_TAG
                        background = actionSurface(activity, circle = true, circleColors = actionColors)
                        isDuplicateParentStateEnabled = true
                        addView(
                            ImageView(activity).apply {
                                scaleType = ImageView.ScaleType.CENTER_INSIDE
                                bindActionIcon(this, item, iconColor)
                            },
                            FrameLayout.LayoutParams(
                                dp(activity, (actionSize * 0.57f).toInt().coerceAtLeast(20)),
                                dp(activity, (actionSize * 0.57f).toInt().coerceAtLeast(20)),
                                Gravity.CENTER
                            )
                        )
                    },
                    LinearLayout.LayoutParams(dp(activity, actionSize), dp(activity, actionSize)).apply {
                        if (showText) marginStart = dp(activity, 8)
                    }
                )
            }
            setOnClickListener {
                closeMenu()
                dispatchAction(activity, item)
            }
        }
    }

    private fun cancelPendingAction() {
        pendingAction?.let(mainHandler::removeCallbacks)
        pendingAction = null
    }

    private fun dispatchAction(activity: Activity, item: FloatingShortcutItem) {
        if (pendingAction != null) return
        val decor = activity.window?.decorView ?: return
        val activityRef = WeakReference(activity)
        val action = Runnable {
            pendingAction = null
            val target = activityRef.get() ?: return@Runnable
            if (!installed || !enabled || !isUsable(target) || currentActivity.get() !== target) return@Runnable
            executeAction(target, item)
        }
        pendingAction = action
        // Render menu removal before initializing a Compose page or starting a host Activity.
        // Posting from the frame callback runs navigation after this frame's traversal.
        decor.postOnAnimation {
            if (pendingAction === action) mainHandler.post(action)
        }
    }

    private fun executeAction(activity: Activity, item: FloatingShortcutItem) {
        val success = runCatching {
            when (item.actionType) {
                FloatingShortcutSettings.ACTION_MODULE_SETTINGS -> {
                    SettingsUI.show(activity)
                    true
                }
                FloatingShortcutSettings.ACTION_PLUGIN_AGENT -> {
                    val toggled = MiuixSettingsPage.toggleFloatingScriptPluginAgent(activity)
                    (activity.window?.decorView as? ViewGroup)?.post {
                        currentBubble.get()?.bringToFront()
                    }
                    toggled
                }
                FloatingShortcutSettings.ACTION_ACTIVITY -> {
                    val className = normalizeActivityClass(activity, item.target)
                    val intent = Intent().setComponent(ComponentName(activity.packageName, className))
                    activity.startActivity(intent)
                    true
                }
                else -> false
            }
        }.onFailure { error ->
            HLog.e("$TAG 打开快捷项失败: ${item.title} ${error.message}", error)
        }.getOrDefault(false)
        if (!success) Toast.makeText(activity, "无法打开${item.title}", Toast.LENGTH_SHORT).show()
    }

    private fun normalizeActivityClass(context: Context, value: String): String {
        val target = value.trim()
        return if (target.startsWith('.')) context.packageName + target else target
    }

    private fun positionMenu(
        context: Context,
        decor: ViewGroup,
        bubble: View,
        menu: View,
        list: LinearLayout
    ) {
        val margin = dp(context, 12)
        val gap = dp(context, 10)
        val bubbleParams = bubble.layoutParams as? FrameLayout.LayoutParams ?: return
        if (isHorizontalDirection()) {
            positionHorizontalMenu(context, decor, bubble, bubbleParams, menu, list)
            return
        }
        val bubbleCenter = bubbleParams.leftMargin + bubble.width / 2
        val iconOnRight = bubbleCenter >= decor.width / 2
        orientActionViews(list, iconOnRight, context)
        val actionSize = actionSizeDp()
        val expandUp = expandDirection() == FloatingShortcutSettings.EXPAND_UP
        val availableHeight = if (expandUp) {
            bubbleParams.topMargin - gap - margin
        } else {
            decor.height - bubbleParams.topMargin - bubble.height - gap - margin
        }
        val maxHeight = availableHeight
            .coerceAtLeast(dp(context, actionSize))
            .coerceAtMost((decor.height - margin * 2).coerceAtLeast(dp(context, actionSize)))
        val maxWidth = (decor.width - margin * 2).coerceAtLeast(dp(context, 80))
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        )
        val params = menu.layoutParams as? FrameLayout.LayoutParams ?: return
        params.width = menu.measuredWidth.coerceAtMost(maxWidth)
        params.height = menu.measuredHeight.coerceAtMost(maxHeight)
        val showIcons = prefs?.getString(
            FloatingShortcutSettings.KEY_DISPLAY_MODE,
            FloatingShortcutSettings.DEFAULT_DISPLAY_MODE
        ) != FloatingShortcutSettings.DISPLAY_TEXT
        val itemGravity = when {
            !showIcons -> Gravity.CENTER_HORIZONTAL
            iconOnRight -> Gravity.END
            else -> Gravity.START
        }
        list.gravity = itemGravity
        for (index in 0 until list.childCount) {
            val child = list.getChildAt(index)
            (child.layoutParams as? LinearLayout.LayoutParams)?.let { childParams ->
                childParams.gravity = itemGravity
                child.layoutParams = childParams
            }
        }
        val desiredLeft = when {
            !showIcons -> bubbleCenter - params.width / 2
            iconOnRight -> bubbleCenter + dp(context, actionSize / 2) - params.width
            else -> bubbleCenter - dp(context, actionSize / 2)
        }
        params.leftMargin = desiredLeft.coerceIn(
            margin,
            (decor.width - params.width - margin).coerceAtLeast(margin)
        )
        val desiredTop = if (expandUp) {
            bubbleParams.topMargin - params.height - gap
        } else {
            bubbleParams.topMargin + bubble.height + gap
        }
        params.topMargin = desiredTop.coerceIn(
            margin,
            (decor.height - params.height - margin).coerceAtLeast(margin)
        )
        menu.layoutParams = params
        menu.pivotX = (bubbleCenter - params.leftMargin).coerceIn(0, params.width).toFloat()
        menu.pivotY = if (expandUp) params.height.toFloat() else 0f
    }

    private fun positionHorizontalMenu(
        context: Context,
        decor: ViewGroup,
        bubble: View,
        bubbleParams: FrameLayout.LayoutParams,
        menu: View,
        list: LinearLayout
    ) {
        val margin = dp(context, 12)
        val gap = dp(context, 10)
        val expandLeft = expandDirection() == FloatingShortcutSettings.EXPAND_LEFT
        val bubbleCenterY = bubbleParams.topMargin + bubble.height / 2
        orientActionViews(list, expandLeft, context)
        list.gravity = Gravity.CENTER_VERTICAL
        val screenWidth = (decor.width - margin * 2).coerceAtLeast(1)
        val maxHeight = (decor.height - margin * 2).coerceAtLeast(1)
        list.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        )
        val widestAction = (0 until list.childCount).maxOfOrNull {
            list.getChildAt(it).measuredWidth
        } ?: dp(context, actionSizeDp())
        val minimumWidth = (widestAction + list.paddingLeft + list.paddingRight)
            .coerceAtMost((screenWidth - bubble.width - gap).coerceAtLeast(1))
        // Keep at least one complete action accessible when opening toward a screen edge.
        val maxBubbleLeft = (decor.width - margin - bubble.width).coerceAtLeast(margin)
        val adjustedLeft = if (expandLeft) {
            bubbleParams.leftMargin.coerceAtLeast(margin + minimumWidth + gap)
        } else {
            bubbleParams.leftMargin.coerceAtMost(decor.width - margin - bubble.width - minimumWidth - gap)
        }.coerceIn(margin, maxBubbleLeft)
        if (adjustedLeft != bubbleParams.leftMargin) {
            bubbleParams.leftMargin = adjustedLeft
            bubble.layoutParams = bubbleParams
            persistPosition(bubble, decor)
        }
        val availableWidth = if (expandLeft) {
            bubbleParams.leftMargin - gap - margin
        } else {
            decor.width - bubbleParams.leftMargin - bubble.width - gap - margin
        }
        val maxWidth = availableWidth.coerceAtLeast(minimumWidth).coerceAtMost(screenWidth)
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        )
        val params = menu.layoutParams as? FrameLayout.LayoutParams ?: return
        params.width = menu.measuredWidth.coerceAtMost(maxWidth)
        params.height = menu.measuredHeight.coerceAtMost(maxHeight)
        val desiredLeft = if (expandLeft) {
            bubbleParams.leftMargin - params.width - gap
        } else {
            bubbleParams.leftMargin + bubble.width + gap
        }
        params.leftMargin = desiredLeft.coerceIn(
            margin,
            (decor.width - params.width - margin).coerceAtLeast(margin)
        )
        params.topMargin = (bubbleCenterY - params.height / 2).coerceIn(
            margin,
            (decor.height - params.height - margin).coerceAtLeast(margin)
        )
        menu.layoutParams = params
        menu.pivotX = if (expandLeft) params.width.toFloat() else 0f
        menu.pivotY = (bubbleCenterY - params.topMargin).coerceIn(0, params.height).toFloat()
    }

    private fun orientActionViews(list: LinearLayout, iconOnRight: Boolean, context: Context) {
        for (index in 0 until list.childCount) {
            val action = list.getChildAt(index) as? LinearLayout ?: continue
            val label = (0 until action.childCount)
                .map(action::getChildAt)
                .firstOrNull { it.tag == ACTION_LABEL_TAG }
            val icon = (0 until action.childCount)
                .map(action::getChildAt)
                .firstOrNull { it.tag == ACTION_ICON_TAG }
            if (label == null || icon == null) continue
            val first = if (iconOnRight) label else icon
            if (action.getChildAt(0) === first) continue
            val labelParams = label.layoutParams as LinearLayout.LayoutParams
            val iconParams = icon.layoutParams as LinearLayout.LayoutParams
            labelParams.marginStart = if (iconOnRight) 0 else dp(context, 8)
            iconParams.marginStart = if (iconOnRight) dp(context, 8) else 0
            action.removeAllViews()
            if (iconOnRight) {
                action.addView(label, labelParams)
                action.addView(icon, iconParams)
            } else {
                action.addView(icon, iconParams)
                action.addView(label, labelParams)
            }
        }
    }

    private fun closeMenu(animated: Boolean = false) {
        val dismissLayer = currentDismissLayer.get()
        (dismissLayer?.parent as? ViewGroup)?.removeView(dismissLayer)
        currentDismissLayer.clear()
        val menu = currentMenu.get() ?: return
        val parent = menu.parent as? ViewGroup
        if (!animated || parent == null || menu.visibility != View.VISIBLE) {
            menu.animate().cancel()
            parent?.removeView(menu)
            if (currentMenu.get() === menu) currentMenu.clear()
            return
        }
        menu.isClickable = false
        menu.animate().cancel()
        menu.animate()
            .alpha(0f)
            .scaleX(0.88f)
            .scaleY(0.88f)
            .translationX(if (isHorizontalDirection()) menuAnimationTranslation(menu.context) else 0f)
            .translationY(if (isHorizontalDirection()) 0f else menuAnimationTranslation(menu.context))
            .setDuration(140L)
            .withEndAction {
                (menu.parent as? ViewGroup)?.removeView(menu)
                if (currentMenu.get() === menu) currentMenu.clear()
            }
            .start()
    }

    private fun installDragGesture(bubble: View, decor: ViewGroup) {
        val slop = ViewConfiguration.get(bubble.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startLeft = 0
        var startTop = 0
        var startMenuLeft = 0
        var startMenuTop = 0
        var dragging = false
        bubble.setOnTouchListener { view, event ->
            val params = view.layoutParams as? FrameLayout.LayoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startLeft = params.leftMargin
                    startTop = params.topMargin
                    val menuParams = currentMenu.get()?.layoutParams as? FrameLayout.LayoutParams
                    startMenuLeft = menuParams?.leftMargin ?: 0
                    startMenuTop = menuParams?.topMargin ?: 0
                    dragging = false
                    view.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        view.isPressed = false
                    }
                    if (dragging) {
                        val margin = dp(view.context, 8)
                        val baseMaxTop = (decor.height - view.height - margin).coerceAtLeast(margin)
                        val openMenu = currentMenu.get()?.takeIf { it.parent === decor }
                        val direction = expandDirection()
                        val expandUp = direction == FloatingShortcutSettings.EXPAND_UP
                        val expandDown = direction == FloatingShortcutSettings.EXPAND_DOWN
                        val baseMaxLeft = (decor.width - view.width - margin).coerceAtLeast(margin)
                        val minLeft = if (openMenu != null && direction == FloatingShortcutSettings.EXPAND_LEFT) {
                            (margin + openMenu.width + dp(view.context, 10)).coerceAtMost(baseMaxLeft)
                        } else {
                            margin
                        }
                        val maxLeft = if (openMenu != null && direction == FloatingShortcutSettings.EXPAND_RIGHT) {
                            (decor.width - margin - openMenu.width - dp(view.context, 10) - view.width)
                                .coerceAtLeast(minLeft)
                                .coerceAtMost(baseMaxLeft)
                        } else {
                            baseMaxLeft
                        }
                        val minTop = if (openMenu != null && expandUp) {
                            (margin + openMenu.height + dp(view.context, 10)).coerceAtMost(baseMaxTop)
                        } else {
                            margin
                        }
                        val maxTop = if (openMenu != null && expandDown) {
                            (decor.height - margin - openMenu.height - dp(view.context, 10) - view.height)
                                .coerceAtLeast(minTop)
                                .coerceAtMost(baseMaxTop)
                        } else {
                            baseMaxTop
                        }
                        params.leftMargin = (startLeft + dx.toInt()).coerceIn(minLeft, maxLeft)
                        params.topMargin = (startTop + dy.toInt()).coerceIn(
                            minTop,
                            maxTop
                        )
                        view.layoutParams = params
                        moveOpenMenuDuringDrag(
                            decor,
                            params.leftMargin - startLeft,
                            params.topMargin - startTop,
                            startMenuLeft,
                            startMenuTop
                        )
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    if (dragging) {
                        persistPosition(view, decor)
                        repositionOpenMenu(view, decor)
                    } else {
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    if (dragging) {
                        persistPosition(view, decor)
                        repositionOpenMenu(view, decor)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun moveOpenMenuDuringDrag(
        decor: ViewGroup,
        dx: Int,
        dy: Int,
        startLeft: Int,
        startTop: Int
    ) {
        val menu = currentMenu.get() ?: return
        val params = menu.layoutParams as? FrameLayout.LayoutParams ?: return
        val margin = dp(menu.context, 12)
        params.leftMargin = (startLeft + dx).coerceIn(
            margin,
            (decor.width - menu.width - margin).coerceAtLeast(margin)
        )
        params.topMargin = (startTop + dy).coerceIn(
            margin,
            (decor.height - menu.height - margin).coerceAtLeast(margin)
        )
        menu.layoutParams = params
    }

    private fun repositionOpenMenu(bubble: View, decor: ViewGroup) {
        val menu = currentMenu.get() as? FrameLayout ?: return
        val list = menu.getChildAt(0) as? LinearLayout ?: return
        positionMenu(bubble.context, decor, bubble, menu, list)
        menu.bringToFront()
        bubble.bringToFront()
    }

    private fun restorePosition(view: View, decor: ViewGroup): Boolean {
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return false
        if (!applyStoredPosition(view.context, params, decor.width, decor.height, view.width, view.height)) return false
        view.layoutParams = params
        return true
    }

    private fun applyStoredPosition(
        context: Context,
        params: FrameLayout.LayoutParams,
        containerWidth: Int,
        containerHeight: Int,
        viewWidth: Int,
        viewHeight: Int
    ): Boolean {
        if (containerWidth <= 0 || containerHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return false
        val margin = dp(context, 8)
        val availableX = (containerWidth - viewWidth - margin * 2).coerceAtLeast(0)
        val availableY = (containerHeight - viewHeight - margin * 2).coerceAtLeast(0)
        val x = prefs?.getFloat(
            FloatingShortcutSettings.KEY_POSITION_X,
            FloatingShortcutSettings.DEFAULT_POSITION_X
        )?.coerceIn(0f, 1f) ?: FloatingShortcutSettings.DEFAULT_POSITION_X
        val y = prefs?.getFloat(
            FloatingShortcutSettings.KEY_POSITION_Y,
            FloatingShortcutSettings.DEFAULT_POSITION_Y
        )?.coerceIn(0f, 1f) ?: FloatingShortcutSettings.DEFAULT_POSITION_Y
        params.leftMargin = margin + (availableX * x).toInt()
        params.topMargin = margin + (availableY * y).toInt()
        return true
    }

    private fun persistPosition(view: View, decor: ViewGroup) {
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val margin = dp(view.context, 8)
        val availableX = (decor.width - view.width - margin * 2).coerceAtLeast(0)
        val availableY = (decor.height - view.height - margin * 2).coerceAtLeast(0)
        params.leftMargin = params.leftMargin.coerceIn(margin, margin + availableX)
        params.topMargin = params.topMargin.coerceIn(margin, margin + availableY)
        view.layoutParams = params
        prefs?.edit()
            ?.putFloat(
                FloatingShortcutSettings.KEY_POSITION_X,
                if (availableX == 0) 0f else (params.leftMargin - margin).toFloat() / availableX
            )
            ?.putFloat(
                FloatingShortcutSettings.KEY_POSITION_Y,
                if (availableY == 0) 0f else (params.topMargin - margin).toFloat() / availableY
            )
            ?.apply()
    }

    internal fun onIconFileChanged(path: String) {
        runOnMain {
            if (!installed || !enabled) return@runOnMain
            val used = prefs?.getString(FloatingShortcutSettings.KEY_BUBBLE_ICON, "") == path ||
                prefs?.getString(FloatingShortcutSettings.KEY_BUBBLE_DARK_ICON, "") == path ||
                enabledItems.any { it.iconPath == path || it.darkIconPath == path }
            if (used) refreshCurrentActivity()
        }
    }

    private fun prefetchIcons() {
        if (!enabled) return
        FloatingShortcutIconLoader.prefetch(buildList {
            add(prefs?.getString(FloatingShortcutSettings.KEY_BUBBLE_ICON, "").orEmpty())
            add(prefs?.getString(FloatingShortcutSettings.KEY_BUBBLE_DARK_ICON, "").orEmpty())
            enabledItems.forEach { item ->
                add(item.iconPath)
                add(item.darkIconPath)
            }
        })
    }

    private fun bindBubbleIcon(view: ImageView, tint: Int) {
        val regularPath = prefs?.getString(FloatingShortcutSettings.KEY_BUBBLE_ICON, "").orEmpty()
        val darkPath = prefs?.getString(FloatingShortcutSettings.KEY_BUBBLE_DARK_ICON, "").orEmpty()
        val path = if (isDark(view.context) && darkPath.isNotBlank()) darkPath else regularPath
        FloatingShortcutIconLoader.bind(view, path, FloatingShortcutGlyphDrawable(FloatingShortcutGlyph.MENU, tint))
    }

    private fun bindActionIcon(view: ImageView, item: FloatingShortcutItem, tint: Int) {
        val path = if (isDark(view.context) && item.darkIconPath.isNotBlank()) {
            item.darkIconPath
        } else {
            item.iconPath
        }
        val fallback = if (item.actionType == FloatingShortcutSettings.ACTION_PLUGIN_AGENT) {
            HchatAgentIconDrawable(tint, HchatAgentIconDrawable.Frame.CIRCLE)
        } else {
            FloatingShortcutGlyphDrawable(FloatingShortcutGlyphs.forItem(item), tint)
        }
        FloatingShortcutIconLoader.bind(view, path, fallback)
    }

    private fun ovalSurface(context: Context, colors: IntArray): Drawable {
        val tone = representativeColor(colors)
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            colors.takeIf { it.size > 1 }
        ).apply {
            shape = GradientDrawable.OVAL
            if (colors.size == 1) setColor(colors[0])
            setStroke(dp(context, 1), if (isLightColor(tone)) 0x18000000 else 0x28ffffff)
        }
    }

    private fun actionSurface(context: Context, circle: Boolean, circleColors: IntArray? = null): Drawable {
        val surfaceColors = if (circle) {
            circleColors ?: actionColors(context)
        } else if (isDark(context)) {
            intArrayOf(0xf2383b40.toInt())
        } else {
            intArrayOf(0xf5ffffff.toInt())
        }
        val surfaceTone = representativeColor(surfaceColors)
        val base = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            surfaceColors.takeIf { it.size > 1 }
        ).apply {
            if (circle) {
                shape = GradientDrawable.OVAL
            } else {
                cornerRadius = dp(context, 14).toFloat()
            }
            if (surfaceColors.size == 1) setColor(surfaceColors[0])
            setStroke(dp(context, 1), if (isLightColor(surfaceTone)) 0x16000000 else 0x24ffffff)
        }
        val mask = if (circle) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        } else {
            null
        }
        return RippleDrawable(
            ColorStateList.valueOf(rippleColor(surfaceTone)),
            base,
            mask
        )
    }

    private fun bubbleSizeDp(): Int = prefs?.getInt(
        FloatingShortcutSettings.KEY_BUBBLE_SIZE,
        FloatingShortcutSettings.DEFAULT_BUBBLE_SIZE
    )?.coerceIn(
        FloatingShortcutSettings.MIN_BUTTON_SIZE,
        FloatingShortcutSettings.MAX_BUTTON_SIZE
    ) ?: FloatingShortcutSettings.DEFAULT_BUBBLE_SIZE

    private fun actionSizeDp(): Int = prefs?.getInt(
        FloatingShortcutSettings.KEY_ACTION_SIZE,
        FloatingShortcutSettings.DEFAULT_ACTION_SIZE
    )?.coerceIn(
        FloatingShortcutSettings.MIN_BUTTON_SIZE,
        FloatingShortcutSettings.MAX_BUTTON_SIZE
    ) ?: FloatingShortcutSettings.DEFAULT_ACTION_SIZE

    private fun expandDirection(): String = prefs?.getString(
        FloatingShortcutSettings.KEY_EXPAND_DIRECTION,
        FloatingShortcutSettings.DEFAULT_EXPAND_DIRECTION
    )?.takeIf {
        it == FloatingShortcutSettings.EXPAND_UP || it == FloatingShortcutSettings.EXPAND_DOWN ||
            it == FloatingShortcutSettings.EXPAND_LEFT || it == FloatingShortcutSettings.EXPAND_RIGHT
    } ?: FloatingShortcutSettings.DEFAULT_EXPAND_DIRECTION

    private fun isHorizontalDirection(): Boolean = when (expandDirection()) {
        FloatingShortcutSettings.EXPAND_LEFT, FloatingShortcutSettings.EXPAND_RIGHT -> true
        else -> false
    }

    private fun menuAnimationTranslation(context: Context): Float {
        val distance = dp(context, 8).toFloat()
        return when (expandDirection()) {
            FloatingShortcutSettings.EXPAND_UP, FloatingShortcutSettings.EXPAND_LEFT -> distance
            else -> -distance
        }
    }

    private fun bubbleColors(): IntArray = parseColorSpec(
        prefs?.getString(
            FloatingShortcutSettings.KEY_BUBBLE_COLOR,
            FloatingShortcutSettings.DEFAULT_BUBBLE_COLOR
        ),
        Color.WHITE
    )

    private fun actionColors(context: Context): IntArray {
        val fallback = if (isDark(context)) 0xf2383b40.toInt() else 0xf5ffffff.toInt()
        return parseColorSpec(
            prefs?.getString(
                FloatingShortcutSettings.KEY_ACTION_COLOR,
                FloatingShortcutSettings.DEFAULT_ACTION_COLOR
            ),
            fallback
        )
    }

    private fun labelTextSizeSp(): Int = prefs?.getInt(
        FloatingShortcutSettings.KEY_LABEL_TEXT_SIZE,
        FloatingShortcutSettings.DEFAULT_LABEL_TEXT_SIZE
    )?.coerceIn(
        FloatingShortcutSettings.MIN_LABEL_TEXT_SIZE,
        FloatingShortcutSettings.MAX_LABEL_TEXT_SIZE
    ) ?: FloatingShortcutSettings.DEFAULT_LABEL_TEXT_SIZE

    private fun labelColors(context: Context): IntArray {
        val fallback = if (isDark(context)) Color.WHITE else 0xff202124.toInt()
        return parseColorSpec(
            prefs?.getString(
                FloatingShortcutSettings.KEY_LABEL_COLOR,
                FloatingShortcutSettings.DEFAULT_LABEL_COLOR
            ),
            fallback
        )
    }

    private fun applyLabelColors(view: TextView, colors: IntArray) {
        view.paint.shader = null
        view.setTextColor(colors[0])
        if (colors.size < 2) return
        view.post {
            val availableWidth = (view.width - view.totalPaddingLeft - view.totalPaddingRight)
                .toFloat()
                .coerceAtLeast(1f)
            val textWidth = view.paint.measureText(view.text.toString()).coerceIn(1f, availableWidth)
            val startX = view.totalPaddingLeft.toFloat()
            view.paint.shader = LinearGradient(
                startX,
                0f,
                startX + textWidth,
                0f,
                colors[0],
                colors[1],
                Shader.TileMode.CLAMP
            )
            view.invalidate()
        }
    }

    private fun parseColorSpec(value: String?, fallback: Int): IntArray {
        val parts = value.orEmpty().split(',').take(2)
        val start = parseColor(parts.getOrNull(0), fallback)
        val end = parts.getOrNull(1)?.let { parseColor(it, start) }
        return if (end != null && end != start) intArrayOf(start, end) else intArrayOf(start)
    }

    private fun parseColor(value: String?, fallback: Int): Int {
        return value?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching { Color.parseColor(raw) }.getOrNull()
        } ?: fallback
    }

    private fun contrastColor(background: Int): Int =
        if (isLightColor(background)) 0xff202124.toInt() else Color.WHITE

    private fun representativeColor(colors: IntArray): Int {
        val start = colors.first()
        val end = colors.last()
        return Color.argb(
            (Color.alpha(start) + Color.alpha(end)) / 2,
            (Color.red(start) + Color.red(end)) / 2,
            (Color.green(start) + Color.green(end)) / 2,
            (Color.blue(start) + Color.blue(end)) / 2
        )
    }

    private fun rippleColor(background: Int): Int =
        if (isLightColor(background)) 0x18000000 else 0x28ffffff

    private fun isLightColor(color: Int): Boolean {
        val luminance = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
        return luminance >= 160
    }

    private fun shouldShow(activity: Activity): Boolean {
        if (!isUsable(activity)) return false
        val scope = currentScope()
        if (scope == FloatingShortcutSettings.SCOPE_ALL) return true
        val inChatPage = chatPageVisible || WeChatApis.chatPage()?.isInChatPage == true
        return activity.javaClass.name == LAUNCHER_ACTIVITY && !inChatPage
    }

    private fun currentScope(): String {
        return prefs?.getString(
            FloatingShortcutSettings.KEY_SCOPE,
            FloatingShortcutSettings.DEFAULT_SCOPE
        ) ?: FloatingShortcutSettings.DEFAULT_SCOPE
    }

    private fun isUsable(activity: Activity): Boolean {
        return !activity.isFinishing && !activity.isDestroyed && activity.packageName == "com.tencent.mm"
    }

    private fun isDark(context: Context): Boolean {
        return context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun runOnMain(block: () -> Unit) {
        val guarded = Runnable {
            runCatching(block).onFailure { error ->
                HLog.e("$TAG 主线程悬浮层操作失败: ${error.message}", error)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) guarded.run() else mainHandler.post(guarded)
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }
}
