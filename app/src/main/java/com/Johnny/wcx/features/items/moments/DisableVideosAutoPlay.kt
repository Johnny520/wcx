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
object DisableVideosAutoPlay : SwitchFeature(), IResolveDEX {

    private const val TAG = "DisableVideosAutoPlay"

    // ── 原有 Hook 点 ①：SnsAutoPlayUtil.checkAutoPlay ──────────────────────
    private val methodCheckAutoPlay by dexMethod {
        matcher {
            usingEqStrings(
                "checkAutoPlay",
                "com.tencent.mm.plugin.sns.util.SnsAutoPlayUtil"
            )
        }
    }

    // ── 原有 Hook 点 ②：ImproveAutoPlayManager.autoPlay$2.invoke ────────────
    private val methodImproveAutoPlayInvoke by dexMethod {
        matcher {
            usingEqStrings(
                "invoke",
                $$"com.tencent.mm.plugin.sns.ui.improve.util.ImproveAutoPlayManager$autoPlay$2"
            )
        }
    }

    // ── 新增 Hook 点 ③：SnsVideoView / SightView 自动开始播放 ────────────────
    private val methodVideoStartPlay by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.ui")
        matcher {
            usingEqStrings("start", "SnsVideoView")
        }
    }

    // ── 新增 Hook 点 ④：视频预加载 / prepare 阶段拦截 ────────────────────────
    private val methodVideoPrepare by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns")
        matcher {
            usingEqStrings("prepare", "SnsVideo")
        }
    }

    override fun onEnable() {
        WeLogger.i(TAG, "========== 禁止朋友圈视频自动播放: 已开启 ==========")

        // Hook ①：SnsAutoPlayUtil.checkAutoPlay
        try {
            methodCheckAutoPlay.hookBefore {
                try {
                    if (method is java.lang.reflect.Method) {
                        val returnType = (method as java.lang.reflect.Method).returnType
                        if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
                            WeLogger.d(TAG, "拦截 checkAutoPlay: 返回 false, 调用栈=${Thread.currentThread().stackTrace.take(5).joinToString(" <- ") { it.methodName }}")
                            result = false
                        }
                    }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "checkAutoPlay hook 异常", e)
                }
            }
            WeLogger.d(TAG, "Hook ① checkAutoPlay 注册成功")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Hook ① checkAutoPlay 注册失败", e)
        }

        // Hook ②：ImproveAutoPlayManager.autoPlay$2.invoke
        try {
            methodImproveAutoPlayInvoke.hookBefore {
                try {
                    if (method is java.lang.reflect.Method) {
                        val returnType = (method as java.lang.reflect.Method).returnType
                        if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
                            WeLogger.d(TAG, "拦截 ImproveAutoPlay invoke: 返回 false")
                            result = false
                        }
                    }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "ImproveAutoPlay hook 异常", e)
                }
            }
            WeLogger.d(TAG, "Hook ② ImproveAutoPlay 注册成功")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Hook ② ImproveAutoPlay 注册失败 (可能微信版本已变更)", e)
        }

        // Hook ③：视频 View 开始播放 — 兜底拦截
        try {
            methodVideoStartPlay.hookBefore {
                try {
                    WeLogger.d(TAG, "拦截视频 start 播放: 阻断自动播放")
                    result = null
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "videoStart hook 异常", e)
                }
            }
            WeLogger.d(TAG, "Hook ③ videoStart 注册成功")
        } catch (e: Throwable) {
            WeLogger.w(TAG, "Hook ③ videoStart 注册失败 (可能微信版本已变更): ${e.message}")
        }

        // Hook ④：视频 prepare 阶段 — 兜底拦截
        try {
            methodVideoPrepare.hookBefore {
                try {
                    WeLogger.d(TAG, "拦截视频 prepare: 阻断预加载自动播放")
                    result = null
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "videoPrepare hook 异常", e)
                }
            }
            WeLogger.d(TAG, "Hook ④ videoPrepare 注册成功")
        } catch (e: Throwable) {
            WeLogger.w(TAG, "Hook ④ videoPrepare 注册失败 (可能微信版本已变更): ${e.message}")
        }

        WeLogger.i(TAG, "========== 禁止朋友圈视频自动播放: 全部 Hook 注册完成 ==========")
    }

    override fun onDisable() {
        WeLogger.i(TAG, "禁止朋友圈视频自动播放: 已关闭，恢复原生行为")
    }
}