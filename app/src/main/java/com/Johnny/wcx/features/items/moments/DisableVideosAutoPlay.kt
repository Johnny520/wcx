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

/**
 * 禁止朋友圈信息流里的视频自动播放。
 *
 * 旧实现（沿用上游 WeKit 的写法）靠
 * `usingEqStrings("checkAutoPlay", "com.tencent.mm.plugin.sns.util.SnsAutoPlayUtil")` 定位，
 * 但微信 8.0.79 已经把 `SnsAutoPlayUtil` / `ImproveAutoPlayManager` 这两个类删掉或改名，
 * matcher 永远不命中，`by dexMethod` 在解析阶段就抛异常——表现就是「开关能开，但一点效果都没有」。
 *
 * 现在改微信 8.x 里真正在用的 OnlineVideoView，定位方式换成「埋点常量」：
 * 微信为了性能统计，给每个方法开头都塞了
 * `SnsMethodCalculate.markStartTimeMs("<方法名>", "com.tencent.mm.plugin.sns.ui.OnlineVideoView")`，
 * 方法体里因此同时存在「方法名」和「类名」两个字符串常量，正好可以当作跨版本稳定的锚点，
 * 完全不依赖混淆后的类名/方法名（8.0.79 里那个方法是 `J()Z`，任何版本都可能变）。
 *
 * 拦截点是 `resumeByDataGain()`——视频数据就绪后自动恢复播放，这是信息流自动播放的主路径；
 * 用户主动点播放走的是 `resumePlay()`，不受影响。另外加了全屏播放页白名单，
 * 保证点开视频后仍然能正常播放。
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

    /** 视频数据就绪后自动恢复播放的方法名（埋点常量）。 */
    private const val ANCHOR_RESUME_BY_DATA_GAIN = "resumeByDataGain"

    /**
     * 视频数据就绪 → 自动恢复播放。
     *
     * 同一方法体内同时引用「方法名」与「类名」两个常量，定位唯一且稳定。
     */
    private val methodResumeByDataGain by dexMethod(allowMultiple = true, allowFailure = true) {
        matcher {
            usingEqStrings(ANCHOR_RESUME_BY_DATA_GAIN, ANCHOR_CLASS)
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
        if (methodResumeByDataGain.isPlaceholder) {
            WeLogger.e(TAG, "未定位到 $ANCHOR_RESUME_BY_DATA_GAIN（锚点失效），禁止自动播放不生效")
            return
        }
        runCatching {
            methodResumeByDataGain.hookBefore {
                val view = thisObject as? View
                if (view != null && isPlayerPage(view.context)) return@hookBefore
                result = false
            }
            WeLogger.i(TAG, "已安装：拦截信息流视频自动播放（$ANCHOR_RESUME_BY_DATA_GAIN）")
        }.onFailure {
            WeLogger.e(TAG, "安装失败", it)
        }
    }

    override fun onDisable() {
        // Hook 由框架自动解除
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
