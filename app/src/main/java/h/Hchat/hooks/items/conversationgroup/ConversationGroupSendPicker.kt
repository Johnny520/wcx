package h.Hchat.hooks.items.conversationgroup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import h.Hchat.utils.HLog
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal object ConversationGroupSendPicker {
    private const val TAG = "[Hchat:ConversationGroup]"
    private const val REQUEST_CODE_START = 0x7610
    private const val REQUEST_CODE_END = 0x76ff
    private const val CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
    private val nextRequestCode = AtomicInteger(REQUEST_CODE_START)
    private val pending = ConcurrentHashMap<Int, ConversationGroupFileRequest<PickedFile?>>()
    private val requests = ConcurrentHashMap<Int, ConversationGroupFileRequest<PickedFile?>>()
    private val hooks = ConversationGroupFileResultHooks(::onResult, ::onHostFailure)

    fun launch(activity: Activity, mimeType: String, chooserTitle: String, callback: (PickedFile?) -> Unit): Unit = synchronized(hooks) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!hooks.ensure(activity.javaClass)) {
            cancelAll()
            callback(null)
            return
        }
        val requestCode = allocateRequestCode()
        val request = ConversationGroupFileRequest.create(activity, callback) {
            synchronized(hooks) {
                pending.remove(requestCode)
                requests.remove(requestCode)
                if (pending.isEmpty()) hooks.clear()
            }
        }
        if (request == null) {
            if (pending.isEmpty()) hooks.clear()
            callback(null)
            return
        }
        requests[requestCode] = request
        pending[requestCode] = request
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }.preferSystemDocumentsUi(activity)
        runCatching { activity.startActivityForResult(intent, requestCode) }.onFailure { firstError ->
            val fallback = Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, chooserTitle)
            runCatching { activity.startActivityForResult(fallback, requestCode) }.onFailure { secondError ->
                pending.remove(requestCode)
                request.deliver(null)
                HLog.e("$TAG 启动发送文件选择器失败: ${secondError.message}", secondError)
                HLog.e("$TAG 系统文档选择器错误: ${firstError.message}", firstError)
            }
        }
    }

    fun cancelAll() = synchronized(hooks) {
        requests.values.toList().forEach { it.cancel() }
        pending.clear()
        hooks.clear()
    }

    private fun onHostFailure(activity: Activity, code: Int) {
        val request = pending[code] ?: return
        if (request.activity.get() === activity && pending.remove(code, request)) request.cancel()
    }

    private fun onResult(activity: Activity, code: Int, resultCode: Int, data: Intent?) {
        val request = pending[code] ?: return
        if (request.activity.get() !== activity || !pending.remove(code, request)) return
        try {
            val uri = data?.data
            if (resultCode != Activity.RESULT_OK || uri == null) {
                request.deliver(null)
                return
            }
            val context = activity.applicationContext
            request.execute(task = { canceled ->
                if (canceled.get()) throw java.util.concurrent.CancellationException()
                takeReadPermission(context, data, uri)
                if (canceled.get()) throw java.util.concurrent.CancellationException()
                PickedFile(uri, displayName(context, uri))
            }, failure = { error ->
                HLog.e("$TAG 读取发送文件信息失败", error)
                null
            })
        } catch (error: Throwable) {
            request.cancel()
            HLog.e("$TAG 处理文件选择结果失败", error)
        }
    }

    fun materialize(context: Context, picked: PickedFile): MaterializedFile {
        val root = File(context.cacheDir, "hchat_conversation_group_send").apply { mkdirs() }
        cleanup(root)
        val target = uniqueFile(root, picked.displayName)
        context.contentResolver.openInputStream(picked.uri)?.use { input ->
            FileOutputStream(target, false).use { output -> input.copyTo(output) }
        } ?: error("无法读取所选文件")
        require(target.isFile && target.length() > 0L) { "所选文件内容为空" }
        return MaterializedFile(target.absolutePath, picked.displayName)
    }

    private fun takeReadPermission(context: Context, data: Intent, uri: Uri) {
        if (uri.scheme != "content") return
        runCatching {
            val flags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if ((flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)
            }
        }.getOrNull().orEmpty()
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':').orEmpty()
        return sanitizeName(queried.ifBlank { fallback }.ifBlank { "file_${System.currentTimeMillis()}" })
    }

    private fun sanitizeName(value: String): String {
        return value.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .trim()
            .take(180)
            .ifBlank { "file_${System.currentTimeMillis()}" }
    }

    private fun uniqueFile(root: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").takeIf(String::isNotBlank)
        repeat(1000) { index ->
            val suffix = if (index == 0) "" else "_$index"
            val candidateName = buildString {
                append(base)
                append(suffix)
                if (extension != null) append('.').append(extension)
            }
            val candidate = File(root, candidateName)
            if (!candidate.exists()) return candidate
        }
        return File(root, "${System.currentTimeMillis()}_$name")
    }

    private fun cleanup(root: File) {
        val cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS
        root.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }

    private fun allocateRequestCode(): Int {
        repeat(REQUEST_CODE_END - REQUEST_CODE_START + 1) {
            val candidate = nextRequestCode.updateAndGet { current ->
                if (current >= REQUEST_CODE_END) REQUEST_CODE_START else current + 1
            }
            if (!requests.containsKey(candidate)) return candidate
        }
        error("文件选择请求已满")
    }

    private fun Intent.preferSystemDocumentsUi(context: Context): Intent {
        for (packageName in listOf("com.google.android.documentsui", "com.android.documentsui")) {
            val copy = Intent(this).setPackage(packageName)
            if (runCatching { context.packageManager.queryIntentActivities(copy, 0) }
                    .getOrDefault(emptyList()).isNotEmpty()
            ) {
                setPackage(packageName)
                break
            }
        }
        return this
    }

    data class PickedFile(val uri: Uri, val displayName: String)

    data class MaterializedFile(val path: String, val displayName: String)
}
