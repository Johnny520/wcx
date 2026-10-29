package com.Johnny.wcx.features.items.stability

import android.view.View
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.ClassLoaders
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method

/**
 * 媒体预览操作栏护栏。
 *
 * 崩溃现场（微信 8.0.80，模块 v344，聊天 → 相册 → 点图片放大）：
 *
 * ```
 * java.lang.NullPointerException: Attempt to invoke virtual method
 *   'android.view.View android.view.View.findViewById(int)' on a null object reference
 *     at com.tencent.mm.plugin.picker.chrome.LocalMediaPreviewActionBarView.a(Unknown Source:30)
 *     at qq0.l0.onViewAttachedToWindow(Unknown Source:22)
 * ```
 *
 * 反汇编确认 `a()` 的流程是：`LayoutInflater.inflate(...)` → 建 ViewBinding →
 * `binding.root.findViewById(...)` 若干次 → 返回根 View。其中每个 `findViewById`
 * 前面都有 `if-nez` 空检查，正常情况下不会 NPE —— 说明构建期间的状态被外部破坏了
 * （模块 hook 不在调用链上，但可能间接改了 View/资源状态）。
 *
 * 处理策略：**成功透传、失败降级**。
 * 用 `invokeOriginalMethod` 提前执行原方法：
 *  - 正常返回 → 原样交回，功能零变化；
 *  - 抛异常 → 返回一个空的 FrameLayout 顶替操作栏，预览页照常打开、微信不崩
 *    （代价只是底部「返回/勾选」按钮不显示，比闪退好得多）。
 */
@Feature(
    name = "媒体预览护栏",
    categories = ["稳定性"],
    description = "拦截媒体预览操作栏构建失败导致的崩溃，失败时降级为空操作栏而不是闪退",
)
object MediaPreviewInflateGuard : SwitchFeature() {

    private const val TAG = "MediaPreviewGuard"

    /** 未混淆（崩溃栈里就是完整类名），可直接按名查找。 */
    private const val TARGET_CLASS =
        "com.tencent.mm.plugin.picker.chrome.LocalMediaPreviewActionBarView"

    /**
     * 默认关闭。
     *
     * 8.0.80 上发现该护栏的降级返回值（一个裸 View）本身会引发新的崩溃：
     * 上层拿它当操作栏去取子控件 / getInflateRootView 时拿到 null。
     * 在找到「能返回合法容器」的正确降级方式之前先默认关闭，
     * 需要时可在「稳定性」分类里手动开启。
     */
    override val defaultEnabled = false

    /** 不再强制随启动生效：由用户开关控制。 */
    override val shouldEnableOnStartup = false

    override fun onEnable() {
        runCatching {
            val clazz = Class.forName(TARGET_CLASS, false, ClassLoaders.HOST)
            // 目标方法：无参 + 返回 View（8.0.80 混淆为 a()）
            val methods = clazz.declaredMethods.filter {
                it.parameterCount == 0 && View::class.java.isAssignableFrom(it.returnType)
            }
            if (methods.isEmpty()) {
                WeLogger.w(TAG, "没找到 $TARGET_CLASS 的无参 View 构建方法，护栏未安装")
                return@runCatching
            }
            methods.forEach { method -> hookOne(method) }
            WeLogger.i(
                TAG,
                "媒体预览护栏已安装（${methods.size} 个候选：" +
                    methods.joinToString { "${it.name}:${it.returnType.simpleName}" } + "）",
            )
        }.onFailure {
            WeLogger.w(TAG, "安装媒体预览护栏失败：${it.message}")
        }
    }

    private fun hookOne(method: Method) {
        runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        // 先正常执行一次，成功就原样交回（功能零变化）
                        param.result = XposedBridge.invokeOriginalMethod(
                            param.method,
                            param.thisObject,
                            param.args,
                        )
                    } catch (t: Throwable) {
                        val host = param.thisObject as? View
                        if (host == null) {
                            // 拿不到 Context 就无法降级，只能记录后放行（让原方法自行抛）
                            WeLogger.e(TAG, "操作栏构建失败且无法降级（宿主不是 View）", t)
                            return
                        }
                        WeLogger.w(TAG, "媒体预览操作栏构建失败，已降级为空 View（预览页照常打开）", t)
                        param.result = View(host.context)
                    }
                }
            })
        }.onFailure {
            WeLogger.w(TAG, "hook ${method.name} 失败：${it.message}")
        }
    }
}
