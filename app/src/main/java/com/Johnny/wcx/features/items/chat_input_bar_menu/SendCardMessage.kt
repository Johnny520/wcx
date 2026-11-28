package com.Johnny.wcx.features.items.chat_input_bar_menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Send_time_extension
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.ui.WeChatInputBarMenuApi
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.WeLogger

/**
 * 发送卡片消息。
 *
 * 两种用法：
 * 1. 点一下弹表单，只填「标题 / 描述 / 图片网址」（跳转网址可选，留空就跳图片地址），
 *    自动拼成微信 appmsg(type=5) 卡片并直接发到当前聊天；
 * 2. 兼容老用法：输入框里已经是一段完整 `<msg><appmsg>…</appmsg></msg>` 时，
 *    直接发送它，不弹表单。
 */
@Feature(
    name = "发送卡片消息",
    categories = ["聊天"],
    description = "输入栏长按菜单里填标题、描述、图片网址，自动生成卡片消息发出"
)
object SendCardMessage : SwitchFeature() {

    private const val TAG = "SendCardMessage"

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "send_card_message",
                icon = MaterialSymbols.Outlined.Send_time_extension,
                label = "发送卡片消息",
                onClick = { context, chatFooter ->
                    val talker = runCatching { WeCurrentConversationApi.value }.getOrNull().orEmpty()
                    if (talker.isBlank()) {
                        showToast(context, "请先进入一个聊天再发送卡片消息")
                        return@ActionItem
                    }

                    // 老用法：输入框里已经是完整 XML，原样发出去
                    val typed = runCatching { chatFooter.lastText }.getOrNull().orEmpty()
                    if (typed.contains("<appmsg>")) {
                        val ok = runCatching { WeMessageApi.sendXmlAppMsg(talker, typed) }.getOrDefault(false)
                        if (ok) {
                            showToast(context, "卡片消息已发送")
                            runCatching { chatFooter.lastText = "" }
                        } else {
                            showToast(context, "发送失败，请检查 XML（需要有 title 和 appmsg 节点）")
                        }
                        return@ActionItem
                    }

                    showComposeDialog(context) {
                        CardComposer(
                            onCancel = { onDismiss() },
                            onSend = { title, desc, thumb, link ->
                                val xml = buildCardXml(title, desc, thumb, link)
                                WeLogger.i(TAG, "发送卡片消息 -> $talker")
                                val ok = runCatching { WeMessageApi.sendXmlAppMsg(talker, xml) }.getOrDefault(false)
                                if (ok) {
                                    showToast(context, "卡片消息已发送")
                                    runCatching { chatFooter.lastText = "" }
                                    onDismiss()
                                } else {
                                    showToast(context, "发送失败：卡片消息接口不可用（可在「脚本功能诊断 → 发送文本 API」查看）")
                                }
                            }
                        )
                    }
                }
            )
        )
    }

    override fun onEnable() {
        WeChatInputBarMenuApi.addProvider(provider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }

    /** 拼出微信卡片消息 XML（type=5 = 网页卡片）。跳转网址留空时用图片地址兜底。 */
    private fun buildCardXml(title: String, desc: String, thumbUrl: String, link: String): String {
        val target = link.ifBlank { thumbUrl }
        return buildString {
            append("<msg><appmsg>")
            append("<title>").append(escapeXml(title)).append("</title>")
            append("<des>").append(escapeXml(desc)).append("</des>")
            append("<type>5</type>")
            append("<url>").append(escapeXml(target)).append("</url>")
            append("<thumburl>").append(escapeXml(thumbUrl)).append("</thumburl>")
            append("</appmsg></msg>")
        }
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    @Composable
    private fun CardComposer(
        onCancel: () -> Unit,
        onSend: (title: String, desc: String, thumb: String, link: String) -> Unit
    ) {
        var title by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var thumb by remember { mutableStateOf("") }
        var link by remember { mutableStateOf("") }
        val ctx = LocalContext.current

        // 卡片固定白底，因此内部强制浅色配色，避免深色模式下 Input 白字白底看不见
        MaterialTheme(colorScheme = lightColorScheme()) {
            Column(
                modifier = Modifier
                    .width(330.dp)
                    .heightIn(max = 560.dp)
                    .background(Color.White, RoundedCornerShape(18.dp))
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "发送卡片消息",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1A1A1A)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "填标题、描述、图片网址就能发；跳转网址不填时默认跳到图片地址。",
                    fontSize = 12.sp,
                    color = Color(0xFF8A8A8E)
                )
                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("描述") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = thumb,
                    onValueChange = { thumb = it },
                    label = { Text("图片网址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("跳转网址（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancel) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            when {
                                title.isBlank() -> showToast(ctx, "请填写标题")
                                desc.isBlank() -> showToast(ctx, "请填写描述")
                                thumb.isBlank() -> showToast(ctx, "请填写图片网址")
                                else -> onSend(title.trim(), desc.trim(), thumb.trim(), link.trim())
                            }
                        }
                    ) { Text("发送") }
                }
            }
        }
    }
}
