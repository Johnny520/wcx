package com.Johnny.wcx.features.items.easter_egg

import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.text.TextPaint
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.tencent.mm.ui.base.NoMeasuredTextView
import com.Johnny.reflekt.reflekt
import com.Johnny.reflekt.utils.makeAccessible
import com.Johnny.reflekt.utils.toClass
import com.Johnny.wcx.features.core.BaseFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.TargetProcesses
import java.lang.reflect.Field
import java.time.LocalDate
import java.time.Month
import java.util.WeakHashMap


@Feature(name = "愚人节彩蛋", categories = ["彩蛋"], description = "不显示于模块界面, 愚人节自动启用")
object AprilFools : BaseFeature() {

    const val KEY_SURRENDER = "april_fools_surrender"

    override fun startup() {
        if (!TargetProcesses.isInMain) return

        if (!LocalDate.now().isAprilFools) {
            WePrefs.putBool(KEY_SURRENDER, false)
        } else {
            if (WePrefs.getBoolOrFalse(KEY_SURRENDER)) return
            enable()
        }
    }

    private val viewStateMap = WeakHashMap<View, TextViewAnimationState>()

    private data class TextViewAnimationState(val matrix: Matrix, var offset: Float)

    private lateinit var noMeasuredTvTextProp: Field
    private lateinit var noMeasuredTvPaintProp: Field

    private const val PIXELS_PER_FRAME = 10.0f
    private val RAINBOW_COLORS = intArrayOf(
        Color.RED, Color.YELLOW, Color.GREEN,
        Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
    )

    override fun onEnable() {
        ImageView::class.reflekt()
            .firstConstructor { parameterCount = 4 }.hookAfter {
                applyRotation(thisObject as View)
            }

        "com.tencent.mm.ui.widget.QImageView".toClass().reflekt()
            .firstConstructor().hookAfter {
                applyRotation(thisObject as View)
            }

        TextView::class.reflekt().firstMethod { name = "onDraw" }.hookBefore {
            val tv = thisObject as TextView
            applyRainbowEffect(tv, tv.text, tv.paint)
        }

        NoMeasuredTextView::class.reflekt()
            .firstMethod { name = "onDraw" }.hookBefore {
                val view = thisObject as View

                if (!::noMeasuredTvTextProp.isInitialized) {
                    noMeasuredTvTextProp = view.reflekt().firstField { name = "mText" }.self.makeAccessible()
                    noMeasuredTvPaintProp = view.reflekt().firstField { type = TextPaint::class }.self.makeAccessible()
                }

                applyRainbowEffect(
                    view,
                    noMeasuredTvTextProp.get(view) as CharSequence,
                    noMeasuredTvPaintProp.get(view) as TextPaint
                )
            }
    }

    private fun applyRotation(view: View) {
        view.post {
            ObjectAnimator.ofFloat(view, "rotation", 0f, 360f).apply {
                duration = 1000
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }
    }

    private fun applyRainbowEffect(view: View, text: CharSequence, paint: TextPaint) {
        val width = view.measuredWidth.toFloat()
        if (width <= 0f || text.isEmpty()) return

        val state = viewStateMap.getOrPut(view) { TextViewAnimationState(Matrix(), 0f) }

        val rainbowWidth = width.coerceAtLeast(400f)
        val shader = LinearGradient(
            0f, 0f, rainbowWidth, 0f,
            RAINBOW_COLORS, null, Shader.TileMode.REPEAT
        )

        state.offset += PIXELS_PER_FRAME
        if (state.offset > rainbowWidth) state.offset -= rainbowWidth

        state.matrix.setTranslate(state.offset, 0f)
        shader.setLocalMatrix(state.matrix)

        paint.shader = shader

        view.postInvalidateDelayed(60)
    }
}

val LocalDate.isAprilFools get() = this.month == Month.APRIL && this.dayOfMonth == 1
