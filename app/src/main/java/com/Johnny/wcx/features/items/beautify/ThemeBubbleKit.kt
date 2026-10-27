package com.Johnny.wcx.features.items.beautify

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.graphics.drawable.NinePatchDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.ui.utils.findViewWhich
import com.Johnny.wcx.utils.WeLogger
import com.tencent.mm.ui.widget.MMNeat7extView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.exists

/**
 * 消息气泡套件：备份 / 恢复 / 原子替换 / 按 9-patch 套用到消息视图。
 *
 * 2026-10-27 从 ThemeStore 单体拆出。气泡是「写进模块 assets 的两个点九文件」这条独立链路，
 * 与壁纸渲染完全无关，因此单独成层。
 */
internal object ThemeBubbleKit {

    private const val TAG = "ThemeBubbleKit"

    private val LEFT get() = ThemePaths.LEFT_BUBBLE_FILE
    private val RIGHT get() = ThemePaths.RIGHT_BUBBLE_FILE

    /** 当前模块目录里是否已有气泡文件（有才谈得上替换/清理）。 */
    fun activeExists(side: String): Boolean = runCatching {
        ThemePaths.activeBubble(side).exists() && Files.size(ThemePaths.activeBubble(side)) > 0L
    }.getOrDefault(false)

    /**
     * 应用主题前备份用户原有气泡（只备份第一次，之后一直保留到恢复默认）。
     */
    fun backupCurrent() {
        try {
            if (ThemePaths.hasBubbleBackup()) return
            val left = ThemePaths.activeBubble(LEFT)
            val right = ThemePaths.activeBubble(RIGHT)
            if (left.exists()) {
                Files.copy(left, ThemePaths.backupBubble(LEFT), StandardCopyOption.REPLACE_EXISTING)
            }
            if (right.exists()) {
                Files.copy(right, ThemePaths.backupBubble(RIGHT), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Throwable) {
            WeLogger.w(TAG, "备份气泡异常：${e.message}", e)
        }
    }

    /** 恢复默认：把备份的气泡还回去，并清掉备份。 */
    fun restoreBackup() {
        try {
            val left = ThemePaths.backupBubble(LEFT)
            val right = ThemePaths.backupBubble(RIGHT)
            if (left.exists()) {
                Files.copy(left, ThemePaths.activeBubble(LEFT), StandardCopyOption.REPLACE_EXISTING)
            }
            if (right.exists()) {
                Files.copy(right, ThemePaths.activeBubble(RIGHT), StandardCopyOption.REPLACE_EXISTING)
            }
            runCatching { ThemePaths.backupBubbleDir.toFile().deleteRecursively() }
        } catch (e: Throwable) {
            WeLogger.w(TAG, "还原气泡异常：${e.message}", e)
        }
    }

    /** 清掉当前生效的气泡文件（换主题前调用，避免新旧叠加）。 */
    fun clearActive() {
        runCatching {
            Files.deleteIfExists(ThemePaths.activeBubble(LEFT))
            Files.deleteIfExists(ThemePaths.activeBubble(RIGHT))
        }.onFailure { WeLogger.w(TAG, "清理旧气泡失败：${it.message}") }
    }

    /**
     * 原子替换气泡：先全部写成 .tmp，全部成功后逐个 move 覆盖。
     *
     * - 任一步失败 → 删掉 tmp，旧气泡原样保留（不会出现「两边气泡都没了」的空窗）
     * - 新主题没提供的那一侧，才删掉旧的，避免新旧叠加
     */
    fun replaceAtomic(entries: List<Pair<java.nio.file.Path, java.nio.file.Path>>) {
        val staged = ArrayList<Pair<java.nio.file.Path, java.nio.file.Path>>()
        try {
            for ((src, dst) in entries) {
                val tmp = dst.resolveSibling("${dst.fileName}.tmp")
                Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING)
                if (Files.size(tmp) <= 0L) error("气泡写入为空：$src")
                staged += tmp to dst
            }
            for ((tmp, dst) in staged) {
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING)
            }
            val provided = entries.map { it.second.fileName.toString() }.toSet()
            listOf(LEFT, RIGHT).filterNot { it in provided }.forEach { name ->
                Files.deleteIfExists(ThemePaths.activeBubble(name))
            }
            WeLogger.i(TAG, "气泡已替换：${provided.joinToString()}")
        } catch (e: Throwable) {
            staged.forEach { (tmp, _) -> runCatching { Files.deleteIfExists(tmp) } }
            WeLogger.w(TAG, "替换气泡失败，保留原有气泡：${e.message}", e)
            throw e
        }
    }

    // ══════════════════════════════════════════════════════════
    // 套用到消息视图（9-patch）
    // ══════════════════════════════════════════════════════════

    private val ninePatchCache =
        java.util.Collections.synchronizedMap(LinkedHashMap<String, Bitmap>(4, 0.75f, true))

    /** 解码点九原图并缓存（聊天列表会反复创建视图，不能每条都 decodeFile）。 */
    private fun ninePatchBitmap(file: java.nio.file.Path): Bitmap? {
        val key = "${file.absolutePathString()}|${runCatching { file.toFile().lastModified() }.getOrDefault(0L)}"
        ninePatchCache[key]?.let { if (!it.isRecycled) return it }
        val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePathString()) }.getOrNull() ?: return null
        ninePatchCache[key] = bmp
        if (ninePatchCache.size > 6) {
            val eldest = ninePatchCache.keys.firstOrNull()
            if (eldest != null && eldest != key) ninePatchCache.remove(eldest)
        }
        return bmp
    }

    /**
     * 把主题气泡套到一条消息的气泡视图上（left=对方 / right=自己）。
     * 文件不存在或解码失败时静默返回，绝不影响微信原样式。
     */
    fun applyToView(bubbleView: View, isSelfSender: Boolean) {
        val fileName = if (isSelfSender) RIGHT else LEFT
        val file = ThemePaths.activeBubble(fileName)
        if (!file.exists()) return
        val bitmap = ninePatchBitmap(file) ?: return
        try {
            val paddingLeft = bubbleView.paddingLeft
            val paddingTop = bubbleView.paddingTop
            val paddingRight = bubbleView.paddingRight
            val paddingBottom = bubbleView.paddingBottom

            val inner = Bitmap.createBitmap(bitmap, 1, 1, bitmap.width - 2, bitmap.height - 2)
            val hRanges = getRanges(bitmap, z = false, z2 = false)   // 左右两条黑边
            val vRanges = getRanges(bitmap, z = true, z2 = false)    // 上下两条黑边
            val topRange = getRanges(bitmap, z = true, z2 = true).firstOrNull()
            val bottomRange = getRanges(bitmap, z = false, z2 = true).firstOrNull()
            val rect = Rect(
                hRanges.firstOrNull()?.start ?: 0,
                topRange?.start ?: 0,
                bitmap.width - 2 - (hRanges.lastOrNull()?.end ?: 0),
                bitmap.height - 2 - (bottomRange?.end ?: 0)
            )
            val byteBuffer = ByteBuffer.allocate((vRanges.size + hRanges.size) * 8 + 68).apply {
                order(ByteOrder.nativeOrder())
                put(1.toByte())
                put((hRanges.size * 2).toByte())
                put((vRanges.size * 2).toByte())
                put(9.toByte())
                putInt(0)
                putInt(0)
                putInt(rect.left)
                putInt(rect.right)
                putInt(rect.top)
                putInt(rect.bottom)
                putInt(0)
                for (r in hRanges) { putInt(r.start); putInt(r.end) }
                for (r in vRanges) { putInt(r.start); putInt(r.end) }
                repeat(9) { putInt(1) }
            }
            val np = NinePatchDrawable(bubbleView.resources, inner, byteBuffer.array(), rect, null)
            val pressed = np.constantState?.newDrawable()?.mutate()?.apply {
                val hsv = FloatArray(3)
                AndroidColor.colorToHSV(0xFF000000.toInt(), hsv)
                hsv[2] *= 0.8f
                setTint(AndroidColor.HSVToColor(hsv))
            }
            val stateList = StateListDrawable().apply {
                if (pressed != null) {
                    addState(intArrayOf(android.R.attr.state_pressed), pressed)
                    addState(intArrayOf(android.R.attr.state_focused), pressed)
                }
                addState(intArrayOf(), np)
            }
            bubbleView.apply {
                background = stateList
                setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
            }
        } catch (e: Throwable) {
            WeLogger.w(TAG, "套用气泡异常：${e.message}", e)
        }
    }

    /** 消息视图创建时按类型找到真正承载气泡的那个子视图并套用。 */
    fun applyToMessageView(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam, view: View) {
        try {
            val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
            @Suppress("DEPRECATION")
            when (msgInfo.type) {
                MessageType.TEXT, MessageType.LINK, MessageType.GROUP_NOTE, MessageType.QUOTE -> {
                    val bubble = view.findViewWhich<MMNeat7extView> { it is MMNeat7extView } ?: return
                    applyToView(bubble, msgInfo.isSelfSender)
                }
                MessageType.VOIP -> {
                    val bubbleView = view.findViewWhich<LinearLayout> {
                        it.javaClass == LinearLayout::class.java &&
                            it.tag?.javaClass?.name?.startsWith("com.tencent.mm.ui.chatting.viewitems") == true
                    } ?: return
                    val bubbleTextView = bubbleView.findViewWhich<TextView> { it is TextView } ?: return
                    applyToView(bubbleTextView, msgInfo.isSelfSender)
                }
                else -> { /* 其它消息类型不换气泡 */ }
            }
        } catch (e: Throwable) {
            WeLogger.w(TAG, "消息视图套用气泡异常：${e.message}", e)
        }
    }

    /** 解析点九图某一行/列的黑边区间（原样沿用老实现，改了点九图就会画错）。 */
    private data class Range(val start: Int, val end: Int)

    /**
     * @param z  true = 扫竖边（列方向取像素），false = 扫横边（行方向取像素）
     * @param z2 true = 取图片最外那一条边（1px，用于定位 content 区），false = 取内侧第一条边（拉伸区间）
     */
    private fun getRanges(bitmap: Bitmap, z: Boolean, z2: Boolean): ArrayList<Range> {
        val width = if (z) bitmap.width else bitmap.height
        val i = width - 1
        var i2 = -1
        return ArrayList<Range>().apply {
            for (i3 in 1 until i) {
                val pixel = if (z && z2) {
                    bitmap.getPixel(i3, bitmap.height - 1)
                } else if (z) {
                    bitmap.getPixel(i3, 0)
                } else if (z2) {
                    bitmap.getPixel(bitmap.width - 1, i3)
                } else {
                    bitmap.getPixel(0, i3)
                }
                val iAlpha = AndroidColor.alpha(pixel)
                val iRed = AndroidColor.red(pixel)
                val iGreen = AndroidColor.green(pixel)
                val iBlue = AndroidColor.blue(pixel)
                if (iAlpha == 255 && iRed == 0 && iGreen == 0 && iBlue == 0) {
                    if (i2 == -1) i2 = i3 - 1
                } else if (i2 != -1) {
                    add(Range(i2, i3 - 1))
                    i2 = -1
                }
            }
            if (i2 != -1) add(Range(i2, width - 2))
        }
    }
}
