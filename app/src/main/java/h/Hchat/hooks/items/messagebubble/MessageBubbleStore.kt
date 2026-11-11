package h.Hchat.hooks.items.messagebubble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.NinePatch
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

object MessageBubbleStore {
    private data class CacheEntry(
        val modified: Long,
        val size: Long,
        val asset: BubbleAsset
    )

    private data class BubbleAsset(
        val bitmap: Bitmap,
        val ninePatchChunk: ByteArray?,
        val padding: Rect?,
        val textColor: Int
    )

    private val cache = ConcurrentHashMap<MessageBubbleSlot, CacheEntry>()

    @JvmStatic
    fun hasAsset(context: Context, slot: MessageBubbleSlot): Boolean {
        val file = assetFile(context, slot)
        return file.isFile && file.length() > 0L
    }

    @JvmStatic
    fun preload(context: Context, darkMode: Boolean) {
        for (outgoing in listOf(false, true)) {
            val requested = MessageBubbleSlot.resolve(outgoing, darkMode)
            if (loadAsset(context, requested) == null && requested.darkMode) {
                loadAsset(context, MessageBubbleSlot.resolve(outgoing, darkMode = false))
            }
        }
    }

    @JvmStatic
    fun createDrawable(context: Context, slot: MessageBubbleSlot): Drawable? {
        val asset = loadAsset(context, slot) ?: return null
        return MessageBubbleDrawable(asset.bitmap, asset.ninePatchChunk, asset.padding)
    }

    @JvmStatic
    fun recommendedTextColor(context: Context, slot: MessageBubbleSlot): Int? {
        return loadAsset(context, slot)?.textColor
    }

    @JvmStatic
    @Synchronized
    fun saveFromUri(context: Context, slot: MessageBubbleSlot, uri: Uri): Boolean {
        val target = assetFile(context, slot)
        val temporary = File(target.parentFile, target.name + ".tmp")
        return runCatching {
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_FILE_BYTES) error("气泡文件不能超过 32 MB")
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            } ?: error("无法读取气泡文件")
            if (!temporary.isFile || temporary.length() <= 0L) error("气泡文件为空")
            val decoded = decodeAsset(temporary) ?: error("无法解析气泡图片")
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.getOrElse {
                if (target.exists() && !target.delete()) error("旧气泡文件删除失败")
                if (!temporary.renameTo(target)) error("气泡文件替换失败")
            }
            cache[slot] = CacheEntry(target.lastModified(), target.length(), decoded)
            true
        }.getOrElse {
            temporary.delete()
            HLog.e("$TAG 保存${slot.displayName}失败: ${it.message}", it)
            false
        }
    }

    @JvmStatic
    @Synchronized
    fun remove(context: Context, slot: MessageBubbleSlot): Boolean {
        cache.remove(slot)
        val file = assetFile(context, slot)
        return !file.exists() || file.delete()
    }

    @Synchronized
    private fun loadAsset(context: Context, slot: MessageBubbleSlot): BubbleAsset? {
        val file = assetFile(context, slot)
        if (!file.isFile || file.length() <= 0L) {
            cache.remove(slot)
            return null
        }
        cache[slot]?.takeIf {
            it.modified == file.lastModified() &&
                it.size == file.length() &&
                !it.asset.bitmap.isRecycled
        }?.let { return it.asset }
        val decoded = decodeAsset(file) ?: return null
        cache[slot] = CacheEntry(file.lastModified(), file.length(), decoded)
        return decoded
    }

    private fun decodeAsset(file: File): BubbleAsset? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 ||
            width > MAX_DIMENSION || height > MAX_DIMENSION ||
            width.toLong() * height.toLong() > MAX_PIXELS
        ) {
            return null
        }
        val decodedPadding = Rect()
        val bitmap = FileInputStream(file).use { input ->
            BitmapFactory.decodeStream(
                input,
                decodedPadding,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            )
        }?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 } ?: return null
        val compiledChunk = bitmap.ninePatchChunk
            ?.takeIf { chunk -> NinePatch.isNinePatchChunk(chunk) }
        if (compiledChunk != null) {
            return BubbleAsset(
                bitmap,
                compiledChunk.copyOf(),
                decodedPadding.validPadding(),
                recommendedTextColor(bitmap)
            )
        }
        return decodeRawNinePatch(bitmap) ?: BubbleAsset(bitmap, null, null, recommendedTextColor(bitmap))
    }

    private fun decodeRawNinePatch(source: Bitmap): BubbleAsset? {
        if (source.width < 3 || source.height < 3 || !hasRawNinePatchBorder(source)) return null
        val innerWidth = source.width - 2
        val innerHeight = source.height - 2
        val xDivs = markerSpan(source, horizontal = true, trailing = false)
            .takeIf { it.isNotEmpty() } ?: intArrayOf(0, innerWidth)
        val yDivs = markerSpan(source, horizontal = false, trailing = false)
            .takeIf { it.isNotEmpty() } ?: intArrayOf(0, innerHeight)
        val contentX = markerSpan(source, horizontal = true, trailing = true)
        val contentY = markerSpan(source, horizontal = false, trailing = true)
        val padding = if (contentX.size == 2 && contentY.size == 2) {
            Rect(
                contentX[0],
                contentY[0],
                (innerWidth - contentX[1]).coerceAtLeast(0),
                (innerHeight - contentY[1]).coerceAtLeast(0)
            )
        } else {
            null
        }
        val bitmap = Bitmap.createBitmap(source, 1, 1, innerWidth, innerHeight)
        source.recycle()
        val chunk = buildNinePatchChunk(xDivs, yDivs, padding)
            .takeIf { runCatching { NinePatch.isNinePatchChunk(it) }.getOrDefault(false) }
        return BubbleAsset(bitmap, chunk, padding, recommendedTextColor(bitmap))
    }

    private fun hasRawNinePatchBorder(bitmap: Bitmap): Boolean {
        if (!isTransparent(bitmap.getPixel(0, 0)) ||
            !isTransparent(bitmap.getPixel(bitmap.width - 1, 0)) ||
            !isTransparent(bitmap.getPixel(0, bitmap.height - 1)) ||
            !isTransparent(bitmap.getPixel(bitmap.width - 1, bitmap.height - 1))
        ) {
            return false
        }
        var topMarker = false
        var leftMarker = false
        for (x in 1 until bitmap.width - 1) {
            val top = bitmap.getPixel(x, 0)
            if (!isTransparent(top) && !isBlackMarker(top)) return false
            if (isBlackMarker(top)) topMarker = true
            val bottom = bitmap.getPixel(x, bitmap.height - 1)
            if (!isTransparent(bottom) && !isBlackMarker(bottom)) return false
        }
        for (y in 1 until bitmap.height - 1) {
            val left = bitmap.getPixel(0, y)
            if (!isTransparent(left) && !isBlackMarker(left)) return false
            if (isBlackMarker(left)) leftMarker = true
            val right = bitmap.getPixel(bitmap.width - 1, y)
            if (!isTransparent(right) && !isBlackMarker(right)) return false
        }
        return topMarker && leftMarker
    }

    private fun markerSpan(bitmap: Bitmap, horizontal: Boolean, trailing: Boolean): IntArray {
        val length = if (horizontal) bitmap.width else bitmap.height
        var first = -1
        var last = -1
        for (coordinate in 1 until length - 1) {
            val pixel = if (horizontal) {
                bitmap.getPixel(coordinate, if (trailing) bitmap.height - 1 else 0)
            } else {
                bitmap.getPixel(if (trailing) bitmap.width - 1 else 0, coordinate)
            }
            if (isBlackMarker(pixel)) {
                if (first < 0) first = coordinate - 1
                last = coordinate
            }
        }
        return if (first >= 0 && last > first) intArrayOf(first, last) else intArrayOf()
    }

    private fun buildNinePatchChunk(xDivs: IntArray, yDivs: IntArray, padding: Rect?): ByteArray {
        val colors = IntArray((xDivs.size + 1) * (yDivs.size + 1)) { NO_COLOR }
        return ByteBuffer.allocate(32 + (xDivs.size + yDivs.size + colors.size) * 4)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(1.toByte())
                put(xDivs.size.toByte())
                put(yDivs.size.toByte())
                put(colors.size.toByte())
                putInt(0)
                putInt(0)
                putInt(padding?.left ?: 0)
                putInt(padding?.right ?: 0)
                putInt(padding?.top ?: 0)
                putInt(padding?.bottom ?: 0)
                putInt(0)
                xDivs.forEach { putInt(it) }
                yDivs.forEach { putInt(it) }
                colors.forEach { putInt(it) }
            }
            .array()
    }

    private fun isTransparent(color: Int): Boolean = color ushr 24 == 0

    private fun isBlackMarker(color: Int): Boolean {
        return color ushr 24 != 0 && color and 0x00FFFFFF == 0
    }

    private fun recommendedTextColor(bitmap: Bitmap): Int {
        val stepX = (bitmap.width / 32).coerceAtLeast(1)
        val stepY = (bitmap.height / 32).coerceAtLeast(1)
        var luminance = 0.0
        var samples = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                if (Color.alpha(color) >= 96) {
                    luminance += (0.299 * Color.red(color) +
                        0.587 * Color.green(color) +
                        0.114 * Color.blue(color)) / 255.0
                    samples++
                }
                x += stepX
            }
            y += stepY
        }
        return if (samples > 0 && luminance / samples >= 0.58) Color.BLACK else Color.WHITE
    }

    private fun Rect.validPadding(): Rect? {
        return takeIf {
            left >= 0 && top >= 0 && right >= 0 && bottom >= 0 &&
                (left + top + right + bottom > 0)
        }?.let(::Rect)
    }

    private fun assetFile(context: Context, slot: MessageBubbleSlot): File {
        return File(File(HchatStorage.storageDir(context), DIRECTORY_NAME), slot.fileName)
    }

    private class MessageBubbleDrawable(
        bitmap: Bitmap,
        chunk: ByteArray?,
        padding: Rect?
    ) : Drawable() {
        private val bitmap = bitmap
        private val ninePatch = chunk?.let {
            runCatching {
                if (NinePatch.isNinePatchChunk(it)) NinePatch(bitmap, it, "HchatMessageBubble") else null
            }.getOrNull()
        }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val contentPadding = padding?.let(::Rect)
        private var baseAlpha = 255
        private var pressed = false

        override fun draw(canvas: Canvas) {
            paint.alpha = if (pressed) (baseAlpha * PRESSED_ALPHA).toInt() else baseAlpha
            val patch = ninePatch
            if (patch != null) {
                patch.draw(canvas, bounds, paint)
            } else {
                canvas.drawBitmap(bitmap, null, bounds, paint)
            }
        }

        override fun setAlpha(alpha: Int) {
            baseAlpha = alpha.coerceIn(0, 255)
            invalidateSelf()
        }

        override fun getAlpha(): Int = baseAlpha

        override fun getPadding(padding: Rect): Boolean {
            val value = contentPadding ?: return false
            padding.set(value)
            return true
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun isStateful(): Boolean = true

        override fun onStateChange(state: IntArray): Boolean {
            val nextPressed = state.contains(android.R.attr.state_pressed)
            if (nextPressed == pressed) return false
            pressed = nextPressed
            invalidateSelf()
            return true
        }
    }

    private const val TAG = "[Hchat:MessageBubble]"
    private const val DIRECTORY_NAME = "message_bubbles"
    private const val MAX_FILE_BYTES = 32L * 1024L * 1024L
    private const val MAX_DIMENSION = 2048
    private const val MAX_PIXELS = 2_000_000L
    private const val NO_COLOR = 1
    private const val PRESSED_ALPHA = 0.82f
}
