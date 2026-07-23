package com.Johnny.wcx.features.items.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Html
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withTranslation
import com.tencent.mm.ui.widget.MMNeat7extView
import com.Johnny.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.isDarkMode
import com.Johnny.wcx.utils.strings.replaceEmojis
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.spans.LastLineSpacingSpan
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Heading
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph

@Feature(name = "Markdown 渲染", categories = ["聊天"], description = "渲染 Markdown 消息")
object MarkdownRendering : ClickableFeature(), IResolveDex {

    private const val TAG = "MarkdownRendering"

    private const val KEY_USE_MARKWON = "use_markwon"
    private const val KEY_COMPACT_HTML = "compact_html"
    private const val KEY_NO_TEXT_SIZING = "no_text_sizing"

    private lateinit var markwon: Markwon

    private external fun convertMarkdownToHtmlNative(markdown: String): String?

    // Apply a small compensation to the max width to prevent unnecessary text wrapping
    private const val MAX_WIDTH_BUFFER = 40

    override fun onEnable() {
        MMNeat7extView::class.reflekt()
            .firstMethod { name = "onDraw" }
            .hookBefore {
                val neatTextView = thisObject as View
                if (!::markwon.isInitialized) {
                    markwon = buildMarkwon(neatTextView.context)
                }

                var origText = (neatTextView.reflekt()
                    .firstField {
                        type = CharSequence::class
                        superclass()
                    }.get()!! as CharSequence).toString()
                if (origText.isBlank()) return@hookBefore
                origText = origText.replaceEmojis()

                val msgInfo: Any

                val tag = neatTextView.tag
                val fMsgInfoWrapper = tag.reflekt()
                    .firstFieldOrNull {
                        type = classMsgInfoWrapper.clazz
                        superclass()
                    }

                if (fMsgInfoWrapper != null) {
                    val msgInfoWrapper = fMsgInfoWrapper.get()!!
                    msgInfo = MessageInfo(
                        msgInfoWrapper.reflekt()
                            .firstField {
                                superclass()
                            }
                            .get()!!.reflekt()
                            .firstField { type = WeMessageApi.classMsgInfo.clazz }
                            .get()!!)
                } else {
                    msgInfo = MessageInfo(
                        tag.reflekt()
                            .firstField {
                                type = WeMessageApi.classMsgInfo.clazz
                                superclass()
                            }.get()!!
                    )
                }

                if (!(msgInfo.type?.isText ?: false)) return@hookBefore
                val isSelfSender = msgInfo.isSelfSender

                val canvas = args[0] as Canvas
                val context = neatTextView.context

                val isDarkMode = context.isDarkMode

                val textPaint = TextPaint().apply {
                    color =
                        if (isDarkMode && !isSelfSender) "#CDCDCD".toColorInt() else "#282828".toColorInt()

                    val spSize = 17f
                    textSize = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP,
                        spSize,
                        context.resources.displayMetrics
                    )

                    isAntiAlias = true
                    typeface = Typeface.DEFAULT
                }

                // Respecting bubble constraints
                val horizontalPadding = neatTextView.paddingLeft + neatTextView.paddingRight
                val maxWidth = neatTextView.width - horizontalPadding + MAX_WIDTH_BUFFER

                if (maxWidth <= 0) return@hookBefore

                if (WePrefs.getBoolOrFalse(KEY_USE_MARKWON)) {
                    drawMarkdownWithMarkwon(
                        canvas,
                        origText,
                        neatTextView.paddingLeft.toFloat(),
                        neatTextView.paddingTop.toFloat(),
                        maxWidth,
                        textPaint
                    )
                    result = null
                } else {
                    val html = convertMarkdownToHtmlNative(origText)

                    if (html != null) {
                        drawHtmlOnCanvas(
                            canvas,
                            html,
                            neatTextView.paddingLeft.toFloat(),
                            neatTextView.paddingTop.toFloat(),
                            maxWidth,
                            textPaint
                        )
                        result = null
                    } else {
                        WeLogger.e(TAG, "convertMarkdownToHtmlNative returned nullptr, falling back to original rendering")
                    }
                }
            }
    }

    private fun drawMarkdownWithMarkwon(
        canvas: Canvas,
        markdownString: String,
        x: Float,
        y: Float,
        maxWidth: Int,
        textPaint: TextPaint
    ) {
        val node = markwon.parse(markdownString)
        val spanned = markwon.render(node)
        val staticLayout = buildStaticLayout(spanned, textPaint, maxWidth)

        canvas.withTranslation(x, y) {
            staticLayout.draw(this)
        }
    }

    private fun drawHtmlOnCanvas(
        canvas: Canvas,
        htmlString: String,
        x: Float,
        y: Float,
        maxWidth: Int,
        textPaint: TextPaint
    ) {
        var spanned = Html.fromHtml(
            htmlString,
            if (WePrefs.getBoolOrFalse(KEY_COMPACT_HTML))
                Html.FROM_HTML_MODE_COMPACT
            else Html.FROM_HTML_MODE_LEGACY
        )

        if (WePrefs.getBoolOrFalse(KEY_NO_TEXT_SIZING)) {
            spanned = SpannableStringBuilder(spanned)

            val relativeSpans = spanned.getSpans(
                0, spanned.length,
                RelativeSizeSpan::class.java
            )
            for (span in relativeSpans) {
                spanned.removeSpan(span)
            }

            val absoluteSpans = spanned.getSpans(
                0, spanned.length,
                AbsoluteSizeSpan::class.java
            )
            for (span in absoluteSpans) {
                spanned.removeSpan(span)
            }
        }

        val staticLayout = buildStaticLayout(spanned, textPaint, maxWidth)

        canvas.withTranslation(x, y) {
            staticLayout.draw(this)
        }
    }

    private fun buildStaticLayout(spanned: Spanned, textPaint: TextPaint, maxWidth: Int): StaticLayout {
        return StaticLayout.Builder
            .obtain(spanned, 0, spanned.length, textPaint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.1f)
            .setIncludePad(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
                }
            }
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }

    private fun buildMarkwon(context: Context): Markwon {
        return Markwon.builder(context)
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                    if (WePrefs.getBoolOrFalse(KEY_NO_TEXT_SIZING)) {
                        builder.setFactory(Heading::class.java) { _, _ ->
                            StyleSpan(Typeface.BOLD)
                        }
                    }
                    builder.setFactory(Paragraph::class.java) { _, _ ->
                        LastLineSpacingSpan(0)
                    }
                    builder.setFactory(BulletList::class.java) { _, _ ->
                        LastLineSpacingSpan(0)
                    }
                    builder.setFactory(OrderedList::class.java) { _, _ ->
                        LastLineSpacingSpan(0)
                    }
                    builder.setFactory(BlockQuote::class.java) { _, _ ->
                        LastLineSpacingSpan(0)
                    }
                }
            })
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create {})
            .usePlugin(TaskListPlugin.create(context))
            .build()
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("Markdown 渲染") },
                text = {
                    Column {
                        var useMarkwon by remember { mutableStateOf(WePrefs.getBoolOrFalse(KEY_USE_MARKWON)) }

                        Text(
                            "解析与渲染引擎",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                useMarkwon = false
                                WePrefs.putBool(KEY_USE_MARKWON, false)
                            },
                            trailingContent = { RadioButton(!useMarkwon, null) },
                            supportingContent = { Text("使用 Rust crate 解析并转换为 HTML, 再使用 android.text.HTML 渲染") },
                            headlineContent = { Text("markdown-rs + Html") },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                useMarkwon = true
                                WePrefs.putBool(KEY_USE_MARKWON, true)
                            },
                            trailingContent = { RadioButton(useMarkwon, null) },
                            supportingContent = { Text("使用 Markwon Java 库直接渲染 Markdown") },
                            headlineContent = { Text("Markwon") },
                        )

                        var noTextSizing by
                        remember { mutableStateOf(WePrefs.getBoolOrFalse(KEY_NO_TEXT_SIZING)) }
                        Text(
                            "通用引擎设定",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                noTextSizing = !noTextSizing
                                WePrefs.putBool(KEY_NO_TEXT_SIZING, noTextSizing)
                            },
                            trailingContent = { Switch(noTextSizing, null) },
                            supportingContent = { Text("不对 Headers, Subheaders 等组件改变字体大小") },
                            headlineContent = { Text("禁止改变字体大小") },
                        )

                        var compactHtml by
                        remember { mutableStateOf(WePrefs.getBoolOrFalse(KEY_COMPACT_HTML)) }
                        Text(
                            "markdown-rs + Html 引擎设定",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                compactHtml = !compactHtml
                                WePrefs.putBool(KEY_COMPACT_HTML, compactHtml)
                            },
                            trailingContent = { Switch(compactHtml, null) },
                            supportingContent = { Text("使用一个而非两个换行来分段") },
                            headlineContent = { Text("使用紧凑 HTML 渲染") },
                        )
                    }
                },
                confirmButton = { TextButton(onDismiss) { Text("关闭") } }
            )
        }
    }

    private val classMsgInfoWrapper by dexClass {
        matcher {
            usingEqStrings("other", "null cannot be cast to non-null type com.tencent.mm.storage.MsgInfo")
        }
    }
}
