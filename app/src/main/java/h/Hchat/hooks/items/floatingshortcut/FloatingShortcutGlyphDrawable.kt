package h.Hchat.hooks.items.floatingshortcut

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class FloatingShortcutGlyph {
    MENU,
    SETTINGS,
    SCAN,
    MOMENTS,
    FINDER,
    FAVORITE,
    WALLET,
    LAUNCH
}

object FloatingShortcutGlyphs {
    fun forItem(item: FloatingShortcutItem): FloatingShortcutGlyph {
        if (item.actionType == FloatingShortcutSettings.ACTION_MODULE_SETTINGS) {
            return FloatingShortcutGlyph.SETTINGS
        }
        val target = item.target.lowercase()
        return when {
            item.id == "scan" || "scanner" in target -> FloatingShortcutGlyph.SCAN
            item.id == "moments" || ".sns." in target -> FloatingShortcutGlyph.MOMENTS
            item.id == "finder" || ".finder." in target -> FloatingShortcutGlyph.FINDER
            item.id == "favorite" || ".fav." in target -> FloatingShortcutGlyph.FAVORITE
            item.id == "wallet" || ".mall." in target || ".wallet" in target -> FloatingShortcutGlyph.WALLET
            else -> FloatingShortcutGlyph.LAUNCH
        }
    }
}

class FloatingShortcutGlyphDrawable(
    private val glyph: FloatingShortcutGlyph,
    color: Int
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val size = minOf(bounds.width(), bounds.height()).toFloat()
        if (size <= 0f) return
        val left = bounds.exactCenterX() - size / 2f
        val top = bounds.exactCenterY() - size / 2f
        fun x(value: Float) = left + size * value
        fun y(value: Float) = top + size * value

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.065f
        path.reset()
        when (glyph) {
            FloatingShortcutGlyph.MENU -> {
                paint.style = Paint.Style.FILL
                val cell = size * 0.18f
                val gap = size * 0.11f
                val total = cell * 2f + gap
                val startX = bounds.exactCenterX() - total / 2f
                val startY = bounds.exactCenterY() - total / 2f
                for (row in 0..1) {
                    for (column in 0..1) {
                        val cellLeft = startX + column * (cell + gap)
                        val cellTop = startY + row * (cell + gap)
                        canvas.drawRoundRect(
                            cellLeft,
                            cellTop,
                            cellLeft + cell,
                            cellTop + cell,
                            cell * 0.28f,
                            cell * 0.28f,
                            paint
                        )
                    }
                }
            }

            FloatingShortcutGlyph.SETTINGS -> {
                val centerX = bounds.exactCenterX()
                val centerY = bounds.exactCenterY()
                val outerRadius = size * 0.29f
                canvas.drawCircle(centerX, centerY, outerRadius, paint)
                canvas.drawCircle(centerX, centerY, size * 0.105f, paint)
                for (index in 0 until 8) {
                    val angle = index * PI / 4.0
                    canvas.drawLine(
                        centerX + cos(angle).toFloat() * size * 0.34f,
                        centerY + sin(angle).toFloat() * size * 0.34f,
                        centerX + cos(angle).toFloat() * size * 0.43f,
                        centerY + sin(angle).toFloat() * size * 0.43f,
                        paint
                    )
                }
            }

            FloatingShortcutGlyph.SCAN -> {
                path.moveTo(x(0.36f), y(0.17f))
                path.lineTo(x(0.17f), y(0.17f))
                path.lineTo(x(0.17f), y(0.36f))
                path.moveTo(x(0.64f), y(0.17f))
                path.lineTo(x(0.83f), y(0.17f))
                path.lineTo(x(0.83f), y(0.36f))
                path.moveTo(x(0.17f), y(0.64f))
                path.lineTo(x(0.17f), y(0.83f))
                path.lineTo(x(0.36f), y(0.83f))
                path.moveTo(x(0.83f), y(0.64f))
                path.lineTo(x(0.83f), y(0.83f))
                path.lineTo(x(0.64f), y(0.83f))
                canvas.drawPath(path, paint)
                canvas.drawLine(x(0.28f), y(0.50f), x(0.72f), y(0.50f), paint)
            }

            FloatingShortcutGlyph.MOMENTS -> {
                val centerX = bounds.exactCenterX()
                val centerY = bounds.exactCenterY()
                rect.set(x(0.16f), y(0.16f), x(0.84f), y(0.84f))
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.09f
                for (index in 0 until 6) {
                    val startAngle = -87f + index * 60f
                    canvas.drawArc(rect, startAngle, 54f, false, paint)
                    val innerAngle = Math.toRadians((startAngle + 17f).toDouble())
                    val outerAngle = Math.toRadians((startAngle + 52f).toDouble())
                    canvas.drawLine(
                        centerX + cos(innerAngle).toFloat() * size * 0.13f,
                        centerY + sin(innerAngle).toFloat() * size * 0.13f,
                        centerX + cos(outerAngle).toFloat() * size * 0.31f,
                        centerY + sin(outerAngle).toFloat() * size * 0.31f,
                        paint
                    )
                }
                paint.strokeWidth = size * 0.06f
                canvas.drawCircle(centerX, centerY, size * 0.12f, paint)
            }

            FloatingShortcutGlyph.FINDER -> {
                rect.set(x(0.14f), y(0.23f), x(0.86f), y(0.77f))
                canvas.drawRoundRect(rect, size * 0.13f, size * 0.13f, paint)
                paint.style = Paint.Style.FILL
                path.moveTo(x(0.43f), y(0.36f))
                path.lineTo(x(0.43f), y(0.64f))
                path.lineTo(x(0.67f), y(0.50f))
                path.close()
                canvas.drawPath(path, paint)
            }

            FloatingShortcutGlyph.FAVORITE -> {
                paint.style = Paint.Style.FILL
                val centerX = bounds.exactCenterX()
                val centerY = bounds.exactCenterY() + size * 0.02f
                for (index in 0 until 10) {
                    val radius = if (index % 2 == 0) size * 0.37f else size * 0.17f
                    val angle = -PI / 2.0 + index * PI / 5.0
                    val pointX = centerX + cos(angle).toFloat() * radius
                    val pointY = centerY + sin(angle).toFloat() * radius
                    if (index == 0) path.moveTo(pointX, pointY) else path.lineTo(pointX, pointY)
                }
                path.close()
                canvas.drawPath(path, paint)
            }

            FloatingShortcutGlyph.WALLET -> {
                rect.set(x(0.14f), y(0.27f), x(0.84f), y(0.76f))
                canvas.drawRoundRect(rect, size * 0.10f, size * 0.10f, paint)
                canvas.drawLine(x(0.23f), y(0.27f), x(0.67f), y(0.17f), paint)
                rect.set(x(0.58f), y(0.42f), x(0.88f), y(0.62f))
                canvas.drawRoundRect(rect, size * 0.06f, size * 0.06f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x(0.68f), y(0.52f), size * 0.025f, paint)
            }

            FloatingShortcutGlyph.LAUNCH -> {
                path.moveTo(x(0.55f), y(0.20f))
                path.lineTo(x(0.80f), y(0.20f))
                path.lineTo(x(0.80f), y(0.45f))
                path.moveTo(x(0.79f), y(0.21f))
                path.lineTo(x(0.46f), y(0.54f))
                path.moveTo(x(0.67f), y(0.44f))
                path.lineTo(x(0.67f), y(0.75f))
                path.lineTo(x(0.22f), y(0.75f))
                path.lineTo(x(0.22f), y(0.30f))
                path.lineTo(x(0.53f), y(0.30f))
                canvas.drawPath(path, paint)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

}
