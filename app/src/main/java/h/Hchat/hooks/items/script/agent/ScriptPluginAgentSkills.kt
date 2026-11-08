package h.Hchat.hooks.items.script.agent

import android.content.Context
import android.net.Uri
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.zip.ZipInputStream

/** Skills contain instructions and reference files; this store never executes their scripts. */
object ScriptPluginAgentSkills {
    data class Skill(val id: String, val name: String, val description: String, val enabled: Boolean, val error: String = "")

    private fun store(context: Context) = ScriptPluginAgentSkillStore(
        File(ScriptPluginRuntime.scriptDir(context).parentFile!!.canonicalFile, "Agent/skills")
    )

    @Synchronized fun list(context: Context): List<Skill> = store(context).list()
    @Synchronized fun readDocument(context: Context, id: String): String = store(context).readDocument(id)
    @Synchronized fun saveDocument(context: Context, id: String?, content: String): Skill = store(context).saveDocument(id, content)
    @Synchronized fun setEnabled(context: Context, id: String, enabled: Boolean) = store(context).setEnabled(id, enabled)
    @Synchronized fun delete(context: Context, id: String) = store(context).delete(id)
    @Synchronized fun importFile(context: Context, uri: Uri): List<Skill> =
        (context.contentResolver.openInputStream(uri) ?: error("无法打开所选文件")).use { store(context).importStream(it) }
    @Synchronized fun catalog(context: Context): String = store(context).catalog()

    fun isKnownToolName(name: String) = name == "hchat.skills.list" || name == "hchat.skills.read"

    @Synchronized fun call(context: Context, name: String, args: JSONObject): String = store(context).call(name, args)

    fun toolCatalog(): String = JSONObject().put("tools", JSONArray().apply {
        put(JSONObject().put("name", "hchat.skills.list")
            .put("description", "分页列出用户安装的 Skills，包括启用状态和无效文档错误。使用技能前先读取其 SKILL.md。")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("offset", JSONObject().put("type", "integer").put("minimum", 0).put("description", "列表偏移，默认 0"))
                .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 50).put("description", "每页条数，默认 20，最多 50")))))
        put(JSONObject().put("name", "hchat.skills.read")
            .put("description", "按需读取已启用 Skill 的 SKILL.md 或附属文本；返回可读文件列表。scripts 仅作为文本，不执行。")
            .put("inputSchema", JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("skill_id", JSONObject().put("type", "string").put("description", "skills.list 或技能目录中的准确 ID"))
                    .put("path", JSONObject().put("type", "string").put("description", "技能内相对路径，默认 SKILL.md；可传目录列出文件"))
                    .put("offset", JSONObject().put("type", "integer").put("minimum", 0).put("description", "文本字符偏移，默认 0"))
                    .put("max_chars", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 24000).put("description", "本页最多字符数，默认 12000")))
                .put("required", JSONArray(listOf("skill_id")))))
    }).toString()
}

/** File-only implementation also used by JVM regression tests. */
internal class ScriptPluginAgentSkillStore(private val directory: File) {
    private fun root(create: Boolean = false): File {
        val value = directory.absoluteFile
        require(value == value.canonicalFile) { "技能目录不能使用符号链接" }
        if (create) check(value.isDirectory || value.mkdirs()) { "无法创建技能目录" }
        require(!value.exists() || value.isDirectory) { "技能路径不是目录" }
        return value
    }

    private fun skillDirectory(id: String): File {
        require(ID.matches(id)) { "skill_id 必须为 1–64 位小写字母、数字或短横线" }
        return safeChild(root(), id)
    }

    fun list(): List<ScriptPluginAgentSkills.Skill> {
        val root = root()
        val states = readStates()
        return root.listFiles().orEmpty().filter { !it.name.startsWith('.') && it.isDirectory }
            .sortedBy { it.name }.map { file ->
                val enabled = states.optBoolean(file.name, true)
                try {
                    val info = parseDocument(readDocument(file.name))
                    ScriptPluginAgentSkills.Skill(file.name, info.first, info.second, enabled)
                } catch (error: Exception) {
                    ScriptPluginAgentSkills.Skill(file.name, file.name, "", enabled, error.message ?: "技能文档无效")
                }
            }
    }

    fun readDocument(id: String): String = readText(safeChild(skillDirectory(id), "SKILL.md"), MAX_DOCUMENT_BYTES)

    fun saveDocument(id: String?, content: String): ScriptPluginAgentSkills.Skill {
        val metadata = parseDocument(content)
        val targetId = id ?: metadata.first
        val enabled = readStates().optBoolean(targetId, true)
        root(create = true)
        val target = skillDirectory(targetId)
        if (id == null) {
            require(!exists(target)) { "已存在同 ID 的 Skill：$targetId，请编辑现有技能或修改 name" }
            val stage = File(root(), ".new-${UUID.randomUUID()}")
            check(stage.mkdir()) { "无法创建技能暂存目录" }
            try {
                File(stage, "SKILL.md").writeText(content, Charsets.UTF_8)
                check(!exists(target) && stage.renameTo(target)) { "安装技能失败" }
            } finally {
                removeTree(stage)
            }
        } else {
            require(target.isDirectory) { "Skill 不存在：$id" }
            atomicWrite(safeChild(target, "SKILL.md"), content)
        }
        return ScriptPluginAgentSkills.Skill(targetId, metadata.first, metadata.second, enabled)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        require(skillDirectory(id).isDirectory) { "Skill 不存在：$id" }
        if (enabled) parseDocument(readDocument(id))
        val states = readStates().put(id, enabled)
        atomicWrite(stateFile(), states.toString())
    }

    fun delete(id: String) {
        val target = skillDirectory(id)
        require(target.isDirectory) { "Skill 不存在：$id" }
        val states = readStates()
        states.remove(id)
        // Rename first so readers never see a half-deleted skill; delete links themselves, never their targets.
        val trash = File(root(), ".delete-${UUID.randomUUID()}")
        check(target.renameTo(trash)) { "移除 Skill 失败" }
        try {
            atomicWrite(stateFile(), states.toString())
        } catch (error: Exception) {
            check(trash.renameTo(target)) { "更新技能状态失败，原文件保留于 ${trash.name}" }
            throw error
        }
        removeTree(trash)
    }

    fun importStream(input: InputStream): List<ScriptPluginAgentSkills.Skill> {
        val bytes = readBounded(input, MAX_ARCHIVE_BYTES)
        return if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
            importZip(bytes)
        } else {
            listOf(saveDocument(null, decodeText(bytes)))
        }
    }

    private fun importZip(bytes: ByteArray): List<ScriptPluginAgentSkills.Skill> {
        rejectZipLinks(bytes)
        val states = readStates()
        root(create = true)
        val stage = File(root(), ".import-${UUID.randomUUID()}")
        check(stage.mkdir()) { "无法创建导入暂存目录" }
        val installed = ArrayList<File>()
        try {
            var total = 0
            var count = 0
            val names = HashSet<String>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= MAX_FILES) { "ZIP 最多包含 $MAX_FILES 个目录或文件" }
                    val path = entry.name.trimEnd('/')
                    validateRelativePath(path)
                    require(names.add(path)) { "ZIP 包含重复路径：$path" }
                    val target = safeChild(stage, path)
                    if (entry.isDirectory) {
                        check(target.isDirectory || target.mkdirs()) { "无法创建 ZIP 子目录" }
                    } else {
                        val data = readBounded(zip, MAX_FILE_BYTES)
                        total += data.size
                        require(total <= MAX_UNPACKED_BYTES) { "ZIP 解压总大小不能超过 8 MiB" }
                        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "无法创建 ZIP 子目录" }
                        target.writeBytes(data)
                    }
                    zip.closeEntry()
                }
            }
            val documents = stage.walkTopDown().filter { it.isFile && it.name == "SKILL.md" }.toList()
            require(documents.isNotEmpty()) { "ZIP 内未找到 SKILL.md" }
            val sources = documents.map { it.parentFile!! }
            require(sources.none { parent -> sources.any { child -> child != parent && child.path.startsWith(parent.path + File.separator) } }) {
                "ZIP 的技能目录不能互相嵌套"
            }
            val metadata = documents.map { parseDocument(readText(it, MAX_DOCUMENT_BYTES)) }
            require(metadata.map { it.first }.distinct().size == metadata.size) { "ZIP 内多个 Skill 使用了相同 name" }
            val destinations = metadata.map { skillDirectory(it.first) }
            destinations.forEach { require(!exists(it)) { "已存在同 ID 的 Skill：${it.name}，未导入任何技能" } }
            // Validate the entire archive before moving any skill into the visible catalog.
            sources.zip(destinations).forEach { (source, destination) ->
                check(!exists(destination) && source.renameTo(destination)) { "安装 Skill 失败：${destination.name}" }
                installed += destination
            }
            return metadata.map { ScriptPluginAgentSkills.Skill(it.first, it.first, it.second, states.optBoolean(it.first, true)) }
        } catch (error: Exception) {
            installed.forEach(::removeTree)
            throw error
        } finally {
            removeTree(stage)
        }
    }

    fun catalog(): String {
        val skills = list().filter { it.enabled && it.error.isEmpty() }
        if (skills.isEmpty()) return ""
        val result = StringBuilder("可用 Skills（仅目录；使用前通过 hchat.skills.read 读取 SKILL.md）：\n")
        var included = 0
        for (skill in skills) {
            val line = skillJson(skill, skill.description.take(400)).toString() + "\n"
            if (result.length + line.length > 7700) break
            result.append(line)
            included++
        }
        if (included < skills.size) result.append("目录已截断；调用 hchat.skills.list 读取完整列表。\n")
        return result.toString()
    }

    fun call(name: String, args: JSONObject): String {
        require(ScriptPluginAgentSkills.isKnownToolName(name)) { "未知 Skills 工具：$name" }
        return when (name) {
            "hchat.skills.list" -> {
                val skills = list()
                val offset = args.optInt("offset", 0)
                val limit = args.optInt("limit", 20)
                require(offset in 0..skills.size && limit in 1..50) { "offset 超过列表范围，或 limit 不在 1–50 内" }
                val end = minOf(skills.size, offset + limit)
                JSONObject().put("ok", true).put("offset", offset).put("total", skills.size)
                    .put("nextOffset", if (end < skills.size) end else JSONObject.NULL)
                    .put("skills", JSONArray(skills.subList(offset, end).map { skillJson(it) })).toString()
            }
            else -> readTool(args).toString()
        }
    }

    private fun readTool(args: JSONObject): JSONObject {
        val id = args.optString("skill_id", "")
        val target = skillDirectory(id)
        require(readStates().optBoolean(id, true)) { "Skill 已停用：$id" }
        parseDocument(readDocument(id))
        val path = args.optString("path", "SKILL.md").ifBlank { "SKILL.md" }
        val file = if (path == ".") target else safeChild(target, path)
        val offset = args.optInt("offset", 0)
        val limit = args.optInt("max_chars", 12000)
        require(offset >= 0 && limit in 1..24000) { "offset 必须非负，max_chars 必须为 1–24000" }
        val listing = readableFiles(target)
        val result = JSONObject().put("ok", true).put("skillId", id).put("path", path)
            .put("readOnly", true).put("scriptsExecuted", false)
            .put("files", JSONArray(listing.first)).put("filesTruncated", listing.second)
        if (file.isDirectory) return result.put("type", "directory")
        val text = readText(file, if (path == "SKILL.md") MAX_DOCUMENT_BYTES else MAX_FILE_BYTES)
        require(offset <= text.length) { "offset 超过文件字符数 ${text.length}" }
        require(offset == text.length || !text[offset].isLowSurrogate()) { "offset 落在 Unicode 字符中间，请使用上次返回的 nextOffset" }
        var end = minOf(text.length, offset + limit)
        if (end < text.length && text[end].isLowSurrogate()) {
            if (end - offset > 1) end-- else end++
        }
        return result.put("type", "text").put("offset", offset).put("content", text.substring(offset, end))
            .put("totalChars", text.length).put("nextOffset", if (end < text.length) end else JSONObject.NULL)
    }

    private fun readableFiles(skill: File): Pair<List<String>, Boolean> {
        val files = ArrayList<String>()
        var inspected = 0
        var truncated = false
        fun visit(directory: File, depth: Int) {
            if (depth > MAX_DEPTH) { truncated = true; return }
            for (child in directory.listFiles().orEmpty().sortedBy { it.name }) {
                if (++inspected > MAX_FILES) { truncated = true; return }
                if (Files.isSymbolicLink(child.toPath())) continue
                if (child.isDirectory) visit(child, depth + 1)
                else if (child.isFile && child.length() <= MAX_FILE_BYTES) {
                    if (runCatching { readText(child, MAX_FILE_BYTES) }.isSuccess) files += child.relativeTo(skill).invariantSeparatorsPath
                }
            }
        }
        visit(skill, 0)
        return files to truncated
    }

    private fun stateFile(): File = safeChild(root().parentFile!!, "skills-state.json")

    private fun readStates(): JSONObject {
        val file = stateFile()
        return if (exists(file)) JSONObject(readText(file, MAX_FILE_BYTES)) else JSONObject()
    }

    private fun skillJson(skill: ScriptPluginAgentSkills.Skill, description: String = skill.description) = JSONObject()
        .put("id", skill.id).put("name", skill.name).put("description", description)
        .put("enabled", skill.enabled).put("error", skill.error)

    private fun atomicWrite(file: File, text: String) {
        val temp = File(file.parentFile, ".write-${UUID.randomUUID()}")
        try {
            temp.writeText(text, Charsets.UTF_8)
            check(temp.renameTo(file)) { "保存文件失败：${file.name}" }
        } finally {
            temp.delete()
        }
    }

    private fun safeChild(parent: File, relative: String): File {
        validateRelativePath(relative)
        val child = File(parent, relative).absoluteFile
        require(child == child.canonicalFile && child.path.startsWith(parent.absolutePath + File.separator)) {
            "技能文件不能使用符号链接或越过技能目录"
        }
        var cursor = child
        while (cursor != parent) {
            require(!Files.isSymbolicLink(cursor.toPath())) { "技能文件不能使用符号链接" }
            cursor = cursor.parentFile ?: error("技能路径无效")
        }
        return child
    }

    private fun validateRelativePath(path: String) {
        val parts = path.split('/')
        require(path.isNotBlank() && path.length <= 1024 && parts.size <= MAX_DEPTH &&
            !File(path).isAbsolute && path.none { it == '\\' || it == ':' || it.code < 32 } &&
            parts.none { it.isEmpty() || it == "." || it == ".." }) { "技能内路径无效或越界：$path" }
    }

    private fun readText(file: File, maxBytes: Int): String {
        require(!Files.isSymbolicLink(file.toPath()) && file.isFile) { "文件不存在、不是普通文件或是符号链接：${file.name}" }
        require(file.length() <= maxBytes) { "文件超过读取上限：${file.name}" }
        return file.inputStream().use { decodeText(readBounded(it, maxBytes)) }
    }

    private fun readBounded(input: InputStream, maximum: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(result.size() + count <= maximum) { "文件或解压数据超过大小上限（$maximum 字节）" }
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    private fun decodeText(bytes: ByteArray): String {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require(text.none { it.code < 32 && it != '\t' && it != '\n' && it != '\r' }) { "只能读取 UTF-8 文本，不能读取二进制文件" }
        return text
    }

    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun removeTree(file: File) {
        if (Files.isSymbolicLink(file.toPath())) { check(file.delete()) { "无法删除符号链接" }; return }
        if (file.isDirectory) file.listFiles().orEmpty().forEach(::removeTree)
        check(!exists(file) || file.delete()) { "无法删除技能暂存文件：${file.name}" }
    }

    /** Inspect central directory attributes because ZipInputStream does not expose Unix link types. */
    private fun rejectZipLinks(bytes: ByteArray) {
        fun u16(at: Int): Int = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
        fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 65557)).firstOrNull {
            u32(it) == 0x06054b50L && it + 22 + u16(it + 20) == bytes.size
        } ?: error("ZIP 中央目录缺失或损坏")
        require(u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 8) == u16(end + 10)) { "不支持分卷 ZIP" }
        val count = u16(end + 10)
        require(count in 1..MAX_FILES) { "ZIP 最多包含 $MAX_FILES 个目录或文件" }
        var at = u32(end + 16).toInt()
        val size = u32(end + 12)
        require(at >= 0 && size >= 0 && at.toLong() + size == end.toLong()) { "ZIP 中央目录无效，不支持 ZIP64" }
        repeat(count) {
            require(at >= 0 && at + 46 <= end && u32(at) == 0x02014b50L) { "ZIP 中央目录条目损坏" }
            require(u16(at + 8) and 1 == 0) { "不支持加密 ZIP" }
            require((u32(at + 38) ushr 16).toInt() and 0xf000 != 0xa000) { "ZIP 不能包含符号链接" }
            at += 46 + u16(at + 28) + u16(at + 30) + u16(at + 32)
            require(at <= end) { "ZIP 中央目录条目越界" }
        }
        require(at == end) { "ZIP 中央目录长度不匹配" }
    }

    private fun parseDocument(document: String): Pair<String, String> {
        require(document.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) { "SKILL.md 不能超过 256 KiB" }
        decodeText(document.toByteArray(Charsets.UTF_8))
        val lines = document.removePrefix("\uFEFF").replace("\r\n", "\n").split('\n')
        require(lines.firstOrNull() == "---") { "SKILL.md 必须以 YAML 头部 --- 开始" }
        val end = (1 until lines.size).firstOrNull { lines[it] == "---" || lines[it] == "..." }
            ?: error("SKILL.md 的 YAML 头部缺少结束分隔符")
        require(lines.take(end).sumOf { it.length } <= 16384) { "SKILL.md 元数据过长" }
        val values = HashMap<String, String>()
        var index = 1
        while (index < end) {
            val line = lines[index++]
            if (line.isBlank() || line.trimStart().startsWith('#')) continue
            require(!line.startsWith(' ') && !line.startsWith('\t')) { "YAML 头部包含不支持的缩进或多行标量" }
            val match = META_KEY.matchEntire(line) ?: error("无法解析 YAML 元数据：${line.take(80)}")
            val key = match.groupValues[1]
            val value = match.groupValues[2].trim()
            val following = ArrayList<String>()
            while (index < end && (lines[index].isBlank() || lines[index].firstOrNull()?.isWhitespace() == true)) {
                following += lines[index++]
            }
            if (key != "name" && key != "description") continue
            require(!values.containsKey(key)) { "SKILL.md 重复定义 $key" }
            values[key] = if (BLOCK.matches(value)) {
                val populated = following.filter { it.isNotBlank() }
                require(populated.isNotEmpty() && populated.none { it.startsWith('\t') }) { "$key 的块标量无效" }
                val indent = populated.minOf { it.takeWhile { char -> char == ' ' }.length }
                require(indent > 0) { "$key 的块标量必须缩进" }
                following.joinToString(if (value.startsWith('>')) " " else "\n") { it.drop(indent) }.trim()
            } else {
                require(following.all { it.isBlank() || it.trimStart().startsWith('#') }) { "$key 不支持这种多行格式，请使用 | 或 > 块标量" }
                parseScalar(value, key)
            }
        }
        val name = values["name"] ?: error("SKILL.md 缺少 name")
        val description = values["description"]?.replace(Regex("\\s+"), " ")?.trim() ?: error("SKILL.md 缺少 description")
        require(ID.matches(name)) { "name 必须为 1–64 位小写字母、数字，单词间可用短横线连接" }
        require(description.isNotBlank() && description.length <= 2048) { "description 必须为 1–2048 个字符" }
        return name to description
    }

    private fun parseScalar(value: String, key: String): String {
        require(value.isNotBlank()) { "$key 不能为空" }
        if (value.startsWith('\'') || value.startsWith('"')) {
            val quote = value[0]
            val result = StringBuilder()
            var index = 1
            while (index < value.length) {
                val char = value[index++]
                if (char == quote) {
                    if (quote == '\'' && index < value.length && value[index] == '\'') {
                        result.append('\''); index++; continue
                    }
                    require(value.substring(index).trim().let { it.isEmpty() || it.startsWith('#') }) { "$key 引号后包含无效内容" }
                    return result.toString()
                }
                if (quote == '"' && char == '\\') {
                    require(index < value.length) { "$key 转义不完整" }
                    when (val escape = value[index++]) {
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        '"', '\\', '/' -> result.append(escape)
                        'u', 'x' -> {
                            val digits = if (escape == 'u') 4 else 2
                            require(index + digits <= value.length) { "$key Unicode 转义不完整" }
                            result.append(value.substring(index, index + digits).toIntOrNull(16)?.toChar() ?: error("$key Unicode 转义无效"))
                            index += digits
                        }
                        else -> error("$key 不支持转义 \\$escape")
                    }
                } else result.append(char)
            }
            error("$key 引号未闭合")
        }
        val plain = value.replace(Regex("\\s+#.*$"), "").trim()
        require(plain.first() !in "[]{}&*!|>%@`#" && !Regex(":(?:\\s|$)").containsMatchIn(plain)) { "$key 必须为文本标量，不能使用列表、对象、别名或标签" }
        return plain
    }

    companion object {
        private val ID = Regex("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?")
        private val META_KEY = Regex("([A-Za-z_][A-Za-z0-9_-]*):[ \\t]*(.*)")
        private val BLOCK = Regex("[|>][+-]?(?:[ \\t]+#.*)?")
        private const val MAX_DOCUMENT_BYTES = 256 * 1024
        private const val MAX_FILE_BYTES = 1024 * 1024
        private const val MAX_ARCHIVE_BYTES = 12 * 1024 * 1024
        private const val MAX_UNPACKED_BYTES = 8 * 1024 * 1024
        private const val MAX_FILES = 256
        private const val MAX_DEPTH = 16
    }
}
