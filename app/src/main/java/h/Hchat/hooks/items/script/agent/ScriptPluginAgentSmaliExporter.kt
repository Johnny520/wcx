package h.Hchat.hooks.items.script.agent

import android.util.AtomicFile
import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.Adaptors.MethodDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import org.json.JSONObject
import java.io.File
import java.io.StringWriter
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal class ScriptPluginAgentSmaliExporter(
    private val apkPath: String,
    cacheRoot: File
) {
    private val cacheFile = File(cacheRoot, "${pathHash(apkPath)}.json")
    private val classEntryCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_CACHED_CLASSES
        }
    }
    private var apkStamp = ""

    fun exportClass(
        value: String,
        arguments: JSONObject,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val descriptor = classDescriptor(value)
        val located = locateClass(descriptor, cancellation)
            ?: throw IllegalArgumentException("没有找到类: $descriptor")
        val smali = renderClass(located.dex, located.classDef)
        return chunkedResult(descriptor, located.entryName, smali, arguments)
    }

    fun exportMethod(
        descriptor: String,
        arguments: JSONObject,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        require(descriptor.contains("->")) { "export_method_smali 需要完整 descriptor" }
        val classDescriptor = classDescriptor(descriptor.substringBefore("->"))
        val normalizedDescriptor = classDescriptor + "->" + descriptor.substringAfter("->")
        val located = locateClass(classDescriptor, cancellation)
            ?: throw IllegalArgumentException("没有找到类: $classDescriptor")
        val method = located.classDef.methods.firstOrNull {
            methodDescriptor(it) == normalizedDescriptor
        } ?: throw IllegalArgumentException("没有找到方法: $normalizedDescriptor")
        val smali = renderMethod(located.dex, located.classDef, method)
        return chunkedResult(normalizedDescriptor, located.entryName, smali, arguments)
    }

    internal fun locateDexInput(
        value: String,
        cancellation: ScriptPluginAgentCancellation?
    ): LocatedDexInput {
        val descriptor = classDescriptor(value)
        val located = locateClass(descriptor, cancellation)
            ?: throw IllegalArgumentException("没有找到类: $descriptor")
        cancellation?.throwIfCancelled()
        val bytes = ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry(located.entryName)
                ?: throw IllegalStateException("Dex 条目不存在: ${located.entryName}")
            zip.getInputStream(entry).buffered().use { it.readBytes() }
        }
        return LocatedDexInput(descriptor, located.entryName, bytes)
    }

    internal fun locateClassEntries(
        values: Collection<String>,
        cancellation: ScriptPluginAgentCancellation?
    ): Map<String, String> {
        val descriptors = values.mapTo(LinkedHashSet()) { classDescriptor(it) }
        if (descriptors.isEmpty()) return emptyMap()
        refreshCacheIfApkChanged()
        val result = LinkedHashMap<String, String>()
        var cacheChanged = false
        ZipFile(apkPath).use { zip ->
            descriptors.forEach { descriptor ->
                classEntryCache[descriptor]?.let { entryName ->
                    if (zip.getEntry(entryName) != null) {
                        result[descriptor] = entryName
                    } else {
                        classEntryCache.remove(descriptor)
                        cacheChanged = true
                    }
                }
            }
            val unresolved = descriptors.filterTo(LinkedHashSet()) { it !in result }
            for (entry in dexEntries(zip)) {
                if (unresolved.isEmpty()) break
                cancellation?.throwIfCancelled()
                val dex = readDex(zip, entry)
                var scanned = 0
                for (classDef in dex.classes) {
                    if (unresolved.isEmpty()) break
                    if (scanned++ % 2_048 == 0) cancellation?.throwIfCancelled()
                    if (unresolved.remove(classDef.type)) {
                        result[classDef.type] = entry.name
                        classEntryCache[classDef.type] = entry.name
                        cacheChanged = true
                    }
                }
            }
        }
        if (cacheChanged) savePersistentCache()
        return result
    }

    private fun locateClass(
        descriptor: String,
        cancellation: ScriptPluginAgentCancellation?
    ): LocatedClass? {
        refreshCacheIfApkChanged()
        ZipFile(apkPath).use { zip ->
            classEntryCache[descriptor]?.let { entryName ->
                cancellation?.throwIfCancelled()
                zip.getEntry(entryName)?.let { entry ->
                    val dex = readDex(zip, entry)
                    dex.classes.firstOrNull { it.type == descriptor }?.let {
                        return LocatedClass(entryName, dex, it)
                    }
                }
                classEntryCache.remove(descriptor)
                savePersistentCache()
            }
            for (entry in dexEntries(zip)) {
                cancellation?.throwIfCancelled()
                val dex = readDex(zip, entry)
                val classDef = dex.classes.firstOrNull { it.type == descriptor } ?: continue
                classEntryCache[descriptor] = entry.name
                savePersistentCache()
                return LocatedClass(entry.name, dex, classDef)
            }
        }
        return null
    }

    private fun readDex(zip: ZipFile, entry: ZipEntry): DexBackedDexFile {
        return zip.getInputStream(entry).buffered().use {
            DexBackedDexFile.fromInputStream(null, it)
        }
    }

    private fun dexEntries(zip: ZipFile): List<ZipEntry> {
        return zip.entries().asSequence()
            .filter { !it.isDirectory && DEX_ENTRY.matches(it.name) }
            .sortedBy { dexIndex(it.name) }
            .toList()
    }

    private fun renderClass(dex: DexBackedDexFile, classDef: ClassDef): String {
        val output = StringWriter()
        val writer = BaksmaliWriter(output, classDef.type)
        ClassDefinition(options(dex), classDef).writeTo(writer)
        writer.flush()
        return output.toString()
    }

    private fun renderMethod(dex: DexBackedDexFile, classDef: ClassDef, method: Method): String {
        val output = StringWriter()
        val writer = BaksmaliWriter(output, classDef.type)
        val classDefinition = ClassDefinition(options(dex), classDef)
        val implementation = method.implementation
        if (implementation == null) {
            MethodDefinition.writeEmptyMethodTo(writer, method, classDefinition)
        } else {
            MethodDefinition(classDefinition, method, implementation).writeTo(writer)
        }
        writer.flush()
        return output.toString()
    }

    private fun options(dex: DexBackedDexFile): BaksmaliOptions {
        return BaksmaliOptions().apply {
            apiLevel = dex.opcodes.api
            debugInfo = true
            parameterRegisters = true
        }
    }

    private fun chunkedResult(
        descriptor: String,
        sourceEntry: String,
        smali: String,
        arguments: JSONObject
    ): String {
        val offset = arguments.optInt("offset", 0).coerceIn(0, smali.length)
        val maxChars = arguments.optInt("max_chars", DEFAULT_CHUNK_CHARS)
            .coerceIn(MIN_CHUNK_CHARS, MAX_CHUNK_CHARS)
        var end = (offset + maxChars).coerceAtMost(smali.length)
        if (end < smali.length) {
            val lineEnd = smali.lastIndexOf('\n', end - 1)
            if (lineEnd >= offset + MIN_CHUNK_CHARS) end = lineEnd + 1
        }
        return JSONObject().apply {
            put("ok", true)
            put("descriptor", descriptor)
            put("sourceEntry", sourceEntry)
            put("sourcePath", apkPath)
            put("offset", offset)
            put("returnedLength", end - offset)
            put("totalLength", smali.length)
            put("truncated", end < smali.length)
            if (end < smali.length) put("nextOffset", end)
            put("smali", smali.substring(offset, end))
        }.toString()
    }

    private fun refreshCacheIfApkChanged() {
        val file = File(apkPath)
        val current = "${file.length()}:${file.lastModified()}"
        if (current != apkStamp) {
            classEntryCache.clear()
            apkStamp = current
            loadPersistentCache(file)
        }
    }

    private fun loadPersistentCache(apk: File) {
        runCatching {
            val input = AtomicFile(cacheFile).openRead()
            val json = JSONObject(input.use { it.readBytes().toString(Charsets.UTF_8) })
            if (json.optInt("schema", 0) != CACHE_SCHEMA ||
                json.optString("apkPath", "") != apkPath ||
                json.optLong("apkLength", -1L) != apk.length() ||
                json.optLong("apkLastModified", -1L) != apk.lastModified()
            ) {
                AtomicFile(cacheFile).delete()
                return@runCatching
            }
            val entries = json.optJSONObject("entries") ?: return@runCatching
            entries.keys().forEach { descriptor ->
                val entryName = entries.optString(descriptor, "")
                if (descriptor.startsWith('L') && descriptor.endsWith(';') && DEX_ENTRY.matches(entryName)) {
                    classEntryCache[descriptor] = entryName
                }
            }
        }.onFailure { AtomicFile(cacheFile).delete() }
    }

    private fun savePersistentCache() {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val apk = File(apkPath)
            val json = JSONObject().apply {
                put("schema", CACHE_SCHEMA)
                put("apkPath", apkPath)
                put("apkLength", apk.length())
                put("apkLastModified", apk.lastModified())
                put("entries", JSONObject().apply {
                    classEntryCache.forEach { (descriptor, entryName) -> put(descriptor, entryName) }
                })
            }
            val atomicFile = AtomicFile(cacheFile)
            val output = atomicFile.startWrite()
            try {
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(output)
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                throw error
            }
            pruneOldCaches()
        }
    }

    private fun pruneOldCaches() {
        val files = cacheFile.parentFile?.listFiles { file ->
            file.isFile && file.extension == "json"
        }.orEmpty().sortedByDescending { it.lastModified() }
        files.drop(MAX_CACHE_FILES).forEach { it.delete() }
    }

    private fun classDescriptor(value: String): String {
        val clean = value.trim()
        require(clean.isNotBlank()) { "类 descriptor 为空" }
        return if (clean.startsWith('L') && clean.endsWith(';')) {
            clean
        } else {
            "L${clean.replace('.', '/').trimStart('L').trimEnd(';')};"
        }
    }

    private fun methodDescriptor(method: Method): String {
        return buildString {
            append(method.definingClass)
            append("->")
            append(method.name)
            append('(')
            method.parameters.forEach { append(it.type) }
            append(')')
            append(method.returnType)
        }
    }

    private fun dexIndex(name: String): Int {
        val suffix = name.removePrefix("classes").removeSuffix(".dex")
        return if (suffix.isBlank()) 1 else suffix.toIntOrNull() ?: Int.MAX_VALUE
    }

    private data class LocatedClass(
        val entryName: String,
        val dex: DexBackedDexFile,
        val classDef: ClassDef
    )

    internal data class LocatedDexInput(
        val descriptor: String,
        val sourceEntry: String,
        val bytes: ByteArray
    )

    private companion object {
        val DEX_ENTRY = Regex("classes(\\d*)\\.dex")
        const val CACHE_SCHEMA = 1
        const val MAX_CACHED_CLASSES = 2_048
        const val MAX_CACHE_FILES = 8
        const val MIN_CHUNK_CHARS = 1_000
        const val DEFAULT_CHUNK_CHARS = 24_000
        const val MAX_CHUNK_CHARS = 120_000

        fun pathHash(value: String): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                .take(24)
        }
    }
}
