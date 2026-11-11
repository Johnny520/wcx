package h.Hchat.hooks.items.autooriginal

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

class AutoOriginalImageFeature : BaseFeature() {
    private var runtime: AutoOriginalImageRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "自动勾选原图"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(AutoOriginalImageSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = AutoOriginalImageRuntime(context)
        runtime?.install()
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime = null
    }

    companion object {
        const val ID = "auto_original_image"
    }
}

private class AutoOriginalImageRuntime(
    private val context: FeatureContext
) {
    private val prefs = HchatStorage.preferences(
        context.hostContext(),
        AutoOriginalImageSettings.PREFS_NAME
    )
    private val hookedMethods = ConcurrentHashMap.newKeySet<Method>()

    fun install(): Boolean {
        var installed = 0
        TARGET_CLASSES.forEach { className ->
            val clazz = KavaReflector.loadClass(className, context.hostClassLoader()) ?: return@forEach
            val method = KavaReflector.findDeclaredMethod(clazz, "onCreate", Bundle::class.java)
                ?: return@forEach
            if (hookedMethods.contains(method)) {
                installed++
            } else if (hookMethod(method)) {
                installed++
            }
        }
        if (installed == 0) {
            HLog.e("$TAG 未找到图片发送页面 onCreate Hook 入口")
        }
        return installed > 0
    }

    private fun hookMethod(method: Method): Boolean {
        if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) {
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!prefs.getBoolean(
                            AutoOriginalImageSettings.KEY_ENABLE,
                            AutoOriginalImageSettings.DEFAULT_ENABLE
                        )
                    ) {
                        return
                    }
                    val activity = param.thisObject as? Activity ?: return
                    val intent = activity.intent
                    if (!isChatImageSend(intent)) return
                    intent.putExtra("key_send_raw_image", true)
                    intent.putExtra("send_raw_img", true)
                }
            })
            hookedMethods.add(method)
            true
        }.getOrElse {
            HLog.e("$TAG 安装图片发送页面 Hook 失败: ${method.toGenericString()}", it)
            false
        }
    }

    private fun isChatImageSend(intent: Intent): Boolean {
        return intent.getIntExtra("query_source_type", 3) == CHAT_SOURCE_TYPE &&
            !intent.getStringExtra("GalleryUI_ToUser").isNullOrBlank()
    }

    companion object {
        private const val TAG = "[Hchat:AutoOriginalImage]"
        private const val CHAT_SOURCE_TYPE = 3
        private val TARGET_CLASSES = listOf(
            "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI",
            "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI"
        )
    }
}
