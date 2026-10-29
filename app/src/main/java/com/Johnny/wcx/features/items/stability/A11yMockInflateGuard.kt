package com.Johnny.wcx.features.items.stability

import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.ClassLoaders
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无障碍递归护栏。
 *
 * 微信 8.0.79 的 `MMBaseAccessibilityConfig.mockInflateCore` 会自递归，
 * 实测在 `[GT]ColdPool#11` 线程上连续 13138 帧后抛
 * `StackOverflowError: stack size 1037KB`，直接把整个微信打死。日志特征：
 *
 *   MMBaseAccessibilityConfig.mockInflateCore  ← 连续上万次
 *     → onMockInflate → AccProviderFactory.dispatchViewInflate
 *
 * 微信这套 mock-inflate 只服务无障碍语义，普通用户看不到差别，
 * 所以这里按线程计深度，超过阈值就掐断这一层，让进程活下来。
 *
 * 实现要点（都是踩过坑的地方）：
 * 1. 层深记录写进 `param`（setObjectExtra），不用 ThreadLocal<Boolean> 当「本层是否计数」的标记 ——
 *    嵌套调用时单个布尔会被内层消费掉，导致外层不减、深度一路漂高，最后把正常调用也全掐了。
 * 2. 掐断日志只打一次，避免在崩溃路径上刷上万条（日志本身还要 String.format，等于加压）。
 * 3. 先探测返回类型：只有返回 void 才安全回 null，引用类型的回 null 可能让上层 NPE（但比整进程崩好）。
 * 4. 阈值取 128：正常 mock-inflate 只有几层，插件化页面（小程序/朋友圈/视频号）十几层，128 留足余量。
 *
 * 与主题功能没有任何调用关系，属于独立稳定性补丁，默认开启、始终生效。
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

    /**
     * 允许的最大递归深度。
     *
     * 128 太激进：8.0.80 的媒体预览（picker/chrome）布局嵌套较深，正常调用就能摸到，
     * 一旦被掐断就会让上层拿到 null。这里放宽到 512 —— 正常场景（含小程序/朋友圈/视频号）
     * 远达不到，而真正的自递归是上万帧级别，512 仍能在栈溢出之前打断 void 递归链。
     */
    private const val MAX_DEPTH = 512

    /** 本层深度写在 param 上，after 里按自己那份还原 —— 嵌套调用不会互相吃掉。 */
    private const val EXTRA_DEPTH = "wcx_a11y_mock_inflate_depth"

    override val defaultEnabled = true

    /** 护栏必须始终生效，不随任何用户开关变化。 */
    override val shouldEnableOnStartup = true

    /** 每线程当前的递归深度。 */
    private val depth = ThreadLocal<Int>()

    /** 掐断日志只打一次。 */
    private val trimmedOnceLogged = AtomicBoolean(false)

    override fun onEnable() {
        runCatching {
            val clazz = XposedHelpers.findClass(TARGET_CLASS, ClassLoaders.HOST)
            val methods = clazz.declaredMethods.filter { it.name == TARGET_METHOD }
            if (methods.isEmpty()) {
                WeLogger.w(TAG, "没找到 $TARGET_CLASS#$TARGET_METHOD，护栏未安装")
                return@runCatching
            }
            WeLogger.i(
                TAG,
                "目标重载 ${methods.size} 个：" +
                    methods.joinToString { "${it.returnType.simpleName}(${it.parameterTypes.size})" },
            )

            // 同一个 hook 实例挂到所有同名重载上，保证 before/after 严格配对
            XposedBridge.hookAllMethods(clazz, TARGET_METHOD, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val next = (depth.get() ?: 0) + 1
                    depth.set(next)
                    param.setObjectExtra(EXTRA_DEPTH, next)
                    if (next <= MAX_DEPTH) return

                    val returnType = (param.method as? Method)?.returnType

                    // 只掐断「返回 void / 基本类型」的重载。
                    // 返回引用类型时回 null 会让上层直接 NPE —— 实测 8.0.80 媒体预览
                    // LocalMediaPreviewActionBarView.a() 里 getInflateRootView() 拿到 null 后
                    // 立刻 null.findViewById() 崩溃（picker/chrome）。这条路径比 StackOverflow
                    // 更早触发、影响面更大，所以引用类型一律放行。
                    if (returnType != null && returnType != Void.TYPE && !returnType.isPrimitive) {
                        if (trimmedOnceLogged.compareAndSet(false, true)) {
                            WeLogger.w(
                                TAG,
                                "$TARGET_METHOD 深度 $next 超限（返回 ${returnType.simpleName}），放行不掐断，避免上层 NPE",
                            )
                        }
                        return
                    }

                    if (trimmedOnceLogged.compareAndSet(false, true)) {
                        WeLogger.w(TAG, "$TARGET_METHOD 递归深度 $next 超限，已掐断本层（后续静默处理）")
                    }
                    param.result = defaultFor(returnType)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    // 只按本层写进去的值还原，嵌套场景不会漏减
                    val mine = param.getObjectExtra(EXTRA_DEPTH) as? Int ?: return
                    param.setObjectExtra(EXTRA_DEPTH, null)
                    depth.set((mine - 1).coerceAtLeast(0))
                }
            })
            WeLogger.i(TAG, "无障碍递归护栏已安装（上限 $MAX_DEPTH 层，超限直接掐断）")
        }.onFailure {
            WeLogger.w(TAG, "安装无障碍递归护栏失败：${it.message}")
        }
    }

    /**
     * 按返回类型给一个安全值。
     *
     * 只对 void / 基本类型使用：引用类型不会走到这里（调用方已放行），
     * 因为给引用类型返回 null 会让上层 NPE。
     */
    private fun defaultFor(returnType: Class<*>?): Any? {
        if (returnType == null || returnType == Void.TYPE) return null
        if (!returnType.isPrimitive) return null
        return when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> ' '
            else -> null
        }
    }

    override fun onDisable() {
        depth.remove()
        trimmedOnceLogged.set(false)
    }
}
