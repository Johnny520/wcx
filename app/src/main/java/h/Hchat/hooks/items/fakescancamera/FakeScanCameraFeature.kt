package h.Hchat.hooks.items.fakescancamera

import android.app.Activity
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method

class FakeScanCameraFeature : BaseFeature() {
    private var hooker: FakeScanCameraHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "模拟相机扫码"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(FakeScanCameraSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        hooker = FakeScanCameraHooker(context)
        if (hooker?.install(allowDexSearch = false) != true) {
            scheduleInstall()
        }
        subscribe(Events.DexReady::class.java) {
            scheduleInstall()
        }
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name(), priority = -100) {
            hooker?.install(allowDexSearch = true) == true
        }
    }

    companion object {
        const val ID = "fake_scan_camera"
    }
}

private class FakeScanCameraHooker(
    private val context: FeatureContext
) {
    private val prefs = HchatStorage.preferences(context.hostContext(), FakeScanCameraSettings.PREFS_NAME)
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), "Hchat_fake_scan_camera_method_cache")

    @Volatile
    private var qbarStringHandlerInstalled = false

    @Synchronized
    fun install(allowDexSearch: Boolean): Boolean {
        if (qbarStringHandlerInstalled) return true
        var installedAny = false
        locateQBarStringHandlerMethods(allowDexSearch).forEach { method ->
            try {
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return
                        patchQBarStringArgs(param.args)
                    }
                })
                qbarStringHandlerInstalled = true
                installedAny = true
            } catch (e: Throwable) {
                HLog.e("$TAG 安装 QBarStringHandler Hook 失败: ${e.message}", e)
            }
        }
        return installedAny || qbarStringHandlerInstalled
    }

    private fun patchQBarStringArgs(args: Array<Any?>?) {
        if (args == null || args.size < 5) return
        val (sourceIndex, sceneIndex) = when (args.size) {
            16 -> 3 to 4
            15 -> 2 to 3
            else -> return
        }
        val source = intArg(args[sourceIndex]) ?: return
        val scene = intArg(args[sceneIndex]) ?: return
        if (isAlbumOrLongPressScene(source, scene)) {
            args[sourceIndex] = 0
            args[sceneIndex] = 4
        }
    }

    private fun isAlbumOrLongPressScene(source: Int, scene: Int): Boolean {
        return when {
            source == 1 && scene == 34 -> true
            source == 4 && scene == 37 -> true
            else -> false
        }
    }

    private fun intArg(value: Any?): Int? {
        return when (value) {
            is Int -> value
            is Number -> value.toInt()
            else -> null
        }
    }

    private fun locateQBarStringHandlerMethods(allowDexSearch: Boolean): List<Method> {
        val methodCacheKey = methodCacheKey()
        DexMethodCache.loadList(methodPrefs, methodCacheKey, context.hostClassLoader(), CACHE_QBAR_STRING_HANDLER_METHODS)
            .filter { isQBarStringHandlerMethod(it) }
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        if (!allowDexSearch) return emptyList()
        val methods = findMethodsByExactStrings("MicroMsg.QBarStringHandler", "key_offline_scan_show_tips")
            .filter { isQBarStringHandlerMethod(it) }
            .distinctBy { it.toGenericString() }
        if (methods.isNotEmpty()) {
            DexMethodCache.saveList(methodPrefs, methodCacheKey, CACHE_QBAR_STRING_HANDLER_METHODS, methods)
        } else {
            DexMethodCache.clear(methodPrefs, methodCacheKey, CACHE_QBAR_STRING_HANDLER_METHODS)
        }
        return methods
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
            .takeIf { it.isNotBlank() }
            ?.let { "$it|$CACHE_SCHEMA" }
            .orEmpty()
    }

    private fun findMethodsByExactStrings(vararg strings: String): List<Method> {
        return runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingEqStrings(*strings)
                        }
                    )
                }
            ).mapNotNull { it.getMethodInstance(context.hostClassLoader()) }
        }.getOrElse {
            HLog.e("$TAG 精确定位 QBarStringHandler 失败(${strings.joinToString()}): ${it.message}", it)
            emptyList()
        }
    }

    private fun isQBarStringHandlerMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == java.lang.Void.TYPE &&
            (types.size == 15 || types.size == 16) &&
            Activity::class.java.isAssignableFrom(types[0]) &&
            types[1] == String::class.java
    }

    private fun isEnabled(): Boolean {
        return prefs.getBoolean(FakeScanCameraSettings.KEY_ENABLE, FakeScanCameraSettings.DEFAULT_ENABLE)
    }

    companion object {
        private const val TAG = "[Hchat:FakeScanCamera]"
        private const val CACHE_SCHEMA = "fake_scan_camera_v5_eq_qbar_only"
        private const val CACHE_QBAR_STRING_HANDLER_METHODS = "qbar_string_handler_methods"
    }
}
