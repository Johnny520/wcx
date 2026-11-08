package h.Hchat.hooks.items.floatingshortcut

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import h.Hchat.utils.HLog
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** All view/cache bookkeeping runs on main; only bounded image decoding runs on the worker. */
internal object FloatingShortcutIconLoader {
    private const val MAX_EDGE = 256
    private const val MAX_PENDING = 64
    private data class Entry(val bitmap: Bitmap?)
    private class Request {
        val targets = mutableListOf<Pair<WeakReference<ImageView>, Any>>()
    }

    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Entry>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Entry): Int =
            (value.bitmap?.allocationByteCount ?: 0) + 128
    }
    private val pending = mutableMapOf<String, Request>()
    private val bindings = WeakHashMap<ImageView, Any>()
    private val worker = ThreadPoolExecutor(
        0, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(MAX_PENDING),
        { task -> Thread(task, "Hchat-FloatingIcons").apply { isDaemon = true } }
    )

    fun bind(view: ImageView, path: String, fallback: Drawable) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val token = Any()
        bindings[view] = token
        view.setImageDrawable(fallback)
        if (path.isBlank()) return
        cache.get(path)?.let { entry ->
            entry.bitmap?.let { view.setImageDrawable(BitmapDrawable(view.resources, it)) }
            return
        }
        request(path)?.targets?.add(WeakReference(view) to token)
    }

    fun prefetch(paths: List<String>) = onMain {
        paths.asSequence().filter { it.isNotBlank() }.distinct().take(MAX_PENDING).forEach { path ->
            if (cache.get(path) == null) request(path)
        }
    }

    fun invalidate(path: String) = onMain {
        cache.remove(path)
        // The old decode may finish after replacement. Identity checks below discard that result.
        pending.remove(path)
    }

    fun clear() = onMain {
        pending.clear()
        bindings.clear()
        cache.evictAll()
        worker.queue.clear()
        // Do not recycle cached bitmaps: detached views may still draw during their final frame.
    }

    private fun request(path: String): Request? {
        pending[path]?.let { return it }
        if (pending.size >= MAX_PENDING) return null
        val request = Request()
        pending[path] = request
        try {
            worker.execute {
                val bitmap = try {
                    decode(path)
                } catch (error: Throwable) {
                    HLog.e("[Hchat:FloatingShortcut] 图标解码失败", error)
                    null
                }
                main.post {
                    if (pending[path] !== request) return@post
                    pending.remove(path)
                    cache.put(path, Entry(bitmap))
                    if (bitmap != null) request.targets.forEach { (reference, token) ->
                        val target = reference.get()
                        if (target != null && bindings[target] === token && target.isAttachedToWindow) {
                            target.setImageDrawable(BitmapDrawable(target.resources, bitmap))
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            pending.remove(path)
            return null
        }
        return request
    }

    private fun decode(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_EDGE * 2) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
        }) ?: return null
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge <= MAX_EDGE) return bitmap
        val scale = MAX_EDGE.toFloat() / edge
        val scaled = Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }
}
