package com.Johnny.wcx.features.items.moments

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger

@Feature(
    name = "禁止自动播放视频",
    categories = ["朋友圈"],
    description = "禁止朋友圈中的视频自动播放"
)
object DisableVideosAutoPlay : SwitchFeature(), IResolveDex {

    private const val TAG = "DisableVideosAutoPlay"

    // Hook ①：SnsAutoPlayUtil.checkAutoPlay — 自动播放策略判断
    private val methodCheckAutoPlay by dexMethod {
        matcher {
            usingEqStrings(
                "checkAutoPlay",
                "com.tencent.mm.plugin.sns.util.SnsAutoPlayUtil"
            )
        }
    }

    // Hook ②：ImproveAutoPlayManager.autoPlay$2.invoke — 改进版自动播放触发
    private val methodImproveAutoPlayInvoke by dexMethod {
        matcher {
            usingEqStrings(
                "invoke",
                $$"com.tencent.mm.plugin.sns.ui.improve.util.ImproveAutoPlayManager$autoPlay$2"
            )
        }
    }

    override fun onEnable() {
        // Hook ①：SnsAutoPlayUtil.checkAutoPlay → 强制返回 false
        try {
            methodCheckAutoPlay.hookBefore {
                try {
                    if (method is java.lang.reflect.Method) {
                        val returnType = (method as java.lang.reflect.Method).returnType
                        if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
                            result = false
                        }
                    }
                } catch (e: Throwable) {
                    // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "checkAutoPlay hook 注册失败", e)
        }

        // Hook ②：ImproveAutoPlayManager.autoPlay$2.invoke → 强制返回 false
        try {
            methodImproveAutoPlayInvoke.hookBefore {
                try {
                    if (method is java.lang.reflect.Method) {
                        val returnType = (method as java.lang.reflect.Method).returnType
                        if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
                            result = false
                        }
                    }
                } catch (e: Throwable) {
                    // 兜底异常捕获
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "ImproveAutoPlay hook 注册失败", e)
        }
    }

    override fun onDisable() {
        // 关闭功能时无需额外操作，Hook 由框架自动解除
    }
}