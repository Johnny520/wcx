package h.Hchat.hooks.api.sns

import android.content.Context
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class SnsLivePhotoUploadRuntime(
    val setUploadListMethod: Method,
    val elementConstructor: Constructor<*>,
    val liveElementField: Field,
    val thumbPathField: Field,
    val liveTypeField: Field,
    val coverTimeField: Field
)

internal object SnsLivePhotoUploadLocator {
    private const val PREFS_NAME = "Hchat_sns_live_photo_upload_cache"
    private const val CACHE_SET_UPLOAD_LIST = "set_upload_list_v1"
    private const val CACHE_ELEMENT_CLASS = "element_class_v1"

    fun locate(
        context: Context,
        classLoader: ClassLoader,
        dexKitBridge: DexKitBridge,
        helperClass: Class<*>?,
        logger: (String) -> Unit
    ): SnsLivePhotoUploadRuntime? {
        if (helperClass == null) return null
        val prefs = DexMethodCache.prefs(context, PREFS_NAME)
        val runtimeKey = DexMethodCache.runtimeKey(context, classLoader)
        val cachedMethod = DexMethodCache.load(
            prefs,
            runtimeKey,
            classLoader,
            CACHE_SET_UPLOAD_LIST
        )
        val cachedClass = KavaReflector.loadClass(
            prefs.getString(CACHE_ELEMENT_CLASS, ""),
            classLoader
        )
        buildRuntime(helperClass, cachedMethod, cachedClass)?.let { return it }

        DexMethodCache.clear(prefs, runtimeKey, CACHE_SET_UPLOAD_LIST)
        prefs.edit().remove(CACHE_ELEMENT_CLASS).apply()
        return runCatching {
            val candidates = dexKitBridge.findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        usingStrings(
                            listOf(
                                "setUploadList",
                                "livePhotoElement != null >> path:"
                            )
                        )
                    })
                }
            )
            for (methodData in candidates) {
                val method = runCatching {
                    methodData.getMethodInstance(classLoader)
                }.getOrNull() ?: continue
                if (!isSetUploadListMethod(helperClass, method)) continue
                val classNames = methodData.usingFields.mapNotNull { usingField ->
                    usingField.field?.className
                }.distinct()
                for (className in classNames) {
                    val elementClass = KavaReflector.loadClass(className, classLoader) ?: continue
                    val runtime = buildRuntime(helperClass, method, elementClass) ?: continue
                    DexMethodCache.save(prefs, runtimeKey, CACHE_SET_UPLOAD_LIST, method)
                    prefs.edit().putString(CACHE_ELEMENT_CLASS, elementClass.name).apply()
                    return@runCatching runtime
                }
            }
            null
        }.onFailure {
            logger("朋友圈实况上传方法定位失败: ${it.message}")
        }.getOrNull()
    }

    private fun buildRuntime(
        helperClass: Class<*>,
        method: Method?,
        elementClass: Class<*>?
    ): SnsLivePhotoUploadRuntime? {
        if (!isSetUploadListMethod(helperClass, method) || elementClass == null) return null
        val constructor = KavaReflector.findConstructor(
            elementClass,
            String::class.java,
            Integer.TYPE
        ) ?: return null
        val liveElementField = instanceFields(elementClass).singleOrNull { it.type == elementClass }
            ?: return null
        val thumbPathField = namedField(elementClass, "m", String::class.java) ?: return null
        val liveTypeField = namedField(elementClass, "p", Integer.TYPE) ?: return null
        val coverTimeField = namedField(elementClass, "t", java.lang.Long.TYPE) ?: return null
        return SnsLivePhotoUploadRuntime(
            setUploadListMethod = method ?: return null,
            elementConstructor = constructor,
            liveElementField = liveElementField,
            thumbPathField = thumbPathField,
            liveTypeField = liveTypeField,
            coverTimeField = coverTimeField
        )
    }

    private fun isSetUploadListMethod(helperClass: Class<*>, method: Method?): Boolean {
        if (method == null || method.declaringClass != helperClass) return false
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            types.size == 1 &&
            List::class.java.isAssignableFrom(types[0])
    }

    private fun namedField(clazz: Class<*>, name: String, type: Class<*>): Field? {
        return KavaReflector.findFieldRecursive(clazz, name)
            ?.takeIf { !Modifier.isStatic(it.modifiers) && it.type == type }
    }

    private fun instanceFields(clazz: Class<*>): List<Field> {
        val fields = ArrayList<Field>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredFields(current)
                .filterNot { Modifier.isStatic(it.modifiers) }
                .forEach(fields::add)
            current = current.superclass
        }
        return fields
    }
}
