package h.Hchat.hooks.api.sns

import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.FeatureContext
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class SnsForwardLocalMediaMethods(
    val snsRoot: Method?,
    val mediaDirectory: Method?,
    val bigImageName: Method?,
    val videoPath: Method?
)

internal object SnsForwardLocalMediaLocator {
    private const val PREFS_NAME = "Hchat_sns_forward_local_media_method_cache"
    private const val CACHE_ROOT = "sns_root_v1"
    private const val CACHE_DIRECTORY = "media_directory_v1"
    private const val CACHE_BIG_IMAGE = "big_image_name_v1"
    private const val CACHE_VIDEO = "video_path_v1"
    private const val SNS_MODEL_PACKAGE = "com.tencent.mm.plugin.sns.model."

    fun locate(
        context: FeatureContext,
        logger: (String, Throwable?) -> Unit
    ): SnsForwardLocalMediaMethods {
        return SnsForwardLocalMediaMethods(
            snsRoot = locateMethod(
                context,
                CACHE_ROOT,
                listOf("getAccSnsPath", "com.tencent.mm.plugin.sns.model.SnsCore"),
                ::isSnsRootMethod,
                logger
            ),
            mediaDirectory = locateMethod(
                context,
                CACHE_DIRECTORY,
                listOf("getMediaFilePath", "com.tencent.mm.plugin.sns.model.SnsPathHelper"),
                ::isMediaDirectoryMethod,
                logger
            ),
            bigImageName = locateMethod(
                context,
                CACHE_BIG_IMAGE,
                listOf("getSnsBigName", "snsb_"),
                ::isBigImageNameMethod,
                logger
            ),
            videoPath = locateMethod(
                context,
                CACHE_VIDEO,
                listOf(
                    "MicroMsg.SnsVideoLogic",
                    "getSnsVideoPath",
                    "com.tencent.mm.plugin.sns.model.SnsVideoLogic"
                ),
                ::isVideoPathMethod,
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
            logger("定位朋友圈本地媒体方法失败: ${anchors.firstOrNull().orEmpty()}", it)
        }.getOrNull()
        if (method == null) {
            DexMethodCache.clear(prefs, key, cacheName)
        } else {
            DexMethodCache.save(prefs, key, cacheName, method)
        }
        return method
    }

    private fun isSnsRootMethod(method: Method): Boolean {
        return isStaticStringMethod(method) &&
            method.parameterTypes.isEmpty() &&
            method.declaringClass.name.startsWith(SNS_MODEL_PACKAGE)
    }

    private fun isMediaDirectoryMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return isStaticStringMethod(method) &&
            method.declaringClass.name.startsWith(SNS_MODEL_PACKAGE) &&
            types.size == 2 &&
            types[0] == String::class.java &&
            types[1] == String::class.java
    }

    private fun isBigImageNameMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return isStaticStringMethod(method) &&
            types.size == 1 &&
            !types[0].isPrimitive &&
            types[0] != String::class.java
    }

    private fun isVideoPathMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return isStaticStringMethod(method) &&
            method.declaringClass.name.startsWith(SNS_MODEL_PACKAGE) &&
            types.size == 1 &&
            !types[0].isPrimitive &&
            types[0] != String::class.java
    }

    private fun isStaticStringMethod(method: Method): Boolean {
        return Modifier.isStatic(method.modifiers) && method.returnType == String::class.java
    }
}
