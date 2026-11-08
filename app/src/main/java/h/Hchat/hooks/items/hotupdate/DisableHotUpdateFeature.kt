package h.Hchat.hooks.items.hotupdate

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.util.HashSet

class DisableHotUpdateFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "屏蔽热更新"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(DisableHotUpdateSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        if (!isEnabled(context.hostContext())) return
        install(context.hostContext(), context.hostClassLoader(), context.dexKitBridge())
    }

    companion object {
        const val ID = "disable_hot_update"
        private const val TAG = "[Hchat:HotUpdate]"
        private const val TINKER_APP = "com.tencent.tinker.loader.app.TinkerApplication"
        private const val TINKER_LOADER = "com.tencent.tinker.loader.TinkerLoader"
        private const val TINKER_SYNC_RESPONSE = "com.tencent.mm.plugin.hp.util.TinkerSyncResponse"
        private const val PATCH_RESULT = "com.tencent.tinker.lib.service.PatchResult"
        private const val LEGACY_PATCH_SERVICE = "com.tencent.mm.hotpatch.LegacyTinkerCore\$PatchService"
        private const val CACHE_PREFS = "Hchat_hot_update_method_cache"
        private val RESPONSE_HANDLER_STRINGS = listOf(
            "null cannot be cast to non-null type com.tencent.mm.plugin.hp.util.TinkerSyncResponse",
            "onReceiveUpgradePatch. try to start apply",
            "verify patch signature failed, tinker."
        )
        private val RESPONSE_CONSUMER_STRINGS = listOf(
            "before commandNewApkMd5HardCode, response.newApkMd5 = ",
            ", response.fileMd5 = "
        )

        private val earlyInstalledLoaders = HashSet<String>()
        private val lateInstalledLoaders = HashSet<String>()

        @JvmStatic
        fun isEnabled(context: Context?): Boolean {
            if (context == null) return false
            val sp = HchatStorage.preferences(context, DisableHotUpdateSettings.PREFS_NAME)
            return sp.getBoolean(
                DisableHotUpdateSettings.KEY_ENABLE,
                DisableHotUpdateSettings.DEFAULT_ENABLE
            )
        }

        @JvmStatic
        fun install(context: Context?, classLoader: ClassLoader?, dexKit: DexKitBridge?) {
            if (context == null || classLoader == null || dexKit == null) return
            if (!isEnabled(context)) return
            installEarly(context, classLoader)
            val key = loaderKey(classLoader)
            synchronized(this) {
                if (lateInstalledLoaders.contains(key)) return
                val cache = DexMethodCache.prefs(context, CACHE_PREFS)
                val runtimeKey = DexMethodCache.runtimeKey(context, classLoader)
                hookSyncResponseConsumer(classLoader, dexKit, cache, runtimeKey)
                hookUpgradeResponse(classLoader, dexKit, cache, runtimeKey)
                hookManualUpdater(classLoader, dexKit, cache, runtimeKey)
                hookLegacyPatchService(classLoader)
                lateInstalledLoaders.add(key)
            }
        }

        @JvmStatic
        fun installEarly(context: Context?, classLoader: ClassLoader?) {
            if (context == null || classLoader == null) return
            if (!isEnabled(context)) return
            val key = loaderKey(classLoader)
            synchronized(this) {
                if (earlyInstalledLoaders.contains(key)) return
                val installedFlags = hookTinkerFlags(classLoader)
                val installedLoader = hookTinkerLoader(classLoader)
                if (installedFlags || installedLoader) {
                    earlyInstalledLoaders.add(key)
                }
            }
        }

        private fun hookTinkerFlags(classLoader: ClassLoader): Boolean {
            return runCatching {
                val clazz = classLoader.loadClass(TINKER_APP)
                val method = clazz.declaredMethods.firstOrNull {
                    it.name == "getTinkerFlags" &&
                        it.parameterTypes.isEmpty() &&
                        (it.returnType == Int::class.javaPrimitiveType || it.returnType == Int::class.javaObjectType)
                }
                if (method == null) {
                    HLog.e("$TAG 未找到 TinkerApplication.getTinkerFlags")
                    return@runCatching false
                }
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = 0
                    }
                })
                true
            }.onFailure {
                HLog.e("$TAG Hook getTinkerFlags 失败: ${it.message}", it)
            }.getOrDefault(false)
        }

        private fun hookTinkerLoader(classLoader: ClassLoader): Boolean {
            return runCatching {
                val clazz = classLoader.loadClass(TINKER_LOADER)
                val methods = clazz.declaredMethods.filter { method ->
                    method.name == "tryLoadPatchFilesInternal" &&
                        method.parameterTypes.size >= 2 &&
                        method.parameterTypes[0].name == TINKER_APP &&
                        method.parameterTypes[1] == android.content.Intent::class.java
                }
                if (methods.isEmpty()) {
                    HLog.e("$TAG 未找到 TinkerLoader.tryLoadPatchFilesInternal")
                    return@runCatching false
                }
                methods.forEach { method ->
                    HookRegistry.get().hook(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val intent = param.args.getOrNull(1) as? android.content.Intent
                            runCatching {
                                val shareIntentUtil = classLoader.loadClass("com.tencent.tinker.loader.shareutil.ShareIntentUtil")
                                val setReturnCode = shareIntentUtil.getDeclaredMethod(
                                    "setIntentReturnCode",
                                    android.content.Intent::class.java,
                                    Int::class.javaPrimitiveType
                                )
                                setReturnCode.isAccessible = true
                                if (intent != null) {
                                    setReturnCode.invoke(null, intent, -1)
                                }
                            }
                            param.result = blockedReturnValue(method)
                        }
                    })
                }
                true
            }.onFailure {
                HLog.e("$TAG Hook TinkerLoader 失败: ${it.message}", it)
            }.getOrDefault(false)
        }

        private fun hookManualUpdater(
            classLoader: ClassLoader,
            dexKit: DexKitBridge,
            cache: android.content.SharedPreferences,
            runtimeKey: String
        ) {
            val method = findManualUpdaterMethod(classLoader, dexKit, cache, runtimeKey)
            if (method == null) {
                HLog.e("$TAG 未找到热更新补丁应用入口")
                return
            }
            runCatching {
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = blockedReturnValue(method)
                    }
                })
            }.onFailure {
                HLog.e("$TAG Hook 补丁应用入口失败: ${it.message}", it)
            }
        }

        private fun hookSyncResponseConsumer(
            classLoader: ClassLoader,
            dexKit: DexKitBridge,
            cache: android.content.SharedPreferences,
            runtimeKey: String
        ) {
            runCatching {
                val responseClass = runCatching { classLoader.loadClass(TINKER_SYNC_RESPONSE) }.getOrNull()
                if (responseClass == null) {
                    HLog.e("$TAG 未找到 TinkerSyncResponse 类")
                    return
                }
                var count = 0
                // push 进程使用不同的 ClassLoader，但同一微信版本仍可复用主进程已经确认的描述符。
                // 只比较版本/包体等运行时身份，不能因加载器指纹不同而清空缓存并重新扫描。
                val cached = DexMethodCache.loadListCrossProcess(cache, runtimeKey, classLoader, "sync_response_consumer")
                // 描述符只有在主进程通过参数校验后才会写入；跨 ClassLoader 时不要再用
                // 主进程的 Class 对象做一次身份比较，否则会把有效缓存误判为空。
                val methods = cached.ifEmpty {
                    dexKit.findMethod(
                        FindMethod().apply {
                            matcher(MethodMatcher().apply {
                                usingStrings(RESPONSE_CONSUMER_STRINGS)
                            })
                        }
                    ).mapNotNull { data ->
                        runCatching { data.getMethodInstance(classLoader) }.getOrNull()
                            ?.takeIf { method -> isSyncResponseConsumer(method, responseClass) }
                    }
                }
                // 跨进程命中时不要用当前进程的 ClassLoader 指纹重写总缓存键，
                // 否则会清掉同一运行时下其它入口的已验证描述符。
                if (cached.isEmpty()) {
                    if (methods.isNotEmpty()) DexMethodCache.saveList(cache, runtimeKey, "sync_response_consumer", methods)
                    else DexMethodCache.clear(cache, runtimeKey, "sync_response_consumer")
                }
                methods.forEach { method ->
                    HookRegistry.get().hook(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = blockedReturnValue(method)
                        }
                    })
                    count++
                }
                if (count == 0) {
                    HLog.e("$TAG 未找到热更新下发响应消费入口")
                }
            }.onFailure {
                HLog.e("$TAG Hook 热更新下发响应消费失败: ${it.message}", it)
            }
        }

        private fun hookUpgradeResponse(
            classLoader: ClassLoader,
            dexKit: DexKitBridge,
            cache: android.content.SharedPreferences,
            runtimeKey: String
        ) {
            runCatching {
                val cached = DexMethodCache.loadListCrossProcess(cache, runtimeKey, classLoader, "upgrade_response")
                val methods = cached.ifEmpty {
                    dexKit.findMethod(
                        FindMethod().apply {
                            matcher(MethodMatcher().apply {
                                usingStrings(RESPONSE_HANDLER_STRINGS)
                            })
                        }
                    ).mapNotNull { data ->
                        runCatching { data.getMethodInstance(classLoader) }.getOrNull()
                            ?.takeIf { method -> isUpgradeResponseHandler(method) }
                    }
                }
                if (cached.isEmpty()) {
                    if (methods.isNotEmpty()) DexMethodCache.saveList(cache, runtimeKey, "upgrade_response", methods)
                    else DexMethodCache.clear(cache, runtimeKey, "upgrade_response")
                }
                methods.forEach { method ->
                    HookRegistry.get().hook(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = blockedReturnValue(method)
                        }
                    })
                }
            }.onFailure {
                HLog.e("$TAG Hook 热更新响应处理失败: ${it.message}", it)
            }
        }

        private fun findManualUpdaterMethod(
            classLoader: ClassLoader,
            dexKit: DexKitBridge,
            cache: android.content.SharedPreferences,
            runtimeKey: String
        ): Method? {
            // 同一运行时身份的缓存描述符已在主进程通过签名校验，push 进程直接复用，
            // 避免因 ClassLoader 的类型对象不同而重新启动 DexKit 扫描。
            DexMethodCache.loadCrossProcess(cache, runtimeKey, classLoader, "manual_updater")
                ?.let { return it }
            return runCatching {
                val method = dexKit.findMethod(
                    FindMethod().apply {
                        matcher(MethodMatcher().apply {
                            usingStrings(
                                listOf(
                                    "start to run patch",
                                    "hot patch verfiy signature error",
                                    "patch applying is blocked by TinkerEnsuranceOnFault"
                                )
                            )
                        })
                    }
                ).firstNotNullOfOrNull { data ->
                    runCatching { data.getMethodInstance(classLoader) }.getOrNull()
                        ?.takeIf { method -> isManualUpdaterMethod(method) }
                }
                if (method != null) DexMethodCache.save(cache, runtimeKey, "manual_updater", method)
                else DexMethodCache.clear(cache, runtimeKey, "manual_updater")
                method
            }.getOrNull()
        }

        private fun isSyncResponseConsumer(method: Method, responseClass: Class<*>): Boolean {
            return method.parameterTypes.any { it == responseClass || it.name == TINKER_SYNC_RESPONSE }
        }

        private fun isUpgradeResponseHandler(method: Method): Boolean {
            return (method.parameterTypes.size == 1 && method.parameterTypes[0] == java.io.File::class.java) ||
                (method.name == "onGYNetEnd" && method.parameterTypes.size >= 5)
        }

        private fun isManualUpdaterMethod(method: Method): Boolean {
            return method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == String::class.java &&
                method.parameterTypes[1].name == TINKER_SYNC_RESPONSE &&
                method.returnType == Void.TYPE
        }

        private fun hookLegacyPatchService(classLoader: ClassLoader) {
            runCatching {
                val clazz = classLoader.loadClass(LEGACY_PATCH_SERVICE)
                val methods = clazz.declaredMethods.filter { method ->
                    method.parameterTypes.size == 1 &&
                        method.parameterTypes[0].name == PATCH_RESULT &&
                        method.returnType == Void.TYPE
                }
                methods.forEach { method ->
                    HookRegistry.get().hook(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = blockedReturnValue(method)
                        }
                    })
                }
            }.onFailure {
                if (it is ClassNotFoundException) return@onFailure
                HLog.e("$TAG Hook LegacyTinkerCore 失败: ${it.message}", it)
            }
        }

        private fun blockedReturnValue(method: Method): Any? {
            return when (method.returnType) {
                java.lang.Integer.TYPE, java.lang.Integer::class.java -> 0
                java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> false
                java.lang.Long.TYPE, java.lang.Long::class.java -> 0L
                else -> null
            }
        }

        private fun loaderKey(classLoader: ClassLoader): String {
            return "${System.identityHashCode(classLoader)}:${classLoader}"
        }
    }
}
