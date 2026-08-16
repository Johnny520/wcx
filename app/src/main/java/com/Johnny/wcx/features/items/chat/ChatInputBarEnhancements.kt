package com.Johnny.wcx.features.items.chat

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Alternate_email
import com.composables.icons.materialsymbols.outlined.Send_time_extension
import com.composables.icons.materialsymbols.outlined.Voice_chat
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.net.WePacketHelper
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.chat.panel.selectAndSendVoice
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Feature(
    name = "聊天输入栏增强",
    categories = ["聊天"],
    description = "为聊天输入栏添加更多功能\n在聊天界面长按「发送」或「加号菜单」按钮打开菜单\n菜单功能: 「发送语音文件」「发送卡片消息」「@所有人」"
)
object ChatInputBarEnhancements : SwitchFeature(), IResolveDex {

    val methodSendMessage by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings("MicroMsg.ChatFooter", "send msg onClick")
        }
    }

    fun showMenu(context: Context, chatFooter: ChatFooter) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("聊天功能") },
                text = {
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                    ) {
                        item {
                            ActionItem(
                                icon = MaterialSymbols.Outlined.Voice_chat,
                                label = "发送语音文件"
                            ) {
                                onDismiss()
                                selectAndSendVoice(context, WeCurrentConversationApi.value)
                            }
                        }

                        item {
                            ActionItem(
                                icon = MaterialSymbols.Outlined.Send_time_extension,
                                label = "发送卡片消息"
                            ) {
                                onDismiss()
                                val currentConv = WeCurrentConversationApi.value
                                val content = chatFooter.lastText

                                if (content.isEmpty()) {
                                    showToast("输入内容为空!")
                                    return@ActionItem
                                }

                                val isSuccess = WeMessageApi.sendXmlAppMsg(currentConv, content)
                                if (!isSuccess) {
                                    showToast("发送卡片消息失败, 请检查格式")
                                    return@ActionItem
                                }

                                chatFooter.lastText = ""
                            }
                        }

                        item {
                            ActionItem(
                                icon = MaterialSymbols.Outlined.Alternate_email,
                                label = "@所有人"
                            ) {
                                onDismiss()

                                if (!WeCurrentConversationApi.value.isGroupChatWxId) {
                                    showToast("只能在群组里使用!")
                                    return@ActionItem
                                }

                                val contacts = WeDatabaseApi
                                    .getGroupMembers(WeCurrentConversationApi.value)
                                    .filter { c -> c.wxId != WeApi.selfWxId }
                                val content = chatFooter.lastText

                                val reqBody = buildJsonObject {
                                    put("1", 1)
                                    putJsonObject("2") {
                                        putJsonObject("1") {
                                            put("1", WeCurrentConversationApi.value)
                                        }
                                        put("2", contacts.joinToString("") { c ->
                                            "@${c.nickname} "
                                        } + content)
                                        put("3", 1)
                                        put("4", System.currentTimeMillis() / 1000)
                                        put("5", -388413336)
                                        put(
                                            "6",
                                            """<msgsource><atuserlist><![CDATA[${contacts.joinToString(",") { c -> c.wxId }}]]></atuserlist><pua>1</pua><alnode><cf>5</cf><inlenlist>73</inlenlist></alnode><eggIncluded>1</eggIncluded></msgsource>"""
                                        )
                                    }
                                }

                                WePacketHelper.sendCgi(
                                    "/cgi-bin/micromsg-bin/newsendmsg",
                                    522,
                                    0,
                                    0,
                                    reqBody.toString()
                                ) {
                                    onSuccess { _ ->
                                        showToast("已发送 (自己无法看到该消息)")
                                    }
                                }
                            }
                        }

//                                        ActionItem(
//                                            icon = MaterialSymbols.Outlined.Visibility_off,
//                                            label = "隐藏@"
//                                        ) {
//                                            val content = chatFooter.lastText
//
//                                            if (content.isEmpty()) {
//                                                showToast("消息内容为空!")
//                                                return@ActionItem
//                                            }
//
//                                            showComposeDialog(context) {
//                                                ContactsSelector(
//                                                    title = "选择要@的好友",
//                                                    contacts = WeDatabaseApi.getGroupMembers(WeCurrentConversationApi.value),
//                                                    initialSelectedWxIds = emptySet(),
//                                                    onDismiss = onDismiss,
//                                                    onConfirm = { contacts ->
//                                                        if (contacts.isEmpty()) {
//                                                            showToast("请选择至少一个好友")
//                                                            return@ContactsSelector
//                                                        }
//
//                                                        onDismiss()
//                                                        val reqBody = buildJsonObject {
//                                                            put("1", 1)
//                                                            putJsonObject("2") {
//                                                                putJsonObject("1") {
//                                                                    put("1", WeCurrentConversationApi.value)
//                                                                }
//                                                                put("2", """
//                                                                <?xml version="1.0"?>
//                                                                <msg>
//                                                                    <appmsg>
//                                                                        <title>${content}</title>
//                                                                        <action>view</action>
//                                                                        <type>57</type>
//                                                                        <finderLiveProductShare>
//                                                                            <isPriceBeginShow>false</isPriceBeginShow>
//                                                                        </finderLiveProductShare>
//                                                                        <gameshare>
//                                                                            <appbrandext>
//                                                                                <priority>-1</priority>
//                                                                            </appbrandext>
//                                                                        </gameshare>
//                                                                        <appattach />
//                                                                    </appmsg>
//                                                                    <fromusername>${WeApi.selfWxId}</fromusername>
//                                                                    <scene>0</scene>
//                                                                    <appinfo>
//                                                                        <version>1</version>
//                                                                        <appname />
//                                                                    </appinfo>
//                                                                    <commenturl />
//                                                                </msg>
//                                                                """.trimIndent())
//                                                                put("3", 1)
//                                                                put("4", System.currentTimeMillis() / 1000)
//                                                                put("5", -388413336)
//                                                                put(
//                                                                    "6", """<msgsource><atuserlist><![CDATA[${contacts.joinToString(",")}]]></atuserlist><pua>1</pua><alnode><cf>5</cf><inlenlist>73</inlenlist></alnode><eggIncluded>1</eggIncluded></msgsource>"""
//                                                                )
//                                                            }
//                                                        }
//
//                                                        WePacketHelper.sendCgi(
//                                                            "/cgi-bin/micromsg-bin/newsendmsg",
//                                                            522,
//                                                            0,
//                                                            0,
//                                                            reqBody.toString()
//                                                        ) {
//                                                            onSuccess { _ ->
//                                                                showToast("已发送 (自己无法看到该消息)")
//                                                            }
//                                                        }
//                                                    }
//                                                )
//                                            }
//                                        }
                    }
                },
                confirmButton = { Button(onDismiss) { Text("关闭") } }
            )
        }
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(imageVector = icon, contentDescription = label)
        },
        headlineContent = { Text(label) },
    )
}
