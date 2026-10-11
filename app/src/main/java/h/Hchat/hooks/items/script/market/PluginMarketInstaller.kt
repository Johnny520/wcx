package h.Hchat.hooks.items.script.market

import android.content.Context
import android.system.Os
import android.util.Base64
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import h.Hchat.utils.HLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

object PluginMarketInstaller {
    private const val TAG = "[Hchat:PluginMarket]"

    const val MAIN_FILE = "main.java"
    const val SNAPSHOT_FILE = "main.java.bshs"
    const val INFO_FILE = "info.prop"
    const val README_FILE = "README.md"

    const val MAX_PACKAGE_BYTES = 32L * 1024L * 1024L
    const val MAX_EXTRA_FILE_BYTES = 16L * 1024L * 1024L
    const val MAX_EXTRA_FILE_COUNT = 32
    const val MAX_PLUGIN_ID_LENGTH = 64

    private val managedFiles = setOf(MAIN_FILE, SNAPSHOT_FILE, INFO_FILE, README_FILE)

    fun collectUploadPackage(
        context: Context,
        pluginId: String,
        remoteName: String? = null,
        releaseNotes: String = "",
        extraFiles: List<PluginMarketFile> = emptyList()
    ): Result<PluginMarketUploadPackage> = runCatching {
        val plugin = ScriptPluginRuntime.listPlugins(context).firstOrNull { it.id == pluginId }
            ?: error("未找到本地插件: $pluginId")
        val files = ArrayList<PluginMarketFile>()
        files += requireNotNull(readLocalFile(plugin.dir, MAIN_FILE, required = true))
        readLocalSnapshot(plugin.dir)?.let(files::add)
        readLocalFile(plugin.dir, INFO_FILE, required = false)?.let(files::add)
        val readme = File(plugin.dir, README_FILE).takeIf { it.isFile }
            ?: File(plugin.dir, "readme.md").takeIf { it.isFile }
        readme?.let {
            files += requireNotNull(readLocalFile(plugin.dir, it.name, required = true, outputName = README_FILE))
        }
        files += extraFiles
        validateFiles(files, requireMain = true)
        val marketName = remoteName?.trim().takeUnless { it.isNullOrBlank() } ?: plugin.name
        require(plugin.id.length <= 128) { "本地插件目录名不能超过 128 个字符" }
        require(marketName.length <= 100) { "上传插件名不能超过 100 个字符" }
        require(plugin.author.length <= 100) { "插件作者不能超过 100 个字符" }
        require(plugin.version.length <= 64) { "插件版本不能超过 64 个字符" }
        require(releaseNotes.length <= 500) { "更新说明不能超过 500 个字符" }
        val uploader = currentUploaderIdentity()
        PluginMarketUploadPackage(
            localPluginId = plugin.id,
            name = marketName,
            author = plugin.author,
            version = plugin.version,
            updateTime = plugin.updateTime,
            releaseNotes = releaseNotes.trim(),
            uploaderWxId = uploader.wxId,
            uploaderWeChatId = uploader.weChatId,
            uploaderNickname = uploader.nickname,
            files = files
        )
    }

    private fun currentUploaderIdentity(): UploaderIdentity {
        val account = WeChatApis.account() ?: error("当前微信账号资料尚未就绪，请重启微信后重试")
        val wxId = account.selfWxId().trim()
        require(wxId.isNotBlank()) { "无法读取当前账号 wxid，请重启微信后重试" }
        val contact = runCatching { WeChatApis.contacts()?.getContact(wxId) }.getOrNull()
        val weChatId = account.customWxId().trim().ifBlank { contact?.customWxId.orEmpty().trim() }
        val nickname = account.selfName().trim().ifBlank { contact?.nickname.orEmpty().trim() }
        require(wxId.length <= 128) { "当前账号 wxid 长度异常" }
        require(weChatId.length <= 128) { "当前账号微信号长度异常" }
        require(nickname.length <= 100) { "当前账号微信昵称过长" }
        return UploaderIdentity(wxId, weChatId, nickname)
    }

    private data class UploaderIdentity(
        val wxId: String,
        val weChatId: String,
        val nickname: String
    )

    fun install(
        context: Context,
        plugin: PluginMarketPlugin,
        overwrite: Boolean = false,
        localPluginId: String? = null
    ): Result<PluginMarketInstallResult> = runCatching {
        val remoteId = plugin.remotePluginId.trim()
        require(remoteId.isNotBlank()) { "远程插件 ID 不能为空" }
        validateFiles(plugin.files, requireMain = true, requireHashes = true)
        val root = ScriptPluginRuntime.ensureDirs(context).canonicalFile
        val targetId = safePluginId(localPluginId ?: defaultLocalPluginId(plugin))
        require(targetId.isNotBlank()) { "无法生成本地插件目录名" }
        val requestedTarget = File(root, targetId).absoluteFile
        val target = requestedTarget.canonicalFile
        require(target.parentFile == root && requestedTarget == target) {
            "插件安装目录不在脚本插件根目录内"
        }
        if (target.exists() && !target.isDirectory) error("插件目标不是目录: $targetId")
        val legacyId = safePluginId(remoteId)
        val legacyTarget = File(root, legacyId).canonicalFile
        val currentTarget = if (
            localPluginId == null && targetId != legacyId && !target.exists() && legacyTarget.isDirectory
        ) {
            legacyTarget
        } else {
            target
        }
        val currentId = currentTarget.name
        val existed = currentTarget.isDirectory
        if (existed && !overwrite) error("本地插件已存在，需要确认覆盖: $targetId")
        if (existed) ensureSecureTree(currentTarget)

        val parent = root.parentFile ?: error("插件根目录没有父目录")
        val staging = File(parent, ".hchat-plugin-market-stage-${UUID.randomUUID()}")
        val prepared = File(staging, targetId)
        val backup = File(parent, ".hchat-plugin-market-backup-$currentId-${UUID.randomUUID()}")
        deleteTreeIfExists(staging)
        deleteTreeIfExists(backup)
        var movedOld = false
        var installed = false
        var disabled = false
        val wasEnabled = existed && ScriptPluginRuntime.isPluginEnabled(context, currentId)
        try {
            check(staging.mkdirs()) { "创建插件暂存目录失败" }
            if (existed) copyTree(currentTarget, prepared) else check(prepared.mkdirs()) { "创建插件目录失败" }
            removeManagedFilesNotIncluded(prepared, plugin.files)
            plugin.files.forEach { file ->
                val destination = File(prepared, file.name)
                ensurePluginFile(destination, prepared)
                if (destination.exists()) {
                    require(destination.isFile) { "插件文件目标与本地目录冲突: ${file.name}" }
                    check(destination.delete()) { "删除旧插件文件失败: ${file.name}" }
                }
                writeBytesAtomically(destination, file.decodedBytes())
            }
            ensureSecureTree(prepared)
            ScriptPluginRuntime.setPluginEnabled(context, targetId, false).getOrThrow()
            if (currentId != targetId) {
                ScriptPluginRuntime.setPluginEnabled(context, currentId, false).getOrThrow()
            }
            disabled = true
            if (currentTarget.exists()) {
                check(currentTarget.renameTo(backup)) { "备份旧插件目录失败" }
                movedOld = true
            }
            check(prepared.renameTo(target)) { "安装插件目录失败" }
            installed = true
            ScriptPluginRuntime.refreshPluginObserver(context, targetId)
            if (currentId != targetId) ScriptPluginRuntime.refreshPluginObserver(context, currentId)
            runCatching { deleteTreeIfExists(backup) }.onFailure { error ->
                HLog.e("$TAG 清理插件安装备份失败: ${backup.absolutePath}", error)
            }
            PluginMarketInstallResult(targetId, target.absolutePath, existed)
        } catch (error: Throwable) {
            if (disabled) runCatching { ScriptPluginRuntime.setPluginEnabled(context, targetId, false) }
            if (installed && target.exists()) runCatching { deleteTreeIfExists(target) }
            val restored = !movedOld || (backup.exists() && backup.renameTo(currentTarget))
            if (restored) {
                if (movedOld) runCatching { ScriptPluginRuntime.refreshPluginObserver(context, currentId) }
                if (disabled && wasEnabled) {
                    runCatching { ScriptPluginRuntime.setPluginEnabled(context, currentId, true) }
                }
            }
            if (!restored) {
                throw IllegalStateException(
                    "插件安装失败且旧插件恢复失败，备份保留在 ${backup.absolutePath}",
                    error
                )
            }
            throw error
        } finally {
            runCatching { deleteTreeIfExists(staging) }.onFailure { error ->
                HLog.e("$TAG 清理插件安装暂存目录失败: ${staging.absolutePath}", error)
            }
        }
    }

    fun validateDetail(plugin: PluginMarketPlugin): Result<Unit> = runCatching {
        require(plugin.remotePluginId.isNotBlank()) { "远程插件 ID 不能为空" }
        validateFiles(plugin.files, requireMain = true, requireHashes = true)
    }

    fun defaultLocalPluginId(plugin: PluginMarketPlugin): String {
        return safePluginId(plugin.sourcePluginId.ifBlank { plugin.remotePluginId })
    }

    fun createExternalFile(name: String, bytes: ByteArray): PluginMarketFile {
        val safeName = validatePluginFileName(name)
        require(!isManagedFile(safeName)) { "附加文件不能覆盖默认插件文件: $safeName" }
        require(bytes.size.toLong() <= MAX_EXTRA_FILE_BYTES) {
            "$safeName 超过 ${MAX_EXTRA_FILE_BYTES / 1024} KiB"
        }
        val text = runCatching { decodeUtf8(bytes, safeName) }.getOrNull()
        val encoding = if (text != null && !bytes.any { it.toInt() == 0 }) {
            PluginMarketFile.ENCODING_UTF8
        } else {
            PluginMarketFile.ENCODING_BASE64
        }
        val content = if (encoding == PluginMarketFile.ENCODING_UTF8) {
            text.orEmpty()
        } else {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
        return PluginMarketFile(
            name = safeName,
            content = content,
            sha256 = sha256(bytes),
            size = bytes.size.toLong(),
            encoding = encoding
        )
    }

    fun existingLocalPluginId(context: Context, plugin: PluginMarketPlugin): String? {
        val root = ScriptPluginRuntime.scriptDir(context)
        val preferred = defaultLocalPluginId(plugin)
        if (File(root, preferred).isDirectory) return preferred
        val legacy = safePluginId(plugin.remotePluginId)
        return legacy.takeIf { it != preferred && File(root, it).isDirectory }
    }

    fun safePluginId(value: String): String {
        val cleaned = value.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('.', ' ')
            .take(MAX_PLUGIN_ID_LENGTH)
        require(cleaned != "." && cleaned != "..") { "插件目录名无效" }
        return cleaned.ifBlank { "online_plugin" }
    }

    private fun readLocalFile(
        pluginDir: File,
        sourceName: String,
        required: Boolean,
        outputName: String = sourceName
    ): PluginMarketFile? {
        val source = File(pluginDir, sourceName)
        if (!source.isFile) {
            if (required) error("插件缺少 $sourceName")
            return null
        }
        ensureSecureFile(source, pluginDir)
        val content = readUtf8(source)
        return PluginMarketFile(
            name = outputName,
            content = content,
            sha256 = sha256(content.toByteArray(StandardCharsets.UTF_8)),
            size = content.toByteArray(StandardCharsets.UTF_8).size.toLong()
        )
    }

    private fun validateFiles(
        files: List<PluginMarketFile>,
        requireMain: Boolean,
        requireHashes: Boolean = false
    ) {
        val names = files.map { it.name }
        val normalizedNames = names.map { it.lowercase(Locale.ROOT) }
        require(normalizedNames.distinct().size == normalizedNames.size) { "插件包包含重复文件" }
        names.forEach(::validatePluginFileName)
        require(names.count { !isManagedFile(it) } <= MAX_EXTRA_FILE_COUNT) {
            "插件包附加文件不能超过 $MAX_EXTRA_FILE_COUNT 个"
        }
        if (requireMain) require(MAIN_FILE in names) { "插件包缺少 $MAIN_FILE" }
        if (requireMain) require(files.first { it.name == MAIN_FILE }.content.isNotBlank()) {
            "$MAIN_FILE 不能为空"
        }
        var total = 0L
        files.forEach { file ->
            val limit = fileLimit(file.name)
            val bytes = runCatching { file.decodedBytes() }
                .getOrElse { throw IllegalArgumentException("${file.name} 内容解码失败", it) }
            require(bytes.size.toLong() <= limit) {
                "${file.name} 超过 ${limit / 1024} KiB"
            }
            if (file.name == SNAPSHOT_FILE) {
                require(file.encoding == PluginMarketFile.ENCODING_BASE64) {
                    "$SNAPSHOT_FILE 必须使用 Base64 传输"
                }
                require(bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(66, 83, 72, 83))) {
                    "$SNAPSHOT_FILE 不是有效的 BeanShell 快照"
                }
            } else if (isManagedFile(file.name)) {
                require(file.encoding == PluginMarketFile.ENCODING_UTF8 && isStrictUtf8(bytes)) {
                    "${file.name} 不是有效 UTF-8 文本"
                }
            } else {
                require(file.encoding == PluginMarketFile.ENCODING_UTF8 ||
                    file.encoding == PluginMarketFile.ENCODING_BASE64) {
                    "${file.name} 使用了不支持的编码"
                }
                if (file.encoding == PluginMarketFile.ENCODING_UTF8) {
                    require(isStrictUtf8(bytes)) { "${file.name} 不是有效 UTF-8 文本" }
                }
            }
            require(file.size == bytes.size.toLong()) { "${file.name} 文件大小校验失败" }
            if (requireHashes) {
                require(file.sha256.isNotBlank()) { "${file.name} 缺少 sha256" }
                require(file.sha256.equals(sha256(bytes), ignoreCase = true)) {
                    "${file.name} sha256 校验失败"
                }
            }
            total += bytes.size
        }
        require(total <= MAX_PACKAGE_BYTES) { "插件包超过 ${MAX_PACKAGE_BYTES / 1024} KiB" }
    }

    private fun removeManagedFilesNotIncluded(root: File, files: List<PluginMarketFile>) {
        val included = files.map { it.name }.toSet()
        managedFiles.filter { it !in included }.forEach { name ->
            val file = File(root, name)
            if (file.exists()) deleteTreeIfExists(file)
        }
        val lowerReadme = File(root, "readme.md")
        if (lowerReadme.exists()) deleteTreeIfExists(lowerReadme)
    }

    private fun ensurePluginFile(file: File, root: File) {
        val canonical = file.canonicalFile
        require(canonical.parentFile == root.canonicalFile) { "插件文件路径越界" }
        validatePluginFileName(file.name)
    }

    private fun validatePluginFileName(name: String): String {
        val normalized = name.trim()
        require(normalized.isNotBlank() && normalized != "." && normalized != "..") {
            "插件文件名无效"
        }
        require(normalized.length <= 128) { "插件文件名不能超过 128 个字符" }
        require(normalized.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) {
            "插件文件名不能包含路径或控制字符: $normalized"
        }
        require(!isManagedFile(normalized) || normalized in managedFiles) {
            "默认插件文件名必须使用标准大小写: $normalized"
        }
        return normalized
    }

    private fun isManagedFile(name: String): Boolean = managedFiles.any {
        it.equals(name, ignoreCase = true)
    }

    private fun writeBytesAtomically(target: File, content: ByteArray) {
        val temp = File(target.parentFile, ".${target.name}.market-${UUID.randomUUID()}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(content)
            output.fd.sync()
        }
        try {
            Os.rename(temp.absolutePath, target.absolutePath)
        } catch (error: Throwable) {
            temp.delete()
            throw IllegalStateException("写入 ${target.name} 失败", error)
        }
    }

    private fun copyTree(source: File, destination: File) {
        ensureSecureTree(source)
        if (!destination.isDirectory) check(destination.mkdirs()) { "创建插件暂存目录失败" }
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source)
            val target = File(destination, relative.path)
            if (file.isDirectory) {
                if (!target.isDirectory) check(target.mkdirs()) { "创建目录失败: ${target.name}" }
            } else {
                target.parentFile?.let { if (!it.isDirectory) check(it.mkdirs()) { "创建父目录失败" } }
                FileInputStream(file).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun ensureSecureTree(root: File) {
        require(root.absoluteFile == root.canonicalFile) { "插件目录包含不支持的符号链接" }
        if (!root.isDirectory) return
        root.walkTopDown().forEach { file ->
            require(file.absoluteFile == file.canonicalFile) { "插件目录包含不支持的符号链接: ${file.name}" }
        }
    }

    private fun ensureSecureFile(file: File, root: File) {
        require(file.absoluteFile == file.canonicalFile) { "插件文件不能是符号链接: ${file.name}" }
        require(file.canonicalFile.parentFile == root.canonicalFile) { "插件文件路径越界" }
    }

    private fun readUtf8(file: File): String {
        val limit = fileLimit(if (file.name.equals("readme.md", true)) README_FILE else file.name)
        require(file.length() <= limit) {
            "${file.name} 超过 ${limit / 1024} KiB"
        }
        val bytes = file.readBytes()
        require(bytes.size.toLong() <= limit) {
            "${file.name} 超过 ${limit / 1024} KiB"
        }
        return decodeUtf8(bytes, file.name)
    }

    private fun readLocalSnapshot(pluginDir: File): PluginMarketFile? {
        val source = File(pluginDir, SNAPSHOT_FILE)
        if (!source.isFile) return null
        ensureSecureFile(source, pluginDir)
        val limit = fileLimit(SNAPSHOT_FILE)
        require(source.length() <= limit) { "$SNAPSHOT_FILE 超过 ${limit / 1024} KiB" }
        val bytes = source.readBytes()
        require(bytes.size.toLong() <= limit) { "$SNAPSHOT_FILE 超过 ${limit / 1024} KiB" }
        require(bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(66, 83, 72, 83))) {
            "$SNAPSHOT_FILE 不是有效的 BeanShell 快照"
        }
        return PluginMarketFile(
            name = SNAPSHOT_FILE,
            content = Base64.encodeToString(bytes, Base64.NO_WRAP),
            sha256 = sha256(bytes),
            size = bytes.size.toLong(),
            encoding = PluginMarketFile.ENCODING_BASE64
        )
    }

    private fun decodeUtf8(bytes: ByteArray, name: String): String {
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse { throw IllegalArgumentException("$name 不是有效 UTF-8 文本", it) }
    }

    private fun isStrictUtf8(bytes: ByteArray): Boolean = runCatching {
        decodeUtf8(bytes, "插件文件")
        true
    }.getOrDefault(false)

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun fileLimit(name: String): Long = when (name) {
        MAIN_FILE -> 10L * 1024L * 1024L
        SNAPSHOT_FILE -> 16L * 1024L * 1024L
        README_FILE -> 16L * 1024L * 1024L
        INFO_FILE -> 16L * 1024L * 1024L
        else -> MAX_EXTRA_FILE_BYTES
    }

    private fun deleteTreeIfExists(target: File) {
        if (!target.exists()) return
        deleteTree(target)
    }

    private fun deleteTree(target: File) {
        require(target.absoluteFile == target.canonicalFile) { "不支持删除符号链接: ${target.name}" }
        if (target.isDirectory) target.listFiles()?.forEach(::deleteTree)
        check(target.delete()) { "删除文件失败: ${target.name}" }
    }
}
