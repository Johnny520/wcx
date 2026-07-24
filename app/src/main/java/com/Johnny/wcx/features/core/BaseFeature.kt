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
        dexDelegates.forEach { it.findInline(dexKit) }
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
        runCatching {
            action(param)
        }.onFailure { e -> WeLogger.e("executeHookAction", "failed to execute hook of $name", e) }
    }

    companion object {
        private const val TAG = "BaseFeature"
    }
}
