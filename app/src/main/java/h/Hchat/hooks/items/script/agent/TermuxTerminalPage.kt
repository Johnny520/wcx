package h.Hchat.hooks.items.script.agent

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout

// 扩展键行沿用 ZeroTermux 布局，只把第二行首格的 INS 换成 ☰ 会话入口
class TermuxTerminalPage(context: Context) : LinearLayout(context) {

    private data class ExtraKey(val label: String, val seq: String)

    private companion object {
        const val COLOR_TEXT = 0xFFFFFFFF.toInt()

        const val COLOR_TEXT_ACTIVE = 0xFF48BAF3.toInt()

        const val COLOR_KEY_PRESSED = 0xFF9E9E9E.toInt()

        const val COLOR_ROW_BG = 0x80000000.toInt()

        const val KEY_TEXT_SP = 14f

        const val TERMINAL_MARGIN_DP = 3f

        const val ROOT_MARGIN_DP = 3f

        const val ROW_HEIGHT_DP = 37.5f

        const val BOTTOM_SPACE_DP = 1f

        val CTRL = ExtraKey("CTRL", "@ctrl")
        val ALT = ExtraKey("ALT", "@alt")
        val SHIFT = ExtraKey("SHIFT", "@shift")

        val SESSIONS = ExtraKey("DRAWER", "@sessions")

        val MODIFIER_SEQS = setOf("@ctrl", "@alt", "@shift")

        val DISPLAY_MAP = mapOf(
            "LEFT" to "\u2190",
            "RIGHT" to "\u2192",
            "UP" to "\u2191",
            "DOWN" to "\u2193",
            "ENTER" to "\u21B2",
            "TAB" to "\u21B9",
            "BKSP" to "\u232B",
            "DEL" to "\u2326",
            "DRAWER" to "\u2630",
            "KEYBOARD" to "\u2328",
            "PASTE" to "\u2398",
            "SCROLL" to "\u21F3",
            "-" to "\u2015",
        )

        val KEYS = listOf(
            listOf(
                ExtraKey("ESC", "\u001B"),
                ExtraKey("TAB", "\t"),
                CTRL,
                ALT,
                ExtraKey("-", "-"),
                ExtraKey("UP", "\u001B[A"),
                ExtraKey("ENTER", "\r"),
            ),
            listOf(
                SESSIONS,
                ExtraKey("END", "\u001B[F"),
                SHIFT,
                ExtraKey(":", ":"),
                ExtraKey("LEFT", "\u001B[D"),
                ExtraKey("DOWN", "\u001B[B"),
                ExtraKey("RIGHT", "\u001B[C"),
            ),
        )
    }

    val terminal = ProotTerminalView(context)

    private val keysRow = GridLayout(context)
    private val modifierButtons = LinkedHashMap<String, Button>()

    private var platformTop = 0
    private var platformBottom = 0
    private var composeTop = 0
    private var composeBottom = 0

    // 键盘展开/收起动画期间不跟着每帧改 padding，等动画结束一次到位（避免界面被顶得一顿一顿+闪烁）
    private var insetsAnimating = false

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)

        val rootRel = RelativeLayout(context)
        val rootParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        rootParams.leftMargin = dp(ROOT_MARGIN_DP)
        rootParams.rightMargin = dp(ROOT_MARGIN_DP)
        addView(rootRel, rootParams)

        keysRow.id = View.generateViewId()
        buildKeysRow()
        val keysParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            dp(ROW_HEIGHT_DP * KEYS.size),
        )
        keysParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        rootRel.addView(keysRow, keysParams)

        val areaRel = RelativeLayout(context)
        val areaParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT,
        )
        areaParams.addRule(RelativeLayout.ABOVE, keysRow.id)
        rootRel.addView(areaRel, areaParams)

        val innerRel = RelativeLayout(context)
        areaRel.addView(
            innerRel,
            RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val terminalParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT,
        )
        terminalParams.leftMargin = dp(TERMINAL_MARGIN_DP)
        terminalParams.rightMargin = dp(TERMINAL_MARGIN_DP)
        innerRel.addView(terminal, terminalParams)

        addView(View(context), LayoutParams(LayoutParams.MATCH_PARENT, dp(BOTTOM_SPACE_DP)))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(
                WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP
            ) {
                override fun onStart(
                    animation: WindowInsetsAnimation,
                    bounds: WindowInsetsAnimation.Bounds,
                ): WindowInsetsAnimation.Bounds {
                    insetsAnimating = true
                    return bounds
                }

                override fun onProgress(
                    insets: WindowInsets,
                    runningAnimations: MutableList<WindowInsetsAnimation>,
                ): WindowInsets = insets

                override fun onEnd(animation: WindowInsetsAnimation) {
                    insetsAnimating = false
                    requestApplyInsets()
                }
            })
        }

        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                platformTop = bars.top
                platformBottom = ime.bottom
            } else {
                @Suppress("DEPRECATION")
                platformTop = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                platformBottom = insets.systemWindowInsetBottom
            }
            syncInsets()
            insets
        }
    }

    fun applyInsetsFromCompose(top: Int, bottom: Int, imeVisible: Boolean) {
        composeTop = top
        composeBottom = bottom
        syncInsets()
    }

    private fun syncInsets() {
        if (insetsAnimating) return
        setPadding(0, maxOf(platformTop, composeTop), 0, maxOf(platformBottom, composeBottom))
    }

    fun start(): Boolean = terminal.start()

    fun detach() = terminal.detach()

    fun setOnExitRequested(cb: () -> Unit) = terminal.setOnExitRequested(cb)

    private fun buildKeysRow() {
        keysRow.rowCount = KEYS.size
        keysRow.columnCount = KEYS.maxOf { it.size }
        keysRow.setBackgroundColor(COLOR_ROW_BG)
        KEYS.forEachIndexed { row, keys ->
            keys.forEachIndexed { col, key ->
                keysRow.addView(createKeyButton(key, row, col))
            }
        }
    }

    private fun createKeyButton(key: ExtraKey, row: Int, col: Int): Button {
        val button = Button(context, null, android.R.attr.buttonBarButtonStyle)
        button.text = DISPLAY_MAP[key.label] ?: key.label
        button.setTextColor(COLOR_TEXT)
        button.isAllCaps = true
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, KEY_TEXT_SP)
        button.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        button.letterSpacing = 0f
        button.setPadding(0, 0, 0, 0)
        button.minimumWidth = 0
        button.minimumHeight = 0
        button.gravity = Gravity.CENTER
        button.stateListAnimator = null
        button.background = ColorDrawable(Color.TRANSPARENT)
        if (key.seq in MODIFIER_SEQS) modifierButtons[key.seq] = button
        button.setOnClickListener { handleKey(key) }
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.setBackgroundColor(COLOR_KEY_PRESSED)
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }

                MotionEvent.ACTION_UP -> {
                    v.setBackgroundColor(Color.TRANSPARENT)
                    v.performClick()
                }

                MotionEvent.ACTION_CANCEL -> v.setBackgroundColor(Color.TRANSPARENT)
            }
            true
        }
        val params = GridLayout.LayoutParams()
        params.width = 0
        params.height = 0
        params.setMargins(0, 0, 0, 0)
        params.columnSpec = GridLayout.spec(col, GridLayout.FILL, 1f)
        params.rowSpec = GridLayout.spec(row, GridLayout.FILL, 1f)
        button.layoutParams = params
        return button
    }

    private fun handleKey(key: ExtraKey) {
        when (key.seq) {
            "@ctrl" -> {
                terminal.setCtrlActive(!terminal.ctrlActive)
                refreshModifierColors()
            }

            "@alt" -> {
                terminal.setAltActive(!terminal.altActive)
                refreshModifierColors()
            }

            "@shift" -> {
                terminal.setShiftActive(!terminal.shiftActive)
                refreshModifierColors()
            }

            "@sessions" -> terminal.showSessionMenu()

            else -> terminal.sendKey(key.seq)
        }
        terminal.requestTerminalFocus()
    }

    private fun refreshModifierColors() {
        modifierButtons.forEach { (seq, button) ->
            val active = when (seq) {
                "@ctrl" -> terminal.ctrlActive
                "@alt" -> terminal.altActive
                else -> terminal.shiftActive
            }
            button.setTextColor(if (active) COLOR_TEXT_ACTIVE else COLOR_TEXT)
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
