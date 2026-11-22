package com.Johnny.wcx.features.api.core

import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tencent.mm.plugin.gif.MMWXGFJNI
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.fs.asPath
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * 聊天图片的「缓存到微信内部 / 下载到本地」实现。
 *
 * 从 [WeMessageApi] 抽出：这段逻辑只关心 `ImgInfo2` 表与 `image2/` 目录，与发送、
 * 撤回、贴纸都无关；它也是唯一需要 120 秒轮询与 CDN 下载回调的地方，留在 2.4k 行的
 * 门面里既不好读，也不好单独评估超时行为。
 *
 * 对外仍由 [WeMessageApi.cacheImage] / [WeMessageApi.downloadImage] 转发，调用方零改动。
 */
internal object WeMessageImageStore {

    private const val TAG = "WeMessageImageStore"

    private data class ImgInfoRow(
        val localId: Long,
        val talker: String,
        val bigImgPath: String,
        val hevcPath: String?,
        val midImgPath: String?,
        /** reserved1: 若 > 0, 表示存在"原图"行, 值为原图行的 id (仅基础行有意义)。 */
        val hdImgId: Long,
    )

    private fun ImgInfoRow(cursor: Cursor) = ImgInfoRow(
        localId = cursor.getLong(0),
        talker = cursor.getString(1) ?: "",
        bigImgPath = cursor.getString(2) ?: "",
        hevcPath = cursor.getString(3),
        midImgPath = cursor.getString(4),
        hdImgId = cursor.getLong(5),
    )

    private const val IMG_INFO_COLUMNS = "id, msgTalker, bigImgPath, hevcPath, midImgPath, reserved1"

    private fun queryImgInfoRow(msgSvrId: Long): ImgInfoRow? {
        val rows = WeDatabaseApi.rawQuery(
            "SELECT $IMG_INFO_COLUMNS FROM ImgInfo2 WHERE msgSvrId=?",
            arrayOf(msgSvrId.toString())
        )
        return rows.use { rows ->
            if (!rows.moveToFirst()) null else ImgInfoRow(rows)
        }
    }

    /** 按 id 查 ImgInfo2 行 (用于查找基础行 reserved1 指向的"原图"行)。 */
    private fun queryImgInfoRowById(id: Long): ImgInfoRow? {
        val rows = WeDatabaseApi.rawQuery(
            "SELECT $IMG_INFO_COLUMNS FROM ImgInfo2 WHERE id=?",
            arrayOf(id.toString())
        )
        return rows.use { rows ->
            if (!rows.moveToFirst()) null else ImgInfoRow(rows)
        }
    }

    /**
     * 解析 ImgInfo2 行对应的、已真正落地到 image2/ 的大图文件。
     * 微信可能把大图存为 bigImgPath / hevcPath / midImgPath 其中之一 (原图/HEVC 场景),
     * 因此逐个尝试并要求文件确实存在且非空。
     */
    private fun resolveExistingImageFile(row: ImgInfoRow): Path? {
        return listOfNotNull(row.bigImgPath, row.hevcPath, row.midImgPath)
            .filter { it.isNotEmpty() && !it.startsWith("SERVERID://") }
            .firstNotNullOfOrNull { name ->
                resolveImageFile(name)?.takeIf { it.isRegularFile() && it.fileSize() > 0 }
            }
    }

    /**
     * 确保图片已缓存到微信内部 image2/ 存储, 返回真正落地的图片文件。
     *
     * 若消息带"原图" (发送方勾选原图), ImgInfo2 基础行的 reserved1 会指向另一"原图"行,
     * 该行携带原图自己的 CDN key。微信"查看原图"正是对原图行的 localId 触发下载。
     * 因此: 有原图则下载原图行, 否则下载基础行 (即微信压缩过的大图)。
     *
     * 注意: 不能依赖 ImgInfo2.iscomplete —— 该列 DEFAULT 1, 且微信只按 offset<totalLen?0:1 写它,
     * 未下载的行也会读到 1; 微信内部判定完成用的是 totalLen==offset。同理 bigImgPath 被下载服务
     * 在真正写入文件字节 *之前* 就从 SERVERID:// 改写为最终文件名, 所以只能以磁盘上文件是否存在为准。
     */
    private fun ensureImageCachedFile(msgSvrId: Long): Path? {
        val baseRow = queryImgInfoRow(msgSvrId)
        if (baseRow == null) {
            // 查不到图片信息行：多数是消息未入库 / 表结构与当前微信版本不一致。
            WeLogger.w(TAG, "cacheImage: image info row not found, msgSvrId=" + msgSvrId)
            return null
        }

        // 有原图行则优先下载原图, 否则退回基础行
        val targetRow = baseRow.hdImgId.takeIf { it > 0 }
            ?.let { queryImgInfoRowById(it) }
            ?.also { WeLogger.i(TAG, "image has original (hdImgId=${baseRow.hdImgId}), downloading original") }
            ?: baseRow

        // 已在磁盘上则直接返回
        resolveExistingImageFile(targetRow)?.let { return it }

        // 触发 CDN 下载, 轮询直到文件真正落地。talker 用基础行的 (原图行可能未存 msgTalker)。
        if (!triggerDownload(targetRow.localId, targetRow.talker.ifEmpty { baseRow.talker })) {
            WeLogger.w(
                TAG,
                "cacheImage: trigger download rejected, msgSvrId=" + msgSvrId +
                    ", localId=" + targetRow.localId
            )
            return null
        }
        val cached = pollUntilImageFileExists(targetRow.localId)
        if (cached == null) {
            WeLogger.w(
                TAG,
                "cacheImage: download timeout, msgSvrId=" + msgSvrId +
                    ", localId=" + targetRow.localId
            )
        }
        return cached
    }

    /**
     * 缓存图片: 让微信把大图从 CDN 下载到它自己的 image2/ 存储 (相当于在聊天里点击图片下载)。
     * 缓存与下载分离 —— 此方法只负责把图片缓存到微信内部, 不解码也不拷贝到 Download/WCX/。
     * @return 缓存后大图在微信内部的绝对路径, 失败返回 null
     */
    fun cacheImage(msgSvrId: Long): String? {
        return try {
            ensureImageCachedFile(msgSvrId)?.absolutePathString()
        } catch (e: Exception) {
            WeLogger.e(TAG, "cacheImage failed", e)
            null
        }
    }

    /**
     * 下载图片: 先确保图片已缓存到微信内部, 再进行 WXGF 解码并保存到 Download/WCX/。
     * @return 保存到 Download/WCX/ 后的绝对路径, 失败返回 null
     */
    fun downloadImage(msgSvrId: Long): String? {
        return try {
            val file = ensureImageCachedFile(msgSvrId) ?: return null
            decodeAndSave(file)
        } catch (e: Exception) {
            WeLogger.e(TAG, "downloadImage failed", e)
            null
        }
    }

    private fun triggerDownload(imgLocalId: Long, talker: String): Boolean {
        return try {
            val m = WeServiceApi.methodDownloadImageServiceDownloadImage.method
            val downloadService = m.declaringClass.createInstance()

            val msgIdTalker = "com.tencent.mm.plugin.msg.MsgIdTalker".toClass()
                .createInstance(imgLocalId, talker)

            val wClass = m.parameterTypes[5]
            val listener = Proxy.newProxyInstance(wClass.classLoader, arrayOf(wClass)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    "toString" -> "WcxDownloadCallback"
                    else -> null
                }
            }

            val result = m.invoke(downloadService, imgLocalId, msgIdTalker, 0, null, 0, listener, -1, false)
            WeLogger.d(TAG, "triggerDownload result=$result")
            (result as? Int)?.let { it >= 0 } ?: false
        } catch (e: Exception) {
            WeLogger.e(TAG, "triggerDownload failed", e)
            false
        }
    }

    /** 轮询直到该 ImgInfo2 行的图片文件真正落地到磁盘 (以文件存在为准, 而非 iscomplete 标志)。 */
    private fun pollUntilImageFileExists(imgLocalId: Long): Path? {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000)
            val row = queryImgInfoRowById(imgLocalId) ?: continue
            resolveExistingImageFile(row)?.let { return it }
        }
        return null
    }

    /**
     * 读一张图并压成 JPEG（长边 ≤ [maxSide]）。
     *
     * **不触发下载**：只认已经在磁盘上的文件（大图 / 中图 / HEVC）。返回 null 表示这张图
     * 还没落地 —— 调用方应保持 `[图片]` 占位，不要等。CDN 下载要轮询 120 秒，等回来
     * 对方早就聊到下一句了。
     */
    fun readImageJpeg(msgSvrId: Long, maxSide: Int = 800, quality: Int = 70): ByteArray? {
        return try {
            val row = queryImgInfoRow(msgSvrId) ?: return null
            val file = resolveExistingImageFile(row) ?: return null
            val raw = if (looksLikeWxgf(file)) {
                MMWXGFJNI.wxam2PicBuf(file.readBytes(), 0, MMWXGFJNI.WXAM_SCENE_MISC)
                    ?: return null
            } else {
                file.readBytes()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null
            // 先按 2 的幂降采样到不超过 2 倍目标，再精确缩放：一次大图解码只占一份内存。
            var sample = 1
            while (longest / sample > maxSide * 2) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(
                raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
            val scaled = if (maxOf(bitmap.width, bitmap.height) > maxSide) {
                val ratio = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            WeLogger.w(TAG, "readImageJpeg failed: msgSvrId=" + msgSvrId, e)
            null
        }
    }

    private fun decodeAndSave(file: Path): String? {
        return try {
            val outDir = KnownPaths.downloads
            val outFile = outDir / file.name
            outFile.deleteIfExists()
            if (!looksLikeWxgf(file)) {
                // 普通 JPEG/PNG/GIF/WEBP 不需要 WXGF 解码：直接流式拷贝。
                // 原实现把整图读进内存再交给 wxam2PicBuf，大图（10~20MB 原图）会同时存在
                // 原始字节与解码结果两份，低内存机型容易触发 GC。
                Files.copy(file, outFile, StandardCopyOption.REPLACE_EXISTING)
            } else {
                val bytes = file.readBytes()
                val decoded = MMWXGFJNI.wxam2PicBuf(bytes, 0, MMWXGFJNI.WXAM_SCENE_MISC) ?: bytes
                outFile.writeBytes(decoded)
            }
            WeLogger.i(TAG, "saved: ${outFile.absolutePathString()}")
            outFile.absolutePathString()
        } catch (e: Exception) {
            WeLogger.e(TAG, "decodeAndSave failed", e)
            null
        }
    }

    /**
     * 是否是微信私有的 WXGF/WXAM 编码。
     *
     * 只读前 12 字节：命中常见图片 magic 就返回 false（走直接拷贝）；识别不出则返回 true，
     * 继续走原来的「读全文件 + isWxGF/wxam2PicBuf」路径。这样对未知格式保持原行为，
     * 不会因为魔数猜错而把 WXGF 当普通图片拷出去。
     */
    private fun looksLikeWxgf(file: Path): Boolean {
        val header = ByteArray(12)
        val read = runCatching {
            Files.newInputStream(file).use { input ->
                var offset = 0
                while (offset < header.size) {
                    val n = input.read(header, offset, header.size - offset)
                    if (n < 0) break
                    offset += n
                }
                offset
            }
        }.getOrDefault(0)
        if (read < 12) return true
        val knownImage = header.hasMagic(0, 0x47, 0x49, 0x46) ||            // GIF
            header.hasMagic(0, 0x89, 0x50, 0x4e, 0x47) ||                    // PNG
            header.hasMagic(0, 0xff, 0xd8, 0xff) ||                          // JPEG
            (header.hasMagic(0, 0x52, 0x49, 0x46, 0x46) &&                   // RIFF....WEBP
                header.hasMagic(8, 0x57, 0x45, 0x42, 0x50))
        return !knownImage
    }

    private fun resolveImageFile(bigImgPath: String): Path? {
        // 少数字段可能已是绝对路径, 直接使用
        if (bigImgPath.startsWith("/")) return bigImgPath.asPath
        // image2/xx/yy/<name> 需要文件名至少 4 个字符
        if (bigImgPath.length < 4) return null
        val microMsgPath = HostInfo.application.filesDir.asPath.parent / "MicroMsg"
        val accountDir = currentAccountDir(microMsgPath) ?: return null
        return accountDir / "image2" / bigImgPath.take(2) / bigImgPath.substring(2, 4) / bigImgPath
    }

    /**
     * MicroMsg 下最近的 32 位账号目录。
     *
     * 下载轮询每秒调一次 [resolveImageFile]，原实现每次都 `listDirectoryEntries()` 把
     * MicroMsg 下所有目录扫一遍（单张图最多 120 次）。这里按父目录 mtime 缓存：
     * 切号会新建账号目录 → 父目录 mtime 变化 → 自动重扫；读不到 mtime 时退回每次重扫。
     */
    @Volatile
    private var cachedAccountDir: Path? = null

    @Volatile
    private var cachedAccountDirMtime: Long = -1L

    private fun currentAccountDir(microMsgPath: Path): Path? {
        val mtime = runCatching { Files.getLastModifiedTime(microMsgPath).toMillis() }.getOrDefault(-1L)
        cachedAccountDir?.let { if (mtime >= 0L && mtime == cachedAccountDirMtime) return it }
        val found = runCatching {
            microMsgPath.listDirectoryEntries()
                .filter { Files.isDirectory(it) && it.fileName.toString().length == 32 }
                .maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
        }.getOrNull()
        if (found != null) {
            cachedAccountDir = found
            cachedAccountDirMtime = mtime
        }
        return found
    }

}
