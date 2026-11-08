package h.Hchat.hooks.items.hideavatar

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class HideChatAvatarFeature : BaseFeature() {
    private var runtime: HideChatAvatarRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "隐藏头像"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(HideChatAvatarSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = HideChatAvatarRuntime(context)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            runtime?.install() == true
        }
    }

    companion object {
        const val ID = "hide_chat_avatar"
    }
}

private class HideChatAvatarRuntime(
    private val context: FeatureContext
) {
    private val prefs = HchatStorage.preferences(context.hostContext(), HideChatAvatarSettings.PREFS_NAME)
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), "Hchat_hide_chat_avatar_method_cache")
    private val holderFields = ConcurrentHashMap<Class<*>, AvatarFields>()
    private val unsupportedHolderClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val originalMaskWidths = Collections.synchronizedMap(WeakHashMap<View, Int>())

    @Volatile private var installed = false

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        val method = locateAvatarBindMethod() ?: run {
            HLog.e("$TAG 定位聊天头像绑定方法失败")
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    applyVisibility(param.args)
                }
            })
            installed = true
            true
        }.getOrElse {
            HLog.e("$TAG 安装聊天头像绑定 Hook 失败: ${it.message}", it)
            false
        }
    }

    private fun applyVisibility(args: Array<Any?>?) {
        if (args == null || args.size != 4) return
        val message = args.firstOrNull { it != null && isMessageClass(it.javaClass) } ?: return
        val outgoing = resolveOutgoing(args[2] as? String, message) ?: return
        val hide = if (outgoing) {
            prefs.getBoolean(HideChatAvatarSettings.KEY_HIDE_SELF, HideChatAvatarSettings.DEFAULT_HIDE_SELF)
        } else {
            prefs.getBoolean(HideChatAvatarSettings.KEY_HIDE_OTHER, HideChatAvatarSettings.DEFAULT_HIDE_OTHER)
        }
        val holder = args.firstOrNull { it != null && avatarFields(it.javaClass) != null } ?: return
        val fields = avatarFields(holder.javaClass) ?: return
        val avatar = KavaReflector.readField(fields.avatar, holder) as? View ?: return
        val mask = KavaReflector.readField(fields.mask, holder) as? View
        applyAvatarLayout(avatar, mask, hide)
    }

    private fun applyAvatarLayout(avatar: View, mask: View?, hide: Boolean) {
        val wrapper = sequenceOf(mask, avatar.parent as? View)
            .filterNotNull()
            .firstOrNull { it.javaClass.name == MASK_LAYOUT_CLASS }
        if (wrapper == null) {
            if (hide) avatar.visibility = View.GONE
            return
        }

        val params = wrapper.layoutParams ?: return
        if (hide) {
            synchronized(originalMaskWidths) {
                if (!originalMaskWidths.containsKey(wrapper)) {
                    originalMaskWidths[wrapper] = params.width
                }
            }
            if (params.width != 0) {
                params.width = 0
                wrapper.layoutParams = params
            }
            avatar.visibility = View.GONE
            return
        }

        val originalWidth = synchronized(originalMaskWidths) { originalMaskWidths.remove(wrapper) }
        if (originalWidth != null && params.width != originalWidth) {
            params.width = originalWidth
            wrapper.layoutParams = params
        }
    }

    private fun resolveOutgoing(avatarUsername: String?, message: Any): Boolean? {
        val selfWxId = WeChatApis.account()?.selfWxId().orEmpty()
        if (selfWxId.isNotBlank() && !avatarUsername.isNullOrBlank()) {
            return selfWxId == avatarUsername
        }
        for (getter in arrayOf("getIsSend", "isSend")) {
            parseBoolean(KavaReflector.invoke(KavaReflector.findMethod(message.javaClass, getter), message))
                ?.let { return it }
        }
        for (field in arrayOf("field_isSend", "isSend")) {
            parseBoolean(KavaReflector.readField(message, field))?.let { return it }
        }
        return null
    }

    private fun parseBoolean(value: Any?): Boolean? {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() == 1
            is String -> value.trim().toIntOrNull()?.let { it == 1 }
            else -> null
        }
    }

    private fun locateAvatarBindMethod(): Method? {
        val cacheKey = methodCacheKey()
        DexMethodCache.load(methodPrefs, cacheKey, context.hostClassLoader(), CACHE_AVATAR_BIND)
            ?.takeIf { isAvatarBindCandidate(it) }
            ?.let { return it }
        val method = findMethodsByStrings(
            "MicroMsg.ChattingItem",
            "attachAvatarClickListener: getBizKfWorker:%s"
        ).firstOrNull { isAvatarBindCandidate(it) }
        if (method != null) {
            DexMethodCache.save(methodPrefs, cacheKey, CACHE_AVATAR_BIND, method)
        } else {
            DexMethodCache.clear(methodPrefs, cacheKey, CACHE_AVATAR_BIND)
        }
        return method
    }

    private fun findMethodsByStrings(vararg strings: String): List<Method> {
        return runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply { usingStrings(strings.toList()) })
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }
        }.getOrElse {
            HLog.e("$TAG 定位聊天头像绑定方法异常: ${it.message}", it)
            emptyList()
        }
    }

    private fun isAvatarBindCandidate(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == Void.TYPE &&
            types.size == 4 &&
            types[2] == String::class.java &&
            avatarFields(types[0]) != null &&
            types.any(::isMessageClass)
    }

    private fun isMessageClass(clazz: Class<*>): Boolean {
        return clazz.name.startsWith("com.tencent.mm.storage.")
    }

    private fun avatarFields(clazz: Class<*>): AvatarFields? {
        holderFields[clazz]?.let { return it }
        if (unsupportedHolderClasses.contains(clazz)) return null
        var current: Class<*>? = clazz
        var avatar: Field? = null
        var mask: Field? = null
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                when {
                    avatar == null && (field.name == "avatarIV" || field.type.name.endsWith(".ChattingAvatarImageView")) -> {
                        avatar = field
                    }
                    mask == null && (field.name == "avatarMask" || field.type.name == "com.tencent.mm.ui.base.MaskLayout") -> {
                        mask = field
                    }
                }
            }
            current = current.superclass
        }
        val result = avatar?.let { AvatarFields(it, mask) }
        if (result == null) {
            unsupportedHolderClasses += clazz
        } else {
            holderFields[clazz] = result
        }
        return result
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
            .takeIf { it.isNotBlank() }
            ?.let { "$it|$CACHE_SCHEMA" }
            .orEmpty()
    }

    private data class AvatarFields(val avatar: Field, val mask: Field?)

    private companion object {
        const val TAG = "[Hchat:HideChatAvatar]"
        const val CACHE_SCHEMA = "hide_chat_avatar_v1"
        const val CACHE_AVATAR_BIND = "avatar_bind"
        const val MASK_LAYOUT_CLASS = "com.tencent.mm.ui.base.MaskLayout"
    }
}
