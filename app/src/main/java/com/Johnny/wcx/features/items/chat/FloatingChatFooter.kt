package com.Johnny.wcx.features.items.chat

import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.activity.ComponentActivity
import androidx.compose.material3.ListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.Johnny.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.items.chat.FloatingChatFooter.bottomGapDp
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.constructor
import kotlin.math.roundToInt

@Feature(
    name = "悬浮输入框",
    categories = ["聊天"],
    description = "将聊天输入框改为悬浮卡片形式, 带有圆角、阴影和侧边距"
)
object FloatingChatFooter : ClickableFeature(), IResolveDex {

    private const val TAG = "FloatingChatFooter"

    private const val DEFAULT_CORNER_RADIUS = 24
    private const val DEFAULT_SIDE_MARGIN = 12
    private const val DEFAULT_BOTTOM_GAP = 4
    private const val DEFAULT_ELEVATION = 4

    private const val MIN_CORNER_RADIUS = 0
    private const val MAX_CORNER_RADIUS = 32
    private const val MIN_SIDE_MARGIN = 0
    private const val MAX_SIDE_MARGIN = 32
    private const val MIN_BOTTOM_GAP = 0
    private const val MAX_BOTTOM_GAP = 24
    private const val MIN_ELEVATION = 0
    private const val MAX_ELEVATION = 16

    private var cornerRadiusDp by prefOption("floating_chat_footer_corner_radius", DEFAULT_CORNER_RADIUS)
    private var sideMarginDp by prefOption("floating_chat_footer_side_margin", DEFAULT_SIDE_MARGIN)
    private var bottomGapDp by prefOption("floating_chat_footer_bottom_gap", DEFAULT_BOTTOM_GAP)
    private var elevationDp by prefOption("floating_chat_footer_elevation", DEFAULT_ELEVATION)

    /**
     * Locates ChatFooter.refreshBottomHeight() by the unique log string WeChat emits at the
     * start of the method. The intentional typo "keyborPx" is WeChat's own, copied faithfully.
     */
    private val methodRefreshBottomHeight by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings("MicroMsg.ChatFooter", "[refreshBottomHeight] keyborPx:%d")
        }
    }

    override fun onEnable() {
        val reflekt = ChatFooter::class.reflekt()

        // Outline + elevation can be set immediately after construction (no LayoutParams needed)
        ChatFooter::class.constructor.hookAfter {
            applyDrawingStyle(thisObject as ChatFooter)
        }

        // Side margins must wait until onAttachedToWindow: LayoutParams is set by the parent
        // ViewGroup (ChattingScrollLayout) when it calls addView(), which happens after the
        // constructor returns. It is guaranteed to be non-null by the time the view attaches.
        reflekt.firstMethod { name = "onAttachedToWindow" }.hookAfter {
            applySideMargins(thisObject as ChatFooter)
        }

        // Shift the footer upward after WeChat writes its negative bottomMargin so the
        // rounded bottom corners clear the keyboard edge
        methodRefreshBottomHeight.hookAfter {
            applyBottomGap(thisObject as ChatFooter)
        }
    }

    /** Sets the outline provider, corner clipping, and elevation — all drawing properties
     *  that don't require LayoutParams. Safe to call from the constructor hook. */
    private fun applyDrawingStyle(footer: ChatFooter) {
        val density = footer.resources.displayMetrics.density
        footer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // Use the view's full measured height as the outline bounds.
                //
                // When AppPanel is closed ChatFooterBottom is GONE so view.height equals
                // just the input row (~56dp). When AppPanel is open ChatFooterBottom
                // becomes VISIBLE and contributes its height, so view.height expands to
                // include it. In both cases view.height is the correct clip boundary.
                //
                // Do NOT use (view.height + bottomMargin): bottomMargin is set to
                // –keyboardHeight (≈–250dp) by WeChat, and the input row is only ~56dp,
                // so that formula produces a negative value and collapses the outline to 1px.
                val r = view.resources.displayMetrics.density * cornerRadiusDp
                outline.setRoundRect(0, 0, view.width, view.height.coerceAtLeast(1), r)
            }
        }
        footer.clipToOutline = true
        footer.elevation = elevationDp * density
        WeLogger.d(TAG, "applied drawing style: corner=${cornerRadiusDp}dp elev=${elevationDp}dp")
    }

    /** Applies left/right margins so the footer appears as a floating card inset from the
     *  screen edges. Called from onAttachedToWindow where LayoutParams is guaranteed non-null. */
    private fun applySideMargins(footer: ChatFooter) {
        val lp = footer.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val sideMarginPx = (sideMarginDp * footer.resources.displayMetrics.density).toInt()
        lp.leftMargin = sideMarginPx
        lp.rightMargin = sideMarginPx
        footer.requestLayout()
        WeLogger.d(TAG, "applied side margins: side=${sideMarginDp}dp")
    }

    /**
     * WeChat's refreshBottomHeight sets bottomMargin = –keyboardHeight so the footer
     * rides flush against the IME top. We add [bottomGapDp] to lift it slightly so the
     * bottom rounded corners are visible above the keyboard surface.
     *
     * Guard: skip when bottomMargin > 0 — WeChat is not managing the position here.
     */
    private fun applyBottomGap(footer: ChatFooter) {
        val gapDp = bottomGapDp
        if (gapDp == 0) return
        val lp = footer.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.bottomMargin > 0) return
        val gapPx = (gapDp * footer.resources.displayMetrics.density).toInt()
        val adjusted = lp.bottomMargin + gapPx
        if (lp.bottomMargin != adjusted) {
            lp.bottomMargin = adjusted
            footer.requestLayout()
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var cornerInput by remember { mutableFloatStateOf(cornerRadiusDp.toFloat()) }
            var sideInput by remember { mutableFloatStateOf(sideMarginDp.toFloat()) }
            var gapInput by remember { mutableFloatStateOf(bottomGapDp.toFloat()) }
            var elevInput by remember { mutableFloatStateOf(elevationDp.toFloat()) }

            AlertDialogContent(
                title = { Text("悬浮输入框") },
                text = {
                    DefaultColumn {
                        ListItem(
                            headlineContent = { Text("圆角半径: ${cornerInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = cornerInput,
                                    onValueChange = { cornerInput = it },
                                    valueRange = MIN_CORNER_RADIUS.toFloat()..MAX_CORNER_RADIUS.toFloat(),
                                    steps = MAX_CORNER_RADIUS - MIN_CORNER_RADIUS - 1
                                )
                            }
                        )
                        ListItem(
                            headlineContent = { Text("侧边距: ${sideInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = sideInput,
                                    onValueChange = { sideInput = it },
                                    valueRange = MIN_SIDE_MARGIN.toFloat()..MAX_SIDE_MARGIN.toFloat(),
                                    steps = MAX_SIDE_MARGIN - MIN_SIDE_MARGIN - 1
                                )
                            }
                        )
                        ListItem(
                            headlineContent = { Text("底部间距: ${gapInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = gapInput,
                                    onValueChange = { gapInput = it },
                                    valueRange = MIN_BOTTOM_GAP.toFloat()..MAX_BOTTOM_GAP.toFloat(),
                                    steps = MAX_BOTTOM_GAP - MIN_BOTTOM_GAP - 1
                                )
                            }
                        )
                        ListItem(
                            headlineContent = { Text("阴影强度: ${elevInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = elevInput,
                                    onValueChange = { elevInput = it },
                                    valueRange = MIN_ELEVATION.toFloat()..MAX_ELEVATION.toFloat(),
                                    steps = MAX_ELEVATION - MIN_ELEVATION - 1
                                )
                            }
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        cornerRadiusDp = cornerInput.roundToInt()
                        sideMarginDp = sideInput.roundToInt()
                        bottomGapDp = gapInput.roundToInt()
                        elevationDp = elevInput.roundToInt()
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }
}
