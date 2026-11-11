package h.Hchat.hooks.items.customfriendavatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.LinkedHashMap

object CustomFriendAvatarStore {
    private data class CacheEntry(
        val modified: Long,
        val size: Long,
        val bytes: Long,
        val bitmap: Bitmap
    )

    private const val MAX_CACHE_ENTRIES = 128
    private const val MAX_CACHE_BYTES = 16L * 1024 * 1024
    private val cache = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)
    private var cacheBytes = 0L

    @JvmStatic
    fun configuredFriends(context: Context): Set<String> {
        val saved = CustomFriendAvatarSettings.preferences(context)
            .getStringSet(CustomFriendAvatarSettings.KEY_CONFIGURED_FRIENDS, emptySet())
            .orEmpty()
        return saved.filterTo(linkedSetOf()) { avatarFile(context, it).isFile }
    }

    @JvmStatic
    fun hasAvatar(context: Context, wxid: String?): Boolean {
        val id = wxid?.trim().orEmpty()
        return id.isNotEmpty() && avatarFile(context, id).isFile
    }

    @JvmStatic
    fun avatarPath(context: Context, wxid: String?): String {
        val id = wxid?.trim().orEmpty()
        return id.takeIf(String::isNotEmpty)?.let { avatarFile(context, it).absolutePath }.orEmpty()
    }

    @JvmStatic
    fun loadBitmap(context: Context, wxid: String?): Bitmap? {
        val id = wxid?.trim().orEmpty()
        if (id.isEmpty()) return null
        val file = avatarFile(context, id)
        if (!file.isFile || file.length() <= 0L) {
            removeCached(id)
            return null
        }
        synchronized(cache) {
            cache[id]?.let {
                if (it.modified == file.lastModified() &&
                    it.size == file.length() &&
                    !it.bitmap.isRecycled
                ) {
                    return it.bitmap
                }
                cache.remove(id)
                cacheBytes -= it.bytes
            }
        }
        val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
            ?: return null
        putCached(id, file.lastModified(), file.length(), decoded)
        return decoded
    }

    @JvmStatic
    fun loadNotificationBitmap(context: Context, wxid: String?): Bitmap? {
        if (!CustomFriendAvatarSettings.notificationsEnabled(context)) return null
        return loadBitmap(context, wxid)
    }

    @JvmStatic
    @Synchronized
    fun saveFromUri(context: Context, wxid: String, uri: Uri): Boolean {
        return saveFromUri(context, wxid, uri, trackConfiguredFriend = true)
    }

    @JvmStatic
    @Synchronized
    fun saveFromUri(
        context: Context,
        wxid: String,
        uri: Uri,
        trackConfiguredFriend: Boolean
    ): Boolean {
        val id = wxid.trim()
        if (id.isEmpty()) return false
        return runCatching {
            val source = decodeBounded(context, uri) ?: error("无法解析图片")
            val edge = minOf(source.width, source.height)
            val bitmap = if (source.width == edge && source.height == edge) {
                source
            } else {
                Bitmap.createBitmap(
                    source,
                    (source.width - edge) / 2,
                    (source.height - edge) / 2,
                    edge,
                    edge
                )
            }
            val target = avatarFile(context, id)
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(temporary).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    error("头像写入失败")
                }
                output.fd.sync()
            }
            if (!temporary.isFile || temporary.length() <= 0L) error("头像文件为空")
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.getOrElse {
                if (target.exists() && !target.delete()) error("旧头像删除失败")
                if (!temporary.renameTo(target)) error("头像替换失败")
            }
            if (trackConfiguredFriend) {
                val ids = configuredFriends(context).toMutableSet().apply { add(id) }
                CustomFriendAvatarSettings.preferences(context).edit()
                    .putStringSet(CustomFriendAvatarSettings.KEY_CONFIGURED_FRIENDS, ids)
                    .apply()
            }
            removeCached(id)
            true
        }.getOrElse {
            HLog.e("$TAG 保存自定义头像失败: wxid=$id, error=${it.message}", it)
            false
        }
    }

    @JvmStatic
    @Synchronized
    fun remove(context: Context, wxid: String): Boolean {
        val id = wxid.trim()
        if (id.isEmpty()) return false
        val file = avatarFile(context, id)
        val deleted = !file.exists() || file.delete()
        val ids = configuredFriends(context).toMutableSet().apply { remove(id) }
        CustomFriendAvatarSettings.preferences(context).edit()
            .putStringSet(CustomFriendAvatarSettings.KEY_CONFIGURED_FRIENDS, ids)
            .apply()
        removeCached(id)
        return deleted
    }

    @JvmStatic
    fun invalidate(wxid: String?) {
        wxid?.trim()?.takeIf { it.isNotEmpty() }?.let(::removeCached)
    }

    private fun putCached(id: String, modified: Long, size: Long, bitmap: Bitmap) {
        val bytes = bitmap.allocationByteCount.toLong().coerceAtLeast(0L)
        if (bytes == 0L || bytes > MAX_CACHE_BYTES) return
        synchronized(cache) {
            cache.remove(id)?.let { cacheBytes -= it.bytes }
            cache[id] = CacheEntry(modified, size, bytes, bitmap)
            cacheBytes += bytes
            val iterator = cache.entries.iterator()
            while ((cache.size > MAX_CACHE_ENTRIES || cacheBytes > MAX_CACHE_BYTES) && iterator.hasNext()) {
                cacheBytes -= iterator.next().value.bytes
                iterator.remove()
            }
        }
    }

    private fun removeCached(id: String) {
        synchronized(cache) {
            cache.remove(id)?.let { cacheBytes -= it.bytes }
        }
    }

    private fun avatarFile(context: Context, wxid: String): File {
        val directory = File(HchatStorage.storageDir(context), DIRECTORY_NAME)
        return File(directory, sha256(wxid) + ".png")
    }

    private fun decodeBounded(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private const val TAG = "[Hchat:CustomFriendAvatar]"
    private const val DIRECTORY_NAME = "custom_friend_avatars"
    private const val MAX_DIMENSION = 1024
}
