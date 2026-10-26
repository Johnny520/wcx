package com.Johnny.wcx.features.items.stability

import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.ClassLoaders
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 无障碍递归护栏。
 *
 * 微信 8.0.79 的 `MMBaseAccessibilityConfig.mockInflateCore` 在部分机型上会自己调自己，
 * 递归上百层后在主线程抛 `StackOverflowError: stack size 1037KB`，直接把整个微信打死。
 * 日志特征：
 *
 *   at com.tencent.mm.accessibility.base.MMBaseAccessibilityConfig.mockInflateCore
 *   at com.tencent.mm.accessibility.core.provider.AccProviderFactory.dispatchViewInflate
 *   ...
 *
 * 微信自身的 a11y mock-inflate 只服务无障碍语义，普通用户看不到差别，
 * 所以这里按线程计深度，超过阈值直接掐断这一层（param.result = null），
 * 让进程活下来，而不是等栈爆掉。
 *
 * 注意：这个护栏与「主题工坊」没有任何调用关系，属于独立的稳定性补丁，
 * 默认开启且不跟随用户开关（用户看不到它也能生效）。
 */
@Feature(
    name = "无障碍递归护栏",
    categories = ["稳定性"],
    description = "拦截微信无障碍 mock-inflate 的自递归，避免 StackOverflowError 打死微信进程",
)
object A11yMockInflateGuard : SwitchFeature() {

    private const val TAG = "A11yInflateGuard"
    private const val TARGET_CLASS = "com.tencent.mm.accessibility.base.MMBaseAccessibilityConfig"
    private const val TARGET_METHOD = "mockInflateCore"

    /** 允许的最大递归深度。微信正常情况只有几层，64 层足够宽松且远小于爆栈阈值。 */
    private const val MAX_DEPTH = 64

    override val defaultEnabled = true

    /** 护栏必须始终生效，不能因为用户关掉某个开关就失去保护。 */
    override val shouldEnableOnStartup = true

    private val depth = ThreadLocal<Int>()

    /** 本层是否已经 +1 过，用于异常路径安全回退（否则计数会一路漂高）。 */
    private val counted = ThreadLocal.withInitial { false }

    override fun onEnable() {
        runCatching {
            val clazz = XposedHelpers.findClass(TARGET_CLASS, ClassLoaders.HOST)
            // 同一个 hook 实例挂到所有同名重载上：before +1 / after -1 严格配对。
            XposedBridge.hookAllMethods(clazz, TARGET_METHOD, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val next = (depth.get() ?: 0) + 1
                    depth.set(next)
                    counted.set(true)
                    if (next > MAX_DEPTH) {
                        WeLogger.w(TAG, "$TARGET_METHOD 递归深度超限($next)，已掐断本层")
                        param.result = null
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!counted.get()) return
                    counted.set(false)
                    depth.set(((depth.get() ?: 1) - 1).coerceAtLeast(0))
                }
            })
            WeLogger.i(TAG, "无障碍递归护栏已安装（$TARGET_CLASS#$TARGET_METHOD，上限 $MAX_DEPTH 层）")
        }.onFailure {
            WeLogger.w(TAG, "安装无障碍递归护栏失败：${it.message}")
        }
    }

    override fun onDisable() {
        depth.remove()
        counted.remove()
    }
}
