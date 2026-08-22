package com.Johnny.wcx.features.items.scripting_java

import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.HookParam
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookAfterDirectly
import com.Johnny.wcx.utils.hookBeforeDirectly
import com.Johnny.wcx.utils.HookHandle
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.util.function.Consumer
import java.util.function.Function

@Feature(name = "脚本 Hook 服务", categories = ["API"], description = "提供 BeanShell 脚本可用的 Xposed Hook 能力")
object JavaHookApi : ApiFeature() {

    private const val TAG = "JavaHookApi"

    private val hooks = mutableListOf<HookHandle>()

    fun hookBefore(member: Member, consumer: Consumer<HookParam>): HookHandle {
        val unhook = (member as Executable).hookBeforeDirectly {
            runCatching {
                result = consumer.accept(this)
            }.onFailure { WeLogger.e(TAG, "failed to execute script hookBefore action") }
        }
        val handle = unhook
        hooks.add(handle)
        return handle
    }

    fun hookAfter(member: Member, consumer: Consumer<HookParam>): HookHandle {
        val unhook = (member as Executable).hookAfterDirectly {
            runCatching {
                consumer.accept(this)
            }.onFailure { WeLogger.e(TAG, "failed to execute script hookAfter action") }
        }
        val handle = unhook
        hooks.add(handle)
        return handle
    }

    fun hookReplace(member: Member, function: Function<HookParam, Any?>): HookHandle {
        val unhook = (member as Executable).hookBeforeDirectly {
            runCatching {
                result = function.apply(this)
            }.onFailure { WeLogger.e(TAG, "failed to execute script hookReplace action") }
        }
        val handle = unhook
        hooks.add(handle)
        return handle
    }

    fun unhook(handle: HookHandle) {
        if (hooks.remove(handle)) {
            handle.unhook()
        }
    }

    fun unhookEverything() {
        val iterator = hooks.iterator()
        while (iterator.hasNext()) {
            val handle = iterator.next()
            handle.unhook()
            iterator.remove()
        }
    }
}
