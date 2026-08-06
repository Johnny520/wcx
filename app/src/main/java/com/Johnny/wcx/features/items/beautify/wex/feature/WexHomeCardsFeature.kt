package com.Johnny.wcx.features.items.beautify.wex.feature

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.Johnny.wcx.features.items.beautify.wex.WexBeautifyFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookAfterDirectly
import dev.ujhhgtg.reflekt.reflekt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 首页三卡 — 移植自 Wex
 * 在聊天列表顶部插入日历、图片、音乐卡片
 */
object WexHomeCardsFeature {

    private const val TAG = "WexHomeCards"
    private const val TAG_INSERTED = "wex_home_cards"

    fun install() {
        try {
            Activity::class.reflekt()
                .firstMethod { name = "onResume" }
                .hookAfterDirectly {
                    try {
                        val act = thisObject as? Activity ?: return@hookAfterDirectly
                        if (act.javaClass.name != "com.tencent.mm.ui.LauncherUI") return@hookAfterDirectly
                        if (!WexBeautifyFeature.masterEnabled) return@hookAfterDirectly
                        insertCards(act)
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "首页卡片异常", e)
                    }
                }
            WeLogger.d(TAG, "首页卡片 Hook 已注册")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "首页卡片 Hook 注册失败", e)
        }
    }

    private fun insertCards(act: Activity) {
        val decor = act.window?.decorView as? ViewGroup ?: return
        decor.post {
            try {
                val listView = findListView(decor) ?: return@post
                if ((listView.tag as? String) == TAG_INSERTED) return@post

                val d = act.resources.displayMetrics.density
                val root = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
                }

                var added = 0
                if (WexBeautifyFeature.homeCalendarCardEnabled) {
                    val card = buildCalendarCard(act, d)
                    root.addView(card, LinearLayout.LayoutParams(-1, -2))
                    added++
                }
                if (WexBeautifyFeature.homeImageCardEnabled) {
                    val card = buildImageCard(act, d)
                    val lp = LinearLayout.LayoutParams(-1, -2)
                    if (added > 0) lp.topMargin = (10 * d).toInt()
                    root.addView(card, lp)
                    added++
                }
                if (WexBeautifyFeature.homeMusicCardEnabled) {
                    val card = buildMusicCard(act, d)
                    val lp = LinearLayout.LayoutParams(-1, -2)
                    if (added > 0) lp.topMargin = (10 * d).toInt()
                    root.addView(card, lp)
                    added++
                }

                if (root.childCount == 0) {
                    listView.tag = null
                    return@post
                }

                listView.javaClass.getMethod(
                    "addHeaderView", View::class.java, Any::class.java, Boolean::class.javaPrimitiveType
                ).invoke(listView, root, null, false)
                listView.tag = TAG_INSERTED
                WeLogger.d(TAG, "首页卡片插入成功 (${root.childCount}张)")
            } catch (e: Throwable) {
                WeLogger.e(TAG, "首页卡片插入失败", e)
            }
        }
    }

    // ==================== 日历卡片 ====================

    private fun buildCalendarCard(act: Activity, d: Float): View {
        fun dp(v: Float) = (v * d).toInt()
        val cal = Calendar.getInstance()
        val dateStr = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINESE).format(cal.time)
        val weekStr = SimpleDateFormat("EEEE", Locale.CHINESE).format(cal.time)

        return LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(16f).toFloat())
                }
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F5F5F5"))
                cornerRadius = dp(16f).toFloat()
            }
        }.also { card ->
            val textCol = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            textCol.addView(TextView(act).apply {
                text = dateStr
                textSize = 16f
                setTextColor(Color.parseColor("#212121"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            textCol.addView(TextView(act).apply {
                text = weekStr
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
            })
            card.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(TextView(act).apply {
                text = "📅"
                textSize = 28f
                gravity = Gravity.CENTER
            })
        }
    }

    // ==================== 图片卡片 ====================

    private fun buildImageCard(act: Activity, d: Float): View {
        fun dp(v: Float) = (v * d).toInt()
        return LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(16f).toFloat())
                }
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F5F5F5"))
                cornerRadius = dp(16f).toFloat()
            }
        }.also { card ->
            val textCol = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            textCol.addView(TextView(act).apply {
                text = "每日一图"
                textSize = 16f
                setTextColor(Color.parseColor("#212121"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            textCol.addView(TextView(act).apply {
                text = "发现美好瞬间"
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
            })
            card.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(TextView(act).apply {
                text = "🖼️"
                textSize = 28f
                gravity = Gravity.CENTER
            })
        }
    }

    // ==================== 音乐卡片 ====================

    private fun buildMusicCard(act: Activity, d: Float): View {
        fun dp(v: Float) = (v * d).toInt()
        return LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(16f).toFloat())
                }
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F5F5F5"))
                cornerRadius = dp(16f).toFloat()
            }
        }.also { card ->
            val textCol = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            textCol.addView(TextView(act).apply {
                text = "音乐播放器"
                textSize = 16f
                setTextColor(Color.parseColor("#212121"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            textCol.addView(TextView(act).apply {
                text = "当前未在播放"
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
            })
            card.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(TextView(act).apply {
                text = "🎵"
                textSize = 28f
                gravity = Gravity.CENTER
            })
        }
    }

    // ==================== 工具方法 ====================

    private fun findListView(root: View): View? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.javaClass.name.contains("ListView")) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    v.getChildAt(i)?.let { queue.add(it) }
                }
            }
        }
        return null
    }
}