package com.Johnny.wcx.features.items.moments

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.getTopMostActivity
import com.Johnny.wcx.utils.reflection.ClassLoaders
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 禁止朋友圈信息流里的视频自动播放：滑动、停留、封面动画结束都不播；
 * 只有用户点播放按钮（或点开全屏播放页）之后才播。
 *
 * ## 旧实现在 8.0.80 上为什么完全没效果
 *
 * 旧实现用 DexKit 的字符串锚点定位 `resumePlay` / `resumeByDataGain`。
 * 8.0.80 实测这两个串已不作为方法内的字符串常量存在（`resumeByDataGain` 只剩 `"%d resumeByDataGain"`，
 * `resumePlay` 干脆没有），三个 dexMethod 委托全部变占位 → 一个 hook 都没装上，所以滑动照播。
 *
 * ## 现在的做法
 *
 * 朋友圈视频播放器的类名在 8.0.80 上没有混淆（微信自己用类名做埋点），因此直接用类名 + 方法特征定位：
 *
 * - `OnlineVideoView#K()V` —— resumePlay，信息流可见性变化 / 滚动停止 / 封面动画结束都走它
 * - `OnlineVideoView#J()Z` —— resumeByDataGain，数据就绪后自动恢复播放
 * - `SnsTimelineVideoView#K()V` —— 时间线包装层覆盖了 K()，必须单独 hook
 * - `CardOnlineVideoView#K()V` —— 广告卡里的视频
 *
 * 放行条件只有两个：
 * 1. 当前页面是全屏播放页（用户点开后才有的 Activity）；
 * 2. 用户刚刚点过屏幕（1.2 秒窗口内）—— 点播放按钮触发的 resumePlay 都落在这个窗口里。
 *
 * 滑动不会产生「点击」信号（位移/时长超阈值），所以滑动永远不放行。
 */
@Feature(
    name = "禁止自动播放视频",
    categories = ["朋友圈"],
    description = "朋友圈信息流里的视频不再随滑动自动播放，点播放按钮或点开全屏才播放"
)
object DisableVideosAutoPlay : SwitchFeature() {

    private const val TAG = "DisableVideosAutoPlay"

    /** 点击后的放行窗口：点播放按钮到真正 resumePlay 通常只有几十毫秒。 */
    private const val TAP_WINDOW_MS = 900L

    /** 判定「点击」而不是滑动：位移与按住时长的上限。 */
    private const val TAP_SLOP_PX = 30
    private const val TAP_MAX_MS = 400L

    /** 自绘播放标识挂在视频 View 上的 tag key（任意 int，不占资源 id）。 */
    private const val BADGE_TAG = 0x7E0BA9D1

    /** 播放标识直径（dp）。 */
    private const val BADGE_SIZE_DP = 44

    /** 8.0.80 实测未混淆的朋友圈视频 View 类名。 */
    private val videoClassNames = arrayOf(
        "com.tencent.mm.plugin.sns.ui.OnlineVideoView",
        "com.tencent.mm.plugin.sns.ui.video.SnsTimelineVideoView",
        "com.tencent.mm.plugin.sns.ad.timeline.video.online.CardOnlineVideoView",
    )

    /** 全屏播放页：用户主动点进来的，一律放行。 */
    private val playerPages = arrayOf(
        "SnsOnlineVideoActivity",
        "SnsGalleryUI",
        "SnsVideoDetailUI",
        "SnsImageViewUI",
    )

    @Volatile
    private var lastTapAt = 0L

    override fun onEnable() {
        val classes = videoClassNames.mapNotNull { name ->
            val clazz = runCatching { Class.forName(name, false, ClassLoaders.HOST) }.getOrNull()
            if (clazz == null) {
                WeLogger.w(TAG, "未找到 $name（微信版本变化）")
            }
            clazz
        }
        if (classes.isEmpty()) {
            WeLogger.e(TAG, "朋友圈视频类全部未找到，禁止自动播放不可用")
            return
        }

        hookTapSignals(classes)
        hookProbe(classes)
        hookPauseBadge(classes)

        var installed = 0
        classes.forEach { clazz ->
            clazz.declaredMethods.forEach { method ->
                if (method.parameterTypes.isNotEmpty()) return@forEach
                val isResumePlay = method.name == "K" && method.returnType == Void.TYPE
                val isResumeByDataGain =
                    method.name == "J" && method.returnType == java.lang.Boolean.TYPE
                val isPrepared = method.name == "onPrepared" && method.returnType == Void.TYPE

                // onPrepared 不能拦：拦掉它微信会一直停在「准备中」，界面上就是加载圈一直转
                //（下载由 S()V 负责、准备由 A()V 负责，这两个也一并放行）。
                // 正确做法是放行它、执行完立即暂停（F()V 的日志串是 "%d pause play"）。
                if (isPrepared) {
                    runCatching {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                                val view = param.thisObject as? View ?: return
                                if (isPlayerPage(view.context)) return
                                if (SystemClock.elapsedRealtime() - lastTapAt < TAP_WINDOW_MS) return
                                val paused = runCatching {
                                    view.javaClass.getMethod("F").invoke(view)
                                }.isSuccess
                                attachBadge(view)
                                WeLogger.i(
                                    TAG,
                                    "onPrepared 后立即暂停：${clazz.simpleName} paused=$paused",
                                )
                            }
                        })
                        installed++
                    }.onFailure { WeLogger.e(TAG, "hook ${clazz.simpleName}#onPrepared 失败", it) }
                    return@forEach
                }

                if (!isResumePlay && !isResumeByDataGain) return@forEach
                runCatching {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            val view = param.thisObject as? View ?: return
                            if (isPlayerPage(view.context)) {
                                hideBadge(view)
                                return
                            }
                            if (SystemClock.elapsedRealtime() - lastTapAt < TAP_WINDOW_MS) {
                                // 用户自己点播放：把播放标识收起来，交给微信自己的进度环
                                hideBadge(view)
                                return
                            }
                            param.result = if (isResumeByDataGain) false else null
                            WeLogger.i(
                                TAG,
                                "拦截信息流自动播放：${clazz.simpleName}#${method.name}",
                            )
                        }
                    })
                    installed++
                }.onFailure {
                    WeLogger.e(TAG, "hook ${clazz.simpleName}#${method.name} 失败", it)
                }
            }
        }

        if (installed == 0) {
            WeLogger.e(TAG, "播放方法一个都没 hook 上，禁止自动播放不生效")
        } else {
            WeLogger.i(TAG, "已安装 $installed 个播放拦截点：点播放按钮/全屏页放行，滑动不播")
        }
    }

    override fun onDisable() {
        // Hook 由框架自动解除
    }

    /** 探针节流：2 秒内最多 30 条，避免滑一下刷爆日志。 */
    private val probeTimes = java.util.ArrayDeque<Long>()

    /**
     * 暂停时补一个「播放」标识。
     *
     * 微信朋友圈信息流里的视频 View 没有「播放三角」这个控件 —— 正常未播放时它显示的就是
     * 封面/首帧，跟照片长得一样。我们把自动播放拦掉之后，用户就分不清哪张是视频。
     *
     * 这里在微信自己的暂停方法 `F()V`（日志串 `%d pause play`）执行后，往视频 View 中央
     * 挂一个半透明圆底 + 白色三角；用户点播放（K/J 放行）时再收起来。
     */
    private fun hookPauseBadge(classes: List<Class<*>>) {
        classes.forEach { clazz ->
            val pause = runCatching {
                clazz.getDeclaredMethod("F").takeIf {
                    it.returnType == Void.TYPE && it.parameterTypes.isEmpty()
                }
            }.getOrNull() ?: return@forEach
            runCatching {
                XposedBridge.hookMethod(pause, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        if (isPlayerPage(view.context)) return
                        attachBadge(view)
                    }
                })
            }.onFailure { WeLogger.w(TAG, "hook ${clazz.simpleName}#F 失败", it) }
        }
    }

    /** 在视频 View 中央挂播放三角；已挂过就只恢复可见。 */
    private fun attachBadge(view: View) {
        val host = view as? ViewGroup ?: return
        val existing = host.getTag(BADGE_TAG) as? View
        if (existing != null && existing.parent === host) {
            existing.visibility = View.VISIBLE
            return
        }
        val size = dp(view.context, BADGE_SIZE_DP)
        val badge = ImageView(view.context).apply {
            setImageDrawable(badgeDrawable(view.context, size))
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val lp: ViewGroup.LayoutParams = when (host) {
            is FrameLayout -> FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            is RelativeLayout -> RelativeLayout.LayoutParams(size, size).apply {
                addRule(RelativeLayout.CENTER_IN_PARENT)
            }
            else -> ViewGroup.LayoutParams(size, size)
        }
        runCatching {
            host.addView(badge, lp)
            host.setTag(BADGE_TAG, badge)
            WeLogger.d(TAG, "已挂上播放标识：${host.javaClass.simpleName}")
        }.onFailure { WeLogger.w(TAG, "挂播放标识失败", it) }
    }

    /** 收起播放三角（用户开始播放 / 进入全屏页时）。 */
    private fun hideBadge(view: View) {
        val host = view as? ViewGroup ?: return
        (host.getTag(BADGE_TAG) as? View)?.let { it.visibility = View.GONE }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** 半透明圆底 + 白色三角，尺寸与微信自己的圆形按钮接近。 */
    private fun badgeDrawable(context: Context, size: Int): Drawable {
        val safe = size.coerceAtLeast(24)
        val bitmap = Bitmap.createBitmap(safe, safe, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0x66000000
        canvas.drawCircle(safe / 2f, safe / 2f, safe / 2f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        val path = Path().apply {
            moveTo(safe * 0.40f, safe * 0.29f)
            lineTo(safe * 0.73f, safe * 0.50f)
            lineTo(safe * 0.40f, safe * 0.71f)
            close()
        }
        canvas.drawPath(path, paint)
        return BitmapDrawable(context.resources, bitmap)
    }

    /**
     * 探针：只记录、不拦截。
     *
     * `A/J/K` 之外，朋友圈视频 View 上还有别的短名方法参与起播。这里把它们的调用序列打到
     * 日志里（仅朋友圈信息流页、带节流），用户再报「还在自动播」时能直接看出走的是哪个方法。
     */
    private fun hookProbe(classes: List<Class<*>>) {
        classes.forEach { clazz ->
            clazz.declaredMethods.forEach { method ->
                if (method.name.length > 2) return@forEach
                if (method.name == "K" || method.name == "J" || method.name == "A" ||
                    method.name == "S" || method.name == "onPrepared"
                ) {
                    return@forEach
                }
                runCatching {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            val view = param.thisObject as? View ?: return
                            if (isPlayerPage(view.context)) return
                            if (isSnsPage(view.context) != true) return
                            probeLog("${clazz.simpleName}#${method.name}")
                        }
                    })
                }
            }
        }
    }

    private fun probeLog(label: String) {
        val now = SystemClock.elapsedRealtime()
        synchronized(probeTimes) {
            while (probeTimes.isNotEmpty() && now - (probeTimes.peekFirst() ?: now) > 2000L) {
                probeTimes.pollFirst()
            }
            if (probeTimes.size >= 30) return
            probeTimes.addLast(now)
        }
        WeLogger.i(TAG, "[probe] $label")
    }

    /**
     * 记录「用户刚刚点过」。
     *
     * 一路是 View.performClick —— 播放按钮这类子控件被点都会走它；
     * 另一路是视频 View 自己的 onTouchEvent，兜住自定义触摸（没有 setOnClickListener 的控件）。
     */
    private fun hookTapSignals(classes: List<Class<*>>) {
        runCatching {
            val performClick = View::class.java.getDeclaredMethod("performClick")
            XposedBridge.hookMethod(performClick, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    // 只在朋友圈相关页面的点击才记为「视频点击」，否则在聊天页点赞也会误放行。
                    val view = param.thisObject as? View ?: return
                    if (isSnsPage(view.context) == false) return
                    lastTapAt = SystemClock.elapsedRealtime()
                }
            })
        }.onFailure { WeLogger.e(TAG, "hook View.performClick 失败", it) }

        classes.forEach { clazz ->
            runCatching {
                val onTouch = clazz.getDeclaredMethod("onTouchEvent", MotionEvent::class.java)
                onTouch.isAccessible = true
                XposedBridge.hookMethod(onTouch, object : XC_MethodHook() {
                    private var downAt = 0L
                    private var downX = 0f
                    private var downY = 0f

                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val event = param.args.firstOrNull() as? MotionEvent ?: return
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downAt = SystemClock.elapsedRealtime()
                                downX = event.x
                                downY = event.y
                            }

                            MotionEvent.ACTION_UP -> {
                                val duration = SystemClock.elapsedRealtime() - downAt
                                val dx = (event.x - downX).toDouble()
                                val dy = (event.y - downY).toDouble()
                                val distance = Math.hypot(dx, dy)
                                if (downAt > 0L && duration < TAP_MAX_MS && distance < TAP_SLOP_PX) {
                                    lastTapAt = SystemClock.elapsedRealtime()
                                }
                            }
                        }
                    }
                })
            }.onFailure {
                // 多数版本里这几个 View 没有 override onTouchEvent（触摸由子控件 / 父类处理），
                // 属正常情况：点击识别靠上面的 View.performClick，这里只作为补充。
                WeLogger.d(TAG, "${clazz.simpleName} 没有可 hook 的 onTouchEvent：${it.message}")
            }
        }
    }

    /**
     * 当前是否处在全屏播放页。
     *
     * 先沿 View 的 ContextWrapper 链找 Activity；播放器用 application context 构造时链上没有 Activity，
     * 再用 ActivityThread 里的栈顶 Activity 兜底 —— 否则全屏页也会被拦，表现为「点进去也播不了」。
     */
    private fun isPlayerPage(context: Context?): Boolean {
        var ctx = context
        var guard = 0
        while (ctx != null && guard++ < 8) {
            if (ctx is Activity) return isPlayerPageName(ctx.javaClass.name)
            ctx = (ctx as? ContextWrapper)?.baseContext
        }
        val top = getTopMostActivity() ?: return false
        return isPlayerPageName(top.javaClass.name)
    }

    private fun isPlayerPageName(name: String): Boolean = playerPages.any { name.contains(it) }

    /**
     * 当前 Context 是否属于朋友圈（Sns）页面。
     *
     * 解析不出 Activity（播放器可能是用 application context 构造的）时返回 null，
     * 调用方按「无法判断」处理 —— 宁可放行，也不要出现「点了播放按钮却不播」。
     */
    private fun isSnsPage(context: Context?): Boolean? {
        var ctx = context
        var guard = 0
        while (ctx != null && guard++ < 8) {
            if (ctx is Activity) return ctx.javaClass.name.contains("Sns")
            ctx = (ctx as? ContextWrapper)?.baseContext
        }
        val top = getTopMostActivity() ?: return null
        return top.javaClass.name.contains("Sns")
    }
}
