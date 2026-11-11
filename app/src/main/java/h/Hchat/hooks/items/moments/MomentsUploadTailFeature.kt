package h.Hchat.hooks.items.moments

import de.robv.android.xposed.XC_MethodHook
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Modifier

class MomentsUploadTailFeature : BaseFeature() {
    private var hooker: MomentsUploadTailHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "朋友圈上传尾巴"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MomentsUploadTailSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        hooker = MomentsUploadTailHooker(context, ::logError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(
            ID,
            name(),
            DexInstallScheduler.Stage.WARMUP
        ) {
            hooker?.install() == true
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        hooker = null
    }

    companion object {
        const val ID = "moments_upload_tail"
    }
}

private class MomentsUploadTailHooker(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = HchatStorage.preferences(
        context.hostContext(),
        MomentsUploadTailSettings.PREFS_NAME
    )
    @Volatile
    private var installed = false

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        val finder = context.dexFinder()
        runCatching { finder.resolveSnsUploadApi() }.onFailure {
            logger("朋友圈上传尾巴定位朋友圈发布方法失败", it)
        }
        val commit = finder.snsCommitMethod ?: return false
        val sdkId = finder.snsSetSdkIdMethod ?: return false
        val sdkAppName = finder.snsSetSdkAppNameMethod ?: return false
        if (Modifier.isAbstract(commit.modifiers) || commit.declaringClass.isInterface) return false
        return runCatching {
            HookRegistry.get().hook(commit, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!prefs.getBoolean(
                            MomentsUploadTailSettings.KEY_ENABLE,
                            MomentsUploadTailSettings.DEFAULT_ENABLE
                        )
                    ) return
                    val id = prefs.getString(
                        MomentsUploadTailSettings.KEY_SDK_ID,
                        MomentsUploadTailSettings.DEFAULT_SDK_ID
                    ).orEmpty().trim()
                    val name = prefs.getString(
                        MomentsUploadTailSettings.KEY_SDK_APP_NAME,
                        MomentsUploadTailSettings.DEFAULT_SDK_APP_NAME
                    ).orEmpty().trim()
                    if (id.isBlank() || name.isBlank()) return
                    KavaReflector.invoke(sdkId, param.thisObject, id)
                    KavaReflector.invoke(sdkAppName, param.thisObject, name)
                }
            })
            installed = true
            true
        }.onFailure {
            logger("朋友圈上传尾巴Hook安装失败: ${commit.toGenericString()}", it)
        }.getOrDefault(false)
    }
}
