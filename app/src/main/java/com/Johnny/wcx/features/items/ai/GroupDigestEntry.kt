package com.Johnny.wcx.features.items.ai

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.ai.group.GroupDigestStore
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Summarize
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.ui.style.WxPalette
import com.Johnny.wcx.activity.settings.ai.GroupDigestPanel
import com.Johnny.wcx.ui.utils.InjectedUiTheme
import com.Johnny.wcx.ui.utils.LifecycleOwnerProvider
import com.Johnny.wcx.ui.utils.setLifecycleOwner
import com.Johnny.wcx.ui.utils.theme.ThemeSettings

/**
 * 首页「群总结」入口：标题栏内、搜索图标左边一枚 48dp 触摸区的圆形按钮 + 红点。
 *
 * 挂在 `decorView` 上按固定坐标摆放，**不往微信的标题栏 / 会话列表 View 树里插** ——
 * 插进去会随版本改动错位，行内注入还会遇到列表回收复用的问题。View 只有 48dp，
 * 方块外的触摸自然落到微信自己的控件上，不需要 `FLAG_NOT_TOUCH_MODAL`。
 *
 * 可见性靠一份 400ms 轮询：只有微信首页、且面板没打开时才显示。侧边栏功能开着时它的
 * 轮询已经在扫同一棵 decor 树，这里就跳过自己那次判断（见 [sync]）。
 */
object GroupDigestEntry {

    private const val SIZE_DP = 48
    private const val POLL_MS = 400L
    private const val LAUNCHER = "com.tencent.mm.ui.LauncherUI"

    private var root: FrameLayout? = null
    private var host: Activity? = null
    private var callbacks: Application.ActivityLifecycleCallbacks? = null
    private var handler: Handler? = null
    private var polling = false

    /** 红点数字；Compose 侧直接读这个 State，写线程不做限制。 */
    private val unread = mutableStateOf(0)

    /**
     * 跟 Activity 生命周期挂载，**不依赖侧边栏功能是否开启**。
     *
     * 侧边栏关闭时它的 400ms 轮询不会启动，所以这里必须自己跟 resume / pause。
     */
    fun register(app: Application) {
        if (callbacks != null) return
        val created = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = onResume(activity)
            override fun onActivityPaused(activity: Activity) = onPause(activity)
            override fun onActivityDestroyed(activity: Activity) {
                if (host === activity) {
                    stopPolling()
                    detach()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        }
        runCatching { app.registerActivityLifecycleCallbacks(created) }
            .onSuccess { callbacks = created }
    }

    fun unregister() {
        callbacks?.let { runCatching { HostInfo.application.unregisterActivityLifecycleCallbacks(it) } }
        callbacks = null
        stopPolling()
        detach()
    }

    private fun onResume(act: Activity) {
        if (act.javaClass.name != LAUNCHER) return
        attach(act)
        startPolling(act)
    }

    private fun onPause(act: Activity) {
        if (host !== act) return
        stopPolling()
        detach()
    }

    /**
     * 每 400ms 判一次：Tab 切换不发 Activity 生命周期，只能轮询。
     * 侧边栏的轮询在跑时交给它，避免同一个 decor 树被扫两遍。
     */
    private fun startPolling(act: Activity) {
        if (polling) return
        polling = true
        val h = Handler(Looper.getMainLooper())
        handler = h
        h.post(object : Runnable {
            override fun run() {
                if (!polling) return
                sync(act, isHomeTabVisible(act))
                h.postDelayed(this, POLL_MS)
            }
        })
    }

    private fun stopPolling() {
        polling = false
        handler?.removeCallbacksAndMessages(null)
        handler = null
    }

    fun sync(act: Activity, visible: Boolean) {
        val current = root
        if (current == null) {
            if (visible) attach(act)
            return
        }
        // 换 Activity / 被宿主移除：重挂，避免挂在一个已经销毁的 window 上。
        if (host !== act || current.parent == null) {
            detach()
            if (visible) attach(act)
            return
        }
        current.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) refreshUnread()
    }

    fun refreshUnread() {
        val total = runCatching { GroupDigestStore.unreadTotal() }.getOrDefault(0)
        if (unread.value != total) unread.value = total
    }

    fun detach() {
        val view = root ?: return
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        root = null
        host = null
    }

    private fun statusBarHeight(context: android.content.Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private fun actionBarHeight(context: android.content.Context): Int {
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
        return try {
            attrs.getDimensionPixelSize(0, dp(56, context))
        } finally {
            attrs.recycle()
        }
    }

    private fun dp(value: Int, context: android.content.Context): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun attach(act: Activity) {
        if (root != null) detach()
        val decor = act.window?.decorView as? ViewGroup ?: return
        val density = act.resources.displayMetrics.density
        val size = (SIZE_DP * density).toInt()
        val container = FrameLayout(act)
        val compose = ComposeView(act).apply {
            setLifecycleOwner(LifecycleOwnerProvider.lifecycleOwner)
            setContent {
                InjectedUiTheme(darkTheme = ThemeSettings.themeMode.resolve()) {
                    GroupDigestButton(unread.value) {
                        runCatching { showDigestDialog(act) }
                    }
                }
            }
        }
        container.addView(compose, FrameLayout.LayoutParams(size, size))
        val lp = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.END or Gravity.TOP
            // 标题栏内、搜索图标左边：状态栏 + 标题栏内垂直居中；右侧让出「＋」和
            // 「搜索」两个 48dp 触摸区。只覆盖坐标、不插进微信的标题栏 View 树。
            topMargin = statusBarHeight(act) + ((actionBarHeight(act) - size) / 2)
            rightMargin = dp(92, act)
        }
        decor.addView(container, lp)
        root = container
        host = act
        refreshUnread()
    }
}

@Composable
private fun GroupDigestButton(count: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 只留 48dp 触摸区，不画底色；clip 只用来收住点击涟漪。
                .clip(CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            // 与微信标题栏自身的图标同色：浅色下深灰、深色下近白，两种主题都不会糊在背景里。
            Icon(
                imageVector = MaterialSymbols.Outlined.Summarize,
                contentDescription = "群总结",
                tint = WxPalette.textPrimary(),
                modifier = Modifier.size(22.dp),
            )
        }
        if (count > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(WxPalette.Danger),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (count > 9) "9+" else count.toString(),
                    fontSize = 9.sp,
                    color = Color.White,
                )
            }
        }
    }

    private const val TAG = "GroupDigestEntry"

    /**
     * 当前是否停在「微信」首页 Tab。
     *
     * 只看 Activity 类名不够：LauncherUI 四个 Tab 共用一个 Activity，通讯录/发现/我 也满足。
     * 这里按「会话列表可见」判定，与侧边栏是同一套判据，但不依赖侧边栏开关是否打开。
     */
    private fun isHomeTabVisible(act: Activity): Boolean = runCatching {
        if (act.isFinishing) return@runCatching false
        var found = false
        fun scan(view: View, depth: Int) {
            if (found || depth > 16) return
            if (view.javaClass.name == "com.tencent.mm.ui.conversation.ConversationListView" && view.isShown) {
                found = true
                return
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) scan(view.getChildAt(i), depth + 1)
            }
        }
        act.window?.decorView?.let { scan(it, 0) }
        found
    }.getOrDefault(false)

    /** 打开群总结面板：直接用 Compose 对话框，不经过侧边栏的面板体系。 */
    private fun showDigestDialog(act: Activity) {
        runCatching {
            showComposeDialog(act) {
                InjectedUiTheme(darkTheme = ThemeSettings.themeMode.resolve()) {
                    GroupDigestPanel(onClose = { onDismiss() })
                }
            }
        }.onFailure {
            WeLogger.e(TAG, "打开群总结面板失败", it)
            showToast("打开群总结面板失败：" + it.message)
        }
    }
}
