package com.Johnny.wcx.features.items.system.agent

import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import com.Johnny.wcx.features.api.agent.WeAgentService
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.agent.WeAgentBall
import com.Johnny.wcx.ui.agent.WeAgentPanel
import com.Johnny.wcx.ui.utils.InjectedUiTheme
import com.Johnny.wcx.ui.utils.LifecycleOwnerProvider
import com.Johnny.wcx.ui.utils.setLifecycleOwner
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.getSystemService
import com.Johnny.wcx.utils.android.showToast

/**
 * Manages the WeAgent system overlay (`TYPE_APPLICATION_OVERLAY`): a draggable floating ball and an
 * expandable panel window, both added to the [WindowManager] rather than a host Activity's view
 * tree. This survives across all WeChat Activities (and even when WeChat is backgrounded) with no
 * per-Activity hooks.
 *
 * The overlay lives in WeChat's process, so the effective `SYSTEM_ALERT_WINDOW` grant is WeChat's;
 * we gate mounting on [Settings.canDrawOverlays] and toast guidance if it's missing.
 */
object WeAgentOverlayController {

    private const val TAG = "WeAgentOverlayController"

    private const val PREF_BALL_X = "weagent_ball_x"
    private const val PREF_BALL_Y = "weagent_ball_y"

    private val wm: WindowManager
        get() = HostInfo.application.getSystemService<WindowManager>()

    private var ballView: ComposeView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: ComposeView? = null

    // Ball window position captured at drag start (absolute-offset dragging, set in onDragStart).
    private var dragStartX = 0
    private var dragStartY = 0

    /** Whether the ball window is currently attached to the [WindowManager]. */
    @Volatile
    var isShown = false
        private set

    /** Whether the feature is enabled (user wants the overlay). Distinct from actual attachment. */
    @Volatile
    private var desiredVisible = false

    /** When true, the ball is only attached while WeChat is in the foreground (§ 界面 setting). */
    @Volatile
    private var foregroundOnly = false

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(HostInfo.application)

    /**
     * Marks the overlay as desired (feature enabled) and reconciles visibility. With
     * [foregroundOnly] on, the ball is only attached while WeChat is foreground; the tracker drives
     * later attach/detach. Idempotent.
     */
    fun show() {
        desiredVisible = true
        if (!canDrawOverlays()) {
            showToast("请在系统设置中为微信开启「显示在其他应用上层」")
            WeLogger.w(TAG, "no SYSTEM_ALERT_WINDOW permission for host process")
            return
        }
        wireForegroundTracker()
        reconcile()
    }

    /** Marks the overlay as no longer desired and detaches it. */
    fun hide() {
        desiredVisible = false
        reconcile()
    }

    /**
     * Sets whether the overlay is foreground-only. Registers the foreground tracker when enabling so
     * background transitions detach the ball, and reconciles immediately (e.g. re-attaches if WeChat
     * is already foreground, or detaches now if it's background).
     */
    fun setForegroundOnly(enabled: Boolean) {
        foregroundOnly = enabled
        if (enabled) wireForegroundTracker()
        reconcile()
    }

    private fun wireForegroundTracker() {
        WeChatForegroundTracker.onChanged = { reconcile() }
        WeChatForegroundTracker.ensureRegistered()
    }

    /** True when the ball should currently be attached given desire, permission, and foreground. */
    private fun shouldBeVisible(): Boolean =
        desiredVisible && canDrawOverlays() &&
                (!foregroundOnly || WeChatForegroundTracker.isForeground)

    /** Attaches or detaches the ball window to match [shouldBeVisible]. Must run on the main thread. */
    private fun reconcile() {
        val want = shouldBeVisible()
        if (want && !isShown) {
            runCatching { addBall() }.onFailure { WeLogger.e(TAG, "failed to add ball", it) }
            isShown = true
        } else if (!want && isShown) {
            removePanel()
            ballView?.let { runCatching { wm.removeView(it) } }
            ballView = null
            ballParams = null
            isShown = false
        }
    }

    // -----------------------------------------------------------------------------------------
    // Ball window
    // -----------------------------------------------------------------------------------------

    private fun addBall() {
        val params = baseLayoutParams(focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = WePrefs.getIntOrDef(PREF_BALL_X, 24)
            y = WePrefs.getIntOrDef(PREF_BALL_Y, 240)
        }
        ballParams = params

        val owner = LifecycleOwnerProvider.lifecycleOwner
        val view = ComposeView(HostInfo.application).apply {
            setLifecycleOwner(owner)
            setContent {
                InjectedUiTheme {
                    WeAgentBall(
                        state = WeAgentService.ballState.value,
                        onClick = { togglePanel() },
                        onDragStart = {
                            ballParams?.let { dragStartX = it.x; dragStartY = it.y }
                        },
                        onDrag = { dx, dy ->
                            val p = ballParams
                            val v = ballView
                            if (p != null && v != null) {
                                p.x = dragStartX + dx.toInt()
                                p.y = dragStartY + dy.toInt()
                                runCatching { wm.updateViewLayout(v, p) }
                            }
                        },
                        onDragEnd = {
                            val p = ballParams ?: return@WeAgentBall
                            val v = ballView
                            if (v != null) {
                                clampToScreen(v, p)
                                runCatching { wm.updateViewLayout(v, p) }
                            }
                            WePrefs.putInt(PREF_BALL_X, p.x)
                            WePrefs.putInt(PREF_BALL_Y, p.y)
                        },
                    )
                }
            }
        }
        ballView = view
        wm.addView(view, params)
    }

    /** Keeps the ball fully on-screen after a drag. */
    private fun clampToScreen(view: View, params: WindowManager.LayoutParams) {
        val metrics = view.resources.displayMetrics
        val w = if (view.width > 0) view.width else (52 * metrics.density).toInt()
        val h = if (view.height > 0) view.height else (52 * metrics.density).toInt()
        params.x = params.x.coerceIn(0, (metrics.widthPixels - w).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - h).coerceAtLeast(0))
    }

    // -----------------------------------------------------------------------------------------
    // Panel window
    // -----------------------------------------------------------------------------------------

    fun togglePanel() {
        if (panelView != null) removePanel() else addPanel()
    }

    private fun addPanel() {
        val params = baseLayoutParams(focusable = true).apply {
            gravity = Gravity.CENTER
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
        val owner = LifecycleOwnerProvider.lifecycleOwner
        val view = ComposeView(HostInfo.application).apply {
            setLifecycleOwner(owner)
            setContent {
                InjectedUiTheme {
                    WeAgentPanel(onDismiss = { removePanel() })
                }
            }
        }
        panelView = view
        runCatching { wm.addView(view, params) }.onFailure { WeLogger.e(TAG, "failed to add panel", it) }
    }

    private fun removePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
    }

    // -----------------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun baseLayoutParams(focusable: Boolean): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!focusable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        )
    }
}
