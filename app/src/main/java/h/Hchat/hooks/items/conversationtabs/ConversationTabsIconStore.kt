package h.Hchat.hooks.items.conversationtabs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import h.Hchat.preferences.HchatStorage
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max

object ConversationTabsIconStore {
    @JvmStatic
    fun isSupportedTabKey(tabKey: String?): Boolean =
        tabKey != null && tabKey.matches(Regex("[A-Za-z0-9_-]{1,64}"))

    @JvmStatic
    fun saveFromUri(context: Context, tabKey: String, uri: Uri): String? {
        val key = tabKey.trim()
        if (!isSupportedTabKey(key)) return null
        val bitmap = runCatching { decodeScaled(context, uri) }.getOrNull() ?: return null
        val directory = iconDirectory(context)
        if (!directory.isDirectory && !directory.mkdirs()) {
            bitmap.recycle()
            return null
        }
        val target = File(
            directory,
            "$key-${System.currentTimeMillis()}-${UUID.randomUUID()}.png"
        )
        val atomicFile = AtomicFile(target)
        var output: FileOutputStream? = null
        return try {
            val stream = atomicFile.startWrite()
            output = stream
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            atomicFile.finishWrite(stream)
            output = null
            if (target.isFile && target.length() > 0L) {
                target.absolutePath
            } else {
                atomicFile.delete()
                null
            }
        } catch (error: Throwable) {
            h.Hchat.utils.HLog.e("[Hchat:ConversationTabs] 导入标签图标失败", error)
            output?.let { runCatching { atomicFile.failWrite(it) } }
            atomicFile.delete()
            null
        } finally {
            bitmap.recycle()
        }
    }

    @JvmStatic
    fun delete(context: Context, path: String?): Boolean {
        val value = path?.trim().orEmpty()
        if (value.isEmpty()) return false
        val root = runCatching { iconDirectory(context).canonicalFile }.getOrNull() ?: return false
        val target = runCatching { File(value).canonicalFile }.getOrNull() ?: return false
        if (target.parentFile != root) return false
        return !target.exists() || runCatching { target.delete() }.getOrDefault(false)
    }

    @JvmStatic
    fun loadBitmap(path: String?): Bitmap? {
        val value = path?.trim().orEmpty()
        if (value.isEmpty()) return null
        val file = File(value)
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
    }

    private fun decodeScaled(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > MAX_ICON_EDGE * 2) {
            sampleSize *= 2
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        } ?: return null
        val longestEdge = max(decoded.width, decoded.height)
        if (longestEdge <= MAX_ICON_EDGE) return decoded
        val scale = MAX_ICON_EDGE.toFloat() / longestEdge
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun iconDirectory(context: Context): File {
        return File(HchatStorage.storageDir(context), DIRECTORY_NAME)
    }

    private const val DIRECTORY_NAME = "conversation_tab_icons"
    private const val MAX_ICON_EDGE = 128
}
