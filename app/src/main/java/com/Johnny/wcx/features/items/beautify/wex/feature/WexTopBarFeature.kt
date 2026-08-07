package com.Johnny.wcx.features.items.beautify.wex.feature

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.Johnny.wcx.features.items.beautify.wex.WexBeautifyFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookAfterDirectly
import com.Johnny.wcx.utils.hookBeforeDirectly
import dev.ujhhgtg.reflekt.reflekt
import java.io.File
import java.lang.ref.WeakReference

/**
 * 顶栏美化 — 移植自 Wex（无 Activity.onResume Hook 版本）
 *
 * 策略：
 * - 不 Hook Activity.onResume
 * - Hook LayoutInflater.inflate 检测 LauncherUI 布局创建
 * - Hook TextView.setText 替换标题文字
 * - 使用 ViewTreeObserver.OnGlobalLayoutListener 检测布局就绪
 * - 仅表面级修改：只修改视图外观，不拦截页面初始化
 */
object WexTopBarFeature {

    private const val TAG = "WexTopBar"
    private const val TAG_SEARCH = "wex_top_search"
    private const val TAG_PROFILE = "wex_top_profile"

    private var searchBtn: View? = null
    private var lastActivityRef = WeakReference<Activity>(null)

    fun install() {
        try {
            // Hook TextView.setText — 替换标题文字
            hookTitleReplacement()

            // Hook LayoutInflater.inflate — 检测 LauncherUI 布局创建
            hookLayoutInflater()

            WeLogger.d(TAG, "顶栏美化 Hook 已注册（无 onResume 版本）")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "顶栏美化 Hook 注册失败", e)
        }
    }

    /**
     * Hook TextView.setText 替换标题"微信"。
     * 仅修改 setText 参数，不拦截任何其他逻辑。
     */
    private fun hookTitleReplacement() {
        try {
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
            WeLogger.e(TAG, "标题替换 Hook 注册失败", e)
        }
    }

    /**
     * Hook LayoutInflater.inflate，检测 LauncherUI 布局创建。
     * 当检测到 LauncherUI 的布局被加载时，注册 GlobalLayoutListener 等待布局就绪。
     */
    private fun hookLayoutInflater() {
        try {
            LayoutInflater::class.reflekt()
                .firstMethod { name = "inflate"; parameters(Int::class, ViewGroup::class, Boolean::class) }
                .hookAfterDirectly {
                    try {
                        val root = result as? ViewGroup ?: return@hookAfterDirectly
                        // 检查是否是 LauncherUI 的布局
                        if (!isLauncherUIView(root)) return@hookAfterDirectly

                        WeLogger.d(TAG, "检测到 LauncherUI 布局创建，注册 GlobalLayoutListener")

                        // 注册 GlobalLayoutListener 等待布局就绪
                        root.viewTreeObserver.addOnGlobalLayoutListener(
                            object : ViewTreeObserver.OnGlobalLayoutListener {
                                private var applied = false

                                override fun onGlobalLayout() {
                                    if (applied) return
                                    if (!WexBeautifyFeature.masterEnabled) return
                                    if (root.width <= 0 || root.height <= 0) return

                                    applied = true
                                    root.post {
                                        // 移除监听器（兼容旧 API）
                                        try {
                                            root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                                        } catch (_: Throwable) {}

                                        applyTopBar(root)
                                    }
                                }
                            }
                        )
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "LayoutInflater hook 异常", e)
                    }
                }
            WeLogger.d(TAG, "LayoutInflater inflate Hook 已注册")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "LayoutInflater hook 注册失败", e)
        }
    }

    /**
     * 判断 View 是否属于 LauncherUI。
     * 简单检查：根布局的 context 是否为 LauncherUI Activity。
     */
    private fun isLauncherUIView(view: ViewGroup): Boolean {
        try {
            val context = view.context
            return context is Activity && context.javaClass.name == "com.tencent.mm.ui.LauncherUI"
        } catch (e: Throwable) {
            return false
        }
    }

    /**
     * 应用顶栏美化（在布局就绪后调用）。
     * 仅修改视图外观，不拦截页面初始化。
     */
    private fun applyTopBar(root: ViewGroup) {
        try {
            if (WexBeautifyFeature.topSearchBarEnabled) {
                applySearchBar(root)
            }
            if (WexBeautifyFeature.topProfileEnabled) {
                applyProfile(root)
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "顶栏美化应用失败", e)
        }
    }

    /**
     * 应用搜索框美化：隐藏原生搜索按钮，插入自定义搜索框。
     */
    private fun applySearchBar(root: ViewGroup) {
        // 隐藏原生搜索按钮
        val btn = findViewByDesc(root, "搜索")
        if (btn != null) {
            searchBtn = btn
            btn.visibility = View.GONE
            WeLogger.d(TAG, "原生搜索按钮已隐藏")
        }

        // 防重复
        if (root.findViewWithTag<View>(TAG_SEARCH) != null) return

        val listView = findListView(root) ?: return
        val bar = buildSearchBar(root.context)
        try {
            listView.javaClass.getMethod(
                "addHeaderView", View::class.java, Any::class.java, Boolean::class.javaPrimitiveType
            ).invoke(listView, bar, null, false)
            WeLogger.d(TAG, "自定义搜索框已插入列表顶部")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "插入搜索框失败", e)
        }
    }

    private fun buildSearchBar(context: android.content.Context): View {
        val d = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = TAG_SEARCH
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        }

        val box = LinearLayout(context).apply {
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

        box.addView(TextView(context).apply {
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

    /**
     * 应用头像昵称美化：在顶栏 Toolbar 中插入头像+昵称+状态指示器。
     */
    private fun applyProfile(root: ViewGroup) {
        val toolbar = findViewByClassName(root, "Toolbar") as? ViewGroup ?: return
        if (toolbar.findViewWithTag<View>(TAG_PROFILE) != null) return

        var container: ViewGroup = toolbar
        for (i in 0 until toolbar.childCount) {
            val c = toolbar.getChildAt(i)
            if (c is android.widget.RelativeLayout) { container = c; break }
        }

        val context = root.context
        val profile = buildProfile(context) ?: return
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

    private fun buildProfile(context: android.content.Context): View? {
        val d = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()

        val nickname = WexBeautifyFeature.topNickname
        val status = WexBeautifyFeature.topStatus
        val dotRed = WexBeautifyFeature.topDotColor == "red"
        val avatarBmp = loadAvatar()

        if (avatarBmp == null && nickname.isEmpty() && status.isEmpty()) return null

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = TAG_PROFILE
            setPadding(dp(12f), 0, 0, 0)
        }

        if (avatarBmp != null) {
            val avatarSize = dp(44f)
            row.addView(ImageView(context).apply {
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
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6f), 0, 0, 0)
            }
            if (nickname.isNotEmpty()) {
                textCol.addView(TextView(context).apply {
                    text = nickname
                    textSize = 13f
                    setTextColor(Color.parseColor("#1A1A1A"))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    isSingleLine = true
                })
            }
            if (status.isNotEmpty()) {
                val statusRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                statusRow.addView(View(context).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor(if (dotRed) "#F44336" else "#4CAF50"))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(6f), dp(6f))
                })
                statusRow.addView(TextView(context).apply {
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