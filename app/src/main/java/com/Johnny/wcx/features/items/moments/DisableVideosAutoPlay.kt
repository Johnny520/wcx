package com.Johnny.wcx.features.items.moments

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.getTopMostActivity

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
 * 2. 放行条件只有一条：**当前 Activity 是全屏播放页**（点开视频后的 SnsOnlineVideoActivity /
 *    图集 SnsGalleryUI / 视频详情 SnsVideoDetailUI）。信息流（SnsTimeLineUI、ImproveSnsTimelineUI、
 *    单条详情 SnsCommentDetailUI）里一律不播 —— 不管用户怎么滑动、有没有点播放按钮，
 *    信息流只会显示封面和播放按钮，要看必须点进全屏页。
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

    override fun onEnable() {
        var installed = 0

        if (methodResumeByDataGain.isPlaceholder) {
            WeLogger.w(TAG, "未定位到 $ANCHOR_RESUME_BY_DATA_GAIN（锚点失效）")
        } else {
            runCatching {
                methodResumeByDataGain.hookBefore {
                    val view = thisObject as? View ?: return@hookBefore
                    if (isPlayerPage(view.context)) return@hookBefore
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
                    if (isPlayerPage(view.context)) return@hookBefore
                    // void 方法：result = null 即跳过原方法（XposedBridge 视为提前返回）
                    result = null
                    WeLogger.d(TAG, "拦截信息流播放（OnlineVideoView#resumePlay）")
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
                    if (isPlayerPage(view.context)) return@hookBefore
                    result = null
                    WeLogger.d(TAG, "拦截信息流播放（SnsTimelineVideoView#resumePlay）")
                }
                installed++
            }.onFailure { WeLogger.e(TAG, "安装 SnsTimelineVideoView#resumePlay 拦截失败", it) }
        }

        if (installed == 0) {
            WeLogger.e(TAG, "三个播放锚点全部失效，禁止自动播放不生效")
        } else {
            WeLogger.i(TAG, "已安装 $installed 个播放拦截点：仅全屏播放页放行")
        }
    }

    override fun onDisable() {
        // Hook 由框架自动解除
    }

    /**
     * 当前是否处在全屏播放页。
     *
     * 先沿 View 的 ContextWrapper 链找 Activity；播放器是用 application context 构造的时候链上
     * 没有 Activity，再用 ActivityThread 里的栈顶 Activity 兜底 —— 否则全屏页也会被拦，
     * 表现为「点进去也播不了」。
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
}
