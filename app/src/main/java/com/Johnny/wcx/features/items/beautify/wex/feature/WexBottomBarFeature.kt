package com.Johnny.wcx.features.items.beautify.wex.feature

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import com.Johnny.wcx.features.items.beautify.wex.WexBeautifyFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookAfterDirectly
import dev.ujhhgtg.reflekt.reflekt

/**
 * 悬浮圆角底栏 — 移植自 Wex
 * 将微信底部导航栏改为悬浮圆角样式
 */
object WexBottomBarFeature {

    private const val TAG = "WexBottomBar"

    fun install() {
        try {
            // Hook Activity.onResume，在 LauncherUI 恢复时应用底栏美化
            Activity::class.reflekt()
                .firstMethod { name = "onResume" }
                .hookAfterDirectly {
                    try {
                        val act = thisObject as? Activity ?: return@hookAfterDirectly
                        if (act.javaClass.name != "com.tencent.mm.ui.LauncherUI") return@hookAfterDirectly
                        if (!WexBeautifyFeature.bottomBarEnabled) return@hookAfterDirectly
                        applyBottomBar(act)
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "底栏美化异常", e)
                    }
                }
            WeLogger.d(TAG, "底栏美化 Hook 已注册")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "底栏美化 Hook 注册失败", e)
        }
    }

    private fun applyBottomBar(act: Activity) {
        val decor = act.window?.decorView as? ViewGroup ?: return
        decor.post {
            try {
                // 查找底部导航栏 ViewGroup
                val bottomBar = findBottomBarView(decor) ?: return@post
                applyRoundedStyle(bottomBar, act)
                WeLogger.d(TAG, "底栏圆角样式已应用")
            } catch (e: Throwable) {
                WeLogger.e(TAG, "底栏美化应用失败", e)
            }
        }
    }

    private fun findBottomBarView(root: ViewGroup): ViewGroup? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            // 查找底部导航栏：包含 "BottomNavigation" 或 "TabLayout" 的类名
            val className = v.javaClass.name.lowercase()
            if (className.contains("bottomnavigation") || className.contains("tablayout") ||
                (v is ViewGroup && v.childCount in 4..5 && className.contains("linearlayout") &&
                        v.javaClass.name.contains("com.tencent.mm.ui"))
            ) {
                return v as? ViewGroup
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    v.getChildAt(i)?.let { queue.add(it) }
                }
            }
        }
        return null
    }

    private fun applyRoundedStyle(view: ViewGroup, act: Activity) {
        val d = act.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()

        // 设置圆角背景
        view.clipToOutline = true
        view.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dp(24f).toFloat())
            }
        }
        // 设置圆角背景
        view.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#E6FFFFFF"))
            cornerRadius = dp(24f).toFloat()
        }
        // 设置 margin，让底栏悬浮
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
        lp?.let {
            val margin = dp(8f)
            it.setMargins(margin, margin, margin, margin + dp(8f))
            view.layoutParams = it
        }
    }
}