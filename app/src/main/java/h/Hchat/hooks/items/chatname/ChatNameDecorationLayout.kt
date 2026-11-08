package h.Hchat.hooks.items.chatname

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ReplacementSpan
import android.view.View
import android.widget.TextView
import h.Hchat.hooks.items.membertitle.MemberTitleStore
import h.Hchat.R

object ChatNameDecorationLayout {
    data class Row(val nameView: TextView) {
        val titleView: TextView get() = nameView
        val tailView: TextView get() = nameView
    }

    private data class TitleState(
        val text: CharSequence = "",
        val bgStart: Int = 0,
        val bgEnd: Int = 0,
        val textStart: Int = 0,
        val textEnd: Int = 0,
        val onClick: (() -> Unit)? = null
    )

    private data class State(
        var baseName: CharSequence = "",
        var baseNameStyle: BaseNameStyle? = null,
        var title: TitleState = TitleState(),
        var tail: CharSequence = "",
        var lastRendered: String = ""
    )

    private data class BaseNameStyle(
        val color: MemberTitleStore.ColorSpec?,
        val weight: Int
    )

    // 装饰状态只归当前视图所有，回调即使引用本行也不会形成进程级保留链。
    private data class Decoration(val row: Row, val state: State)

    fun current(nameView: TextView): Row? {
        return (nameView.getTag(R.id.hchat_chat_name_decoration) as? Decoration)?.row
    }

    fun ensure(nameView: TextView): Row {
        current(nameView)?.let { return it }
        val row = Row(nameView)
        nameView.setTag(
            R.id.hchat_chat_name_decoration,
            Decoration(row, State(baseName = stripInlineDecorations(nameView.text)))
        )
        nameView.setSingleLine(false)
        nameView.maxLines = Int.MAX_VALUE
        nameView.ellipsize = null
        nameView.highlightColor = 0
        return row
    }

    fun displayNameText(nameView: TextView): CharSequence {
        (nameView.getTag(R.id.hchat_chat_name_decoration) as? Decoration)?.state?.let {
            if (it.lastRendered.isNotEmpty() && nameView.text?.toString() == it.lastRendered) {
                return it.baseName
            }
        }
        return stripInlineDecorations(nameView.text)
    }

    fun setBaseName(row: Row, text: CharSequence) {
        state(row).baseName = text
        compose(row)
    }

    fun showBaseNameStyle(row: Row, color: MemberTitleStore.ColorSpec?, weight: Int) {
        stateForDecoration(row).baseNameStyle = BaseNameStyle(color, weight)
        compose(row)
    }

    fun hideBaseNameStyle(row: Row) {
        stateForDecoration(row).baseNameStyle = null
        compose(row)
    }

    fun showTitle(
        row: Row,
        text: CharSequence,
        backgroundStart: Int,
        backgroundEnd: Int,
        textColorStart: Int,
        textColorEnd: Int,
        onClick: (() -> Unit)?
    ) {
        stateForDecoration(row).title = TitleState(
            text = text,
            bgStart = backgroundStart,
            bgEnd = backgroundEnd,
            textStart = textColorStart,
            textEnd = textColorEnd,
            onClick = onClick
        )
        compose(row)
    }

    fun hideTitle(row: Row) {
        stateForDecoration(row).title = TitleState()
        compose(row)
    }

    fun showTail(row: Row, text: CharSequence) {
        stateForDecoration(row).tail = text
        compose(row)
    }

    fun hideTail(row: Row) {
        stateForDecoration(row).tail = ""
        compose(row)
    }

    fun requestFit(row: Row) {
        row.nameView.requestLayout()
    }

    private fun state(row: Row): State {
        val view = row.nameView
        val existing = view.getTag(R.id.hchat_chat_name_decoration) as? Decoration
        if (existing != null) return existing.state
        return State(baseName = stripInlineDecorations(view.text)).also {
            view.setTag(R.id.hchat_chat_name_decoration, Decoration(row, it))
        }
    }

    private fun stateForDecoration(row: Row): State {
        val state = state(row)
        val current = row.nameView.text
        if (current?.toString().orEmpty() != state.lastRendered) {
            state.baseName = stripInlineDecorations(current)
        }
        return state
    }

    private fun compose(row: Row) {
        val tv = row.nameView
        val state = state(row)
        val builder = SpannableStringBuilder()
        val title = state.title
        if (title.text.isNotEmpty()) {
            val start = builder.length
            builder.append(title.text)
            val end = builder.length
            builder.setSpan(
                TitleBadgeSpan(title.bgStart, title.bgEnd, title.textStart, title.textEnd),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            title.onClick?.let {
                builder.setSpan(NoUnderlineClickSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            builder.append(" ")
        }
        val baseStart = builder.length
        builder.append(state.baseName)
        val baseEnd = builder.length
        state.baseNameStyle?.takeIf { baseEnd > baseStart }?.let { style ->
            builder.setSpan(
                StyledTextSpan(style.color, style.weight),
                baseStart,
                baseEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (state.tail.isNotEmpty()) {
            builder.append(" ")
            builder.append(state.tail)
        }
        tv.movementMethod = if (title.onClick != null) LinkMovementMethod.getInstance() else null
        tv.highlightColor = 0
        tv.text = builder
        state.lastRendered = builder.toString()
        tv.setSingleLine(false)
        tv.maxLines = Int.MAX_VALUE
        tv.ellipsize = null
        tv.requestLayout()
    }

    private fun stripInlineDecorations(text: CharSequence?): CharSequence {
        if (text == null) return ""
        if (text !is Spanned) return text
        val badgeSpans = text.getSpans(0, text.length, TitleBadgeSpan::class.java)
        val styledTextSpans = text.getSpans(0, text.length, StyledTextSpan::class.java)
        if (badgeSpans.isEmpty() && styledTextSpans.isEmpty()) return text
        val builder = SpannableStringBuilder(text)
        styledTextSpans.forEach(builder::removeSpan)
        badgeSpans
            .mapNotNull { span ->
                val start = builder.getSpanStart(span)
                val end = builder.getSpanEnd(span)
                if (start >= 0 && end >= start) start to end else null
            }
            .sortedByDescending { it.first }
            .forEach { (start, end) ->
                val removeEnd = (end + 1).coerceAtMost(builder.length)
                builder.delete(start, removeEnd)
            }
        return builder
    }

    class StyledTextSpan(
        private val color: MemberTitleStore.ColorSpec?,
        weight: Int
    ) : ReplacementSpan() {
        private val resolvedWeight = weight.coerceIn(100, 900)

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            val oldTypeface = paint.typeface
            val oldFakeBold = paint.isFakeBoldText
            applyWeight(paint)
            val width = paint.measureText(text, start, end).toInt()
            paint.typeface = oldTypeface
            paint.isFakeBoldText = oldFakeBold
            return width
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val oldColor = paint.color
            val oldShader = paint.shader
            val oldTypeface = paint.typeface
            val oldFakeBold = paint.isFakeBoldText
            applyWeight(paint)
            val spec = color
            if (spec != null) {
                if (spec.isGradient) {
                    val width = paint.measureText(text, start, end).coerceAtLeast(1f)
                    paint.shader = LinearGradient(
                        x,
                        0f,
                        x + width,
                        0f,
                        spec.startColor,
                        spec.endColor,
                        Shader.TileMode.CLAMP
                    )
                } else {
                    paint.shader = null
                    paint.color = spec.startColor
                }
            }
            canvas.drawText(text, start, end, x, y.toFloat(), paint)
            paint.color = oldColor
            paint.shader = oldShader
            paint.typeface = oldTypeface
            paint.isFakeBoldText = oldFakeBold
        }

        private fun applyWeight(paint: Paint) {
            val current = paint.typeface
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                paint.typeface = Typeface.create(current, resolvedWeight, current?.isItalic == true)
                paint.isFakeBoldText = false
            } else {
                val style = if (resolvedWeight >= 600) Typeface.BOLD else Typeface.NORMAL
                paint.typeface = Typeface.create(current, style)
                paint.isFakeBoldText = resolvedWeight >= 600
            }
        }
    }

    class TitleBadgeSpan(
        private val bgStart: Int,
        private val bgEnd: Int,
        private val textStart: Int,
        private val textEnd: Int
    ) : ReplacementSpan() {
        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            val horizontalPadding = badgeHPadding(paint)
            val verticalPadding = badgeVPadding(paint)
            val metrics = paint.fontMetricsInt
            fm?.let {
                it.ascent = metrics.ascent - verticalPadding
                it.descent = metrics.descent + verticalPadding
                it.top = metrics.top - verticalPadding
                it.bottom = metrics.bottom + verticalPadding
            }
            return (paint.measureText(text, start, end) + horizontalPadding * 2).toInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val oldColor = paint.color
            val oldShader = paint.shader
            val oldFakeBold = paint.isFakeBoldText
            val horizontalPadding = badgeHPadding(paint).toFloat()
            val width = paint.measureText(text, start, end) + horizontalPadding * 2
            val rect = RectF(x, top.toFloat() + badgeTopInset(paint), x + width, bottom.toFloat() - badgeBottomInset(paint))
            val radius = paint.textSize * 0.28f
            paint.shader = if (bgStart != bgEnd) {
                LinearGradient(rect.left, 0f, rect.right, 0f, bgStart, bgEnd, Shader.TileMode.CLAMP)
            } else {
                null
            }
            paint.color = bgStart
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = if (textStart != textEnd) {
                LinearGradient(rect.left, 0f, rect.right, 0f, textStart, textEnd, Shader.TileMode.CLAMP)
            } else {
                null
            }
            paint.color = textStart
            paint.isFakeBoldText = true
            canvas.drawText(text, start, end, x + horizontalPadding, y.toFloat(), paint)
            paint.color = oldColor
            paint.shader = oldShader
            paint.isFakeBoldText = oldFakeBold
        }

        private fun badgeHPadding(paint: Paint): Int = (paint.textSize * 0.45f + 0.5f).toInt()
        private fun badgeVPadding(paint: Paint): Int = (paint.textSize * 0.14f + 0.5f).toInt()
        private fun badgeTopInset(paint: Paint): Float = paint.textSize * 0.05f
        private fun badgeBottomInset(paint: Paint): Float = paint.textSize * 0.05f
    }

    private class NoUnderlineClickSpan(
        private val onClick: () -> Unit
    ) : ClickableSpan() {
        override fun onClick(widget: View) {
            onClick()
        }

        override fun updateDrawState(ds: TextPaint) {
            ds.isUnderlineText = false
        }
    }
}
