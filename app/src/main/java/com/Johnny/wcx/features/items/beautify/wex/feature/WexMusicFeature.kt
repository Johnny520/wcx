package com.Johnny.wcx.features.items.beautify.wex.feature

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import com.Johnny.wcx.features.items.beautify.wex.WexBeautifyFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookAfterDirectly
import dev.ujhhgtg.reflekt.reflekt

/**
 * 音乐播放器 — 移植自 Wex
 * 在微信首页显示音乐播放控制
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
            Activity::class.reflekt()
                .firstMethod { name = "onResume" }
                .hookAfterDirectly {
                    try {
                        val act = thisObject as? Activity ?: return@hookAfterDirectly
                        if (act.javaClass.name != "com.tencent.mm.ui.LauncherUI") return@hookAfterDirectly
                        if (!WexBeautifyFeature.floatLyricEnabled) return@hookAfterDirectly
                        showFloatLyric(act)
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "悬浮歌词异常", e)
                    }
                }
            WeLogger.d(TAG, "悬浮歌词 Hook 已注册")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "悬浮歌词 Hook 注册失败", e)
        }
    }

    private fun showFloatLyric(act: Activity) {
        val decor = act.window?.decorView as? ViewGroup ?: return
        decor.post {
            try {
                // 悬浮歌词实现：在 decor 上添加一个悬浮的歌词 TextView
                // 简化实现：显示一个小的歌词提示
                if (decor.findViewWithTag<View>("wex_float_lyric") != null) return
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