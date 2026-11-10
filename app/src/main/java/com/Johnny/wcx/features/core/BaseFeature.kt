@file:Suppress("NOTHING_TO_INLINE")

package com.Johnny.wcx.features.core

import androidx.compose.runtime.Composable
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.ujhhgtg.reflekt.reflected.BaseReflectedMethod
import dev.ujhhgtg.reflekt.reflected.ReflectedConstructor
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.dsl.BaseDexDelegate
import com.Johnny.wcx.dexkit.dsl.DexConstructorDelegate
import com.Johnny.wcx.dexkit.dsl.DexMethodDelegate
import com.Johnny.wcx.utils.HookAction
import com.Johnny.wcx.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Executable
import kotlin.reflect.KClass

abstract class BaseFeature {

    var name: String = ""
    var categories: List<String> = emptyList()

    val displayName: String
        get() = "${categories.joinToString(",")}/$name"

    var description: String = ""

    /**
     * 当前微信版本是否支持本功能；返回非空字符串表示不支持（开关会拒绝开启并提示该文案）。
     *
     * 用于「微信把某个模块整体删掉」这类情况（例如 8.0.77 起多人通话 MultiTalk 被移除），
     * 避免用户开了开关却毫无反应、误以为模块坏了。
     */
    open val unavailableReason: String?
        get() = null

    /**
     * 当前微信版本是否 ≥ major.minor.patch。
     *
     * 「微信从某版起整体移除了本功能依赖的实现」这类判断必须按版本号来：这类功能在 UI 上
     * 直接把条目隐藏（不显示「失效/需适配」），因此不能依赖 DexKit 委托是否解析完成——
     * 未解析时 isPlaceholder 同样为 true，会让功能列表项在解析结束前闪一下消失。
     */
    protected fun hostVersionAtLeast(major: Int, minor: Int, patch: Int): Boolean {
        val nums = runCatching {
            com.Johnny.wcx.utils.HostInfo.versionName
                .split('.')
                .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        }.getOrDefault(emptyList())
        if (nums.isEmpty()) return false
        val actual = intArrayOf(nums.getOrElse(0) { 0 }, nums.getOrElse(1) { 0 }, nums.getOrElse(2) { 0 })
        val expected = intArrayOf(major, minor, patch)
        for (i in 0..2) {
            if (actual[i] != expected[i]) return actual[i] > expected[i]
        }
        return true
    }

    /**
     * 运行时回执：功能「开着但当前状态不健康」时返回具体原因；null = 没有已知问题。
     *
     * 自检页会把「开关已开 && runtimeIssue() != null」直接列为「开着但没生效」，并可一键重启。
     * 只有功能自己确认「该生效却没生效」时才返回（例如注入型功能找不到注入容器、
     * 采集不到内容）；没进入过相关界面、无法判断时请返回 null，避免误报。
     */
    open fun runtimeIssue(): String? = null

    /** 本进程内最近一次 Hook 命中的时间（elapsedRealtime；0 = 从未命中）。 */
    @Volatile
    var lastHookHitAt: Long = 0L
        private set

    /** Hook 命中打点：由框架在每次 hook 回调里自动调用，功能无需自己埋点。 */
    internal fun noteHookHit() {
        lastHookHitAt = android.os.SystemClock.elapsedRealtime()
    }

    /** WeKit 兼容: 功能稳定标识 (FeaturesLoader 注入, 本模块保留以兼容连带代码)。 */
    var technicalId: String = ""

    open fun startup() {
        error("You shouldn't inherit BaseFeature")
    }

    /** Whether this feature's hooks are currently installed (runtime truth). */
    var isActive: Boolean = false
        private set

    fun enable() {
        if (isActive) return

        runCatching {
            isActive = true
            onEnable()
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to enable feature $displayName", e)
            // ensure transaction is fully discarded
            unhookAll()
            isActive = false
        }
    }

    fun disable() {
        if (!isActive) return

        runCatching {
            isActive = false
            unhookAll()
            onDisable()
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to disable feature $displayName", e)
            isActive = true
        }
    }

    open fun onEnable() {}

    open fun onDisable() {}

    @Composable
    open fun Ui() {
    }

    private val _dexDelegates = mutableListOf<BaseDexDelegate>()
    val dexDelegates: List<BaseDexDelegate> get() = _dexDelegates
    internal fun registerDexDelegate(d: BaseDexDelegate) {
        _dexDelegates += d
    }

    internal fun resolveInlineDex(dexKit: DexKitBridge) {
        // 每个委托独立容错：某个锚点命中多个候选、或解析时抛异常，只让这一个委托降级，
        // 绝不能把整个 API 拖下水。典型事故：8.0.80 的 img feature service 有一个方法
        // 命中 2 个候选，IllegalStateException 直接把 WeMessageApi 的解析整体打断，
        // 连带语音/图片/复读等所有发送能力一起不可用。
        dexDelegates.forEach { delegate ->
            runCatching { delegate.findInline(dexKit) }
                .onFailure { WeLogger.e(TAG, "dex delegate resolve failed (${delegate.key})", it) }
        }
    }

    internal val unhooks = mutableListOf<XC_MethodHook.Unhook>()
    internal fun registerUnhook(u: XC_MethodHook.Unhook) {
        unhooks += u
    }

    internal fun unhookAll() {
        unhooks.forEach { it.unhook() }
        unhooks.clear()
    }

    // --- hookBefore ---

    internal fun Executable.hookBefore(
        priority: Int = 50,
        action: HookAction
    ) = registerUnhook(
        XposedBridge.hookMethod(
            this,
            object :
                XC_MethodHook(priority) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    executeHookAction(param, action)
                }
            }
        ))

    @JvmName("hookBefore2")
    internal fun BaseReflectedMethod.hookBefore(
        priority: Int = 50,
        action: HookAction
    ) = self.hookBefore(priority, action)

    @JvmName("hookBefore3")
    internal fun ReflectedConstructor<*>.hookBefore(
        priority: Int = 50,
        action: HookAction
    ) = this.self.hookBefore(priority, action)

    internal fun Class<*>.hookBeforeOnCreate(
        action: HookAction
    ) = this.reflekt().firstMethod { name = "onCreate" }.hookBefore(50, action)

    internal fun Class<*>.hookAfterOnCreate(
        action: HookAction
    ) = this.reflekt().firstMethod { name = "onCreate" }.hookAfter(50, action)

    internal fun KClass<*>.hookBeforeOnCreate(
        action: HookAction
    ) = this.reflekt().firstMethod { name = "onCreate" }.hookBefore(50, action)

    internal fun KClass<*>.hookAfterOnCreate(
        action: HookAction
    ) = this.reflekt().firstMethod { name = "onCreate" }.hookAfter(50, action)

    // --- end hookBefore ---

    // --- hookAfter ---

    internal fun Executable.hookAfter(
        priority: Int = 50,
        action: HookAction
    ) = registerUnhook(
        XposedBridge.hookMethod(
            this,
            object :
                XC_MethodHook(priority) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    executeHookAction(param, action)
                }
            }
        ))

    @JvmName("hookAfter2")
    internal fun BaseReflectedMethod.hookAfter(
        priority: Int = 50,
        action: HookAction
    ) = self.hookAfter(priority, action)

    @JvmName("hookAfter3")
    internal fun ReflectedConstructor<*>.hookAfter(
        priority: Int = 50,
        action: HookAction
    ) = this.self.hookAfter(priority, action)

    // --- end hookAfter ---

    // --- dex delegate ---

    internal fun DexMethodDelegate.hookBefore(
        priority: Int = 50,
        action: HookAction
    ) = method.hookBefore(priority, action)

    internal fun DexMethodDelegate.hookAfter(
        priority: Int = 50,
        action: HookAction
    ) = method.hookAfter(priority, action)

    internal fun DexConstructorDelegate.hookBefore(
        priority: Int = 50,
        action: HookAction
    ) = constructor.hookBefore(priority, action)

    internal fun DexConstructorDelegate.hookAfter(
        priority: Int = 50,
        action: HookAction
    ) = constructor.hookAfter(priority, action)

    // --- end dex delegate ---

    internal fun executeHookAction(param: XC_MethodHook.MethodHookParam, action: HookAction) {
        noteHookHit()
        runCatching {
            action(param)
        }.onFailure { e -> WeLogger.e("executeHookAction", "failed to execute hook of $name", e) }
    }

    companion object {
        private const val TAG = "BaseFeature"
    }
}
