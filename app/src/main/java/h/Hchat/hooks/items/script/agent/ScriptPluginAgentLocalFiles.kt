package h.Hchat.hooks.items.script.agent

import java.io.File
import java.io.RandomAccessFile
import java.net.URLConnection

object ScriptPluginAgentLocalFiles {
    data class ReadResult(
        val context: String,
        val imagePaths: List<String>,
        val notes: List<String>
    )

    fun extractMentionedPaths(text: String): List<File> {
        val values = LinkedHashSet<String>()
        text.lineSequence().forEach { line ->
            val candidate = cleanPath(line.trim())
            if (candidate.startsWith("/") && File(candidate).exists()) values += candidate
        }
        PATH_REGEX.findAll(text).forEach { match ->
            val candidate = cleanPath(match.value)
            if (candidate.isNotBlank()) values += candidate
        }
        return values.mapNotNull { canonicalFile(File(it)) }.distinctBy { it.path }
    }

    fun readMentioned(text: String): ReadResult {
        return readPaths(extractMentionedPaths(text), emptyMap())
    }

    fun readAttachments(attachments: List<ScriptPluginAgentAttachment>): ReadResult {
        val files = attachments.map { File(it.path) }
        val mimeTypes = attachments.associate { attachment ->
            canonicalFile(File(attachment.path))?.path.orEmpty() to attachment.mimeType
        }
        return readPaths(files, mimeTypes)
    }

    fun readRequested(path: String, allowedRoots: List<File>): ReadResult {
        val target = canonicalFile(File(path.trim()))
            ?: return errorResult(path, "路径无效")
        val roots = allowedRoots.mapNotNull(::canonicalFile)
        val allowed = roots.any { root ->
            target.path == root.path || target.path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator)
        }
        if (!allowed) return errorResult(path, "只能读取用户消息中明确提供的路径或其子项")
        return readPaths(listOf(target), emptyMap())
    }

    fun imageMimeType(path: String, declared: String = ""): String {
        if (declared.startsWith("image/")) return declared
        return URLConnection.guessContentTypeFromName(path)
            ?.takeIf { it.startsWith("image/") }
            ?: when (File(path).extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> ""
            }
    }

    private fun readPaths(files: List<File>, declaredMimeTypes: Map<String, String>): ReadResult {
        if (files.isEmpty()) return ReadResult("", emptyList(), emptyList())
        val sections = ArrayList<String>()
        val images = ArrayList<String>()
        val notes = ArrayList<String>()
        files.mapNotNull(::canonicalFile).distinctBy { it.path }.take(MAX_FILES).forEach { file ->
            when {
                !file.exists() -> sections += fileSection(file.path, "文件不存在")
                file.isDirectory -> {
                    val children = file.listFiles()
                        ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                        ?.take(MAX_DIRECTORY_ENTRIES)
                        .orEmpty()
                    val listing = children.joinToString("\n") { child ->
                        val suffix = if (child.isDirectory) "/" else " (${child.length()} bytes)"
                        child.name + suffix
                    }.ifBlank { "目录为空或无法读取" }
                    sections += fileSection(file.path, listing)
                    notes += "读取目录: ${file.name.ifBlank { file.path }}"
                }
                file.isFile -> {
                    val declared = declaredMimeTypes[file.path].orEmpty()
                    val imageMime = imageMimeType(file.path, declared)
                    if (imageMime.isNotBlank()) {
                        if (file.length() <= MAX_IMAGE_BYTES) {
                            images += file.path
                            sections += fileSection(file.path, "图片，类型=$imageMime，大小=${file.length()} bytes")
                            notes += "读取图片: ${file.name}"
                        } else {
                            sections += fileSection(file.path, "图片超过 ${MAX_IMAGE_BYTES / 1024 / 1024} MB，未发送给模型")
                        }
                    } else if (looksLikeText(file, declared)) {
                        sections += fileSection(file.path, readText(file))
                        notes += "读取文件: ${file.name}"
                    } else {
                        sections += fileSection(file.path, "二进制文件，类型=${declared.ifBlank { "未知" }}，大小=${file.length()} bytes")
                        notes += "检查文件: ${file.name}"
                    }
                }
                else -> sections += fileSection(file.path, "不是普通文件或目录")
            }
        }
        return ReadResult(
            context = sections.joinToString("\n\n").take(MAX_CONTEXT_CHARS),
            imagePaths = images.distinct(),
            notes = notes.distinct()
        )
    }

    private fun looksLikeText(file: File, declaredMimeType: String): Boolean {
        if (declaredMimeType.startsWith("text/") || file.extension.lowercase() in TEXT_EXTENSIONS) return true
        return runCatching {
            file.inputStream().use { input ->
                val sample = ByteArray(minOf(4096L, file.length()).toInt())
                val count = input.read(sample)
                if (count <= 0) return@use true
                var controls = 0
                for (index in 0 until count) {
                    val value = sample[index].toInt() and 0xff
                    if (value == 0) return@use false
                    if (value < 9 || value in 14..31) controls++
                }
                controls * 10 < count
            }
        }.getOrDefault(false)
    }

    private fun readText(file: File): String {
        if (file.length() <= MAX_TEXT_BYTES) {
            return runCatching { file.readText(Charsets.UTF_8) }
                .getOrElse { "读取失败: ${it.message}" }
        }
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                val first = ByteArray(TEXT_SLICE_BYTES)
                val firstCount = input.read(first)
                val tailSize = minOf(TEXT_SLICE_BYTES.toLong(), file.length()).toInt()
                val last = ByteArray(tailSize)
                input.seek((file.length() - tailSize).coerceAtLeast(0L))
                val lastCount = input.read(last)
                buildString {
                    append(String(first, 0, firstCount.coerceAtLeast(0), Charsets.UTF_8))
                    append("\n\n... 文件中间内容已截断 ...\n\n")
                    append(String(last, 0, lastCount.coerceAtLeast(0), Charsets.UTF_8))
                }
            }
        }.getOrElse { "读取失败: ${it.message}" }
    }

    private fun fileSection(path: String, content: String): String {
        return "<local_file path=${JSONObjectQuote.quote(path)}>\n$content\n</local_file>"
    }

    private fun errorResult(path: String, message: String): ReadResult {
        return ReadResult(fileSection(path, message), emptyList(), listOf("读取失败: $path"))
    }

    private fun cleanPath(value: String): String {
        return value.trim().trim('"', '\'', '`', ',', ';', '，', '。', '；', ')', '）', ']', '】')
    }

    private fun canonicalFile(file: File): File? {
        return runCatching { file.canonicalFile }.getOrNull()
    }

    private object JSONObjectQuote {
        fun quote(value: String): String = org.json.JSONObject.quote(value)
    }

    private val PATH_REGEX = Regex(
        """(?<![A-Za-z0-9_])/(?:storage|sdcard|data|mnt|system|vendor|product|apex)(?:/[^\s\"'`<>|]+)+"""
    )
    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "java", "kt", "kts", "xml", "json", "jsonl", "prop", "properties",
        "log", "csv", "tsv", "html", "htm", "css", "js", "ts", "py", "sh", "c", "cc", "cpp",
        "h", "hpp", "gradle", "toml", "yaml", "yml", "ini", "conf", "sql", "bsh"
    )
    private const val MAX_FILES = 12
    private const val MAX_DIRECTORY_ENTRIES = 120
    private const val MAX_TEXT_BYTES = 512L * 1024L
    private const val TEXT_SLICE_BYTES = 256 * 1024
    private const val MAX_IMAGE_BYTES = 10L * 1024L * 1024L
    private const val MAX_CONTEXT_CHARS = 120_000
}
