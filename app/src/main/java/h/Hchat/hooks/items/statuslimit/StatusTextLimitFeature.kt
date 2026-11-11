package h.Hchat.hooks.items.statuslimit

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector

class StatusTextLimitFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "解除状态词长度限制"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(StatusTextLimitSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        install(context.hostContext(), context.hostClassLoader())
    }

    companion object {
        const val ID = "status_text_limit"
        private const val TAG = "[Hchat:StatusTextLimit]"
        private const val STATUS_ACTIVITY = "com.tencent.mm.plugin.textstatus.ui.TextStatusDoWhatActivityV2"
        private const val WECHAT_DEFAULT_LIMIT = 10
        private const val HCHAT_LIMIT = 2000

        @Volatile
        private var installed = false

        @JvmStatic
        fun isEnabled(context: Context?): Boolean {
            if (context == null) return false
            val sp = HchatStorage.preferences(context, StatusTextLimitSettings.PREFS_NAME)
            return sp.getBoolean(StatusTextLimitSettings.KEY_ENABLE, StatusTextLimitSettings.DEFAULT_ENABLE)
        }

        @JvmStatic
        fun install(context: Context?, classLoader: ClassLoader?) {
            if (context == null || classLoader == null || installed) return
            synchronized(this) {
                if (installed) return
                val activityClass = KavaReflector.loadClass(STATUS_ACTIVITY, classLoader)
                if (activityClass == null) {
                    HLog.e("$TAG 未找到状态词编辑页类")
                    installed = true
                    return
                }
                val constructors = KavaReflector.declaredConstructors(activityClass)
                if (constructors.isEmpty()) {
                    HLog.e("$TAG 未找到状态词编辑页构造方法")
                    installed = true
                    return
                }
                constructors.forEach { constructor ->
                    HookRegistry.get().hook(constructor, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isEnabled(context)) return
                            expandLimit(param.thisObject)
                        }
                    })
                }
                installed = true
            }
        }

        private fun expandLimit(activity: Any?) {
            if (activity == null) return
            var changed = false
            KavaReflector.declaredFields(activity.javaClass)
                .asSequence()
                .filter { it.type == Integer.TYPE && !KavaReflector.isStatic(it) }
                .filter { KavaReflector.readField(it, activity) == WECHAT_DEFAULT_LIMIT }
                .forEach { field ->
                    if (KavaReflector.writeField(field, activity, HCHAT_LIMIT)) {
                        changed = true
                    }
                }
            if (!changed) {
                HLog.e("$TAG 未找到可修改的状态词长度字段")
            }
        }
    }
}
