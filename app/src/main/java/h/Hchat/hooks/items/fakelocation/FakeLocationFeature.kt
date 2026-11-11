package h.Hchat.hooks.items.fakelocation

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

class FakeLocationFeature : BaseFeature() {
    private var hooker: FakeLocationHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "虚拟定位"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(FakeLocationSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        hooker = FakeLocationHooker(context, ::logFeatureError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        hooker = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name(), stage = DexInstallScheduler.Stage.BRIDGE) {
            hooker?.install() == true
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "fake_location"
        internal const val CACHE_PREFS_NAME = "Hchat_fake_location_method_cache"
        internal const val CACHE_LOCATION_CALLBACKS = "location_callbacks_v1"

        @JvmStatic
        fun isEnabled(context: Context?): Boolean {
            if (context == null) return false
            return HchatStorage.preferences(context, FakeLocationSettings.PREFS_NAME)
                .getBoolean(FakeLocationSettings.KEY_ENABLE, FakeLocationSettings.DEFAULT_ENABLE)
        }

        @JvmStatic
        fun installAppBrandProcessHook(context: Context, classLoader: ClassLoader): Boolean {
            val prefs = DexMethodCache.prefs(context, CACHE_PREFS_NAME)
            val runtimeKey = DexMethodCache.runtimeKey(context, classLoader)
            val callbacks = DexMethodCache.loadListCrossProcess(
                prefs,
                runtimeKey,
                classLoader,
                CACHE_LOCATION_CALLBACKS
            ).filter(::isLocationCallback)
            if (callbacks.isEmpty()) return false
            return FakeLocationProcessHooker.install(context, callbacks) { message, throwable ->
                HLog.e("[Hchat:FakeLocation] $message", throwable)
            }
        }
    }
}

private class FakeLocationHooker(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val cache = DexMethodCache.prefs(context.hostContext(), FakeLocationFeature.CACHE_PREFS_NAME)

    @Volatile private var installed = false

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        val callbacks = locateLocationCallbacks()
        if (callbacks.isEmpty()) return false
        installed = FakeLocationProcessHooker.install(context.hostContext(), callbacks, logger)
        return installed
    }

    private fun locateLocationCallbacks(): List<Method> {
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        DexMethodCache.loadList(
            cache,
            runtimeKey,
            context.hostClassLoader(),
            FakeLocationFeature.CACHE_LOCATION_CALLBACKS
        )
            .filter(::isLocationCallback)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        val methods = LinkedHashSet<Method>()
        CALLBACK_ANCHORS.forEach { anchors ->
            val candidates = runCatching {
                context.dexKitBridge().findMethod(
                    FindMethod().apply {
                        matcher(MethodMatcher().apply { usingEqStrings(*anchors) })
                    }
                )
            }.getOrElse {
                logger("定位微信定位回调失败: ${anchors.joinToString()}", it)
                emptyList()
            }
            candidates.mapNotNullTo(methods) { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                    ?.takeIf(::isLocationCallback)
            }
        }

        val result = methods.distinctBy { it.toGenericString() }
        if (result.isEmpty()) {
            DexMethodCache.clear(cache, runtimeKey, FakeLocationFeature.CACHE_LOCATION_CALLBACKS)
            logger("未找到微信定位回调方法", null)
        } else {
            DexMethodCache.saveList(
                cache,
                runtimeKey,
                FakeLocationFeature.CACHE_LOCATION_CALLBACKS,
                result
            )
        }
        return result
    }

    companion object {
        private val CALLBACK_ANCHORS = listOf(
            arrayOf("MicroMsg.SLocationListener"),
            arrayOf("MicroMsg.SLocationListenerWgs84"),
            arrayOf("MicroMsg.DefaultTencentLocationManager", "[mlocationListener]error:%d, reason:%s")
        )
    }
}

private object FakeLocationProcessHooker {
    private val hookedCallbacks = ConcurrentHashMap.newKeySet<Method>()
    private val hookedGetters = ConcurrentHashMap.newKeySet<Method>()
    private val rejectedLocationClasses = ConcurrentHashMap.newKeySet<Class<*>>()

    fun install(
        context: Context,
        callbacks: List<Method>,
        logger: (String, Throwable?) -> Unit
    ): Boolean {
        callbacks.forEach { callback ->
            if (!hookedCallbacks.add(callback)) return@forEach
            runCatching {
                HookRegistry.get().hook(callback, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            hookLocationGetters(context, param.args?.firstOrNull(), logger)
                        }.onFailure {
                            logger("虚拟定位运行时 Hook 失败", it)
                        }
                    }
                })
            }.onFailure {
                hookedCallbacks.remove(callback)
                logger("虚拟定位回调 Hook 安装失败: ${callback.toGenericString()}", it)
            }
        }
        return callbacks.all(hookedCallbacks::contains)
    }

    private fun hookLocationGetters(
        context: Context,
        location: Any?,
        logger: (String, Throwable?) -> Unit
    ) {
        if (location == null) return
        val clazz = location.javaClass
        val latitudeMethod = KavaReflector.findMethodRecursive(clazz, "getLatitude")
        val longitudeMethod = KavaReflector.findMethodRecursive(clazz, "getLongitude")
        if (!isConcreteDoubleGetter(latitudeMethod) || !isConcreteDoubleGetter(longitudeMethod)) {
            if (rejectedLocationClasses.add(clazz)) {
                logger("TencentLocation 经纬度 getter 不匹配: ${clazz.name}", null)
            }
            return
        }
        hookGetter(context, latitudeMethod!!) { FakeLocationSettings.latitude(context) }
        hookGetter(context, longitudeMethod!!) { FakeLocationSettings.longitude(context) }
    }

    private fun hookGetter(context: Context, method: Method, value: () -> Double) {
        if (!hookedGetters.add(method)) return
        runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!FakeLocationFeature.isEnabled(context)) return
                    param.result = value()
                }
            })
        }.onFailure {
            hookedGetters.remove(method)
            throw it
        }
    }

    private fun isConcreteDoubleGetter(method: Method?): Boolean {
        if (method == null) return false
        return !Modifier.isAbstract(method.modifiers) &&
            !Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.isEmpty() &&
            (method.returnType == java.lang.Double.TYPE || method.returnType == java.lang.Double::class.java)
    }

}

private fun isLocationCallback(method: Method): Boolean {
    val types = method.parameterTypes
    return !Modifier.isAbstract(method.modifiers) &&
        !Modifier.isStatic(method.modifiers) &&
        method.name == "onLocationChanged" &&
        method.returnType == Void.TYPE &&
        types.size == 3 &&
        types[0].name.contains("TencentLocation") &&
        types[1] == Integer.TYPE &&
        types[2] == String::class.java
}
