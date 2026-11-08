package h.Hchat.hooks.api.sns

import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.FeatureContext
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class SnsForwardNativeMediaMethods(
    val imageManager: Method?,
    val imageDownload: Method?,
    val videoService: Method?,
    val videoDownload: Method?,
    val videoFullPath: Method?,
    val videoFinishedPath: Method?,
    val videoThumbPath: Method?
)

internal object SnsForwardNativeMediaLocator {
    private const val PREFS_NAME = "Hchat_sns_forward_native_media_method_cache"
    private const val SNS_MODEL_PACKAGE = "com.tencent.mm.plugin.sns.model."

    fun locate(
        context: FeatureContext,
        logger: (String, Throwable?) -> Unit
    ): SnsForwardNativeMediaMethods {
        return SnsForwardNativeMediaMethods(
            imageManager = locateMethod(
                context,
                "image_manager_v1",
                listOf("getSnsDownManager", "com.tencent.mm.plugin.sns.model.SnsCore"),
                ::isManagerGetter,
                logger
            ),
            imageDownload = locateMethod(
                context,
                "image_download_v1",
                listOf("addDownLoadSns", "com.tencent.mm.plugin.sns.model.DownloadManager"),
                ::isImageDownload,
                logger
            ),
            videoService = locateMethod(
                context,
                "video_service_v1",
                listOf("getSnsVideoService", "com.tencent.mm.plugin.sns.model.SnsCore"),
                ::isManagerGetter,
                logger
            ),
            videoDownload = locateMethod(
                context,
                "video_download_v1",
                listOf("addSnsVideoTask", "com.tencent.mm.plugin.sns.model.SnsVideoService"),
                ::isVideoDownload,
                logger
            ),
            videoFullPath = locateMethod(
                context,
                "video_full_path_v1",
                listOf("getSnsVideoFullPath", "getSnsVideoFullPath have flag %s, %s >>"),
                ::isVideoStatusPath,
                logger
            ),
            videoFinishedPath = locateMethod(
                context,
                "video_finished_path_v1",
                listOf("isDownloadFinish", "it don't download video[%s] finish. file[%b], return null."),
                ::isVideoStatusPath,
                logger
            ),
            videoThumbPath = locateMethod(
                context,
                "video_thumb_path_v1",
                listOf("getSnsVideoThumbImagePath"),
                ::isVideoMediaPath,
                logger
            )
        )
    }

    private fun locateMethod(
        context: FeatureContext,
        cacheName: String,
        anchors: List<String>,
        validator: (Method) -> Boolean,
        logger: (String, Throwable?) -> Unit
    ): Method? {
        val prefs = DexMethodCache.prefs(context.hostContext(), PREFS_NAME)
        val key = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        DexMethodCache.load(prefs, key, context.hostClassLoader(), cacheName)
            ?.takeIf(validator)
            ?.let { return it }
        val method = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply { usingStrings(anchors) })
                }
            ).asSequence()
                .mapNotNull { data ->
                    runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                }
                .firstOrNull(validator)
        }.onFailure {
            logger("定位朋友圈原生媒体方法失败: ${anchors.firstOrNull().orEmpty()}", it)
        }.getOrNull()
        if (method == null) {
            DexMethodCache.clear(prefs, key, cacheName)
        } else {
            DexMethodCache.save(prefs, key, cacheName, method)
        }
        return method
    }

    private fun isManagerGetter(method: Method): Boolean {
        return Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.isEmpty() &&
            !method.returnType.isPrimitive &&
            method.declaringClass.name.startsWith(SNS_MODEL_PACKAGE)
    }

    private fun isImageDownload(method: Method): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) &&
            method.returnType == Boolean::class.javaPrimitiveType &&
            method.declaringClass.name.startsWith(SNS_MODEL_PACKAGE) &&
            types.size == 4 &&
            !types[0].isPrimitive &&
            types[1] == Integer.TYPE &&
            !types[2].isPrimitive &&
            !types[3].isPrimitive
    }

    private fun isVideoDownload(method: Method): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) &&
            method.returnType == Boolean::class.javaPrimitiveType &&
            method.declaringClass.name.startsWith(SNS_MODEL_PACKAGE) &&
            types.size == 7 &&
            !types[0].isPrimitive &&
            types[1] == Integer.TYPE &&
            types[2] == String::class.java &&
            types[3] == Boolean::class.javaPrimitiveType &&
            types[4] == Boolean::class.javaPrimitiveType &&
            types[5] == Integer.TYPE &&
            types[6] == String::class.java
    }

    private fun isVideoStatusPath(method: Method): Boolean {
        val types = method.parameterTypes
        return isStaticStringMethod(method) &&
            method.declaringClass.name.startsWith(SNS_MODEL_PACKAGE) &&
            types.size == 2 &&
            types[0] == String::class.java &&
            !types[1].isPrimitive
    }

    private fun isVideoMediaPath(method: Method): Boolean {
        val types = method.parameterTypes
        return isStaticStringMethod(method) &&
            method.declaringClass.name.startsWith(SNS_MODEL_PACKAGE) &&
            types.size == 1 &&
            !types[0].isPrimitive
    }

    private fun isStaticStringMethod(method: Method): Boolean {
        return Modifier.isStatic(method.modifiers) && method.returnType == String::class.java
    }
}
