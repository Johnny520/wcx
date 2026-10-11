package h.Hchat.hooks.items.script.agent

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.impl.NoOpCodeCache
import jadx.api.plugins.JadxPlugin
import jadx.api.plugins.loader.JadxPluginLoader
import jadx.api.usage.impl.EmptyUsageInfoCache
import jadx.plugins.input.dex.DexInputPlugin
import org.json.JSONObject

internal class ScriptPluginAgentJavaExporter(
    private val apkPath: String,
    private val smaliExporter: ScriptPluginAgentSmaliExporter
) {
    fun exportClass(
        value: String,
        arguments: JSONObject,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val input = smaliExporter.locateDexInput(value, cancellation)
        val code = decompile(input, cancellation) { javaClass -> javaClass.code }
        return chunkedResult(input.descriptor, input.sourceEntry, code, arguments)
    }

    fun exportMethod(
        descriptor: String,
        arguments: JSONObject,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        require(descriptor.contains("->")) { "export_method_java 需要完整 descriptor" }
        val input = smaliExporter.locateDexInput(descriptor.substringBefore("->"), cancellation)
        val shortId = descriptor.substringAfter("->")
        val normalizedDescriptor = "${input.descriptor}->$shortId"
        val code = decompile(input, cancellation) { javaClass ->
            javaClass.code
            val method = javaClass.searchMethodByShortId(shortId)
                ?: javaClass.methods.firstOrNull {
                    it.methodNode.methodInfo.shortId == shortId
                }
                ?: throw IllegalArgumentException("没有找到方法: $normalizedDescriptor")
            method.codeStr.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("方法没有可导出的 Java 代码: $normalizedDescriptor")
        }
        return chunkedResult(normalizedDescriptor, input.sourceEntry, code, arguments)
    }

    private fun decompile(
        input: ScriptPluginAgentSmaliExporter.LocatedDexInput,
        cancellation: ScriptPluginAgentCancellation?,
        readCode: (jadx.api.JavaClass) -> String
    ): String {
        cancellation?.throwIfCancelled()
        val className = input.descriptor
            .removePrefix("L")
            .removeSuffix(";")
            .replace('/', '.')
        val args = JadxArgs().apply {
            threadsCount = 1
            setSkipResources(true)
            setSkipFilesSave(true)
            setDeobfuscationOn(false)
            setIncludeDependencies(false)
            setMoveInnerClasses(false)
            setInlineAnonymousClasses(false)
            setShowInconsistentCode(true)
            setReplaceConsts(false)
            setLoadJadxClsSetFile(false)
            codeCache = NoOpCodeCache.INSTANCE
            usageInfoCache = EmptyUsageInfoCache()
            classFilter = java.util.function.Predicate { it == className }
            pluginLoader = object : JadxPluginLoader {
                override fun load(): List<JadxPlugin> = emptyList()
                override fun close() = Unit
            }
        }
        val codeLoader = DexInputPlugin().loadDex(input.bytes, input.sourceEntry)
        return JadxDecompiler(args).use { decompiler ->
            decompiler.addCustomCodeLoader(codeLoader)
            decompiler.load()
            cancellation?.throwIfCancelled()
            val javaClass = decompiler.searchJavaClassByOrigFullName(className)
                ?: throw IllegalArgumentException("JADX 没有找到类: ${input.descriptor}")
            readCode(javaClass).also { cancellation?.throwIfCancelled() }
        }
    }

    private fun chunkedResult(
        descriptor: String,
        sourceEntry: String,
        java: String,
        arguments: JSONObject
    ): String {
        val offset = arguments.optInt("offset", 0).coerceIn(0, java.length)
        val maxChars = arguments.optInt("max_chars", DEFAULT_CHUNK_CHARS)
            .coerceIn(MIN_CHUNK_CHARS, MAX_CHUNK_CHARS)
        var end = (offset + maxChars).coerceAtMost(java.length)
        if (end < java.length) {
            val lineEnd = java.lastIndexOf('\n', end - 1)
            if (lineEnd >= offset + MIN_CHUNK_CHARS) end = lineEnd + 1
        }
        return JSONObject().apply {
            put("ok", true)
            put("descriptor", descriptor)
            put("sourceEntry", sourceEntry)
            put("sourcePath", apkPath)
            put("offset", offset)
            put("returnedLength", end - offset)
            put("totalLength", java.length)
            put("truncated", end < java.length)
            if (end < java.length) put("nextOffset", end)
            put("java", java.substring(offset, end))
        }.toString()
    }

    private companion object {
        const val MIN_CHUNK_CHARS = 1_000
        const val DEFAULT_CHUNK_CHARS = 24_000
        const val MAX_CHUNK_CHARS = 48_000
    }
}
