package h.Hchat.hooks.items.moments

import android.content.Context
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.api.sns.SnsContentTypes
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.WeakHashMap

class MomentsBottomDetailFeature : BaseFeature() {
    private var runtime: MomentsBottomDetailRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "朋友圈底部详情"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MomentsBottomDetailSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = MomentsBottomDetailRuntime(context, ::logFeatureError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(
            "$ID:time",
            "${name()}时间",
            stage = DexInstallScheduler.Stage.BRIDGE,
            priority = -10
        ) {
            runtime?.installTimeHook() == true
        }
        DexInstallScheduler.schedule(
            "$ID:group",
            "${name()}可见范围",
            stage = DexInstallScheduler.Stage.BRIDGE
        ) {
            runtime?.installGroupHook() == true
        }
        DexInstallScheduler.schedule(
            "$ID:profile",
            "${name()}个人主页",
            stage = DexInstallScheduler.Stage.BRIDGE,
            priority = -9
        ) {
            runtime?.installProfileHook() == true
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "moments_bottom_detail"

        @JvmStatic
        fun isEnabled(context: Context?): Boolean {
            if (context == null) return false
            return HchatStorage.preferences(context, MomentsBottomDetailSettings.PREFS_NAME)
                .getBoolean(MomentsBottomDetailSettings.KEY_ENABLE, MomentsBottomDetailSettings.DEFAULT_ENABLE)
        }
    }
}

private class MomentsBottomDetailRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = HchatStorage.preferences(
        context.hostContext(),
        MomentsBottomDetailSettings.PREFS_NAME
    )
    private val methodPrefs = DexMethodCache.prefs(
        context.hostContext(),
        CACHE_PREFS
    )

    @Volatile
    private var timeHookInstalled = false

    @Volatile
    private var groupHookInstalled = false

    @Volatile
    private var profileHookInstalled = false

    private val profileInfoByAdapter = ThreadLocal.withInitial {
        WeakHashMap<Any, LinkedHashMap<Long, Any>>()
    }

    @Synchronized
    fun installTimeHook(): Boolean {
        if (timeHookInstalled) return true
        val timeMethod = locateTimeMethod() ?: return false
        return runCatching {
            HookRegistry.get().hook(timeMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!MomentsBottomDetailFeature.isEnabled(context.hostContext())) return
                    val original = param.result as? String ?: return
                    param.result = formatDetails(param.thisObject, original)
                }
            })
            timeHookInstalled = true
            true
        }.getOrElse {
            logger("朋友圈底部详情时间 Hook 安装失败", it)
            false
        }
    }

    @Synchronized
    fun installGroupHook(): Boolean {
        if (groupHookInstalled) return true
        val owner = locateTimeMethod()?.declaringClass ?: return false
        val groupMethod = locateGroupMethod(owner) ?: return false
        return runCatching {
            HookRegistry.get().hook(groupMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!MomentsBottomDetailFeature.isEnabled(context.hostContext())) return
                    if (prefs.getBoolean(
                            MomentsBottomDetailSettings.KEY_HIDE_GROUP_ICON,
                            MomentsBottomDetailSettings.DEFAULT_HIDE_GROUP_ICON
                        )
                    ) {
                        param.result = false
                    }
                }
            })
            groupHookInstalled = true
            true
        }.getOrElse {
            logger("朋友圈底部详情可见范围 Hook 安装失败", it)
            false
        }
    }

    @Synchronized
    fun installProfileHook(): Boolean {
        if (profileHookInstalled) return true
        val formatterMethod = locateProfileFormatterMethod() ?: return false
        val itemMethod = locateProfileItemMethod(formatterMethod.declaringClass) ?: return false
        return runCatching {
            HookRegistry.get().hook(itemMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!MomentsBottomDetailFeature.isEnabled(context.hostContext())) return
                    val adapter = param.thisObject ?: return
                    val info = param.result ?: return
                    if (hasSnsFields(info.javaClass)) {
                        val createTime = readLong(info, "field_createTime") ?: return
                        val recent = profileInfoByAdapter.get().getOrPut(adapter) { LinkedHashMap() }
                        recent[createTime] = info
                        while (recent.size > PROFILE_RECENT_ITEM_LIMIT) {
                            val oldest = recent.keys.firstOrNull() ?: break
                            recent.remove(oldest)
                        }
                    }
                }
            })
            HookRegistry.get().hook(formatterMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!MomentsBottomDetailFeature.isEnabled(context.hostContext())) return
                    val adapter = param.thisObject ?: return
                    val createTime = (param.args.getOrNull(2) as? Number)?.toLong() ?: return
                    val info = profileInfoByAdapter.get()[adapter]?.get(createTime) ?: return
                    val primary = param.args.getOrNull(0) as? TextView ?: return
                    val secondary = param.args.getOrNull(1) as? TextView ?: return
                    val original = profileOriginalText(primary, secondary)
                    primary.text = ""
                    secondary.text = formatDetails(info, original)
                    secondary.contentDescription = secondary.text
                }
            })
            locateFlutterProfileSwitchMethod()?.let { method ->
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (MomentsBottomDetailFeature.isEnabled(context.hostContext())) {
                            param.result = false
                        }
                    }
                })
            }
            profileHookInstalled = true
            true
        }.getOrElse {
            logger("朋友圈底部详情个人主页 Hook 安装失败", it)
            false
        }
    }

    private fun profileOriginalText(primary: TextView, secondary: TextView): String {
        secondary.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        return primary.text.toString() + secondary.text.toString()
    }

    private fun formatDetails(info: Any?, originalText: String): String {
        if (info == null) return originalText
        val snsId = readLong(info, "field_snsId") ?: return originalText
        val userName = KavaReflector.readField(info, "field_userName")?.toString().orEmpty()
        val createTime = readLong(info, "field_createTime") ?: 0L
        val type = readLong(info, "field_type")?.toInt() ?: 0
        val time = formatTime(createTime, originalText)
        val values = mapOf(
            MomentsBottomDetailSettings.VAR_ORIGINAL_TEXT to originalText,
            MomentsBottomDetailSettings.VAR_TIME to time,
            MomentsBottomDetailSettings.VAR_TYPE to SnsContentTypes.classify(type).label,
            MomentsBottomDetailSettings.VAR_SNS_ID to java.lang.Long.toUnsignedString(snsId),
            MomentsBottomDetailSettings.VAR_USER_NAME to userName
        )
        val template = prefs.getString(
            MomentsBottomDetailSettings.KEY_TEXT_FORMAT,
            MomentsBottomDetailSettings.DEFAULT_TEXT_FORMAT
        ).let(MomentsBottomDetailSettings::normalizeTextFormat)
        return TEMPLATE_REGEX.replace(template) { match -> values[match.value].orEmpty() }
    }

    private fun formatTime(createTime: Long, originalText: String): String {
        if (createTime <= 0L) return originalText
        val pattern = prefs.getString(
            MomentsBottomDetailSettings.KEY_TIME_FORMAT,
            MomentsBottomDetailSettings.DEFAULT_TIME_FORMAT
        ).let(MomentsBottomDetailSettings::normalizeTimeFormat)
        return runCatching {
            SimpleDateFormat(pattern, Locale.CHINA).apply {
                timeZone = TimeZone.getDefault()
            }.format(Date(createTime * 1000L))
        }.getOrElse { originalText }
    }

    private fun readLong(receiver: Any, fieldName: String): Long? {
        val value = KavaReflector.readField(receiver, fieldName) ?: return null
        return (value as? Number)?.toLong() ?: value.toString().toLongOrNull()
    }

    private fun locateTimeMethod(): Method? {
        val runtimeKey = methodCacheKey()
        DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_TIME_METHOD
        )?.takeIf(::isTimeMethod)?.let { return it }
        val method = findMethod(
            MethodMatcher().apply {
                returnType("java.lang.String")
                paramCount(0)
                usingEqStrings(TIME_ANCHOR)
            },
            ::isTimeMethod
        )
        if (method != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, CACHE_TIME_METHOD, method)
        } else {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_TIME_METHOD)
            logger("朋友圈底部详情未找到时间方法", null)
        }
        return method
    }

    private fun locateGroupMethod(owner: Class<*>): Method? {
        val runtimeKey = methodCacheKey()
        DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_GROUP_METHOD
        )?.takeIf { isGroupMethod(it, owner) }?.let { return it }
        val method = findMethod(
            MethodMatcher().apply {
                paramCount(0)
                usingEqStrings(GROUP_ANCHOR)
            },
            { isGroupMethod(it, owner) }
        )
        if (method != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, CACHE_GROUP_METHOD, method)
        } else {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_GROUP_METHOD)
            logger("朋友圈底部详情未找到可见范围方法", null)
        }
        return method
    }

    private fun locateProfileFormatterMethod(): Method? {
        val runtimeKey = methodCacheKey()
        DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_PROFILE_FORMATTER_METHOD
        )?.takeIf(::isProfileFormatterMethod)?.let { return it }
        val method = findMethod(
            MethodMatcher().apply {
                usingEqStrings(PROFILE_TIME_ANCHOR, PROFILE_TIME_OWNER_ANCHOR)
            },
            ::isProfileFormatterMethod
        )
        if (method != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, CACHE_PROFILE_FORMATTER_METHOD, method)
        } else {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_PROFILE_FORMATTER_METHOD)
            logger("朋友圈底部详情未找到个人主页时间方法", null)
        }
        return method
    }

    private fun locateProfileItemMethod(profileBaseOwner: Class<*>): Method? {
        val runtimeKey = methodCacheKey()
        DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_PROFILE_ITEM_METHOD
        )?.takeIf { isProfileItemMethod(it, profileBaseOwner) }?.let { return it }
        val method = findMethod(
            MethodMatcher().apply {
                usingEqStrings(PROFILE_ITEM_ANCHOR, PROFILE_ITEM_OWNER_ANCHOR)
            },
            { isProfileItemMethod(it, profileBaseOwner) }
        )
        if (method != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, CACHE_PROFILE_ITEM_METHOD, method)
        } else {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_PROFILE_ITEM_METHOD)
            logger("朋友圈底部详情未找到个人主页条目方法", null)
        }
        return method
    }

    private fun locateFlutterProfileSwitchMethod(): Method? {
        val runtimeKey = methodCacheKey()
        DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_FLUTTER_PROFILE_SWITCH
        )?.takeIf(::isFlutterProfileSwitchMethod)?.let { return it }
        val method = findMethod(
            MethodMatcher().apply {
                usingEqStrings(FLUTTER_PROFILE_ANCHOR, FLUTTER_PROFILE_OWNER_ANCHOR)
            },
            ::isFlutterProfileSwitchMethod
        )
        if (method != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, CACHE_FLUTTER_PROFILE_SWITCH, method)
        } else {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_FLUTTER_PROFILE_SWITCH)
        }
        return method
    }

    private fun findMethod(
        methodMatcher: MethodMatcher,
        predicate: (Method) -> Boolean
    ): Method? {
        return runCatching {
            context.dexKitBridge().findMethod(FindMethod().apply { matcher(methodMatcher) })
                .mapNotNull { data ->
                    runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                }
                .firstOrNull(predicate)
        }.onFailure {
            logger("朋友圈底部详情 DexKit 定位失败", it)
        }.getOrNull()
    }

    private fun isTimeMethod(method: Method): Boolean {
        return !KavaReflector.isStatic(method) &&
            !KavaReflector.isAbstract(method) &&
            !method.declaringClass.isInterface &&
            method.parameterCount == 0 &&
            method.returnType == String::class.java &&
            hasSnsFields(method.declaringClass)
    }

    private fun isGroupMethod(method: Method, owner: Class<*>): Boolean {
        return !KavaReflector.isStatic(method) &&
            !KavaReflector.isAbstract(method) &&
            !method.declaringClass.isInterface &&
            method.parameterCount == 0 &&
            (method.returnType == Boolean::class.javaPrimitiveType ||
                method.returnType == Boolean::class.javaObjectType) &&
            method.declaringClass.isAssignableFrom(owner)
    }

    private fun isProfileFormatterMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return !KavaReflector.isStatic(method) &&
            !KavaReflector.isAbstract(method) &&
            !method.declaringClass.isInterface &&
            method.returnType == Void.TYPE &&
            types.size == 3 &&
            TextView::class.java.isAssignableFrom(types[0]) &&
            TextView::class.java.isAssignableFrom(types[1]) &&
            types[2] == Long::class.javaPrimitiveType
    }

    private fun isProfileItemMethod(method: Method, profileBaseOwner: Class<*>): Boolean {
        val types = method.parameterTypes
        return !KavaReflector.isStatic(method) &&
            !KavaReflector.isAbstract(method) &&
            !method.declaringClass.isInterface &&
            types.size == 1 &&
            types[0] == Int::class.javaPrimitiveType &&
            !method.returnType.isPrimitive &&
            method.returnType != Void.TYPE &&
            profileBaseOwner.isAssignableFrom(method.declaringClass)
    }

    private fun isFlutterProfileSwitchMethod(method: Method): Boolean {
        return !KavaReflector.isAbstract(method) &&
            !method.declaringClass.isInterface &&
            method.parameterTypes.isEmpty() &&
            method.returnType == Boolean::class.javaPrimitiveType
    }

    private fun hasSnsFields(clazz: Class<*>): Boolean {
        return KavaReflector.findFieldRecursive(clazz, "field_snsId") != null &&
            KavaReflector.findFieldRecursive(clazz, "field_userName") != null &&
            KavaReflector.findFieldRecursive(clazz, "field_createTime") != null &&
            KavaReflector.findFieldRecursive(clazz, "field_type") != null
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
    }

    companion object {
        private const val CACHE_PREFS = "Hchat_moments_bottom_detail_method_cache"
        private const val CACHE_TIME_METHOD = "time_method"
        private const val CACHE_GROUP_METHOD = "group_method"
        private const val CACHE_PROFILE_FORMATTER_METHOD = "profile_formatter_method"
        private const val CACHE_PROFILE_ITEM_METHOD = "profile_item_method"
        private const val CACHE_FLUTTER_PROFILE_SWITCH = "flutter_profile_switch"
        private const val TIME_ANCHOR = "getTimeString"
        private const val GROUP_ANCHOR = "getShowGroupEnable"
        private const val PROFILE_TIME_ANCHOR = "cerateTimeView"
        private const val PROFILE_TIME_OWNER_ANCHOR = "formatTimeInGrid"
        private const val PROFILE_ITEM_ANCHOR = "getItem"
        private const val PROFILE_ITEM_OWNER_ANCHOR = "com.tencent.mm.plugin.sns.ui.SnsSelfAdapter"
        private const val PROFILE_RECENT_ITEM_LIMIT = 32
        private const val FLUTTER_PROFILE_ANCHOR = "enableFlutterSNSPage"
        private const val FLUTTER_PROFILE_OWNER_ANCHOR = "com.tencent.mm.plugin.sns.router.SnsRouter"
        private val TEMPLATE_REGEX = Regex("\\$\\{(?:originalText|time|type|snsId|userName)\\}")
    }
}
