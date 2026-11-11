package h.Hchat.hooks.items.legacychatinput

import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.KavaReflector

class LegacyChatInputFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "恢复旧版聊天输入框"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(LegacyChatInputSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        val loadPackageParam = context.loadPackageParam() ?: return
        val packageName = loadPackageParam.packageName ?: return
        if (!packageName.startsWith(WECHAT_PACKAGE_PREFIX)) return
        if (loadPackageParam.processName != null && loadPackageParam.processName != packageName) return

        val versionCode = runCatching {
            context.hostContext().packageManager.getPackageInfo(packageName, 0).versionCode
        }.getOrElse {
            logError("读取微信版本失败，旧版输入框 Hook 未安装", it)
            return
        }
        if (versionCode != LegacyChatInputPolicy.TARGET_VERSION_CODE) return

        val classLoader = context.hostClassLoader()
        val configManagerClass = KavaReflector.loadClass(CONFIG_MANAGER_CLASS, classLoader)
        val configBaseClass = KavaReflector.loadClass(CONFIG_BASE_CLASS, classLoader)
        val inputBarConfigClass = KavaReflector.loadClass(
            LegacyChatInputPolicy.INPUT_BAR_CONFIG_CLASS_NAME,
            classLoader
        )
        if (configManagerClass == null || configBaseClass == null || inputBarConfigClass == null) {
            logError("微信 8.0.80 输入栏配置类未找到，旧版输入框 Hook 未安装", null)
            return
        }
        if (!configBaseClass.isAssignableFrom(inputBarConfigClass)) {
            logError("输入栏配置类与配置读取方法参数不匹配，旧版输入框 Hook 未安装", null)
            return
        }

        val readMethods = runCatching {
            KavaReflector.declaredMethods(configManagerClass).filter { method ->
                method.name == CONFIG_READ_METHOD &&
                    method.returnType == Integer.TYPE &&
                    method.parameterTypes.contentEquals(arrayOf(configBaseClass))
            }
        }.getOrElse {
            logError("定位微信输入栏配置读取方法失败", it)
            return
        }
        val readMethod = readMethods.singleOrNull() ?: run {
            logError(
                "微信输入栏配置读取方法不唯一或不存在: " +
                    "$CONFIG_MANAGER_CLASS#$CONFIG_READ_METHOD(${configBaseClass.name}):int",
                null
            )
            return
        }
        val preferences = LegacyChatInputSettings.preferences(context.hostContext())

        runCatching {
            HookRegistry.get().hook(readMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = param.args?.firstOrNull() ?: return
                    val enabled = preferences.getBoolean(
                        LegacyChatInputSettings.KEY_ENABLE,
                        LegacyChatInputSettings.DEFAULT_ENABLE
                    )
                    if (LegacyChatInputPolicy.shouldForceLegacy(
                            enabled = enabled,
                            versionCode = versionCode,
                            configClassName = config.javaClass.name
                        )
                    ) {
                        param.result = 0
                    }
                }
            })
        }.onFailure {
            logError("安装微信原生旧版输入栏配置 Hook 失败", it)
        }
    }

    companion object {
        const val ID = "restore_legacy_chat_input"

        private const val WECHAT_PACKAGE_PREFIX = "com.tencent.mm"
        private const val CONFIG_MANAGER_CLASS = "tc2.e"
        private const val CONFIG_BASE_CLASS = "ee5.e"
        private const val CONFIG_READ_METHOD = "c"
    }
}

internal object LegacyChatInputPolicy {
    const val TARGET_VERSION_CODE = 3220
    const val INPUT_BAR_CONFIG_CLASS_NAME =
        "com.tencent.mm.repairer.config.chatting.RepairerConfigInputBarVoiceInput"

    fun shouldForceLegacy(enabled: Boolean, versionCode: Int, configClassName: String): Boolean =
        enabled &&
            versionCode == TARGET_VERSION_CODE &&
            configClassName == INPUT_BAR_CONFIG_CLASS_NAME
}
