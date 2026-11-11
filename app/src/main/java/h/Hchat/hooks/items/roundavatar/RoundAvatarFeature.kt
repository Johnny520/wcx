package h.Hchat.hooks.items.roundavatar

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Icon
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class RoundAvatarFeature : BaseFeature() {
    private var runtime: RoundAvatarRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "圆角头像"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(RoundAvatarSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = RoundAvatarRuntime(context).also { it.installNotificationHooks() }
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            runtime?.installWeChatHooks() == true
        }
    }

    companion object {
        const val ID = "round_avatar"
    }
}

private class RoundAvatarRuntime(
    private val context: FeatureContext
) {
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), "Hchat_round_avatar_method_cache")
    private val hookedMembers = ConcurrentHashMap.newKeySet<Member>()
    private val notificationHookedMembers = ConcurrentHashMap.newKeySet<Member>()

    @Volatile private var weChatInstalled = false

    fun installNotificationHooks() {
        hookNotificationBitmap()
        hookNotificationIcon()
        hookNotificationPost(
            KavaReflector.findMethod(
                NotificationManager::class.java,
                "notify",
                Integer.TYPE,
                Notification::class.java
            )
        )
        hookNotificationPost(
            KavaReflector.findMethod(
                NotificationManager::class.java,
                "notify",
                String::class.java,
                Integer.TYPE,
                Notification::class.java
            )
        )
    }

    @Synchronized
    fun installWeChatHooks(): Boolean {
        if (weChatInstalled) return true
        val members = locateAvatarMembers() ?: run {
            HLog.e("$TAG 定位微信全局头像入口失败")
            return false
        }
        val loadHooked = hookRadius(members.legacyLoad, radiusIndex = 2, maskIndex = -1)
        val constructorHooked = hookRadius(
            members.workerConstructor,
            radiusIndex = 2,
            maskIndex = lastIntParameterIndex(members.workerConstructor.parameterTypes)
        )
        val modifyHooked = members.workerModify?.let { method ->
            hookRadius(method, radiusIndex = 3, maskIndex = lastIntParameterIndex(method.parameterTypes))
        } ?: true
        weChatInstalled = loadHooked && constructorHooked && modifyHooked
        return weChatInstalled
    }

    private fun hookRadius(member: Member, radiusIndex: Int, maskIndex: Int): Boolean {
        if (hookedMembers.contains(member)) return true
        return runCatching {
            HookRegistry.get().hook(member, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!RoundAvatarSettings.enabled(context.hostContext())) return
                    if (radiusIndex !in param.args.indices) return
                    param.args[radiusIndex] = RoundAvatarSettings.radiusFactor(context.hostContext())
                    if (maskIndex in param.args.indices) {
                        val mask = (param.args[maskIndex] as? Number)?.toInt() ?: return
                        param.args[maskIndex] = mask and DEFAULT_RADIUS_MASK.inv()
                    }
                }
            })
            hookedMembers += member
            true
        }.getOrElse {
            HLog.e("$TAG 安装头像弧度 Hook 失败: $member, error=${it.message}", it)
            false
        }
    }

    private fun hookNotificationBitmap() {
        val method = KavaReflector.findMethod(
            Notification.Builder::class.java,
            "setLargeIcon",
            Bitmap::class.java
        ) ?: return
        if (!notificationHookedMembers.add(method)) return
        runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!RoundAvatarSettings.enabled(context.hostContext())) return
                    val source = param.args.firstOrNull() as? Bitmap ?: return
                    param.args[0] = RoundAvatarBitmapProcessor.round(
                        source,
                        RoundAvatarSettings.radiusFactor(context.hostContext())
                    )
                }
            })
        }.onFailure {
            notificationHookedMembers.remove(method)
            HLog.e("$TAG 安装通知 Bitmap 头像 Hook 失败: ${it.message}", it)
        }
    }

    private fun hookNotificationIcon() {
        val method = KavaReflector.findMethod(
            Notification.Builder::class.java,
            "setLargeIcon",
            Icon::class.java
        ) ?: return
        if (!notificationHookedMembers.add(method)) return
        runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!RoundAvatarSettings.enabled(context.hostContext())) return
                    val icon = param.args.firstOrNull() as? Icon ?: return
                    val source = KavaReflector.invokeMethod(icon, "getBitmap") as? Bitmap ?: return
                    val rounded = RoundAvatarBitmapProcessor.round(
                        source,
                        RoundAvatarSettings.radiusFactor(context.hostContext())
                    )
                    if (rounded !== source) param.args[0] = Icon.createWithBitmap(rounded)
                }
            })
        }.onFailure {
            notificationHookedMembers.remove(method)
            HLog.e("$TAG 安装通知 Icon 头像 Hook 失败: ${it.message}", it)
        }
    }

    private fun hookNotificationPost(method: Method?) {
        if (method == null || !notificationHookedMembers.add(method)) return
        runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!RoundAvatarSettings.enabled(context.hostContext())) return
                    val notification = param.args.firstOrNull { it is Notification } as? Notification ?: return
                    val source = notification.largeIcon ?: return
                    notification.largeIcon = RoundAvatarBitmapProcessor.round(
                        source,
                        RoundAvatarSettings.radiusFactor(context.hostContext())
                    )
                }
            })
        }.onFailure {
            notificationHookedMembers.remove(method)
            HLog.e("$TAG 安装通知提交头像 Hook 失败: ${it.message}", it)
        }
    }

    private fun locateAvatarMembers(): AvatarMembers? {
        val runtimeKey = methodCacheKey()
        val cachedLoad = DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_LEGACY_LOAD
        )?.takeIf(::isLegacyLoadMethod)
        val cachedConstructor = DexMethodCache.loadConstructor(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_WORKER_CONSTRUCTOR
        )?.takeIf(::isWorkerConstructor)
        val cachedModify = DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_WORKER_MODIFY
        )
        if (cachedLoad != null && cachedConstructor != null) {
            return AvatarMembers(
                cachedLoad,
                cachedConstructor,
                cachedModify?.takeIf { isWorkerModifyMethod(it, cachedConstructor.declaringClass) }
            )
        }

        val legacyLoad = locateLegacyLoadMethod(runtimeKey) ?: return null
        val workers = locateWorkerMembers(runtimeKey) ?: return null
        return AvatarMembers(legacyLoad, workers.first, workers.second)
    }

    private fun locateLegacyLoadMethod(runtimeKey: String): Method? {
        val candidates = findMethodsByStrings(LEGACY_AVATAR_TAG)
            .filterIsInstance<Method>()
            .filter(::isLegacyLoadMethod)
            .distinctBy { it.toGenericString() }
        val method = candidates.singleOrNull()
        if (method != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, CACHE_LEGACY_LOAD, method)
        } else {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_LEGACY_LOAD)
            if (candidates.size > 1) HLog.e("$TAG 旧式头像入口候选不唯一")
        }
        return method
    }

    private fun locateWorkerMembers(runtimeKey: String): Pair<Constructor<*>, Method?>? {
        val candidates = findMethodsByStrings(WORKER_SCOPE, USERNAME)
        val constructors = candidates.filterIsInstance<Constructor<*>>()
            .filter(::isWorkerConstructor)
            .distinctBy { it.toGenericString() }
        val constructor = constructors.singleOrNull() ?: run {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_WORKER_CONSTRUCTOR)
            if (constructors.size > 1) HLog.e("$TAG 新式头像构造入口候选不唯一")
            return null
        }
        val modifyCandidates = candidates.filterIsInstance<Method>()
            .filter { isWorkerModifyMethod(it, constructor.declaringClass) }
            .distinctBy { it.toGenericString() }
        val modify = modifyCandidates.singleOrNull()
        DexMethodCache.saveConstructor(methodPrefs, runtimeKey, CACHE_WORKER_CONSTRUCTOR, constructor)
        if (modify != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, CACHE_WORKER_MODIFY, modify)
        } else {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_WORKER_MODIFY)
            if (modifyCandidates.size > 1) HLog.e("$TAG 新式头像更新入口候选不唯一")
        }
        return constructor to modify
    }

    private fun findMethodsByStrings(vararg strings: String): List<Member> {
        return runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply { usingEqStrings(*strings) })
                }
            ).mapNotNull { data ->
                runCatching {
                    if (data.isConstructor) {
                        data.getConstructorInstance(context.hostClassLoader())
                    } else {
                        data.getMethodInstance(context.hostClassLoader())
                    }
                }.getOrNull()
            }
        }.getOrElse {
            HLog.e("$TAG 定位头像入口异常: ${it.message}", it)
            emptyList()
        }
    }

    private fun isLegacyLoadMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            types.contentEquals(
                arrayOf(
                    ImageView::class.java,
                    String::class.java,
                    java.lang.Float.TYPE,
                    java.lang.Boolean.TYPE
                )
            )
    }

    private fun isWorkerConstructor(constructor: Constructor<*>): Boolean {
        val types = constructor.parameterTypes
        return types.size == 7 &&
            types[0].name == "com.tencent.mm.sdk.coroutines.LifecycleScope" &&
            types[1] == String::class.java &&
            types[2] == java.lang.Float.TYPE &&
            types.any { it == Integer.TYPE }
    }

    private fun isWorkerModifyMethod(method: Method, owner: Class<*>): Boolean {
        val types = method.parameterTypes
        return Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.declaringClass == owner &&
            types.size == 8 &&
            types[0] == owner &&
            types[1].name == "com.tencent.mm.sdk.coroutines.LifecycleScope" &&
            types[2] == String::class.java &&
            types[3] == java.lang.Float.TYPE &&
            types.any { it == Integer.TYPE }
    }

    private fun lastIntParameterIndex(types: Array<Class<*>>): Int {
        return types.indexOfLast { it == Integer.TYPE || it == Int::class.java }
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
            .takeIf { it.isNotBlank() }
            ?.let { "$it|$CACHE_SCHEMA" }
            .orEmpty()
    }

    private data class AvatarMembers(
        val legacyLoad: Method,
        val workerConstructor: Constructor<*>,
        val workerModify: Method?
    )

    private companion object {
        const val TAG = "[Hchat:RoundAvatar]"
        const val CACHE_SCHEMA = "round_avatar_v1"
        const val CACHE_LEGACY_LOAD = "legacy_load"
        const val CACHE_WORKER_CONSTRUCTOR = "worker_constructor"
        const val CACHE_WORKER_MODIFY = "worker_modify"
        const val LEGACY_AVATAR_TAG = "MicroMsg.AvatarDrawable"
        const val WORKER_SCOPE = "workerScope"
        const val USERNAME = "username"
        const val DEFAULT_RADIUS_MASK = 1 shl 2
    }
}

private object RoundAvatarBitmapProcessor {
    private data class CacheEntry(val factor: Float, val bitmap: WeakReference<Bitmap>)

    private val cache = WeakHashMap<Bitmap, CacheEntry>()
    private val originals = WeakHashMap<Bitmap, WeakReference<Bitmap>>()

    @Synchronized
    fun round(source: Bitmap, radiusFactor: Float): Bitmap {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return source
        val original = originals[source]?.get()?.takeUnless { it.isRecycled } ?: source
        val factor = RoundAvatarSettings.normalizeRadiusFactor(radiusFactor)
        cache[original]?.takeIf { it.factor == factor }?.bitmap?.get()?.takeUnless { it.isRecycled }?.let {
            return it
        }
        return runCatching {
            val output = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
            output.density = original.density
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(original, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            val radius = minOf(original.width, original.height) * factor
            Canvas(output).drawRoundRect(
                0f,
                0f,
                original.width.toFloat(),
                original.height.toFloat(),
                radius,
                radius,
                paint
            )
            cache[original] = CacheEntry(factor, WeakReference(output))
            originals[output] = WeakReference(original)
            output
        }.getOrElse {
            HLog.e("[Hchat:RoundAvatar] 处理通知头像失败: ${it.message}", it)
            source
        }
    }
}
