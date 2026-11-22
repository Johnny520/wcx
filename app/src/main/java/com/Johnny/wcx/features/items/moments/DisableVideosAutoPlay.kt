package com.Johnny.wcx.features.items.moments

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookBeforeDirectly
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 禁止朋友圈信息流里的视频自动播放，点开 / 点播放按钮仍可正常播放。
 *
 * ## 为什么旧实现（只 hook `resumeByDataGain`）在 8.0.80 上没效果
 *
 * 旧实现假定信息流自动播放一定走 `OnlineVideoView#resumeByDataGain`（数据就绪后恢复播放）。
 * 但在 8.0.80 上实测，能触发播放的入口至少有三条（全 dex 扫 invoke 目标得到）：
 *
 * - `OnlineVideoViewHelper#r` → `OnlineVideoView#J()`（resumeByDataGain，数据就绪）
 * - `SnsTimelineVideoView#K()`（improve 时间线包装层）→ `OnlineVideoView#K()`（resumePlay）
 * - `ik4.* / wf4.* / dm4.q0` 等时间线 UIC / 协程回调 → `SnsTimelineVideoView#K()`
 *
 * 也就是说，可见性变化 / 封面动画结束这些「自动播放」场景是直接调 `resumePlay` 的，
 * 和用户点播放按钮走同一个方法 —— 只 hook resumeByDataGain 自然拦不住。
 *
 * ## 现在的做法
 *
 * 1. 三条播放入口（resumeByDataGain / OnlineVideoView#resumePlay / SnsTimelineVideoView#resumePlay）
 *    全部挂 hook，全屏播放页（SnsOnlineVideoActivity / 图集 / 视频详情）白名单放行。
 * 2. 用「触摸门控」区分自动与手动：hook `Activity#dispatchTouchEvent` 只在 ACTION_DOWN 时记一个
 *    时间戳（全进程一次赋值，开销可忽略）。播放请求发生在 [TOUCH_GATE_MS] 内说明是用户刚点的 ——
 *    放行；超过这个窗口还没人碰屏幕，就是信息流自己弹出来的播放 —— 拦掉。
 *
 * 这样不依赖任何一个混淆类的方法语义，也不会因为微信换播放器而失效。
 */
@Feature(
    name = "禁止自动播放视频",
    categories = ["朋友圈"],
    description = "禁止朋友圈信息流中的视频自动播放，点开或点播放按钮仍可正常播放"
)
object DisableVideosAutoPlay : SwitchFeature(), IResolveDex {

    private const val TAG = "DisableVideosAutoPlay"

    /** 埋点里的类名常量（微信自己写的字符串，未混淆）。 */
    private const val ANCHOR_CLASS = "com.tencent.mm.plugin.sns.ui.OnlineVideoView"

    /** improve 时间线的视频包装层，播放请求会先经过它。 */
    private const val ANCHOR_TIMELINE_CLASS = "com.tencent.mm.plugin.sns.ui.video.SnsTimelineVideoView"

    /** 视频数据就绪后自动恢复播放的方法名（埋点常量）。 */
    private const val ANCHOR_RESUME_BY_DATA_GAIN = "resumeByDataGain"

    /** 从暂停恢复播放的方法名（埋点常量）。 */
    private const val ANCHOR_RESUME_PLAY = "resumePlay"

    /** 触摸门控窗口：这段时间内发生过 ACTION_DOWN 就认为是用户主动点的播放。 */
    private const val TOUCH_GATE_MS = 2500L

    /** 视频数据就绪 → 自动恢复播放（`OnlineVideoView#J()Z`）。 */
    private val methodResumeByDataGain by dexMethod(allowMultiple = true, allowFailure = true) {
        matcher {
            usingEqStrings(ANCHOR_RESUME_BY_DATA_GAIN, ANCHOR_CLASS)
        }
    }

    /** 信息流 resumePlay（`OnlineVideoView#K()V`）；可见性变化、封面动画结束都会走这里。 */
    private val methodResumePlay by dexMethod(allowMultiple = true, allowFailure = true) {
        matcher {
            declaredClass(ANCHOR_CLASS)
            paramTypes()
            returnType("void")
            usingEqStrings(ANCHOR_RESUME_PLAY)
        }
    }

    /** improve 时间线的 resumePlay（`SnsTimelineVideoView#K()V`）。 */
    private val methodTimelineResumePlay by dexMethod(allowMultiple = true, allowFailure = true) {
        matcher {
            declaredClass(ANCHOR_TIMELINE_CLASS)
            paramTypes()
            returnType("void")
            usingEqStrings(ANCHOR_RESUME_PLAY)
        }
    }

    /** 全屏播放页：这里的播放都是用户主动点开的，必须放行。 */
    private val playerPages = arrayOf(
        "SnsOnlineVideoActivity",
        "SnsGalleryUI",
        "SnsVideoDetailUI",
        "SnsImageViewUI",
    )

    /** 最近一次 ACTION_DOWN 的时刻（uptimeMillis）。 */
    private val lastTouchAt = AtomicLong(0L)

    /**
     * 用户已经主动播过的 View。
     *
     * 视频播放过程中微信会为了缓冲恢复而再次调 resumePlay —— 那时早就不在触摸窗口内了。
     * 若继续拦，用户点开的视频会在第一次卡顿后再也播不动。所以只要某个 View 被「用户主动」
     * 放行过一次，这个 View 之后的所有播放请求一律放行（WeakHashMap，View 回收即自动清理）。
     */
    private val userStartedViews = WeakHashMap<View, Boolean>()

    override fun onEnable() {
        var installed = 0

        if (methodResumeByDataGain.isPlaceholder) {
            WeLogger.w(TAG, "未定位到 $ANCHOR_RESUME_BY_DATA_GAIN（锚点失效）")
        } else {
            runCatching {
                methodResumeByDataGain.hookBefore {
                    val view = thisObject as? View ?: return@hookBefore
                    if (allowPlay(view)) return@hookBefore
                    result = false
                }
                installed++
            }.onFailure { WeLogger.e(TAG, "安装 resumeByDataGain 拦截失败", it) }
        }

        if (methodResumePlay.isPlaceholder) {
            WeLogger.w(TAG, "未定位到 OnlineVideoView#resumePlay（锚点失效）")
        } else {
            runCatching {
                methodResumePlay.hookBefore {
                    val view = thisObject as? View ?: return@hookBefore
                    if (allowPlay(view)) return@hookBefore
                    // void 方法：result = null 即跳过原方法（XposedBridge 视为提前返回）
                    result = null
                    WeLogger.d(TAG, "拦截信息流自动播放（OnlineVideoView#resumePlay）")
                }
                installed++
            }.onFailure { WeLogger.e(TAG, "安装 OnlineVideoView#resumePlay 拦截失败", it) }
        }

        if (methodTimelineResumePlay.isPlaceholder) {
            WeLogger.w(TAG, "未定位到 SnsTimelineVideoView#resumePlay（锚点失效）")
        } else {
            runCatching {
                methodTimelineResumePlay.hookBefore {
                    val view = thisObject as? View ?: return@hookBefore
                    if (allowPlay(view)) return@hookBefore
                    result = null
                    WeLogger.d(TAG, "拦截信息流自动播放（SnsTimelineVideoView#resumePlay）")
                }
                installed++
            }.onFailure { WeLogger.e(TAG, "安装 SnsTimelineVideoView#resumePlay 拦截失败", it) }
        }

        installTouchTracker()

        if (installed == 0) {
            WeLogger.e(TAG, "三个播放锚点全部失效，禁止自动播放不生效")
        } else {
            WeLogger.i(TAG, "已安装 $installed 个播放拦截点（触摸门控 ${TOUCH_GATE_MS}ms）")
        }
    }

    override fun onDisable() {
        // Hook 由框架自动解除
    }

    /** 只记录 ACTION_DOWN：全进程所有 Activity 共用，一次 long 赋值，无额外开销。 */
    private fun installTouchTracker() {
        runCatching {
            val method = Activity::class.java
                .getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
            method.hookBeforeDirectly {
                val event = args.firstOrNull() as? MotionEvent ?: return@hookBeforeDirectly
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    lastTouchAt.set(SystemClock.uptimeMillis())
                }
            }
            WeLogger.i(TAG, "触摸门控已安装")
        }.onFailure {
            WeLogger.e(TAG, "触摸门控安装失败：将退化为「全屏页放行、其余全拦」", it)
        }
    }

    /**
     * 决定这次播放请求是否放行。
     *
     * 放行条件：全屏播放页 / 这个 View 之前被用户主动播过 / 最近 [TOUCH_GATE_MS] 内碰过屏幕。
     * 其余一律视为信息流自动播放。
     *
     * 触摸门控安装失败时 lastTouchAt 恒为 0，按「没有触摸」处理：宁可多拦
     * （用户再点一下播放就能播），也不放自动播放。
     */
    private fun allowPlay(view: View): Boolean {
        if (isPlayerPage(view.context)) return true

        synchronized(userStartedViews) {
            if (userStartedViews.containsKey(view)) return true
        }

        val last = lastTouchAt.get()
        val touched = last != 0L && SystemClock.uptimeMillis() - last < TOUCH_GATE_MS
        if (touched) {
            synchronized(userStartedViews) { userStartedViews[view] = true }
            return true
        }
        return false
    }

    /** 当前是否处在全屏播放页（沿 ContextWrapper 链找 Activity）。 */
    private fun isPlayerPage(context: Context?): Boolean {
        var ctx = context
        var guard = 0
        while (ctx != null && guard++ < 8) {
            if (ctx is Activity) {
                val name = ctx.javaClass.name
                return playerPages.any { name.contains(it) }
            }
            ctx = (ctx as? ContextWrapper)?.baseContext
        }
        return false
    }
}
