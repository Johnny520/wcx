package h.Hchat.hooks.api.sns

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList

class WeChatSnsPostObserver(
    private val context: Context,
    private val classLoader: ClassLoader,
    private val dexKitBridge: DexKitBridge?,
    private val logger: (String) -> Unit
) {
    fun interface Listener {
        fun onPostStored(snsInfo: Any)
    }

    inner class Subscription internal constructor(private val listener: Listener) {
        @Volatile private var active = true

        fun unsubscribe() {
            if (!active) return
            active = false
            listeners.remove(listener)
        }
    }

    private val prefs = DexMethodCache.prefs(context, PREFS_NAME)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val suppressDispatch = ThreadLocal<Boolean>()
    @Volatile private var hookedMethod: Method? = null

    fun subscribe(listener: Listener): Subscription {
        listeners.addIfAbsent(listener)
        return Subscription(listener)
    }

    internal fun <T> withoutDispatch(block: () -> T): T {
        val previous = suppressDispatch.get()
        suppressDispatch.set(true)
        return try {
            block()
        } finally {
            if (previous == null) suppressDispatch.remove() else suppressDispatch.set(previous)
        }
    }

    @Synchronized
    fun install(): Boolean {
        hookedMethod?.takeIf(::isPostStoreMethod)?.let { return true }
        val runtimeKey = DexMethodCache.runtimeKey(context, classLoader)
        val method = DexMethodCache.load(prefs, runtimeKey, classLoader, CACHE_STORE)
            ?.takeIf(::isPostStoreMethod)
            ?: locatePostStoreMethod()?.also {
                DexMethodCache.save(prefs, runtimeKey, CACHE_STORE, it)
            }
            ?: return false
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (suppressDispatch.get() == true) return
                    if (param.result != true) return
                    val snsInfo = param.args?.firstOrNull {
                        it?.javaClass?.name == SnsInteractionLocator.SNS_INFO_CLASS
                    } ?: return
                    if (KavaReflector.invokeMethod(snsInfo, "isAd") == true) return
                    if (SnsLocalPostIdentity.isLocalOnly(context, KavaReflector.readField(snsInfo, "field_snsId"))) return
                    listeners.forEach { listener ->
                        runCatching { listener.onPostStored(snsInfo) }
                            .onFailure { logger("朋友圈入库观察回调失败: ${it.message}") }
                    }
                }
            })
            hookedMethod = method
            true
        }.onFailure {
            logger("朋友圈入库观察Hook失败: ${method.toGenericString()} ${it.message}")
        }.getOrDefault(false)
    }

    private fun locatePostStoreMethod(): Method? {
        val bridge = dexKitBridge ?: return null
        val methods = runCatching {
            bridge.findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        usingStrings(
                            listOf(
                                "com.tencent.mm.plugin.sns.storage.SnsInfoStorage",
                                "replaceUserBySnsId"
                            )
                        )
                    })
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(classLoader) }.getOrNull()
            }.filter(::isPostStoreMethod)
                .distinctBy { it.toGenericString() }
        }.onFailure {
            logger("定位朋友圈入库方法失败: ${it.message}")
        }.getOrDefault(emptyList())
        if (methods.size != 1) {
            logger("朋友圈入库方法数量异常: ${methods.size}")
            return null
        }
        return methods.single()
    }

    private fun isPostStoreMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return !Modifier.isAbstract(method.modifiers) &&
            method.returnType == java.lang.Boolean.TYPE &&
            types.size == 2 &&
            types[0] == java.lang.Long.TYPE &&
            types[1].name == SnsInteractionLocator.SNS_INFO_CLASS &&
            method.declaringClass.name.startsWith("com.tencent.mm.plugin.sns.storage.")
    }

    companion object {
        private const val PREFS_NAME = "Hchat_sns_post_observer_cache"
        private const val CACHE_STORE = "sns_info_replace_by_id_v2"
    }
}
