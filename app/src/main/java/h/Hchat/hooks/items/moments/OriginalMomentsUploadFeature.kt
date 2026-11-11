package h.Hchat.hooks.items.moments

import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class OriginalMomentsUploadFeature : BaseFeature() {
    private var hooker: OriginalMomentsUploadHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "朋友圈原图上传"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(OriginalMomentsUploadSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        hooker = OriginalMomentsUploadHooker(context)
        if (hooker?.install(allowDexSearch = false) != true) {
            scheduleInstall()
        }
        subscribe(Events.DexReady::class.java) {
            scheduleInstall()
        }
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            hooker?.install(allowDexSearch = true) == true
        }
    }

    companion object {
        const val ID = "original_moments_upload"
    }
}

private class OriginalMomentsUploadHooker(
    private val context: FeatureContext
) {
    private val prefs = HchatStorage.preferences(context.hostContext(), OriginalMomentsUploadSettings.PREFS_NAME)
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)

    @Volatile
    private var imagePreviewInstalled = false

    @Volatile
    private var createPicInstalled = false

    @Volatile
    private var convertImg2WxamMethod: Method? = null

    @Synchronized
    fun install(allowDexSearch: Boolean): Boolean {
        locateSnsConvertImgMethod(allowDexSearch)?.let { convertImg2WxamMethod = it }
        if (!imagePreviewInstalled) {
            locateImagePreviewSendMethod(allowDexSearch)?.let { method ->
                try {
                    HookRegistry.get().hook(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isEnabled()) return
                            patchGalleryTimelineArgs(param.args)
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isEnabled()) return
                            patchGalleryResultIntent(param.args)
                        }
                    })
                    imagePreviewInstalled = true
                } catch (e: Throwable) {
                    HLog.e("$TAG 安装图库返回 Hook 失败: ${e.message}", e)
                }
            }
        }
        if (!createPicInstalled) {
            locateSnsCreatePicMethod(allowDexSearch)?.let { method ->
                try {
                    HookRegistry.get().hook(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isEnabled()) return
                            bypassImageReencode(param)
                        }
                    })
                    createPicInstalled = true
                } catch (e: Throwable) {
                    HLog.e("$TAG 安装朋友圈图片压缩 Hook 失败: ${e.message}", e)
                }
            }
        }
        return imagePreviewInstalled && createPicInstalled && convertImg2WxamMethod != null
    }

    private fun patchGalleryTimelineArgs(args: Array<Any?>?) {
        if (args == null || args.size < 3) return
        if (args[0] !is Intent) return
        if (args[1] == true) {
            args[1] = false
            args[2] = false
        }
        (args[0] as? Intent)?.putExtra("CropImage_Compress_Img", false)
    }

    private fun patchGalleryResultIntent(args: Array<Any?>?) {
        val intent = args?.firstOrNull() as? Intent ?: return
        intent.putExtra("CropImage_Compress_Img", false)
        intent.putExtra("key_delete_origin_file", false)
    }

    private fun bypassImageReencode(param: XC_MethodHook.MethodHookParam) {
        val args = param.args ?: return
        if (args.size < 3) return
        val dir = args[0] as? String ?: return
        val sourcePath = args[1] as? String ?: return
        val targetName = args[2] as? String ?: return
        if (dir.isBlank() || sourcePath.isBlank() || targetName.isBlank()) return
        if (convertWithoutZip(sourcePath, dir + targetName)) {
            param.result = true
            return
        }
        if (copyWithWechatVfs(dir, sourcePath, targetName)) {
            param.result = true
        }
    }

    private fun convertWithoutZip(sourcePath: String, targetPath: String): Boolean {
        return runCatching {
            val method = convertImg2WxamMethod ?: return false
            KavaReflector.invoke(method, null, sourcePath, targetPath) == true
        }.getOrElse {
            HLog.e("$TAG 原图无压缩转换失败: ${it.message}", it)
            false
        }
    }

    private fun copyWithWechatVfs(dir: String, sourcePath: String, targetName: String): Boolean {
        return runCatching {
            val loader = context.hostClassLoader()
            val vfs = KavaReflector.loadClass("com.tencent.mm.vfs.w6", loader) ?: return false
            val exists = KavaReflector.findDeclaredMethod(vfs, "j", String::class.java)
            val mkdirs = KavaReflector.findDeclaredMethod(vfs, "u", String::class.java)
            val copy = KavaReflector.findDeclaredMethod(vfs, "c", String::class.java, String::class.java)
            val size = KavaReflector.findDeclaredMethod(vfs, "k", String::class.java)
            if (exists == null || mkdirs == null || copy == null || size == null) return false
            if (KavaReflector.invoke(exists, null, sourcePath) != true) return false
            KavaReflector.invoke(mkdirs, null, dir)
            val targetPath = dir + targetName
            KavaReflector.invoke(copy, null, sourcePath, targetPath)
            val copiedSize = (KavaReflector.invoke(size, null, targetPath) as? Number)?.toLong() ?: 0L
            copiedSize > 0L
        }.getOrElse {
            HLog.e("$TAG 原图复制失败: ${it.message}", it)
            false
        }
    }

    private fun locateImagePreviewSendMethod(allowDexSearch: Boolean): Method? {
        val cacheKey = methodCacheKey()
        DexMethodCache.load(methodPrefs, cacheKey, context.hostClassLoader(), CACHE_IMAGE_PREVIEW)
            ?.takeIf { isImagePreviewSendMethod(it) }
            ?.let { return it }
        if (!allowDexSearch) return null
        val method = findMethodsByStrings("CropImage_OutputPath_List", "key_select_video_list")
            .firstOrNull { isImagePreviewSendMethod(it) }
        if (method != null) {
            DexMethodCache.save(methodPrefs, cacheKey, CACHE_IMAGE_PREVIEW, method)
        } else {
            DexMethodCache.clear(methodPrefs, cacheKey, CACHE_IMAGE_PREVIEW)
        }
        return method
    }

    private fun locateSnsCreatePicMethod(allowDexSearch: Boolean): Method? {
        val cacheKey = methodCacheKey()
        DexMethodCache.load(methodPrefs, cacheKey, context.hostClassLoader(), CACHE_CREATE_PIC)
            ?.takeIf { isSnsCreatePicMethod(it) }
            ?.let { return it }
        if (!allowDexSearch) return null
        val method = findMethodsByExactStrings(
            "MicroMsg.snsMediaStorage",
            "SnsCompressResolutionFor2G",
            "SnsCompressResolutionFor3G",
            "SnsCompressResolutionFor4G",
            "SnsCompressResolutionForWifi"
        )
            .firstOrNull { isSnsCreatePicMethod(it) }
        if (method != null) {
            DexMethodCache.save(methodPrefs, cacheKey, CACHE_CREATE_PIC, method)
        } else {
            DexMethodCache.clear(methodPrefs, cacheKey, CACHE_CREATE_PIC)
        }
        return method
    }

    private fun locateSnsConvertImgMethod(allowDexSearch: Boolean): Method? {
        val cacheKey = methodCacheKey()
        DexMethodCache.load(methodPrefs, cacheKey, context.hostClassLoader(), CACHE_CONVERT_IMG)
            ?.takeIf { isSnsConvertImgMethod(it) }
            ?.let { return it }
        if (!allowDexSearch) return null
        val method = findMethodsByExactStrings(
            "MicroMsg.snsMediaStorage",
            "convertImg2WxamWithoutZip origPath:%s OutOfMemoryError! rollback"
        ).firstOrNull { isSnsConvertImgMethod(it) }
        if (method != null) {
            DexMethodCache.save(methodPrefs, cacheKey, CACHE_CONVERT_IMG, method)
        } else {
            DexMethodCache.clear(methodPrefs, cacheKey, CACHE_CONVERT_IMG)
        }
        return method
    }

    private fun findMethodsByStrings(vararg strings: String): List<Method> {
        return runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingStrings(strings.toList())
                        }
                    )
                }
            ).mapNotNull { it.getMethodInstance(context.hostClassLoader()) }
        }.getOrElse {
            HLog.e("$TAG DexKit 定位失败(${strings.joinToString()}): ${it.message}", it)
            emptyList()
        }
    }

    private fun findMethodsByExactStrings(vararg strings: String): List<Method> {
        return runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingEqStrings(*strings)
                        }
                    )
                }
            ).mapNotNull { it.getMethodInstance(context.hostClassLoader()) }
        }.getOrElse {
            HLog.e("$TAG DexKit 精确定位失败(${strings.joinToString()}): ${it.message}", it)
            emptyList()
        }
    }

    private fun isImagePreviewSendMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == Void.TYPE &&
            method.declaringClass.name == "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI" &&
            types.size == 3 &&
            types[0] == Intent::class.java &&
            types[1] == java.lang.Boolean.TYPE &&
            types[2] == java.lang.Boolean.TYPE
    }

    private fun isSnsCreatePicMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == java.lang.Boolean.TYPE &&
            method.declaringClass.name.startsWith("com.tencent.mm.plugin.sns.storage.") &&
            types.size >= 4 &&
            types[0] == String::class.java &&
            types[1] == String::class.java &&
            types[2] == String::class.java &&
            types[3] == java.lang.Boolean.TYPE
    }

    private fun isSnsConvertImgMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return Modifier.isStatic(method.modifiers) &&
            method.returnType == java.lang.Boolean.TYPE &&
            method.declaringClass.name.startsWith("com.tencent.mm.plugin.sns.storage.") &&
            types.size == 2 &&
            types[0] == String::class.java &&
            types[1] == String::class.java
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
            .takeIf { it.isNotBlank() }
            ?.let { "$it|$CACHE_SCHEMA" }
            .orEmpty()
    }

    private fun isEnabled(): Boolean {
        return prefs.getBoolean(
            OriginalMomentsUploadSettings.KEY_ENABLE,
            OriginalMomentsUploadSettings.DEFAULT_ENABLE
        )
    }

    companion object {
        private const val TAG = "[Hchat:OriginalMomentsUpload]"
        private const val CACHE_PREFS = "Hchat_original_moments_upload_method_cache"
        private const val CACHE_SCHEMA = "original_moments_upload_v2"
        private const val CACHE_IMAGE_PREVIEW = "image_preview_send"
        private const val CACHE_CREATE_PIC = "sns_create_pic"
        private const val CACHE_CONVERT_IMG = "sns_convert_img_without_zip"
    }
}
