package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.tencent.mm.ui.chatting.ChattingUI
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger

private const val TAG = "ImmersiveStatusBar"

// 用于标记原始状态已保存的 tag key
private const val TAG_ORIGINAL_BG_LP = 0x7F000001
private const val TAG_ORIGINAL_TOOLBAR_TOP = 0x7F000002
private const val TAG_ORIGINAL_STATUS_BAR_COLOR = 0x7F000003

/**
 * 聊天页沉浸式透明状态栏
 *
 * 开启后聊天界面状态栏全透明，适配手机系统沉浸规范。
 * 聊天背景图片向上延伸填充状态栏区域，无顶部黑边、画面截断、错位问题。
 *
 * 与现有全局主题背景（TabTheme）、单聊聊天背景功能互不冲突，
 * 不影响原有背景渲染逻辑。仅修改状态栏透明度和视图布局参数。
 */
@Feature(
    name = "聊天页沉浸式透明状态栏",
    categories = ["聊天"],
    description = "聊天界面状态栏全透明，聊天背景向上延伸填充状态栏区域"
)
object ImmersiveStatusBar : SwitchFeature() {

    override val defaultEnabled: Boolean = false

    // 记录每个 Activity 修改前的原始值，用于 disable 时恢复
    private val trackedActivities = mutableSetOf<Activity>()

    override fun onEnable() {
        runCatching {
            // Hook ①：ChattingUI.onCreate — 设置沉浸式状态栏
            ChattingUI::class.reflekt().firstMethod { name = "onCreate" }.hookAfter {
                val activity = thisObject as? Activity ?: return@hookAfter
                applyImmersiveStatusBar(activity)
            }

            // Hook ②：ChattingUI.onResume — 重新应用（防止系统或其它功能覆盖）
            ChattingUI::class.reflekt().firstMethod { name = "onResume" }.hookAfter {
                val activity = thisObject as? Activity ?: return@hookAfter
                applyImmersiveStatusBar(activity)
            }

            // Hook ③：ChattingUI.onDestroy — 清理记录，防止内存泄漏
            ChattingUI::class.reflekt().firstMethod { name = "onDestroy" }.hookBefore {
                val activity = thisObject as? Activity ?: return@hookBefore
                trackedActivities.remove(activity)
            }

            WeLogger.i(TAG, "聊天页沉浸式透明状态栏: 已开启")
        }.onFailure {
            WeLogger.e(TAG, "启用失败", it)
        }
    }

    override fun onDisable() {
        runCatching {
            for (activity in trackedActivities.toList()) {
                runCatching {
                    restoreStatusBar(activity)
                }.onFailure {
                    WeLogger.e(TAG, "恢复状态栏失败: ${activity.javaClass.simpleName}", it)
                }
            }
            trackedActivities.clear()
            WeLogger.i(TAG, "聊天页沉浸式透明状态栏: 已关闭")
        }.onFailure {
            WeLogger.e(TAG, "关闭失败", it)
        }
    }

    // ─── 沉浸式状态栏应用 ────────────────────────────────────────────────────────

    /**
     * 对 Activity 应用沉浸式透明状态栏
     */
    private fun applyImmersiveStatusBar(activity: Activity) {
        runCatching {
            val window = activity.window ?: return

            // 保存原始状态栏颜色（仅首次）
            saveOriginalStatusBarColor(window)

            // 1. 设置状态栏透明 + 关闭对比度强制
            window.statusBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
            }

            // 2. 让内容延伸到状态栏后面
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                    (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            }

            // 3. 设置状态栏图标为浅色（微信聊天背景通常为深色）
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.isAppearanceLightStatusBars = false

            // 4. 调整聊天背景视图，使其延伸到状态栏区域
            applyBackgroundExtension(activity)

            // 5. 调整内容区域顶部内边距，避免内容被状态栏遮挡
            applyContentPadding(activity)

            trackedActivities.add(activity)
            WeLogger.d(TAG, "已应用沉浸式状态栏: ${activity.javaClass.simpleName}")
        }.onFailure {
            WeLogger.e(TAG, "applyImmersiveStatusBar 失败", it)
        }
    }

    // ─── 背景视图延伸 ────────────────────────────────────────────────────────────

    /**
     * 查找并调整聊天背景视图，使其向上延伸到状态栏区域
     *
     * 微信聊天背景通常是一个 ImageView 或 View，设置在整个聊天布局的底部。
     * 通过调整其 topMargin 为负的状态栏高度，让背景填充到状态栏下方。
     */
    private fun applyBackgroundExtension(activity: Activity) {
        runCatching {
            val rootView = activity.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
                ?: return

            val backgroundView = findChatBackgroundView(rootView) ?: return

            // 保存原始 layoutParams（仅首次）
            if (backgroundView.getTag(TAG_ORIGINAL_BG_LP) == null) {
                backgroundView.setTag(TAG_ORIGINAL_BG_LP, cloneLayoutParams(backgroundView.layoutParams))
            }

            // 向上延伸状态栏高度
            if (backgroundView.layoutParams is ViewGroup.MarginLayoutParams) {
                val lp = backgroundView.layoutParams as ViewGroup.MarginLayoutParams
                val statusBarHeight = getStatusBarHeight(activity)
                lp.topMargin = -statusBarHeight
                backgroundView.layoutParams = lp
            }

            // 禁用 fitsSystemWindows，让背景覆盖状态栏区域
            backgroundView.fitsSystemWindows = false

            WeLogger.d(TAG, "背景视图已调整: ${backgroundView.javaClass.simpleName}")
        }.onFailure {
            WeLogger.e(TAG, "applyBackgroundExtension 失败", it)
        }
    }

    /**
     * 在视图树中查找聊天背景视图
     *
     * 策略：优先查找带背景 drawable 的 MATCH_PARENT 视图 (通常是背景层)，
     * 其次是 ImageView (聊天背景图片)，最后兜底取第一个全屏子视图。
     */
    private fun findChatBackgroundView(parent: ViewGroup): View? {
        // 第 1 优先：带背景 drawable 且铺满父容器的 View
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.background != null) {
                val lp = child.layoutParams
                if (lp is ViewGroup.LayoutParams &&
                    lp.width == ViewGroup.LayoutParams.MATCH_PARENT &&
                    lp.height == ViewGroup.LayoutParams.MATCH_PARENT
                ) {
                    return child
                }
            }
        }

        // 第 2 优先：ImageView 铺满父容器 (聊天背景图)
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is android.widget.ImageView) {
                val lp = child.layoutParams
                if (lp is ViewGroup.LayoutParams &&
                    lp.width == ViewGroup.LayoutParams.MATCH_PARENT &&
                    lp.height == ViewGroup.LayoutParams.MATCH_PARENT
                ) {
                    return child
                }
            }
        }

        // 第 3 兜底：第一个 MATCH_PARENT 的 View
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val lp = child.layoutParams
            if (lp is ViewGroup.LayoutParams &&
                lp.width == ViewGroup.LayoutParams.MATCH_PARENT &&
                lp.height == ViewGroup.LayoutParams.MATCH_PARENT
            ) {
                return child
            }
        }

        return null
    }

    // ─── 内容区域内边距适配 ──────────────────────────────────────────────────────

    /**
     * 调整内容区域顶部内边距，确保标题栏和消息列表不被状态栏遮挡
     */
    private fun applyContentPadding(activity: Activity) {
        runCatching {
            val rootView = activity.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
                ?: return
            val statusBarHeight = getStatusBarHeight(activity)

            // 查找聊天标题栏，为其添加状态栏高度的顶部内边距
            val toolbar = findChatToolbar(rootView)
            if (toolbar != null) {
                if (toolbar.getTag(TAG_ORIGINAL_TOOLBAR_TOP) == null) {
                    toolbar.setTag(TAG_ORIGINAL_TOOLBAR_TOP, toolbar.paddingTop)
                }
                toolbar.setPadding(
                    toolbar.paddingLeft,
                    statusBarHeight,
                    toolbar.paddingRight,
                    toolbar.paddingBottom
                )
                WeLogger.d(TAG, "Toolbar paddingTop 已调整为: ${statusBarHeight}px")
            }

            // 对整个内容根视图应用 WindowInsets，自动处理安全区域
            ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
                val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                // 仅当 statusBars.top > 0 时才调整 (避免重复设置)
                if (statusBars.top > 0) {
                    view.setPadding(
                        view.paddingLeft,
                        statusBars.top,
                        view.paddingRight,
                        view.paddingBottom
                    )
                }
                WindowInsetsCompat.CONSUMED
            }
        }.onFailure {
            WeLogger.e(TAG, "applyContentPadding 失败", it)
        }
    }

    // ─── 状态栏恢复 ──────────────────────────────────────────────────────────────

    /**
     * 恢复状态栏到功能启用前的状态
     */
    private fun restoreStatusBar(activity: Activity) {
        runCatching {
            val window = activity.window ?: return

            // 恢复原始状态栏颜色
            val originalColor = window.decorView.getTag(TAG_ORIGINAL_STATUS_BAR_COLOR) as? Int
            if (originalColor != null) {
                window.statusBarColor = originalColor
            } else {
                window.statusBarColor = Color.BLACK
            }

            // 恢复对比度强制
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = true
            }

            // 恢复 decorFitsSystemWindows
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(true)
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
                    (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN).inv()
            }

            // 恢复背景视图
            val rootView = window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
            val backgroundView = findChatBackgroundView(rootView)
            if (backgroundView != null) {
                val originalLp = backgroundView.getTag(TAG_ORIGINAL_BG_LP)
                if (originalLp is ViewGroup.LayoutParams) {
                    backgroundView.layoutParams = originalLp
                }
                backgroundView.setTag(TAG_ORIGINAL_BG_LP, null)
                backgroundView.fitsSystemWindows = true
            }

            // 恢复 Toolbar padding
            val toolbar = findChatToolbar(rootView)
            if (toolbar != null) {
                val originalTop = toolbar.getTag(TAG_ORIGINAL_TOOLBAR_TOP)
                if (originalTop is Int) {
                    toolbar.setPadding(
                        toolbar.paddingLeft,
                        originalTop,
                        toolbar.paddingRight,
                        toolbar.paddingBottom
                    )
                }
                toolbar.setTag(TAG_ORIGINAL_TOOLBAR_TOP, null)
            }

            // 移除 WindowInsetsListener
            ViewCompat.setOnApplyWindowInsetsListener(rootView, null)

            window.decorView.setTag(TAG_ORIGINAL_STATUS_BAR_COLOR, null)

            WeLogger.d(TAG, "已恢复状态栏: ${activity.javaClass.simpleName}")
        }.onFailure {
            WeLogger.e(TAG, "restoreStatusBar 失败", it)
        }
    }

    // ─── 辅助方法 ────────────────────────────────────────────────────────────────

    /**
     * 保存原始状态栏颜色（仅首次保存，避免覆盖）
     */
    private fun saveOriginalStatusBarColor(window: android.view.Window) {
        if (window.decorView.getTag(TAG_ORIGINAL_STATUS_BAR_COLOR) == null) {
            window.decorView.setTag(TAG_ORIGINAL_STATUS_BAR_COLOR, window.statusBarColor)
        }
    }

    /**
     * 克隆 ViewGroup.LayoutParams
     */
    private fun cloneLayoutParams(lp: ViewGroup.LayoutParams?): ViewGroup.LayoutParams? {
        return when (lp) {
            is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(lp.width, lp.height).apply {
                leftMargin = lp.leftMargin
                topMargin = lp.topMargin
                rightMargin = lp.rightMargin
                bottomMargin = lp.bottomMargin
            }
            is ViewGroup.LayoutParams -> ViewGroup.LayoutParams(lp.width, lp.height)
            else -> null
        }
    }

    /**
     * 查找聊天标题栏（Toolbar / TitleBar）
     *
     * 微信聊天页的标题栏通常是一个特定类型的 ViewGroup，
     * 包含返回按钮、标题文本、操作按钮等。
     */
    private fun findChatToolbar(parent: ViewGroup): View? {
        val toolbarClassNames = setOf(
            "ChattingTitleBar",
            "MMTitleBar",
            "ActionBarContainer",
            "Toolbar",
        )
        return findViewByClassSuffix(parent, toolbarClassNames)
    }

    /**
     * 递归查找类名以指定后缀结尾的 View
     */
    private fun findViewByClassSuffix(parent: ViewGroup, suffixes: Set<String>): View? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val childClassName = child.javaClass.simpleName
            for (suffix in suffixes) {
                if (childClassName.endsWith(suffix)) return child
            }
            if (child is ViewGroup) {
                val found = findViewByClassSuffix(child, suffixes)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * 获取状态栏高度
     */
    private fun getStatusBarHeight(activity: Activity): Int {
        val resourceId = activity.resources.getIdentifier(
            "status_bar_height", "dimen", "android"
        )
        return if (resourceId > 0) {
            activity.resources.getDimensionPixelSize(resourceId)
        } else {
            // 默认估算值 (24dp)
            (24 * activity.resources.displayMetrics.density).toInt()
        }
    }
}