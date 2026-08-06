package com.Johnny.wcx.features.items.beautify.wex.feature

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.Johnny.wcx.features.items.beautify.wex.WexBeautifyFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookAfterDirectly
import com.Johnny.wcx.utils.hookBeforeDirectly
import dev.ujhhgtg.reflekt.reflekt
import java.io.File

/**
 * 顶栏美化 — 移植自 Wex
 * - 替换原生搜索按钮为自定义搜索框卡片
 * - 自定义标题（替换"微信"文字）
 * - 头像 + 昵称 + 状态
 */
object WexTopBarFeature {

    private const val TAG = "WexTopBar"
    private const val TAG_SEARCH = "wex_top_search"
    private const val TAG_PROFILE = "wex_top_profile"

    private var searchBtn: View? = null

    fun install() {
        try {
            // Hook Activity.onResume
            Activity::class.reflekt()
                .firstMethod { name = "onResume" }
                .hookAfterDirectly {
                    try {
                        val act = thisObject as? Activity ?: return@hookAfterDirectly
                        if (act.javaClass.name != "com.tencent.mm.ui.LauncherUI") return@hookAfterDirectly
                        if (!WexBeautifyFeature.masterEnabled) return@hookAfterDirectly
                        applyTopBar(act)
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "顶栏美化异常", e)
                    }
                }
            WeLogger.d(TAG, "顶栏美化 Hook 已注册")

            // Hook TextView.setText 替换标题
            TextView::class.reflekt()
                .firstMethod { name = "setText"; parameters(CharSequence::class) }
                .hookBeforeDirectly {
                    try {
                        val cs = args[0] as? CharSequence ?: return@hookBeforeDirectly
                        if (cs.toString() != "微信") return@hookBeforeDirectly
                        if (!WexBeautifyFeature.topProfileEnabled) return@hookBeforeDirectly
                        val custom = WexBeautifyFeature.topTitle
                        if (custom.isNotEmpty()) args[0] = custom
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "标题替换异常", e)
                    }
                }
            WeLogger.d(TAG, "标题替换 Hook 已注册")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "顶栏美化 Hook 注册失败", e)
        }
    }

    private fun applyTopBar(act: Activity) {
        val decor = act.window?.decorView as? ViewGroup ?: return
        decor.post {
            try {
                if (WexBeautifyFeature.topSearchBarEnabled) {
                    applySearchBar(act, decor)
                }
                if (WexBeautifyFeature.topProfileEnabled) {
                    applyProfile(act, decor)
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "顶栏美化应用失败", e)
            }
        }
    }

    private fun applySearchBar(act: Activity, decor: ViewGroup) {
        // 隐藏原生搜索按钮
        val btn = findViewByDesc(decor, "搜索")
        if (btn != null) {
            searchBtn = btn
            btn.visibility = View.GONE
            WeLogger.d(TAG, "原生搜索按钮已隐藏")
        }

        // 防重复
        if (decor.findViewWithTag<View>(TAG_SEARCH) != null) return

        val listView = findListView(decor) ?: return
        val bar = buildSearchBar(act)
        try {
            listView.javaClass.getMethod(
                "addHeaderView", View::class.java, Any::class.java, Boolean::class.javaPrimitiveType
            ).invoke(listView, bar, null, false)
            WeLogger.d(TAG, "自定义搜索框已插入列表顶部")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "插入搜索框失败", e)
        }
    }

    private fun buildSearchBar(act: Activity): View {
        val d = act.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()

        val wrapper = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = TAG_SEARCH
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        }

        val box = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, v.height / 2f)
                }
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F2F2F2"))
                cornerRadius = dp(20f).toFloat()
            }
            isClickable = true
            setOnClickListener { searchBtn?.performClick() }
        }

        box.addView(TextView(act).apply {
            text = WexBeautifyFeature.topSearchHint.ifEmpty { "搜索" }
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER
        })

        wrapper.addView(box, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return wrapper
    }

    private fun applyProfile(act: Activity, decor: ViewGroup) {
        val toolbar = findViewByClassName(decor, "Toolbar") as? ViewGroup ?: return
        if (toolbar.findViewWithTag<View>(TAG_PROFILE) != null) return

        var container: ViewGroup = toolbar
        for (i in 0 until toolbar.childCount) {
            val c = toolbar.getChildAt(i)
            if (c is android.widget.RelativeLayout) { container = c; break }
        }

        val profile = buildProfile(act) ?: return
        if (container is android.widget.RelativeLayout) {
            profile.layoutParams = android.widget.RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT)
                addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
            }
        }
        container.addView(profile)
        WeLogger.d(TAG, "头像昵称已插入顶栏")
    }

    private fun buildProfile(act: Activity): View? {
        val d = act.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()

        val nickname = WexBeautifyFeature.topNickname
        val status = WexBeautifyFeature.topStatus
        val dotRed = WexBeautifyFeature.topDotColor == "red"
        val avatarBmp = loadAvatar()

        if (avatarBmp == null && nickname.isEmpty() && status.isEmpty()) return null

        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = TAG_PROFILE
            setPadding(dp(12f), 0, 0, 0)
        }

        if (avatarBmp != null) {
            val avatarSize = dp(44f)
            row.addView(ImageView(act).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, dp(8f).toFloat())
                    }
                }
                layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize)
                setImageBitmap(avatarBmp)
            })
        }

        if (nickname.isNotEmpty() || status.isNotEmpty()) {
            val textCol = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6f), 0, 0, 0)
            }
            if (nickname.isNotEmpty()) {
                textCol.addView(TextView(act).apply {
                    text = nickname
                    textSize = 13f
                    setTextColor(Color.parseColor("#1A1A1A"))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    isSingleLine = true
                })
            }
            if (status.isNotEmpty()) {
                val statusRow = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                statusRow.addView(View(act).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor(if (dotRed) "#F44336" else "#4CAF50"))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(6f), dp(6f))
                })
                statusRow.addView(TextView(act).apply {
                    text = status
                    textSize = 10f
                    setTextColor(Color.parseColor("#999999"))
                    setPadding(dp(3f), 0, 0, 0)
                })
                textCol.addView(statusRow)
            }
            row.addView(textCol)
        }
        return row
    }

    private fun loadAvatar(): Bitmap? {
        val path = WexBeautifyFeature.topAvatarPath
        if (path.isEmpty()) return null
        return try {
            val f = File(path)
            if (f.exists()) BitmapFactory.decodeFile(path) else null
        } catch (e: Throwable) { null }
    }

    // ==================== 工具方法 ====================

    private fun findViewByDesc(root: View, descKeyword: String): View? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            val cd = v.contentDescription
            if (cd != null && cd.toString().contains(descKeyword)) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    v.getChildAt(i)?.let { queue.add(it) }
                }
            }
        }
        return null
    }

    private fun findViewByClassName(root: View, classNameContains: String): View? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.javaClass.name.contains(classNameContains)) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    v.getChildAt(i)?.let { queue.add(it) }
                }
            }
        }
        return null
    }

    private fun findListView(root: View): View? = findViewByClassName(root, "ListView")
}