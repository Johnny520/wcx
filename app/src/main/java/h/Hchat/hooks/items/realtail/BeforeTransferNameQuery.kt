package h.Hchat.hooks.items.realtail

import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class BeforeTransferNameQuery(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    fun interface Callback {
        fun onResult(maskedName: String)
    }

    private var sceneClass: Class<*>? = null
    private var sceneCtor: Constructor<*>? = null
    private var callbackMethod: Method? = null
    private val pending = Collections.synchronizedMap(WeakHashMap<Any, Callback>())
    private val featurePrefs = context.configStore().getFeaturePrefs("real_name_tail_runtime")
    @Volatile private var initialized = false

    fun ensureReady(): Boolean {
        if (initialized && sceneCtor != null && callbackMethod != null) return true
        return runCatching {
            locateScene()
            setupCallbackHook()
            initialized = sceneCtor != null && callbackMethod != null
            initialized
        }.getOrElse {
            logger("实名查询初始化失败", it)
            false
        }
    }

    fun query(wxid: String, roomId: String, callback: Callback): Boolean {
        if (!RealNameTailStore.isSupportedQueryId(wxid)) return false
        if (!ensureReady()) return false
        val ctor = sceneCtor ?: return false
        return runCatching {
            val scene = KavaReflector.newInstance(ctor, wxid, roomId) ?: return@runCatching false
            pending[scene] = callback
            val sent = WeChatApis.network()?.sendRequest(scene) == true
            if (!sent) pending.remove(scene)
            sent
        }.getOrElse {
            logger("实名查询请求失败", it)
            false
        }
    }

    private fun locateScene() {
        sceneClass = null
        sceneCtor = null
        callbackMethod = null

        loadCachedClass("beforetransfer_class")?.let {
            if (selectSceneClass(it)) return@let
        }

        if (sceneClass == null) {
            val matches = runCatching {
                context.dexKitBridge().findClass(
                    org.luckypray.dexkit.query.FindClass().apply {
                        matcher(org.luckypray.dexkit.query.matchers.ClassMatcher().apply {
                            usingStrings(listOf("/cgi-bin/mmpay-bin/beforetransfer"))
                        })
                    }
                )
            }.getOrDefault(emptyList())
            for (data in matches) {
                val clazz = KavaReflector.loadClass(data.name, context.hostClassLoader())
                if (selectSceneClass(clazz)) break
            }
        }

        if (sceneClass == null) {
            val direct = KavaReflector.loadClass("com.tencent.mm.plugin.remittance.model.i", context.hostClassLoader())
            selectSceneClass(direct)
        }

        sceneClass?.let {
            saveCachedClass("beforetransfer_class", it)
            locateCallbackMethod(it)
        }
    }

    private fun selectSceneClass(clazz: Class<*>?): Boolean {
        if (clazz == null) return false
        for (ctor in KavaReflector.declaredConstructors(clazz)) {
            val types = ctor.parameterTypes
            if (types.size == 2 && types[0] == String::class.java && types[1] == String::class.java) {
                sceneClass = clazz
                sceneCtor = KavaReflector.accessible(ctor)
                return true
            }
        }
        return false
    }

    private fun locateCallbackMethod(clazz: Class<*>) {
        for (method in KavaReflector.declaredMethods(clazz)) {
            val types = method.parameterTypes
            if (types.size >= 4
                && types[0] == Integer.TYPE
                && types[1] == Integer.TYPE
                && types[2] == Integer.TYPE
                && types[3] == String::class.java
            ) {
                callbackMethod = KavaReflector.accessible(method)
                return
            }
        }
    }

    private fun setupCallbackHook() {
        val method = callbackMethod ?: return
        HookRegistry.get().hook(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val scene = param.thisObject ?: return
                val callback = pending.remove(scene) ?: return
                val maskedName = extractMaskedName(scene)
                callback.onResult(maskedName)
            }
        })
    }

    private fun extractMaskedName(scene: Any): String {
        val response = KavaReflector.readField(scene, "r") ?: return ""
        val fieldFour = KavaReflector.readField(response, "f") as? String
        return RealNameTailStore.normalizeMaskedName(fieldFour)
    }

    private fun loadCachedClass(key: String): Class<*>? {
        val className = featurePrefs.getString(cacheKey(key), "") ?: ""
        return KavaReflector.loadClass(className, context.hostClassLoader())
    }

    private fun saveCachedClass(key: String, clazz: Class<*>) {
        featurePrefs.edit().putString(cacheKey(key), clazz.name).apply()
    }

    private fun cacheKey(name: String): String {
        val version = WeChatApis.version()?.versionName() ?: "unknown"
        return "feature_${version}_$name"
    }
}
