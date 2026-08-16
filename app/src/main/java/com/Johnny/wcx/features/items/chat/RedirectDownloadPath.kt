package com.Johnny.wcx.features.items.chat

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.fs.createDirsSafe
import com.Johnny.wcx.utils.reflection.BString
import java.io.File
import java.util.Locale
import kotlin.io.path.absolutePathString
import kotlin.io.path.div

@Feature(name = "重定向文件下载路径", categories = ["聊天"], description = "将微信接收的聊天文件保存到自定义文件夹")
object RedirectDownloadPath : ClickableFeature(), IResolveDex {

    private const val TAG = "RedirectDownloadPath"
    private var saveDir by prefOption("redirect_download_path_save_dir", "")

    override fun onEnable() {
        methodDownloadFile.hookBefore {
            val type = args[0] as? String? ?: return@hookBefore
            if (type != "attachment") return@hookBefore
            result = ensureSaveDir()
        }

        methodInitDownloadAttach.hookBefore {
            val msgXml = args.getOrNull(2) as? String ?: return@hookBefore
            val currentPath = args.getOrNull(3) as? String
            val redirectedPath = buildRedirectedFilePath(msgXml, currentPath)
            if (redirectedPath != null) {
                args[3] = redirectedPath
                WeLogger.d(TAG, "redirect app attach download path: $redirectedPath")
            }
        }

        methodInsertDownloadAttach.hookBefore {
            val currentPath = args.getOrNull(0) as? String ?: return@hookBefore
            val redirectedPath = redirectExistingFilePath(currentPath)
            if (redirectedPath != null) {
                args[0] = redirectedPath
                WeLogger.d(TAG, "redirect app attach record path: $redirectedPath")
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var pathInput by remember { mutableStateOf(currentSaveDir()) }
            val normalizedPath = normalizeSaveDir(pathInput)
            val dir = File(normalizedPath)
            val statusText = when {
                dir.isDirectory -> "当前目录已存在。"
                dir.exists() -> "当前路径已存在但不是文件夹，下载时会回退到默认目录。"
                else -> "当前目录不存在，下载时会自动尝试创建。"
            }

            AlertDialogContent(
                title = { Text("重定向文件下载路径") },
                text = {
                    DefaultColumn {
                        OutlinedTextField(
                            value = pathInput,
                            onValueChange = { pathInput = it },
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                            label = { Text("保存目录") },
                            singleLine = true
                        )
                        Text("实际目录：$normalizedPath")
                        Text(statusText)
                        Text("留空使用默认目录：${defaultSaveDir()}")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        pathInput = defaultSaveDir()
                        saveDir = pathInput
                        showToast(context, "已恢复默认路径")
                    }) {
                        Text("恢复默认")
                    }
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        val saveDir = normalizeSaveDir(pathInput)
                        this@RedirectDownloadPath.saveDir = saveDir
                        runCatching { File(saveDir).mkdirs() }
                        showToast(context, "已保存, 后续新下载文件会使用该目录")
                        onDismiss()
                    }) {
                        Text("保存")
                    }
                }
            )
        }
    }

    private val methodDownloadFile by dexMethod {
        searchPackages("com.tencent.mm.vfs")
        matcher {
            declaredClass {
                usingStrings("VFS.VFSStrategy", "Found wrong moving file: ", "accountSalt")
            }

            paramTypes(BString)
            returnType(BString)
        }
    }
    private val methodInitDownloadAttach by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.model.app")
        matcher {
            declaredClass {
                usingStrings(
                    "MicroMsg.AppMsgLogic",
                    "summerbig initDownloadAttach msgLocalId[%d], msgXml[%s], downloadPath[%s]",
                    "summerbig initDownloadAttach ret[%b], rowid[%d], field_totalLen[%d], type[%d], isLargeFile[%d], destFile[%s], msgLocalId[%s], stack[%s]"
                )
            }

            paramTypes("long", "java.lang.String", "java.lang.String", "java.lang.String")
            returnType(BString)
        }
    }
    private val methodInsertDownloadAttach by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.model.app")
        matcher {
            declaredClass {
                usingStrings(
                    "MicroMsg.AppMsgLogic",
                    "summerbig initDownloadAttach ret[%b], rowid[%d], field_totalLen[%d], type[%d], isLargeFile[%d], destFile[%s], msgLocalId[%s], stack[%s]"
                )
            }

            paramTypes(
                "java.lang.String",
                "long",
                "java.lang.String",
                "int",
                "java.lang.String",
                "java.lang.String",
                "long",
                "int",
                "java.lang.String",
                "int"
            )
            returnType(BString)
        }
    }

    private fun ensureSaveDir(): String {
        val configured = File(currentSaveDir())
        if (configured.exists() && !configured.isDirectory) {
            WeLogger.w(TAG, "configured download path is not a directory: ${configured.absolutePath}")
            return createDefaultSaveDir()
        }

        runCatching { configured.mkdirs() }
            .onFailure { WeLogger.w(TAG, "failed to create download path: ${configured.absolutePath}", it) }

        return configured.absolutePath
    }

    private fun currentSaveDir(): String {
        return normalizeSaveDir(saveDir)
    }

    private fun normalizeSaveDir(rawPath: String): String {
        val normalized = rawPath.trim().replace('\\', '/').trimEnd('/')
        if (normalized.isEmpty()) return defaultSaveDir()
        if (normalized.startsWith("/")) return normalized
        return File(KnownPaths.internalStorage.toFile(), normalized).absolutePath
    }

    private fun defaultSaveDir(): String {
        return (KnownPaths.internalStorage / "Download" / "WeiXin").absolutePathString()
    }

    private fun createDefaultSaveDir(): String {
        return (KnownPaths.internalStorage / "Download" / "WeiXin")
            .createDirsSafe()
            .absolutePathString()
    }

    private fun buildRedirectedFilePath(msgXml: String, currentPath: String?): String? {
        val saveDir = File(ensureSaveDir())
        val nameFromXml = extractOriginalFileName(msgXml)
        val extFromXml = extractXmlTag(msgXml, "fileext")
        val fallbackName = currentPath?.let { File(it).name }?.takeIf { it.isNotBlank() }
        val fileName = buildFileName(nameFromXml ?: fallbackName, extFromXml)
        return nextAvailableFile(saveDir, fileName).absolutePath
    }

    private fun redirectExistingFilePath(currentPath: String): String? {
        if (isUnderSaveDir(currentPath)) return null
        if (!looksLikeWechatAttachPath(currentPath)) return null

        val fileName = currentPath
            .replace('\\', '/')
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() }
            ?: return null

        return nextAvailableFile(File(ensureSaveDir()), fileName).absolutePath
    }

    private fun buildFileName(rawName: String?, rawExt: String?): String {
        val ext = rawExt.orEmpty()
            .trim()
            .trimStart('.')
            .substringBefore('/')
            .substringBefore('\\')
        val baseName = rawName.orEmpty()
            .trim()
            .replace('\\', '/')
            .substringAfterLast('/')
            .replace(Regex("[\\u0000-\\u001F]"), "")
            .takeIf { it.isNotBlank() }
            ?: "da_${System.currentTimeMillis()}"

        if (ext.isEmpty()) return baseName
        return if (baseName.lowercase(Locale.ROOT).endsWith(".${ext.lowercase(Locale.ROOT)}")) {
            baseName
        } else {
            "$baseName.$ext"
        }
    }

    private fun nextAvailableFile(dir: File, fileName: String): File {
        val first = File(dir, fileName)
        if (!first.exists()) return first

        repeat(19) { index ->
            val candidate = File(dir, "${index + 1}_$fileName")
            if (!candidate.exists()) return candidate
        }

        val dotIndex = fileName.lastIndexOf('.')
        val prefix = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        val suffix = if (dotIndex > 0) fileName.substring(dotIndex) else ""
        return File(dir, "${prefix}_${System.currentTimeMillis()}$suffix")
    }

    private fun isUnderSaveDir(path: String): Boolean {
        val saveDir = File(currentSaveDir()).absolutePath.trimEnd('/', '\\')
        val target = File(path).absolutePath
        return target == saveDir || target.startsWith("$saveDir${File.separator}")
    }

    @SuppressLint("SdCardPath")
    private fun looksLikeWechatAttachPath(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        return normalized.startsWith("wcf://attachment/") ||
                normalized.contains("/micromsg/") && normalized.contains("/attachment/") ||
                normalized.startsWith("/data/data/com.tencent.mm/") ||
                normalized.startsWith("/data/user/0/com.tencent.mm/")
    }

    private fun extractOriginalFileName(xml: String): String? {
        val title = extractXmlTag(xml, "title")
        val attachFileName = extractXmlTag(xml, "filename")

        return title
            ?: attachFileName
            ?: extractXmlAttr(xml, "title")
            ?: extractXmlAttr(xml, "filename")
    }

    private fun extractXmlTag(xml: String, tag: String): String? {
        val value = Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun extractXmlAttr(xml: String, attr: String): String? {
        val value = Regex("""\b$attr\s*=\s*(['"])(.*?)\1""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(xml)
            ?.groupValues
            ?.getOrNull(2)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }
}
