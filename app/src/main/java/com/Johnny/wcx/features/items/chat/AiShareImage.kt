package com.Johnny.wcx.features.items.chat

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.utils.WeLogger
import java.io.File

/**
 * 把 AI 生成的文字渲染成可以直接发朋友圈 / 群里的分享长图。
 *
 * 参考了三种常见样式（彩条卡片 / 漫画日报 / 霓虹科技），再补一个极简白卡：
 * - 宽度固定 1080，高度按文字量自适应 —— 字多就变长图，字少就是正常竖图；
 * - 全部手绘（Canvas + Paint），不依赖任何外部图片资源，样式参数集中在一个 [Skin] 里；
 * - 高度上限 [MAX_HEIGHT]，超长内容截断并加省略号，避免 OOM。
 */
object AiShareImage {

    private const val TAG = "AiShareImage"

    private const val WIDTH = 1080
    private const val MIN_HEIGHT = 1440
    private const val MAX_HEIGHT = 6000

    private const val PADDING = 72
    private const val TITLE_SIZE = 58f
    private const val SUB_SIZE = 30f
    private const val BODY_SIZE = 38f
    private const val LINE_GAP = 22f
    private const val FOOTER_SIZE = 26f

    /** 分享图样式。 */
    enum class Style(val label: String) {
        CLASSIC("彩条卡片"),
        COMIC("漫画日报"),
        NEON("霓虹科技"),
        MINIMAL("极简白卡"),
    }

    /** 一套样式的全部配色与结构参数。 */
    private class Skin(
        val frame: Int = Color.TRANSPARENT,
        val frameWidth: Int = 0,
        val background: Int,
        val backgroundEnd: Int = Color.TRANSPARENT,
        val card: Int,
        val cardRadius: Int = 40,
        val headerStart: Int,
        val headerEnd: Int,
        val headerHeight: Int = 180,
        val titleColor: Int = Color.WHITE,
        val bodyColor: Int,
        val subColor: Int,
        val footerBar: Int = Color.TRANSPARENT,
        val footerText: Int,
        val cardPadding: Int = 56,
    )

    private fun skinOf(style: Style): Skin = when (style) {
        Style.CLASSIC -> Skin(
            frame = Color.parseColor("#5C2D91"),
            frameWidth = 28,
            background = Color.parseColor("#5C2D91"),
            card = Color.WHITE,
            cardRadius = 44,
            headerStart = Color.parseColor("#2E6CDF"),
            headerEnd = Color.parseColor("#19C2A8"),
            headerHeight = 200,
            bodyColor = Color.parseColor("#1A1A1A"),
            subColor = Color.parseColor("#E8E8E8"),
            footerBar = Color.parseColor("#FFB300"),
            footerText = Color.WHITE,
        )

        Style.COMIC -> Skin(
            background = Color.parseColor("#0B0B0B"),
            card = Color.parseColor("#141414"),
            cardRadius = 36,
            headerStart = Color.parseColor("#FF69B4"),
            headerEnd = Color.parseColor("#FF1493"),
            headerHeight = 220,
            titleColor = Color.WHITE,
            bodyColor = Color.parseColor("#F2F2F2"),
            subColor = Color.parseColor("#FFD700"),
            footerBar = Color.parseColor("#FF69B4"),
            footerText = Color.WHITE,
        )

        Style.NEON -> Skin(
            background = Color.parseColor("#0A0E27"),
            backgroundEnd = Color.parseColor("#1B1035"),
            card = Color.parseColor("#121A3A"),
            cardRadius = 40,
            headerStart = Color.parseColor("#00F0FF"),
            headerEnd = Color.parseColor("#B829DD"),
            headerHeight = 200,
            titleColor = Color.parseColor("#0A0E27"),
            bodyColor = Color.parseColor("#E8F4FF"),
            subColor = Color.parseColor("#9FE8FF"),
            footerBar = Color.parseColor("#00F0FF"),
            footerText = Color.parseColor("#0A0E27"),
        )

        Style.MINIMAL -> Skin(
            background = Color.parseColor("#F2F2F7"),
            card = Color.WHITE,
            cardRadius = 32,
            headerStart = Color.parseColor("#009944"),
            headerEnd = Color.parseColor("#009944"),
            headerHeight = 160,
            titleColor = Color.WHITE,
            bodyColor = Color.parseColor("#1C1C1E"),
            subColor = Color.parseColor("#E6F5EC"),
            footerText = Color.parseColor("#8A8A8E"),
        )
    }

    /**
     * 渲染分享图。
     *
     * [title] 标题（一般是「群名 · 群聊总结」），[subtitle] 副标题（时间 / 会话类型），
     * [body] 正文（AI 返回的文本，按 \n 分段，超宽自动折行）。
     */
    fun render(style: Style, title: String, subtitle: String, body: String): Bitmap {
        val skin = skinOf(style)

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = skin.bodyColor
            textSize = BODY_SIZE
            typeface = Typeface.DEFAULT
        }
        val cardWidth = WIDTH - PADDING * 2 - skin.frameWidth * 2
        val textWidth = (cardWidth - skin.cardPadding * 2).toFloat()

        // 1) 先把正文折成行，才知道画布要多高
        val lines = wrapText(body, bodyPaint, textWidth)
        val lineHeight = BODY_SIZE + LINE_GAP
        var contentHeight = skin.headerHeight + skin.cardPadding * 2 + lines.size * lineHeight
        if (skin.footerBar != Color.TRANSPARENT) contentHeight += 96
        contentHeight += 120 // 页脚

        val height = (PADDING * 2 + contentHeight + skin.frameWidth * 2)
            .coerceIn(MIN_HEIGHT, MAX_HEIGHT)

        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 2) 背景（可渐变）与外框
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (skin.backgroundEnd != Color.TRANSPARENT) {
            bgPaint.shader = LinearGradient(
                0f, 0f, WIDTH.toFloat(), height.toFloat(),
                skin.background, skin.backgroundEnd, Shader.TileMode.CLAMP,
            )
        } else {
            bgPaint.color = skin.background
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), height.toFloat(), bgPaint)

        if (skin.frameWidth > 0) {
            val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = skin.frameWidth.toFloat()
                color = skin.frame
            }
            canvas.drawRect(
                skin.frameWidth / 2f,
                skin.frameWidth / 2f,
                WIDTH - skin.frameWidth / 2f,
                height - skin.frameWidth / 2f,
                framePaint,
            )
        }

        // 3) 卡片
        val cardLeft = (PADDING + skin.frameWidth).toFloat()
        val cardTop = (PADDING + skin.frameWidth).toFloat()
        val cardRight = WIDTH - cardLeft
        val cardBottom = height - cardTop
        val cardRect = RectF(cardLeft, cardTop, cardRight, cardBottom)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = skin.card }
        canvas.drawRoundRect(cardRect, skin.cardRadius.toFloat(), skin.cardRadius.toFloat(), cardPaint)

        // 4) 标题横幅（顶部圆角、底部直角）
        canvas.save()
        canvas.clipRect(cardRect)
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cardLeft, 0f, cardRight, 0f,
                skin.headerStart, skin.headerEnd, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(cardLeft, cardTop, cardRight, cardTop + skin.headerHeight, headerPaint)
        canvas.restore()

        // 5) 标题与副标题
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = skin.titleColor
            textSize = TITLE_SIZE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(
            ellipsize(title, titlePaint, textWidth),
            cardLeft + skin.cardPadding,
            cardTop + skin.cardPadding + TITLE_SIZE,
            titlePaint,
        )
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = skin.subColor
            textSize = SUB_SIZE
        }
        canvas.drawText(
            ellipsize(subtitle, subPaint, textWidth),
            cardLeft + skin.cardPadding,
            cardTop + skin.cardPadding + TITLE_SIZE + SUB_SIZE + 16f,
            subPaint,
        )

        // 6) 正文
        var y = cardTop + skin.headerHeight + skin.cardPadding + BODY_SIZE
        for (line in lines) {
            if (y > cardBottom - 150) break
            canvas.drawText(line, cardLeft + skin.cardPadding, y, bodyPaint)
            y += lineHeight
        }

        // 7) 底部色条
        if (skin.footerBar != Color.TRANSPARENT) {
            val barTop = cardBottom - 96
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = skin.footerBar }
            canvas.drawRect(cardLeft, barTop, cardRight, cardBottom, barPaint)
        }

        // 8) 页脚
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = skin.footerText
            textSize = FOOTER_SIZE
        }
        canvas.drawText(
            "由 WCX AI 生成 · ${AiWorkbench.timeLabel()}",
            cardLeft + skin.cardPadding,
            height - PADDING - skin.frameWidth - 24f,
            footerPaint,
        )

        return bitmap
    }

    /** 按像素宽度折行（中文逐字，英文按空格）。 */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = ArrayList<String>()
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return listOf("")
        for (paragraph in normalized.split('\n')) {
            if (paragraph.isEmpty()) {
                result.add("")
                continue
            }
            val builder = StringBuilder()
            for (ch in paragraph) {
                builder.append(ch)
                if (paint.measureText(builder.toString()) > maxWidth) {
                    // 回退一个字符，把当前字符放到下一行
                    builder.deleteCharAt(builder.length - 1)
                    if (builder.isNotEmpty()) result.add(builder.toString())
                    builder.setLength(0)
                    builder.append(ch)
                }
            }
            if (builder.isNotEmpty()) result.add(builder.toString())
        }
        return result
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var value = text
        while (value.isNotEmpty() && paint.measureText("$value…") > maxWidth) {
            value = value.dropLast(1)
        }
        return "$value…"
    }

    /** 写到缓存目录，返回文件（发送图片用）。 */
    fun saveTemp(context: Context, bitmap: Bitmap): File? = runCatching {
        val file = File(context.cacheDir, "ai_share_${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file
    }.onFailure { WeLogger.e(TAG, "写分享图失败", it) }.getOrNull()

    /** 存到相册（Pictures/WCX）。 */
    fun saveToGallery(context: Context, bitmap: Bitmap, name: String): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WCX")
            }
        }
        val uri = context.contentResolver
            .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
        context.contentResolver.openOutputStream(uri)?.use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        } ?: return@runCatching false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(uri.lastPathSegment ?: ""),
                arrayOf("image/png"),
                null,
            )
        }
        true
    }.onFailure { WeLogger.e(TAG, "保存到相册失败", it) }.getOrDefault(false)

    /** 发到会话。 */
    fun sendToChat(talker: String, file: File): Boolean = runCatching {
        WeMessageApi.sendImage(talker, file.absolutePath)
    }.onFailure { WeLogger.e(TAG, "发送分享图失败", it) }.getOrDefault(false)
}
