package com.Johnny.wcx.features.items.home_screen_menu

import android.app.Activity
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.tencent.mm.ui.LauncherUI
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.ui.WeHomeScreenPopupMenuApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.utils.InjectedUiTheme
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.runOnUiThread
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import com.composables.icons.materialsymbols.MaterialSymbols
import com.Johnny.wcx.ui.utils.LifecycleOwnerProvider
import com.Johnny.wcx.ui.utils.setLifecycleOwner

private const val TAG = "DisableMainPagePullDown"

/**
 * 小程序图标 Drawable，用于首页右上角菜单和标题栏图标
 */
object MiniProgramIcon : Drawable() {
    private val pathData = "M480,880Q397,880 324,848.5Q251,817 197,763Q143,709 111.5,636Q80,563 80,480Q80,397 111.5,324Q143,251 197,197Q251,143 324,111.5Q397,80 480,80Q563,80 636,111.5Q709,143 763,197Q817,251 848.5,324Q880,397 880,480Q880,563 848.5,636Q817,709 763,763Q709,817 636,848.5Q563,880 480,880ZM480,800Q614,800 707,707Q800,614 800,480Q800,346 707,253Q614,160 480,160Q346,160 253,253Q160,346 160,480Q160,614 253,707Q346,800 480,800ZM480,720Q381,720 300.5,639.5Q220,559 220,460Q220,361 300.5,280.5Q381,200 480,200Q579,200 659.5,280.5Q740,361 740,460Q740,559 659.5,639.5Q579,720 480,720ZM480,640Q555,640 607.5,587.5Q660,535 660,460Q660,385 607.5,332.5Q555,280 480,280Q405,280 352.5,332.5Q300,385 300,460Q300,535 352.5,587.5Q405,640 480,640Z"

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
        color = Color.White.toArgb()
    }

    private val path = androidx.core.graphics.PathParser.createPathFromPathData(pathData)

    override fun draw(canvas: android.graphics.Canvas) {
        val bounds = bounds
        canvas.save()
        val scaleX = bounds.width() / 960f
        val scaleY = bounds.height() / 960f
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(scaleX, scaleY)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    override fun getIntrinsicWidth(): Int = (24 * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
    override fun getIntrinsicHeight(): Int = (24 * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
    override fun isAutoMirrored(): Boolean = true
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    private fun Int.dpToPx(): Int = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}

@Feature(name = "禁止主页下滑进入最近页面", categories = ["小程序"], description = "禁止主页下滑进入最近页面，支持通过加号菜单和标题栏图标唤起小程序面板")
object DisableMainPagePullDown : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider, IResolveDex {

    // 两个独立开关
    private var showInPlusMenu by WePrefs.prefOption("disable_main_page_pull_down_show_in_menu", true)
    private var showInTitleBar by WePrefs.prefOption("disable_main_page_pull_down_show_in_title_bar", false)

    // DexKit 方法：查找 LauncherUI 中处理下拉手势的方法
    private val methodOnTouchEvent by dexMethod {
        searchPackages("com.tencent.mm.ui")
        matcher {
            usingEqStrings("MicroMsg.LauncherUI", "onTouchEvent")
        }
    }

    // DexKit 方法：查找打开小程序面板的方法
    private val methodOpenMiniProgramPanel by dexMethod {
        searchPackages("com.tencent.mm")
        matcher {
            usingEqStrings("MicroMsg.LauncherUI", "openTaskList")
        }
    }

    private var titleBarIconView: View? = null

    override fun onEnable() {
        try {
            // 注册到首页右上角菜单
            if (showInPlusMenu) {
                WeHomeScreenPopupMenuApi.addProvider(this)
            }
            WeLogger.i(TAG, "enabled, showInPlusMenu=$showInPlusMenu, showInTitleBar=$showInTitleBar")
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to enable", e)
        }
    }

    override fun onDisable() {
        try {
            WeHomeScreenPopupMenuApi.removeProvider(this)
            removeTitleBarIcon()
            WeLogger.i(TAG, "disabled")
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to disable", e)
        }
    }

    override fun getMenuItems(param: XC_MethodHook.MethodHookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        if (!showInPlusMenu) return emptyList()
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                777030, "最近小程序", MiniProgramIcon
            ) {
                try {
                    triggerMiniProgramPanel()
                    WeLogger.i(TAG, "triggered mini program panel from plus menu")
                } catch (e: Exception) {
                    WeLogger.e(TAG, "failed to trigger mini program panel from plus menu", e)
                }
            }
        )
    }

    /**
     * 核心行为：模拟唤起微信原生最近小程序面板
     * 尝试多种方式，优先使用反射调用内部方法
     */
    private fun triggerMiniProgramPanel() {
        try {
            val launcherUI = LauncherUI.getInstance() ?: run {
                WeLogger.w(TAG, "LauncherUI instance is null")
                return
            }

            // 方式1：尝试通过反射调用 openTaskList 方法
            try {
                val method = launcherUI.javaClass.getDeclaredMethod("openTaskList")
                method.isAccessible = true
                method.invoke(launcherUI)
                WeLogger.i(TAG, "triggered via openTaskList reflection")
                return
            } catch (e: NoSuchMethodException) {
                WeLogger.d(TAG, "openTaskList method not found, trying alternative")
            }

            // 方式2：尝试通过 DexKit 解析的方法
            try {
                if (methodOpenMiniProgramPanel.method != null) {
                    methodOpenMiniProgramPanel.method.invoke(launcherUI)
                    WeLogger.i(TAG, "triggered via dexMethod")
                    return
                }
            } catch (e: Exception) {
                WeLogger.d(TAG, "dexMethod trigger failed", e)
            }

            // 方式3：模拟 dispatchTouchEvent 发送下拉手势
            try {
                val metrics = launcherUI.resources.displayMetrics
                val startX = metrics.widthPixels / 2f
                val startY = 0f
                val endY = metrics.heightPixels / 3f

                val downTime = android.os.SystemClock.uptimeMillis()
                val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY, 0)
                launcherUI.dispatchTouchEvent(downEvent)
                downEvent.recycle()

                val moveSteps = 10
                val moveDuration = 300L / moveSteps
                for (i in 1..moveSteps) {
                    val progress = i.toFloat() / moveSteps
                    val currentY = startY + (endY - startY) * progress
                    val moveTime = downTime + moveDuration * i
                    val moveEvent = MotionEvent.obtain(downTime, moveTime, MotionEvent.ACTION_MOVE, startX, currentY, 0)
                    launcherUI.dispatchTouchEvent(moveEvent)
                    moveEvent.recycle()
                    Thread.sleep(moveDuration)
                }

                val upTime = downTime + 300
                val upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, startX, endY, 0)
                launcherUI.dispatchTouchEvent(upEvent)
                upEvent.recycle()

                WeLogger.i(TAG, "triggered via simulated touch event")
            } catch (e: Exception) {
                WeLogger.e(TAG, "all trigger methods failed", e)
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "triggerMiniProgramPanel failed", e)
        }
    }

    /**
     * 在 LauncherUI 标题栏添加小程序图标按钮
     */
    fun installTitleBarIcon(activity: Activity) {
        try {
            if (!showInTitleBar) {
                WeLogger.d(TAG, "title bar icon disabled by switch")
                return
            }

            removeTitleBarIcon()

            val rootView = activity.window.decorView as ViewGroup
            val lifecycleOwner = LifecycleOwnerProvider.getOrCreate(activity)

            val composeView = ComposeView(activity).apply {
                setLifecycleOwner(lifecycleOwner)
                setContent {
                    InjectedUiTheme {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Package_2,
                            contentDescription = "最近小程序",
                            modifier = Modifier
                                .size(28.dp)
                                .padding(4.dp)
                                .clickable {
                                    try {
                                        triggerMiniProgramPanel()
                                        WeLogger.i(TAG, "triggered mini program panel from title bar icon")
                                    } catch (e: Exception) {
                                        WeLogger.e(TAG, "failed to trigger from title bar icon", e)
                                    }
                                },
                            tint = Color.White,
                        )
                    }
                }
            }

            // 尝试定位标题栏容器并添加图标
            val titleBar = findTitleBarContainer(rootView)
            if (titleBar != null) {
                val params = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                    marginEnd = (8 * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
                }
                titleBar.addView(composeView, params)
                titleBarIconView = composeView
                WeLogger.i(TAG, "title bar icon installed")
            } else {
                WeLogger.w(TAG, "title bar container not found")
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to install title bar icon", e)
        }
    }

    private fun removeTitleBarIcon() {
        try {
            titleBarIconView?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
            }
            titleBarIconView = null
            WeLogger.d(TAG, "title bar icon removed")
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to remove title bar icon", e)
        }
    }

    /**
     * 查找标题栏容器
     */
    private fun findTitleBarContainer(rootView: ViewGroup): ViewGroup? {
        try {
            // 尝试通过常见ID和类名定位标题栏
            val resources = rootView.context.resources
            val possibleIds = listOf(
                resources.getIdentifier("action_bar_root", "id", rootView.context.packageName),
                resources.getIdentifier("action_bar", "id", rootView.context.packageName),
                resources.getIdentifier("toolbar", "id", rootView.context.packageName),
            )

            for (id in possibleIds) {
                if (id != 0) {
                    val view = rootView.findViewById<View>(id)
                    if (view is ViewGroup) return view
                }
            }

            // 兜底：遍历查找 Toolbar / ActionBar
            return findViewByClass(rootView, "androidx.appcompat.widget.Toolbar")
                ?: findViewByClass(rootView, "android.widget.Toolbar")
                ?: findViewByClass(rootView, "com.tencent.mm.ui.widget.MMToolBar")
        } catch (e: Exception) {
            WeLogger.e(TAG, "findTitleBarContainer failed", e)
            return null
        }
    }

    private fun findViewByClass(parent: ViewGroup, className: String): ViewGroup? {
        if (parent.javaClass.name.contains(className)) return parent
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.javaClass.name.contains(className)) return child as? ViewGroup
            if (child is ViewGroup) {
                val found = findViewByClass(child, className)
                if (found != null) return found
            }
        }
        return null
    }

    // 更新菜单显示状态
    fun updateMenuVisibility(visible: Boolean) {
        showInPlusMenu = visible
        if (visible) {
            WeHomeScreenPopupMenuApi.addProvider(this)
        } else {
            WeHomeScreenPopupMenuApi.removeProvider(this)
        }
        WeLogger.i(TAG, "menu visibility updated: $visible")
    }

    // 更新标题栏图标显示状态
    fun updateTitleBarIconVisibility(visible: Boolean) {
        showInTitleBar = visible
        if (!visible) {
            removeTitleBarIcon()
        } else {
            runOnUiThread {
                LauncherUI.getInstance()?.let { installTitleBarIcon(it) }
            }
        }
        WeLogger.i(TAG, "title bar icon visibility updated: $visible")
    }

    private fun Int.dpToPx(): Int = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}