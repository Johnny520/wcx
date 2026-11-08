package h.Hchat.hooks.items.floatingshortcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import h.Hchat.preferences.HchatStorage
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

object FloatingShortcutIconStore {
    private const val MAX_ICON_EDGE = 256

    fun saveFromUri(context: Context, key: String, uri: Uri): String? {
        val safeKey = key.replace(Regex("[^A-Za-z0-9_-]"), "_").take(96)
        if (safeKey.isEmpty()) return null
        val bitmap = runCatching { decodeSampled(context, uri) }.getOrNull() ?: return null
        val directory = iconDirectory(context).apply { mkdirs() }
        val target = File(directory, "$safeKey.png")
        val temporary = File(directory, ".$safeKey-${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(temporary).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.fd.sync()
            }
            if (target.exists() && !target.delete()) error("无法替换旧图标")
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            FloatingShortcutIconLoader.invalidate(target.absolutePath)
            FloatingShortcutRuntime.onIconFileChanged(target.absolutePath)
            target.absolutePath
        } catch (_: Throwable) {
            temporary.delete()
            null
        } finally {
            bitmap.recycle()
        }
    }

    fun delete(context: Context, path: String?) {
        val file = path?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val root = runCatching { iconDirectory(context).canonicalFile }.getOrNull() ?: return
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return
        if (target.parentFile == root) {
            runCatching { target.delete() }
            FloatingShortcutIconLoader.invalidate(target.absolutePath)
            FloatingShortcutRuntime.onIconFileChanged(target.absolutePath)
        }
    }

    private fun decodeSampled(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_ICON_EDGE * 2) {
            sample *= 2
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val longest = max(decoded.width, decoded.height)
        if (longest <= MAX_ICON_EDGE) return decoded
        val scale = MAX_ICON_EDGE.toFloat() / longest
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
        return File(HchatStorage.storageDir(context), "FloatingShortcut/icons")
    }
}
