package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import com.Johnny.wcx.utils.WeLogger
import java.io.File

/**
 * 4 个 Tab（主页 / 通讯录 / 发现 / 我）主题背景渲染器 —— 2026-10-27 从零重写。
 *
 * ## 为什么用 DecorView 的 foreground（前景遮罩）
 *
 * 历史踩过的三种做法全部放弃：
 * 1. 往 DecorView index 0 插层：会改变子节点顺序，微信 LauncherUI 空闲时预加载聊天页
 *    （`com.tencent.mm.ui.bg.queueIdle`）按索引找窗口容器拿到错节点 → `setVisibility` NPE 崩微信。
 * 2. 插进 `android.R.id.content` 底层：被微信各页不透明根容器整块盖住，永远看不见。
 * 3. 自绘 View 挂 DecorView 顶层：后台 Activity 不走 layout，那一层永远 0×0 画不出来。
 *
 * foreground 一次解决全部三个问题：
 * - **绘制顺序**：`View#dispatchDraw` 之后才画 foreground，位于**所有子 View 之上**，
 *   任何容器底色都盖不住它（不需要清背景、不需要近千行清底板逻辑）。
 * - **不新增子 View**：`childCount` / `getChildAt(0)` 完全不变，微信按索引找容器的代码不受影响。
 * - **尺寸**：bounds 由 DecorView 自身决定，DecorView 必然布局，不存在 0×0。
 * - **触摸**：foreground 只负责画，**不参与事件分发** —— 点击、滑动、长按全部照常落到原页面。
 * - **浓度**：用 [Drawable.setAlpha] 表达「壁纸透明度 %」，文字 / 时间 / 分割线始终透得过来。
 */
internal object TabWallpaperRenderer {

    private const val TAG = "TabWallpaper"

    /** 4 张分页图 + 1 张聊天页随机图。 */
    private const val MAX_CACHED_BITMAPS = 5

    /** 与主题工坊的透明度上下限保持一致。 */
    private const val MIN_ALPHA_PCT = 5
    private const val MAX_ALPHA_PCT = 70

    /** path|modified → Bitmap；切 Tab 反复换图不重复解码。 */
    private val bitmapCache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > MAX_CACHED_BITMAPS
    }

    /** 我们写过的窗口 → 原本的 foreground（有些窗口自带遮罩/阴影），恢复时原样放回。 */
    private val savedForeground = java.util.WeakHashMap<ViewGroup, Drawable?>()

    /** 我们写过的窗口 → 当前这张遮罩，用来判断「微信有没有把它换掉」。 */
    private val masks = java.util.WeakHashMap<ViewGroup, WallpaperMaskDrawable>()

    /** 只对挂过的窗口做清理，避免白跑。 */
    private val masked = java.util.WeakHashMap<ViewGroup, Boolean>()

    // ── 诊断快照（主题诊断页直接读，不用再猜） ──
    @Volatile var lastAt = 0L
        private set
    @Volatile var lastTab = -9
        private set
    @Volatile var lastPath = "-"
        private set
    @Volatile var lastAlphaPct = -1
        private set
    @Volatile var lastResult = "还没执行过"
        private set
    @Volatile private var lastDrawable: WallpaperMaskDrawable? = null

    private fun ok(activity: Activity, file: File, tab: Int, alphaPct: Int): String {
        lastAt = System.currentTimeMillis()
        lastTab = tab
        lastPath = file.absolutePath
        lastAlphaPct = alphaPct
        lastResult = "ok"
        WeLogger.d(TAG, "前景遮罩已写入 ${activity.javaClass.simpleName} tab=$tab alpha=${alphaPct}% ${file.name}")
        return "ok"
    }

    private fun fail(reason: String): String {
        lastResult = reason
        WeLogger.w(TAG, "前景遮罩写入失败：$reason")
        return reason
    }

    /**
     * 写入 / 刷新某个窗口的前景遮罩。
     *
     * @param file      当前 Tab 的壁纸文件；null 表示这页没有素材（会清掉遮罩）
     * @param tab       当前 Tab，仅用于快照与日志
     * @param alphaPct  壁纸透明度（5..70）
     * @return "ok" 或失败原因（调用方据此累计失败次数）
     */
    fun apply(activity: Activity, file: File?, tab: Int, alphaPct: Int): String {
        val decor = activity.window?.decorView as? ViewGroup ?: return fail("decorView 不可用")
        if (file == null) {
            clear(activity)
            return fail("本页没有壁纸文件")
        }
        if (!file.exists()) return fail("文件不存在：${file.absolutePath}")
        val bmp = loadBitmap(file) ?: return fail("解码失败：${file.absolutePath}")

        val mask = WallpaperMaskDrawable(bmp)
        mask.setAlpha(alphaPct.coerceIn(MIN_ALPHA_PCT, MAX_ALPHA_PCT) * 255 / 100)
        if (!savedForeground.containsKey(decor)) savedForeground[decor] = decor.foreground
        runCatching { decor.foreground = mask }
            .onFailure { return fail("setForeground 抛异常：${it.message}") }
        masks[decor] = mask
        masked[decor] = true
        lastDrawable = mask
        // 兜底：首次 apply 时 DecorView 可能还没完成布局（bounds 为 0），
        // 这里手动给一次 bounds；后续尺寸变化由 View.sizeChange 自动更新。
        if (decor.width > 0 && decor.height > 0) {
            runCatching { mask.setBounds(0, 0, decor.width, decor.height) }
        }
        runCatching { decor.invalidate() }
        reassertLater(activity, mask, alphaPct)
        return ok(activity, file, tab, alphaPct)
    }

    /**
     * 微信偶尔会在页面刷新时把 foreground 换掉 / 清掉。
     * 这里只做「检查一下、不对就写回」的轻量保活（不动视图树，不遍历背景）。
     */
    private fun reassertLater(activity: Activity, mask: WallpaperMaskDrawable, alphaPct: Int) {
        listOf(300L, 1200L, 3000L).forEach { delay ->
            activity.window?.decorView?.postDelayed({
                val decor = activity.window?.decorView as? ViewGroup ?: return@postDelayed
                if (masked[decor] != true) return@postDelayed
                if (decor.foreground === mask) return@postDelayed
                // 被微信换掉了 → 写回我们的遮罩（原 foreground 早在第一次 apply 时备份过）
                runCatching {
                    mask.setAlpha(alphaPct.coerceIn(MIN_ALPHA_PCT, MAX_ALPHA_PCT) * 255 / 100)
                    if (decor.width > 0 && decor.height > 0) mask.setBounds(0, 0, decor.width, decor.height)
                    decor.foreground = mask
                    decor.invalidate()
                    WeLogger.d(TAG, "前景遮罩被宿主替换，已写回")
                }.onFailure { WeLogger.w(TAG, "前景遮罩写回失败：${it.message}") }
            }, delay)
        }
    }

    /** 临时藏起来（进聊天页时），保留遮罩对象，回来再显示。 */
    fun hide(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (masked[decor] != true) return
        runCatching {
            if (decor.foreground != null) {
                decor.foreground = null
                decor.invalidate()
            }
        }
    }

    /** 从 [hide] 恢复。 */
    fun show(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val mask = masks[decor] ?: return
        if (decor.foreground === mask) return
        runCatching {
            decor.foreground = mask
            decor.invalidate()
        }
    }

    /** 彻底撤销：还回窗口原来的 foreground。 */
    fun clear(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (masked.remove(decor) == null) return
        val mask = masks.remove(decor)
        if (lastDrawable === mask) lastDrawable = null
        runCatching { decor.foreground = savedForeground.remove(decor) }
            .onFailure { WeLogger.w(TAG, "还原 foreground 失败：${it.message}") }
        runCatching { decor.invalidate() }
    }

    fun isMasked(activity: Activity): Boolean =
        runCatching { masked[activity.window?.decorView as? ViewGroup] == true }.getOrDefault(false)

    /** 诊断文本：这一屏到底有没有遮罩、画了几次、尺寸多少。 */
    fun describe(activity: Activity): String = buildString {
        val decor = activity.window?.decorView as? ViewGroup ?: run { append("decorView 不可用"); return }
        val mask = masks[decor]
        val fg = decor.foreground
        append("前景遮罩=")
        when {
            masked[decor] != true -> append("未挂载")
            mask == null -> append("已挂载（遮罩对象丢失）")
            else -> append("已挂载 size=${decor.width}x${decor.height} 子View数=${decor.childCount}")
        }
        append(" 当前foreground=")
        append(
            when {
                fg == null -> "null（被隐藏或被宿主清掉）"
                fg === mask -> "我们的遮罩 alpha=${mask.alphaPct}% 绘制=${mask.drawCount}次 bounds=${mask.lastDrawnBounds}"
                else -> "被宿主替换为 ${fg.javaClass.simpleName}"
            }
        )
        append(" 原foreground=")
        append(if (savedForeground.containsKey(decor)) describeDrawable(savedForeground[decor]) else "未备份")
    }

    private fun describeDrawable(d: Drawable?): String = when (d) {
        null -> "null"
        is android.graphics.drawable.BitmapDrawable ->
            "BitmapDrawable(${d.bitmap?.width}x${d.bitmap?.height}, alpha=${d.alpha})"
        else -> d.javaClass.simpleName
    }

    /** 读图 + 缓存；解码失败返回 null 并留日志。 */
    private fun loadBitmap(file: File): Bitmap? {
        val key = "${file.absolutePath}|${file.lastModified()}"
        synchronized(bitmapCache) { bitmapCache[key] }?.let { return it }
        val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        if (bmp == null) {
            WeLogger.w(TAG, "壁纸解码失败：$key")
            return null
        }
        synchronized(bitmapCache) { bitmapCache[key] = bmp }
        return bmp
    }

    /** 换主题 / 改素材时清缓存，避免拿到旧图。 */
    fun invalidateCache() {
        synchronized(bitmapCache) { bitmapCache.clear() }
        lastPath = "-"
    }

    /**
     * 遮罩本体：把主题图按 bounds 拉伸铺满，只管画、不接事件。
     *
     * 同时充当绘制探针（drawCount / lastDrawnBounds）：
     * drawCount == 0 就说明这一屏 foreground 根本没被绘制（宿主没重绘或被替换），
     * 诊断页能直接看出来，不用再猜。
     */
    private class WallpaperMaskDrawable(private val bitmap: Bitmap) : Drawable() {

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

        @Volatile var drawCount = 0
            private set

        @Volatile var lastDrawnBounds = "0x0"
            private set

        /** 当前透明度百分比，诊断用。 */
        @Volatile var alphaPct = 100
            private set

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            drawCount++
            lastDrawnBounds = "${b.width()}x${b.height()}"
            if (bitmap.isRecycled) return
            canvas.drawBitmap(bitmap, null, b, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            alphaPct = alpha * 100 / 255
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
