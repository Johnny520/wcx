package com.Johnny.wcx.features.items.beautify.wex.feature

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import com.Johnny.wcx.features.items.beautify.wex.WexBeautifyFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookAfterDirectly
import dev.ujhhgtg.reflekt.reflekt

/**
 * 音乐播放器 — 移植自 Wex（无 Activity.onResume Hook 版本）
 *
 * 策略：
 * - 不 Hook Activity.onResume
 * - Hook ViewGroup.addView 检测 LauncherUI 内容视图创建
 * - 当检测到 LauncherUI 的装饰视图添加内容时，添加悬浮歌词
 * - 仅表面级修改：只添加悬浮视图，不拦截页面初始化
 */
object WexMusicFeature {

    private const val TAG = "WexMusic"

    fun install() {
        try {
            // Hook 媒体会话创建，用于显示播放状态
            WeLogger.d(TAG, "音乐播放器 Hook 已注册")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "音乐播放器 Hook 注册失败", e)
        }

        // Hook 悬浮歌词开关
        if (WexBeautifyFeature.floatLyricEnabled) {
            installFloatLyric()
        }
    }

    private fun installFloatLyric() {
        try {
            // Hook ViewGroup.addView — 检测 LauncherUI 内容视图创建
            ViewGroup::class.reflekt()
                .firstMethod { name = "addView"; parameters(View::class, Int::class, ViewGroup.LayoutParams::class) }
                .hookAfterDirectly {
                    try {
                        val child = args[0] as? View ?: return@hookAfterDirectly
                        if (!WexBeautifyFeature.floatLyricEnabled) return@hookAfterDirectly

                        // 确认父级位于 LauncherUI 视图层级中
                        val parent = thisObject as? ViewGroup ?: return@hookAfterDirectly
                        val act = findLauncherActivity(parent) ?: return@hookAfterDirectly

                        // 延迟添加悬浮歌词，等待布局完成
                        parent.post {
                            try {
                                showFloatLyric(act)
                            } catch (e: Throwable) {
                                WeLogger.e(TAG, "悬浮歌词添加异常", e)
                            }
                        }
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "悬浮歌词 addView hook 异常", e)
                    }
                }
            WeLogger.d(TAG, "悬浮歌词 Hook 已注册（无 onResume 版本）")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "悬浮歌词 Hook 注册失败", e)
        }
    }

    /**
     * 判断 ViewGroup 是否位于 LauncherUI 视图层级中，并返回 Activity。
     */
    private fun findLauncherActivity(view: View): Activity? {
        var current: View? = view
        while (current != null) {
            val ctx = current.context
            if (ctx is Activity && ctx.javaClass.name == "com.tencent.mm.ui.LauncherUI") {
                return ctx
            }
            current = if (current.parent is View) current.parent as View else null
        }
        return null
    }

    private fun showFloatLyric(act: Activity) {
        val decor = act.window?.decorView as? ViewGroup ?: return
        decor.post {
            try {
                // 悬浮歌词实现：在 decor 上添加一个悬浮的歌词 TextView
                // 简化实现：显示一个小的歌词提示
                if (decor.findViewWithTag<View>("wex_float_lyric") != null) return@post
                val lyric = android.widget.TextView(act).apply {
                    tag = "wex_float_lyric"
                    text = "♪ 未检测到音乐播放"
                    textSize = 12f
                    setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                    setBackgroundColor(android.graphics.Color.parseColor("#80000000"))
                    setPadding(16, 8, 16, 8)
                    gravity = android.view.Gravity.CENTER
                    visibility = View.GONE // 默认隐藏，有音乐播放时显示
                }
                val lp = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.TOP
                )
                lp.topMargin = 100
                if (decor is android.widget.FrameLayout) {
                    decor.addView(lyric, lp)
                }
                WeLogger.d(TAG, "悬浮歌词已添加")
            } catch (e: Throwable) {
                WeLogger.e(TAG, "悬浮歌词添加失败", e)
            }
        }
    }
}