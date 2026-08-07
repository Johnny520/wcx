package com.Johnny.wcx.features.items.beautify.wex.feature

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.Johnny.wcx.features.items.beautify.wex.WexBeautifyFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookAfterDirectly
import dev.ujhhgtg.reflekt.reflekt

/**
 * 悬浮圆角底栏 — 移植自 Wex（无 Activity.onResume Hook 版本）
 *
 * 策略：
 * - 不 Hook Activity.onResume
 * - Hook ViewGroup.addView 检测底部导航栏的添加
 * - 当检测到符合特征的底部导航栏 ViewGroup 时，应用圆角样式
 * - 仅表面级修改：只修改视图外观，不拦截页面初始化
 */
object WexBottomBarFeature {

    private const val TAG = "WexBottomBar"
    private const val TAG_STYLED = "wex_bottom_bar_styled"

    fun install() {
        try {
            // Hook ViewGroup.addView 检测底部导航栏被添加
            ViewGroup::class.reflekt()
                .firstMethod { name = "addView"; parameters(View::class, Int::class, ViewGroup.LayoutParams::class) }
                .hookAfterDirectly {
                    try {
                        val child = args[0] as? View ?: return@hookAfterDirectly

                        // 检查是否为底部导航栏 ViewGroup
                        if (!isBottomBarView(child)) return@hookAfterDirectly
                        if (!WexBeautifyFeature.bottomBarEnabled) return@hookAfterDirectly
                        if (child.getTag(TAG_STYLED.hashCode()) != null) return@hookAfterDirectly

                        // 标记已处理
                        child.setTag(TAG_STYLED.hashCode(), java.lang.Boolean.TRUE)

                        // 延迟应用样式，等待布局完成
                        child.post {
                            try {
                                applyRoundedStyle(child as ViewGroup, child.context)
                                WeLogger.d(TAG, "底栏圆角样式已应用")
                            } catch (e: Throwable) {
                                WeLogger.e(TAG, "底栏美化应用失败", e)
                            }
                        }
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "底栏 addView hook 异常", e)
                    }
                }
            WeLogger.d(TAG, "底栏美化 Hook 已注册（无 onResume 版本）")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "底栏美化 Hook 注册失败", e)
        }
    }

    /**
     * 判断 View 是否为微信底部导航栏。
     *
     * 检测特征：
     * - 类名包含 BottomNavigation、TabLayout 或特定微信 UI 类名
     * - 子 View 数量在 4-5 个之间（四个 Tab + 可能的红点/角标）
     * - 位于 com.tencent.mm.ui 包下
     */
    private fun isBottomBarView(view: View): Boolean {
        if (view !is ViewGroup) return false
        if (view.childCount !in 4..5) return false

        val className = view.javaClass.name
        val lower = className.lowercase()

        // 确切的底部导航栏类名
        if (lower.contains("bottomnavigation") || lower.contains("tablayout")) return true

        // 微信特定 UI 中的 LinearLayout，包含 4-5 个子 View
        if (className.startsWith("com.tencent.mm.ui") && lower.contains("linearlayout")) return true

        return false
    }

    /**
     * 应用圆角悬浮样式到底部导航栏。
     * 仅修改圆角、背景、margin，不改变任何其他行为。
     */
    private fun applyRoundedStyle(view: ViewGroup, context: Context) {
        val d = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()

        val cornerRadius = dp(24f).toFloat()

        // 设置圆角裁剪
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, cornerRadius)
            }
        }

        // 设置半透明圆角背景
        view.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#E6FFFFFF"))
            this.cornerRadius = cornerRadius
        }

        // 设置悬浮 margin
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
        lp?.let {
            val margin = dp(8f)
            it.setMargins(margin, margin, margin, margin + dp(8f))
            view.layoutParams = it
        }
    }
}