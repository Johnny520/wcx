package h.Hchat.hooks.items.callmedialimit

import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class CallMediaLimitFeature : BaseFeature() {
    private var hooker: CallMediaLimitHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "移除通话媒体限制"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(CallMediaLimitSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        hooker = CallMediaLimitHooker(context, ::logRuntimeError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
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
        const val ID = "call_media_limit"
    }
}

internal class CallMediaLimitHooker(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private data class VoipEventAccess(
        val dispatch: Method,
        val resultField: Field
    )

    private val cache = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
    private val voicePlaybackGuardDepth = ThreadLocal<Int>()
    private val voipResultBooleanFields = ConcurrentHashMap<Class<*>, List<Field>>()
    private val voipResultFailures = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var installed = false

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        return runCatching {
            val occupyMethods = locateDeviceOccupyMethods()
            if (!isDeviceOccupyMethodSet(occupyMethods)) return false
            val voicePlaybackGuard = locateVoicePlaybackGuardMethod() ?: return false
            val voipEventAccess = locateVoipEventAccess() ?: return false

            occupyMethods.forEach { method ->
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return
                        param.result = false
                    }
                })
            }
            HookRegistry.get().hook(voipEventAccess.dispatch, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isVoicePlaybackGuardActive() || !isEnabled()) return
                    clearVoipUsageResult(param.thisObject, voipEventAccess.resultField)
                }
            })
            HookRegistry.get().hook(voicePlaybackGuard, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val depth = voicePlaybackGuardDepth.get() ?: 0
                    voicePlaybackGuardDepth.set(depth + 1)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val depth = (voicePlaybackGuardDepth.get() ?: 1) - 1
                    if (depth <= 0) {
                        voicePlaybackGuardDepth.remove()
                    } else {
                        voicePlaybackGuardDepth.set(depth)
                    }
                }
            })
            installed = true
            true
        }.getOrElse {
            logger("通话媒体限制 Hook 安装失败", it)
            false
        }
    }

    private fun locateDeviceOccupyMethods(): List<Method> {
        val runtimeKey = runtimeKey()
        DexMethodCache.loadList(
            cache,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_DEVICE_OCCUPY_METHODS
        ).takeIf(::isDeviceOccupyMethodSet)?.let { return it }

        val methods = LinkedHashSet<Method>()
        val rawMultiTalkMethods = LinkedHashSet<Method>()
        DEVICE_OCCUPY_ANCHORS.forEachIndexed { index, anchor ->
            val results = runCatching {
                context.dexKitBridge().findMethod(
                    FindMethod().apply {
                        matcher(
                            MethodMatcher().apply {
                                usingEqStrings(DEVICE_OCCUPY_TAG, anchor)
                            }
                        )
                    }
                )
            }.getOrElse {
                logger("定位通话占用方法失败: $anchor", it)
                emptyList()
            }
            results.forEach resultLoop@ { data ->
                val method = runCatching {
                    data.getMethodInstance(context.hostClassLoader())
                }.getOrNull() ?: return@resultLoop
                if (!isDeviceOccupyMethod(method)) return@resultLoop
                methods.add(method)
                if (index != MULTI_TALK_ANCHOR_INDEX) return@resultLoop
                runCatching { data.invokes }.getOrDefault(emptyList()).forEach invokeLoop@ { invoke ->
                    val invokedMethod = runCatching {
                        invoke.getMethodInstance(context.hostClassLoader())
                    }.getOrNull() ?: return@invokeLoop
                    if (isRawMultiTalkMethod(invokedMethod, method.declaringClass)) {
                        rawMultiTalkMethods.add(invokedMethod)
                    }
                }
            }
        }
        methods.addAll(rawMultiTalkMethods)

        val groups = methods.groupBy { it.declaringClass }
            .values
            .filter(::isDeviceOccupyMethodSet)
        val result = groups.singleOrNull()?.distinctBy { it.toGenericString() }.orEmpty()
        if (result.isEmpty()) {
            DexMethodCache.clear(cache, runtimeKey, CACHE_DEVICE_OCCUPY_METHODS)
            logger("通话占用方法组不完整或候选不唯一", null)
        } else {
            DexMethodCache.saveList(cache, runtimeKey, CACHE_DEVICE_OCCUPY_METHODS, result)
        }
        return result
    }

    private fun locateVoicePlaybackGuardMethod(): Method? {
        val runtimeKey = runtimeKey()
        DexMethodCache.load(
            cache,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_VOICE_PLAYBACK_GUARD
        )?.takeIf(::isVoicePlaybackGuardMethod)?.let { return it }

        val candidates = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            declaredClass(CHATTING_VIEWITEMS_PREFIX, StringMatchType.StartsWith)
                            addInvoke(
                                MethodMatcher().apply {
                                    declaredClass(VOIP_DEVICE_EVENT_CLASS)
                                    name("<init>")
                                    paramTypes()
                                }
                            )
                        }
                    )
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter(::isVoicePlaybackGuardMethod)
                .distinctBy { it.toGenericString() }
        }.getOrElse {
            logger("定位语音消息通话检查方法失败", it)
            emptyList()
        }
        val method = candidates.singleOrNull()
        if (method == null) {
            DexMethodCache.clear(cache, runtimeKey, CACHE_VOICE_PLAYBACK_GUARD)
            logger("语音消息通话检查方法缺失或候选不唯一", null)
        } else {
            DexMethodCache.save(cache, runtimeKey, CACHE_VOICE_PLAYBACK_GUARD, method)
        }
        return method
    }

    private fun locateVoipEventAccess(): VoipEventAccess? {
        val eventClass = KavaReflector.loadClass(VOIP_DEVICE_EVENT_CLASS, context.hostClassLoader())
            ?: return logVoipAccessFailure("微信通话状态事件类缺失")
        val dispatch = instanceMethods(eventClass).singleOrNull { method ->
            method.parameterTypes.isEmpty() &&
                method.returnType == java.lang.Boolean.TYPE &&
                !KavaReflector.isStatic(method) &&
                !KavaReflector.isAbstract(method)
        } ?: return logVoipAccessFailure("微信通话状态事件派发方法缺失或候选不唯一")
        val resultField = KavaReflector.declaredFields(eventClass).singleOrNull { field ->
            !KavaReflector.isStatic(field) && !field.type.isPrimitive
        } ?: return logVoipAccessFailure("微信通话状态事件结果字段缺失或候选不唯一")
        return VoipEventAccess(dispatch, resultField)
    }

    private fun clearVoipUsageResult(event: Any?, resultField: Field) {
        val result = KavaReflector.readField(resultField, event) ?: run {
            logVoipResultFailure("result", "微信通话状态事件结果为空")
            return
        }
        val booleanFields = voipResultBooleanFields.computeIfAbsent(result.javaClass) { resultClass ->
            instanceFields(resultClass).filter { field ->
                !KavaReflector.isStatic(field) &&
                    (field.type == java.lang.Boolean.TYPE || field.type == java.lang.Boolean::class.java)
            }
        }
        if (booleanFields.isEmpty()) {
            logVoipResultFailure("fields", "微信通话状态事件未找到布尔结果字段")
            return
        }
        var wroteResult = false
        booleanFields.forEach { field ->
            wroteResult = KavaReflector.writeField(field, result, false) || wroteResult
        }
        if (!wroteResult) {
            logVoipResultFailure("write", "微信通话状态事件结果改写失败")
        }
    }

    private fun isVoicePlaybackGuardActive(): Boolean =
        (voicePlaybackGuardDepth.get() ?: 0) > 0

    private fun isVoicePlaybackGuardMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return KavaReflector.isStatic(method) &&
            method.declaringClass.name.startsWith(CHATTING_VIEWITEMS_PREFIX) &&
            types.size == 3 &&
            types[2].name == MSG_QUOTE_ITEM_CLASS &&
            (method.returnType == java.lang.Boolean.TYPE || method.returnType == java.lang.Void.TYPE)
    }

    private fun instanceMethods(ownerClass: Class<*>): List<Method> {
        val methods = ArrayList<Method>()
        var current: Class<*>? = ownerClass
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredMethods(current)
                .filterNot { KavaReflector.isStatic(it) }
                .forEach { methods.add(it) }
            current = current.superclass
        }
        return methods
    }

    private fun instanceFields(ownerClass: Class<*>): List<Field> {
        val fields = ArrayList<Field>()
        var current: Class<*>? = ownerClass
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredFields(current)
                .filterNot { KavaReflector.isStatic(it) }
                .forEach { fields.add(it) }
            current = current.superclass
        }
        return fields
    }

    private fun logVoipAccessFailure(message: String): VoipEventAccess? {
        logger(message, null)
        return null
    }

    private fun logVoipResultFailure(key: String, message: String) {
        if (voipResultFailures.add(key)) logger(message, null)
    }

    private fun isEnabled(): Boolean {
        return HchatStorage.preferences(context.hostContext(), CallMediaLimitSettings.PREFS_NAME)
            .getBoolean(CallMediaLimitSettings.KEY_ENABLE, CallMediaLimitSettings.DEFAULT_ENABLE)
    }

    private fun isDeviceOccupyMethodSet(methods: List<Method>): Boolean {
        if (methods.size < MIN_DEVICE_OCCUPY_METHODS) return false
        if (methods.map { it.declaringClass }.distinct().size != 1) return false
        return methods.all(::isDeviceOccupyMethod)
    }

    private fun isDeviceOccupyMethod(method: Method): Boolean {
        return KavaReflector.isStatic(method) &&
            (method.returnType == java.lang.Boolean.TYPE ||
                method.returnType == java.lang.Boolean::class.java) &&
            method.parameterTypes.size <= 3
    }

    private fun isRawMultiTalkMethod(method: Method, owner: Class<*>): Boolean {
        return method.declaringClass == owner &&
            KavaReflector.isStatic(method) &&
            method.parameterTypes.isEmpty() &&
            (method.returnType == java.lang.Boolean.TYPE ||
                method.returnType == java.lang.Boolean::class.java)
    }

    private fun runtimeKey(): String =
        DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())

    companion object {
        private const val CACHE_PREFS = "Hchat_call_media_limit_method_cache"
        private const val CACHE_DEVICE_OCCUPY_METHODS = "device_occupy_methods_v1"
        private const val CACHE_VOICE_PLAYBACK_GUARD = "voice_playback_guard_v1"
        private const val DEVICE_OCCUPY_TAG = "MicroMsg.DeviceOccupy"
        private const val VOIP_DEVICE_EVENT_CLASS =
            "com.tencent.mm.autogen.events.VoipCheckIsDeviceUsingEvent"
        private const val MSG_QUOTE_ITEM_CLASS =
            "com.tencent.mm.plugin.msgquote.model.MsgQuoteItem"
        private const val CHATTING_VIEWITEMS_PREFIX = "com.tencent.mm.ui.chatting.viewitems."
        private const val MULTI_TALK_ANCHOR_INDEX = 0
        private const val MIN_DEVICE_OCCUPY_METHODS = 7
        private val DEVICE_OCCUPY_ANCHORS = listOf(
            "isMultiTalking",
            "isCameraUsing",
            "isVoiceUsing",
            "checkAppBrandVoiceUsingAndShowToast isVoiceUsing:%b, isCameraUsing:%b"
        )
    }
}
