package h.Hchat.hooks.items.zombiecheck

import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.media.WeChatInternalServices
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.KavaReflector
import org.json.JSONObject
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Constructor
import java.lang.reflect.Method

class ZombieCheckFeature : BaseFeature() {
    private var runtime: ZombieCheckRuntime? = null
    private var hooker: ZombieCheckHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "僵尸粉检测"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ZombieCheckSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        val currentRuntime = ZombieCheckRuntime(context.hostContext(), ::logRuntimeError)
        val currentHooker = ZombieCheckHooker(context, currentRuntime, ::logRuntimeError)
        currentRuntime.bind(currentHooker)
        runtime = currentRuntime
        hooker = currentHooker
        ZombieCheckController.attach(currentRuntime)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.close()
        ZombieCheckController.detach(runtime)
        runtime = null
        hooker = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            hooker?.install() == true
        }
    }

    private fun logRuntimeError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "zombie_check"
    }
}

internal class ZombieCheckHooker(
    private val context: FeatureContext,
    private val runtime: ZombieCheckRuntime,
    private val logger: (String, Throwable?) -> Unit
) {
    private val cache = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
    @Volatile private var probeConstructor: Constructor<*>? = null
    @Volatile private var deleteMethod: Method? = null
    @Volatile private var deleteService: Any? = null
    @Volatile private var installed = false

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        return runCatching {
            val constructor = locateProbeConstructor() ?: return false
            val callback = locateCallbackMethod(constructor) ?: return false
            locateDeleteMethod()
            WeChatApis.network()?.installNetworkHook(context.dexFinder())
            HookRegistry.get().hook(callback, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args ?: return
                    if (args.size != 3) return
                    runtime.onProbeCallback(
                        scene = param.thisObject,
                        errCode = (args[0] as? Number)?.toInt() ?: return,
                        errMessage = args[1]?.toString().orEmpty(),
                        response = args[2] as? JSONObject
                    )
                }
            })
            probeConstructor = constructor
            installed = true
            runtime.markReady()
            true
        }.getOrElse {
            logger("僵尸粉检测 Hook 安装失败", it)
            false
        }
    }

    fun createProbe(wxid: String): Any? {
        val constructor = probeConstructor ?: return null
        if (!isProbeConstructor(constructor)) return null
        val types = constructor.parameterTypes
        val args = Array<Any?>(types.size) { index -> defaultValue(types[index]) }
        args[0] = 1.0
        args[1] = "1"
        args[2] = wxid
        args[4] = 31
        args[5] = 2
        args[13] = 11
        args[24] = 0
        args[26] = 0
        val scene = KavaReflector.newInstance(constructor, *args) ?: return null
        KavaReflector.invokeMethod(scene, "setProcessName", "RemittanceProcess")
        return scene
    }

    fun sendProbe(scene: Any): Boolean {
        val network = WeChatApis.network() ?: return false
        network.installNetworkHook(context.dexFinder())
        return network.sendRequest(scene)
    }

    fun deleteFriend(wxid: String, clearRecord: Boolean): Boolean {
        val method = deleteMethod ?: locateDeleteMethod() ?: return false
        return runCatching {
            val service = resolveDeleteService(method) ?: run {
                logger("删除好友服务实例获取失败: ${method.toGenericString()}", null)
                return false
            }
            val args = if (method.parameterTypes.size == 2) {
                arrayOf<Any?>(wxid, shouldClearRecord(wxid, clearRecord))
            } else {
                arrayOf<Any?>(wxid)
            }
            KavaReflector.invokeOrThrow(method, service, *args)
            true
        }.getOrElse {
            logger("删除异常好友失败: $wxid", it)
            false
        }
    }

    private fun shouldClearRecord(wxid: String, requested: Boolean): Boolean {
        if (requested) return true
        val store = WeChatApis.messageStore() ?: return false
        if (!store.isAvailable) return false
        return runCatching { store.getLatestMessage(wxid) == null }.getOrDefault(false)
    }

    private fun resolveDeleteService(method: Method): Any? {
        val owner = method.declaringClass
        deleteService?.takeIf(owner::isInstance)?.let {
            return it
        }
        val finder = context.dexFinder()
        if (finder.serviceGetterMethod == null) {
            finder.resolveServiceManagerApi()
        }
        WeChatInternalServices.getService(finder, owner)?.let {
            deleteService = it
            return it
        }
        KavaReflector.staticInstance(owner)?.takeIf(owner::isInstance)?.let {
            deleteService = it
            return it
        }
        val constructor = KavaReflector.declaredConstructors(owner)
            .firstOrNull { it.parameterTypes.isEmpty() }
        val service = constructor?.let { KavaReflector.newInstance(it) }
        if (service != null) {
            deleteService = service
        }
        return service
    }

    private fun locateProbeConstructor(): Constructor<*>? {
        val runtimeKey = runtimeKey()
        DexMethodCache.loadConstructor(cache, runtimeKey, context.hostClassLoader(), CACHE_PROBE_CONSTRUCTOR)
            ?.takeIf(::isProbeConstructor)
            ?.let { return it }

        val candidates = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingEqStrings(
                                "Micromsg.NetSceneTenpayRemittanceGen",
                                "receiver_openid",
                                "placeorder_attach"
                            )
                        }
                    )
                }
            ).mapNotNull { data ->
                if (!data.isConstructor) return@mapNotNull null
                runCatching { data.getConstructorInstance(context.hostClassLoader()) }.getOrNull()
            }.filter(::isProbeConstructor)
                .distinctBy { it.toGenericString() }
        }.getOrElse {
            logger("定位转账核验构造器失败", it)
            emptyList()
        }
        val constructor = candidates.singleOrNull()
        if (constructor != null) {
            DexMethodCache.saveConstructor(cache, runtimeKey, CACHE_PROBE_CONSTRUCTOR, constructor)
        } else {
            DexMethodCache.clear(cache, runtimeKey, CACHE_PROBE_CONSTRUCTOR)
            if (candidates.size > 1) logger("转账核验构造器候选不唯一", null)
        }
        return constructor
    }

    private fun locateCallbackMethod(constructor: Constructor<*>): Method? {
        val runtimeKey = runtimeKey()
        DexMethodCache.load(cache, runtimeKey, context.hostClassLoader(), CACHE_CALLBACK)
            ?.takeIf { isCallbackMethod(it, constructor.declaringClass) }
            ?.let { return it }
        val method = KavaReflector.declaredMethods(constructor.declaringClass)
            .singleOrNull { isCallbackMethod(it, constructor.declaringClass) }
        if (method != null) {
            DexMethodCache.save(cache, runtimeKey, CACHE_CALLBACK, method)
        } else {
            DexMethodCache.clear(cache, runtimeKey, CACHE_CALLBACK)
        }
        return method
    }

    private fun locateDeleteMethod(): Method? {
        val runtimeKey = runtimeKey()
        val cached = DexMethodCache.load(cache, runtimeKey, context.hostClassLoader(), CACHE_DELETE_METHOD)
        cached
            ?.takeIf(::isDeleteMethod)
            ?.let {
                deleteMethod = it
                return it
            }
        val candidates = runCatching {
            val clearRecordFound = context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingEqStrings(
                                "MicroMsg.DeleteContactService",
                                "delete contact %s isClearRecord:%s"
                            )
                        }
                    )
                }
            )
            val clearRecordMethods = clearRecordFound.mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }
            val clearRecordCandidates = clearRecordMethods
                .filter(::isDeleteMethod)
                .distinctBy { it.toGenericString() }
            if (clearRecordCandidates.isNotEmpty()) {
                clearRecordCandidates
            } else {
                val legacyFound = context.dexKitBridge().findMethod(
                    FindMethod().apply {
                        matcher(
                            MethodMatcher().apply {
                                usingEqStrings("MicroMsg.DeleteContactService", "delete contact %s")
                            }
                        )
                    }
                )
                val legacyMethods = legacyFound.mapNotNull { data ->
                    runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                }
                legacyMethods.filter(::isDeleteMethod)
                    .distinctBy { it.toGenericString() }
            }
        }.getOrElse {
            logger("定位删除好友方法失败", it)
            emptyList()
        }
        val method = candidates.singleOrNull()
        if (method != null) {
            DexMethodCache.save(cache, runtimeKey, CACHE_DELETE_METHOD, method)
            deleteMethod = method
        } else {
            DexMethodCache.clear(cache, runtimeKey, CACHE_DELETE_METHOD)
            if (candidates.size > 1) logger("删除好友方法候选不唯一", null)
        }
        return method
    }

    private fun isProbeConstructor(constructor: Constructor<*>): Boolean {
        val types = constructor.parameterTypes
        if (types.size != 29 && types.size != 30) return false
        if (types[0] != java.lang.Double.TYPE) return false
        val stringIndexes = intArrayOf(1, 2, 3, 6, 8, 9, 10, 11, 12, 14, 15, 16, 17, 18, 20, 21, 22, 23, 25, 27, 28)
        if (stringIndexes.any { types[it] != String::class.java }) return false
        val intIndexes = intArrayOf(4, 5, 7, 13, 24, 26)
        if (intIndexes.any { types[it] != java.lang.Integer.TYPE }) return false
        if (types[19].name != "com.tencent.mm.autogen.events.F2fDynamicStartPayEvent") return false
        if (types.size == 30 && types[29] != java.lang.Boolean.TYPE) return false
        return constructor.declaringClass.name.startsWith("com.tencent.mm.plugin.remittance.model.")
    }

    private fun isCallbackMethod(method: Method, owner: Class<*>): Boolean {
        val types = method.parameterTypes
        return method.declaringClass == owner && method.name == "onGYNetEnd" &&
            method.returnType == java.lang.Void.TYPE && types.size == 3 &&
            types[0] == java.lang.Integer.TYPE && types[1] == String::class.java &&
            types[2] == JSONObject::class.java
    }

    private fun isDeleteMethod(method: Method): Boolean {
        if (KavaReflector.isStatic(method) || method.returnType != java.lang.Void.TYPE) return false
        val types = method.parameterTypes
        if (types.size == 2 && types[0] == String::class.java && types[1] == java.lang.Boolean.TYPE) {
            return true
        }
        if (types.size != 1 || types[0] != String::class.java) return false
        return KavaReflector.declaredConstructors(method.declaringClass).any { it.parameterTypes.isEmpty() }
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> false
        java.lang.Byte.TYPE, java.lang.Byte::class.java -> 0.toByte()
        java.lang.Short.TYPE, java.lang.Short::class.java -> 0.toShort()
        java.lang.Integer.TYPE, java.lang.Integer::class.java -> 0
        java.lang.Long.TYPE, java.lang.Long::class.java -> 0L
        java.lang.Float.TYPE, java.lang.Float::class.java -> 0f
        java.lang.Double.TYPE, java.lang.Double::class.java -> 0.0
        String::class.java -> ""
        else -> null
    }

    private fun runtimeKey(): String =
        DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())

    companion object {
        private const val CACHE_PREFS = "Hchat_zombie_check_method_cache"
        private const val CACHE_PROBE_CONSTRUCTOR = "probe_constructor_v1"
        private const val CACHE_CALLBACK = "probe_callback_v1"
        private const val CACHE_DELETE_METHOD = "delete_contact_method_v2"
    }
}
