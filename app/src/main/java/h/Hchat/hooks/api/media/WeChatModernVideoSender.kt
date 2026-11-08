package h.Hchat.hooks.api.media

import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Newer WeChat video pipeline. Discovery is fail-closed and runs through the shared DexKit gate. */
internal class WeChatModernVideoSender(
    private val context: FeatureContext,
    private val legacy: WeChatVideoApi
) {
    private val bridge = context.dexBridgeHolder()
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
    private var bindings: Bindings? = null

    @Synchronized
    fun prepare(): Boolean {
        if (bindings != null) return true
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        if (runtimeKey.isBlank()) return false
        loadBindings(runtimeKey)?.let {
            bindings = it
            return true
        }

        clearCache(runtimeKey)
        var discovered: Bindings? = null
        runCatching {
            DexInstallScheduler.runDexKitTask { discovered = discover() }
        }
        val result = discovered ?: return false
        saveCache(runtimeKey, result)
        bindings = result
        return true
    }

    /** True indicates enqueue only, not delivery. Keep the source until the host finishes copying it. */
    fun send(talker: String, path: String): Boolean {
        if (talker.isBlank() || !File(path).isFile || !prepare()) return false
        val api = bindings ?: return false
        return runCatching {
            val thumb = legacy.generateThumb(path, File(path).parentFile)
            if (thumb.isNullOrBlank() || !File(thumb).isFile) return false
            val filename = api.filename.invoke(null, talker) as? String
            if (filename.isNullOrBlank()) return false
            val element = api.element.newInstance(filename, path, thumb, false, 0, null, null, 0x60, null)
            val option = api.option.newInstance(api.gallery)
            val params = api.params.newInstance(talker, element, false, option)
            val task = api.task.newInstance(params)
            api.submit.invoke(api.owner, task)
            true
        }.getOrDefault(false)
    }

    private fun loadBindings(runtimeKey: String): Bindings? {
        val loader = context.hostClassLoader()
        val params = DexMethodCache.loadConstructor(methodPrefs, runtimeKey, loader, CACHE_PARAMS) ?: return null
        val task = DexMethodCache.loadConstructor(methodPrefs, runtimeKey, loader, CACHE_TASK) ?: return null
        val submit = DexMethodCache.load(methodPrefs, runtimeKey, loader, CACHE_SUBMIT) ?: return null
        val filename = DexMethodCache.load(methodPrefs, runtimeKey, loader, CACHE_FILENAME) ?: return null
        return createBindings(params, task, submit, filename)
    }

    private fun discover(): Bindings? {
        val loader = context.hostClassLoader()
        fun load(name: String) = runCatching { loader.loadClass(name) }.getOrNull()
        val paramsClasses = bridge.findClassesByStrings("VideoSendParams(toUser='")
            .mapNotNull(::load).distinct().mapNotNull { cls ->
                cls.declaredConstructors.singleOrNull { constructor ->
                    val p = constructor.parameterTypes
                    p.size == 4 && p[0] == String::class.java && p[2] == Boolean::class.javaPrimitiveType
                }
            }
        if (paramsClasses.size != 1) return null
        val params = paramsClasses.single()
        val paramsClass = params.declaringClass
        val taskConstructors = bridge.findClassesByStrings(
            "MicroMsg.VideoMsg.SendVideoTask", "copy video file failed.target file path"
        ).mapNotNull(::load).distinct().flatMap { cls ->
            cls.declaredConstructors.filter { it.parameterTypes.contentEquals(arrayOf(paramsClass)) }
        }
        if (taskConstructors.size != 1) return null
        val task = taskConstructors.single()
        val taskClass = task.declaringClass
        val submits = bridge.findMethodsByStrings("MicroMsg.video.VideoSendFeatureServicesendAsync only support SendVideoTask Type")
            .flatMap { info ->
                load(info.className)?.declaredMethods?.filter { method ->
                    method.name == info.methodName && method.parameterCount == 1 &&
                        method.parameterTypes[0].isAssignableFrom(taskClass) && !Modifier.isStatic(method.modifiers)
                }.orEmpty()
            }.distinctBy { it.toGenericString() }
        if (submits.size != 1) return null
        val filenames = bridge.findMethodsByStrings("yyMMddHHmmss", "@")
            .flatMap { info ->
                load(info.className)?.declaredMethods?.filter { method ->
                    method.name == info.methodName && Modifier.isStatic(method.modifiers) &&
                        method.returnType == String::class.java &&
                        method.parameterTypes.contentEquals(arrayOf(String::class.java))
                }.orEmpty()
            }.distinctBy { it.toGenericString() }
        if (filenames.size != 1) return null
        return createBindings(params, task, submits.single(), filenames.single())
    }

    private fun createBindings(
        params: Constructor<*>,
        task: Constructor<*>,
        submit: Method,
        filename: Method
    ): Bindings? {
        val paramsTypes = params.parameterTypes
        if (paramsTypes.size != 4 || paramsTypes[0] != String::class.java ||
            paramsTypes[2] != Boolean::class.javaPrimitiveType
        ) return null
        val element = paramsTypes[1].declaredConstructors.singleOrNull { constructor ->
            val p = constructor.parameterTypes
            p.size == 9 && p[0] == String::class.java && p[1] == String::class.java &&
                p[2] == String::class.java && p[3] == Boolean::class.javaPrimitiveType &&
                p[4] == Int::class.javaPrimitiveType && p[7] == Int::class.javaPrimitiveType
        } ?: return null
        val option = paramsTypes[3].declaredConstructors.singleOrNull {
            it.parameterCount == 1 && it.parameterTypes[0].isEnum
        } ?: return null
        val gallery = option.parameterTypes[0].enumConstants?.singleOrNull {
            (it as? Enum<*>)?.name == "FromGallery"
        } ?: return null
        val taskClass = task.declaringClass
        if (!task.parameterTypes.contentEquals(arrayOf(params.declaringClass)) ||
            submit.parameterCount != 1 || !submit.parameterTypes[0].isAssignableFrom(taskClass) ||
            Modifier.isStatic(submit.modifiers)
        ) return null
        val owners = submit.declaringClass.declaredFields.filter {
            Modifier.isStatic(it.modifiers) && it.type == submit.declaringClass
        }
        if (owners.size != 1) return null
        if (!Modifier.isStatic(filename.modifiers) || filename.returnType != String::class.java ||
            !filename.parameterTypes.contentEquals(arrayOf(String::class.java))
        ) return null
        val owner = owners.single().apply { isAccessible = true }.get(null) ?: return null
        return Bindings(params, element, option, gallery, task, submit, owner, filename).also {
            listOf(it.params, it.element, it.option, it.task).forEach { constructor -> constructor.isAccessible = true }
            it.submit.isAccessible = true
            it.filename.isAccessible = true
        }
    }

    private fun saveCache(runtimeKey: String, value: Bindings) {
        DexMethodCache.saveConstructor(methodPrefs, runtimeKey, CACHE_PARAMS, value.params)
        DexMethodCache.saveConstructor(methodPrefs, runtimeKey, CACHE_TASK, value.task)
        DexMethodCache.save(methodPrefs, runtimeKey, CACHE_SUBMIT, value.submit)
        DexMethodCache.save(methodPrefs, runtimeKey, CACHE_FILENAME, value.filename)
    }

    private fun clearCache(runtimeKey: String) {
        DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_PARAMS)
        DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_TASK)
        DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_SUBMIT)
        DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_FILENAME)
    }

    private data class Bindings(
        val params: Constructor<*>, val element: Constructor<*>, val option: Constructor<*>, val gallery: Any,
        val task: Constructor<*>, val submit: Method, val owner: Any, val filename: Method
    )

    companion object {
        private const val CACHE_PREFS = "Hchat_modern_video_sender_method_cache"
        private const val CACHE_PARAMS = "video_send_params_v1"
        private const val CACHE_TASK = "video_send_task_v1"
        private const val CACHE_SUBMIT = "video_send_submit_v1"
        private const val CACHE_FILENAME = "video_send_filename_v1"
    }
}
