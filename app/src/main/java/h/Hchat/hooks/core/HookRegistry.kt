package h.Hchat.hooks.core

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Member
import java.util.concurrent.CancellationException

/**
 * 统一保存 Xposed hook 句柄，便于防御性卸载。
 */
class HookRegistry private constructor() {
    private val lock = Any()
    private val unhooks = ArrayList<XC_MethodHook.Unhook>()
    private val installationLease = ThreadLocal<Long>()
    @Volatile private var generation = 0L

    fun installationGeneration(): Long = installationLease.get() ?: generation

    fun isInstallationGenerationActive(generation: Long): Boolean = this.generation == generation

    fun <T> withInstallationGeneration(generation: Long, action: () -> T): T {
        val previous = installationLease.get()
        if (!isInstallationGenerationActive(generation) || previous != null && previous != generation) {
            throw CancellationException("安装代次已失效")
        }
        installationLease.set(generation)
        try { return action() }
        finally {
            if (previous == null) installationLease.remove() else installationLease.set(previous)
        }
    }

    fun hook(method: Member, callback: XC_MethodHook): XC_MethodHook.Unhook = synchronized(lock) {
        if (!isInstallationGenerationActive(installationGeneration())) {
            throw CancellationException("安装代次已失效")
        }
        val unhook = XposedBridge.hookMethod(method, callback)
        if (unhook != null) {
            unhooks.add(unhook)
        }
        unhook
    }

    fun add(unhook: XC_MethodHook.Unhook?) {
        if (unhook == null) return
        synchronized(lock) {
            if (isInstallationGenerationActive(installationGeneration())) {
                unhooks.add(unhook)
                return
            }
        }
        unhook.unhook()
        throw CancellationException("安装代次已失效")
    }

    fun size(): Int = synchronized(lock) { unhooks.size }

    fun unhook(unhook: XC_MethodHook.Unhook?) {
        if (unhook == null) return
        unhook.unhook()
        // Xposed 解除回调后，还必须释放模块注册表持有的句柄及脚本解释器。
        synchronized(lock) { unhooks.remove(unhook) }
    }

    fun unhookAll() {
        val previous = synchronized(lock) {
            generation++
            unhooks.toList().also { unhooks.clear() }
        }
        for (unhook in previous) {
            try {
                unhook.unhook()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private val INSTANCE = HookRegistry()

        @JvmStatic
        fun get(): HookRegistry = INSTANCE
    }
}
