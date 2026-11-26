package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import dev.ujhhgtg.reflekt.utils.toClass

/**
 * 自动启用发送原图：选完图片进入预览页时，把「原图」勾上。
 *
 * ## 之前为什么失效
 *
 * 旧实现只在 `AlbumPreviewUI` / `ImagePreviewUI` 的 onCreate **之前**往 Intent 里塞
 * `send_raw_img=true`。这条路在 8.0.80 上不可靠：
 * - 两个类都是直接 `toClass()`，任何一个类改名就抛 ClassNotFoundException，整个功能装不上；
 * - 微信进页面后会自己初始化勾选状态，早塞的 extra 常常被覆盖。
 *
 * ## 现在怎么做
 *
 * 1. 保留原来的 intent extra（老版本仍然吃这一套）；
 * 2. 额外挂 ActivityLifecycleCallbacks：这两个预览页 resume 后延迟 300ms 扫一遍视图树，
 *    按 **控件资源名**（含 origin / raw）找那个勾选项，没勾就点一下。
 *    资源名在微信里一直保留，比按文本或按类型找稳得多。
 */
@Feature(
    name = "自动启用发送原图",
    categories = ["聊天"],
    description = "发送图片/视频时自动勾选「原图」，不再被压缩",
)
object AutoEnableNoCompressOnSendMedia : SwitchFeature() {

    private const val TAG = "AutoEnableNoCompress"

    private const val PREVIEW_CLASS_ALBUM = "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI"
    private const val PREVIEW_CLASS_IMAGE = "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI"

    private val PREVIEW_CLASSES = listOf(PREVIEW_CLASS_ALBUM, PREVIEW_CLASS_IMAGE)

    private var callbacks: Application.ActivityLifecycleCallbacks? = null

    override fun onEnable() {
        // 1) 老路：onCreate 前塞 extra（失败不影响后面的 UI 勾选）
        PREVIEW_CLASSES.forEach { className ->
            runCatching {
                className.toClass().hookBeforeOnCreate {
                    val activity = thisObject as? Activity ?: return@hookBeforeOnCreate
                    activity.intent?.putExtra("send_raw_img", true)
                }
                WeLogger.d(TAG, "已挂 onCreate：$className")
            }.onFailure { WeLogger.d(TAG, "挂 onCreate 失败（$className）：${it.message}") }
        }

        // 2) 新路：页面出来后按资源名把「原图」勾上
        if (callbacks != null) return
        val created = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                val name = activity.javaClass.name
                if (PREVIEW_CLASSES.none { it == name }) return
                val decor = activity.window?.decorView ?: return
                // 页面刚 resume 时勾选框可能还没布局完，稍等一下再扫。
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { checkOriginal(decor) }
                        .onFailure { WeLogger.w(TAG, "勾选原图失败", it) }
                }, 300L)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        runCatching { HostInfo.application.registerActivityLifecycleCallbacks(created) }
            .onSuccess { callbacks = created }
            .onFailure { WeLogger.w(TAG, "注册生命周期失败", it) }
    }

    override fun onDisable() {
        callbacks?.let { runCatching { HostInfo.application.unregisterActivityLifecycleCallbacks(it) } }
        callbacks = null
    }

    /**
     * 扫视图树，按资源名找「原图」勾选项并点它。
     *
     * 用资源名（origin / raw）而不是控件文本：文本会随语言变，类型也不固定
     * （有时是 CheckBox，有时是自绘的 MMSwitchBtn）。
     */
    private fun checkOriginal(root: View): Boolean {
        var clicked = false
        fun walk(view: View, depth: Int) {
            if (clicked || depth > 20) return
            if (view.id != View.NO_ID) {
                val entry = runCatching { view.resources.getResourceEntryName(view.id) }
                    .getOrNull().orEmpty()
                val looksOriginal = entry.contains("origin", ignoreCase = true) ||
                    entry.contains("raw", ignoreCase = true)
                if (looksOriginal && view.isShown) {
                    val checked = runCatching {
                        (view as? android.widget.Checkable)?.isChecked
                    }.getOrNull()
                    if (checked != true) {
                        view.performClick()
                        clicked = true
                        WeLogger.i(TAG, "已勾选原图：$entry")
                        return
                    }
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index), depth + 1)
            }
        }
        walk(root, 0)
        if (!clicked) WeLogger.d(TAG, "没找到原图勾选项（当前页面可能不是预览页）")
        return clicked
    }
}
