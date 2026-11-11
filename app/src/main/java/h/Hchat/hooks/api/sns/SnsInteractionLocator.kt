package h.Hchat.hooks.api.sns

import android.content.Context
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.matchers.ClassMatcher
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class SnsInteractionLocator(
    private val context: Context,
    private val classLoader: ClassLoader,
    private val dexKitBridge: DexKitBridge?,
    private val logger: (String) -> Unit
) {
    private val prefs = DexMethodCache.prefs(context, PREFS_NAME)

    @Volatile
    private var likeMethod: Method? = null

    @Volatile
    private var commentMethod: Method? = null

    @Volatile
    private var commentGuardMethods: List<Method> = emptyList()

    @Volatile
    private var refreshConstructor: Constructor<*>? = null

    @Synchronized
    fun nativeLikeMethod(): Method? {
        likeMethod?.takeIf(::isNativeLikeMethod)?.let { return it }
        val runtimeKey = DexMethodCache.runtimeKey(context, classLoader)
        DexMethodCache.load(prefs, runtimeKey, classLoader, CACHE_LIKE)
            ?.takeIf(::isNativeLikeMethod)
            ?.let {
                likeMethod = it
                return it
            }
        val bridge = dexKitBridge ?: return null
        return runCatching {
            bridge.findClass(
                FindClass().apply {
                    matcher(
                        ClassMatcher().apply {
                            usingEqStrings("MicroMsg.SnsService", "can not add Comment")
                        }
                    )
                }
            ).asSequence()
                .mapNotNull { KavaReflector.loadClass(it.name, classLoader) }
                .flatMap { KavaReflector.declaredMethods(it).asSequence() }
                .firstOrNull(::isNativeLikeMethod)
                ?.also {
                    likeMethod = it
                    DexMethodCache.save(prefs, runtimeKey, CACHE_LIKE, it)
                }
        }.onFailure {
            logger("定位朋友圈原生点赞方法失败: ${it.message}")
        }.getOrNull()
    }

    @Synchronized
    fun nativeCommentMethod(): Method? {
        commentMethod?.takeIf(::isNativeCommentMethod)?.let { return it }
        val runtimeKey = DexMethodCache.runtimeKey(context, classLoader)
        DexMethodCache.load(prefs, runtimeKey, classLoader, CACHE_COMMENT)
            ?.takeIf(::isNativeCommentMethod)
            ?.let {
                commentMethod = it
                return it
            }
        val bridge = dexKitBridge ?: return null
        return runCatching {
            bridge.findClass(
                FindClass().apply {
                    matcher(
                        ClassMatcher().apply {
                            usingEqStrings("MicroMsg.SnsService", "can not add Comment")
                        }
                    )
                }
            ).asSequence()
                .mapNotNull { KavaReflector.loadClass(it.name, classLoader) }
                .flatMap { KavaReflector.declaredMethods(it).asSequence() }
                .firstOrNull(::isNativeCommentMethod)
                ?.also {
                    commentMethod = it
                    DexMethodCache.save(prefs, runtimeKey, CACHE_COMMENT, it)
                }
        }.onFailure {
            logger("定位朋友圈原生评论方法失败: ${it.message}")
        }.getOrNull()
    }

    fun interactionNodeClass(): Class<*>? {
        return nativeCommentMethod()?.returnType?.takeUnless {
            it == Void.TYPE || it.isPrimitive || it == Any::class.java
        }
    }

    @Synchronized
    fun nativeCommentGuardMethods(): List<Method> {
        commentGuardMethods.takeIf { methods ->
            hasCompleteCommentGuardSet(methods)
        }?.let { return it }
        val runtimeKey = DexMethodCache.runtimeKey(context, classLoader)
        DexMethodCache.loadList(prefs, runtimeKey, classLoader, CACHE_COMMENT_GUARDS)
            .filter(::isNativeCommentGuardMethod)
            .distinctBy { it.toGenericString() }
            .takeIf(::hasCompleteCommentGuardSet)
            ?.let {
                commentGuardMethods = it
                return it
            }
        val owner = nativeCommentMethod()?.declaringClass ?: return emptyList()
        val methods = KavaReflector.declaredMethods(owner)
            .filter(::isNativeCommentGuardMethod)
            .distinctBy { it.toGenericString() }
        if (!hasCompleteCommentGuardSet(methods)) {
            DexMethodCache.clear(prefs, runtimeKey, CACHE_COMMENT_GUARDS)
            logger("朋友圈评论保护入口不完整: count=${methods.size}")
            commentGuardMethods = emptyList()
            return emptyList()
        }
        DexMethodCache.saveList(prefs, runtimeKey, CACHE_COMMENT_GUARDS, methods)
        commentGuardMethods = methods
        return methods
    }

    @Synchronized
    fun timelineRefreshConstructor(): Constructor<*>? {
        refreshConstructor?.takeIf(::isTimelineRefreshConstructor)?.let { return it }
        val runtimeKey = DexMethodCache.runtimeKey(context, classLoader)
        DexMethodCache.loadConstructor(prefs, runtimeKey, classLoader, CACHE_REFRESH)
            ?.takeIf(::isTimelineRefreshConstructor)
            ?.let {
                refreshConstructor = it
                return it
            }
        val bridge = dexKitBridge ?: return null
        return runCatching {
            bridge.findClass(
                FindClass().apply {
                    matcher(
                        ClassMatcher().apply {
                            usingEqStrings("MicroMsg.NetSceneSnsTimeLine")
                        }
                    )
                }
            ).asSequence()
                .mapNotNull { KavaReflector.loadClass(it.name, classLoader) }
                .flatMap { KavaReflector.declaredConstructors(it).asSequence() }
                .firstOrNull(::isTimelineRefreshConstructor)
                ?.also {
                    refreshConstructor = it
                    DexMethodCache.saveConstructor(prefs, runtimeKey, CACHE_REFRESH, it)
                }
        }.onFailure {
            logger("定位朋友圈原生刷新请求失败: ${it.message}")
        }.getOrNull()
    }

    private fun isNativeLikeMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return Modifier.isStatic(method.modifiers) &&
            method.returnType != Void.TYPE &&
            types.size == 4 &&
            types[0].name == SNS_INFO_CLASS &&
            types[1] == Integer.TYPE &&
            !types[2].isPrimitive &&
            types[3] == Integer.TYPE
    }

    private fun isNativeCommentMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return Modifier.isStatic(method.modifiers) &&
            method.returnType != Void.TYPE &&
            types.size == 7 &&
            types[0].name == SNS_INFO_CLASS &&
            types[1] == Integer.TYPE &&
            types[2] == String::class.java &&
            types[3] == java.lang.Long.TYPE &&
            types[4] == String::class.java &&
            types[5] == java.lang.Boolean.TYPE &&
            types[6] == Integer.TYPE
    }

    private fun isNativeCommentGuardMethod(method: Method): Boolean {
        return isNativeCommentMethod(method) || isNativeCommentNodeMethod(method)
    }

    private fun isNativeCommentNodeMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return Modifier.isStatic(method.modifiers) &&
            method.returnType != Void.TYPE &&
            (types.size == 7 || types.size == 8) &&
            types[0].name == SNS_INFO_CLASS &&
            types[1] == Integer.TYPE &&
            !types[2].isPrimitive &&
            !types[3].isPrimitive &&
            types[3] == method.returnType &&
            types[4] == java.lang.Boolean.TYPE &&
            types[5] == Integer.TYPE &&
            types[6] == Integer.TYPE &&
            (types.size == 7 || types[7] == String::class.java)
    }

    private fun hasCompleteCommentGuardSet(methods: List<Method>): Boolean {
        return methods.any(::isNativeCommentMethod) && methods.any(::isNativeCommentNodeMethod)
    }

    private fun isTimelineRefreshConstructor(constructor: Constructor<*>): Boolean {
        val types = constructor.parameterTypes
        return types.size == 3 &&
            types[0] == java.lang.Long.TYPE &&
            types[1] == java.lang.Long.TYPE &&
            types[2] == Integer.TYPE
    }

    companion object {
        const val SNS_INFO_CLASS = "com.tencent.mm.plugin.sns.storage.SnsInfo"

        private const val PREFS_NAME = "Hchat_sns_interaction_method_cache"
        private const val CACHE_LIKE = "native_like_v1"
        private const val CACHE_COMMENT = "native_comment_v1"
        private const val CACHE_COMMENT_GUARDS = "native_comment_guards_v2"
        private const val CACHE_REFRESH = "timeline_refresh_v1"
    }
}
