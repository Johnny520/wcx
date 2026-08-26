package com.Johnny.wcx.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Lock
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.ui.WeChatInputBarMenuApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature

import com.Johnny.wcx.features.items.chat.localizedChatString
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.content.m3.RadioButtonWidget
import com.Johnny.wcx.ui.content.m3.SegmentedColumn
import com.Johnny.wcx.ui.content.m3.TextFieldDialogWidget
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.showToast

/**
 * 安全消息发送器。
 *
 * 在自己发出的文本消息入库前向 msgsource 合并
 * `<sec_msg_node><sfn>1</sfn><bubble-type>2</bubble-type></sec_msg_node>` 标记
 * (微信 8.0.72 已验证实现)。消息带标记入库后, doScene 组装发送请求时
 * MsgSource 即包含 sec_msg_node, 双方长按该消息时菜单只剩"删除"。
 *
 * 故意不带 fold-reduce 字段, 否则微信会把长文本折叠成"…更多信息";
 * "只剩删除"菜单由 sfn=1 / bubble-type=2 触发即可。
 *
 * 两种工作模式:
 * - 被动模式 (默认): 功能开启时, 所有自己发出的文本消息一律注入标记;
 * - 主动模式: 平时不注入; 长按发送按钮弹出输入栏菜单, 点击"发送安全消息"
 *   后模拟点击原生发送按钮 (保留引用、@ 等全部原生能力), 仅对这一次
 *   发送流程内入库的文本消息注入标记。
 *
 * 跨版本锚点 (8.0.65–8.0.77 混淆名各不相同, 不使用类名/方法名):
 * - 消息入库方法: MsgInfoStorage 中 "Error insert message msg:%s talker:%s"
 *   日志唯一 (8.0.72 为 h9.ta(f9, boolean), 其余版本名不同但签名一致);
 * - sec_msg_node 合并方法: MsgSourceHelper 中唯一的
 *   "(?s)<sec_msg_node[^>]*>.*?</sec_msg_node>" 正则字面量 (8.0.72 为 az0.ia.O,
 *   8.0.65 为 zt0.t9.N), 签名均为 static (msgInfo, String, boolean) -> void。
 */
@Feature(
    name = "安全消息发送",
    categories = ["聊天"],
    description = "在自己发出的文本消息 msgsource 中注入 sec_msg_node 标记，双方长按该消息只剩「删除」选项。被动模式：对所有发出的文本消息生效；主动模式：长按发送按钮或使用触发前缀，仅对特定消息生效"
)
object SendSecMsg : ClickableFeature(), IResolveDex {

    /** 被动模式: 所有自己发出的文本消息一律注入标记 */
    private const val MODE_PASSIVE = 0

    /** 主动模式 (加号菜单): 长按发送按钮, 通过输入栏菜单主动发送 */
    private const val MODE_ACTIVE_MENU = 1

    /** 主动模式 (触发前缀): 以触发前缀开头的文本剥去前缀后注入标记 */
    private const val MODE_ACTIVE_PREFIX = 2

    private var mode by prefOption("sec_msg_mode", MODE_PASSIVE)
    private var triggerPrefix by prefOption("sec_msg_trigger_prefix", "#sec")

    // 不能带 fold-reduce 字段, 否则微信会把长文本折叠成"…更多信息"
    private const val SEC_XML = "<sec_msg_node><sfn>1</sfn><bubble-type>2</bubble-type></sec_msg_node>"

    // 主动模式 (加号菜单): 点击菜单项时置位; 消息入库发生在发送 onClick 之后的
    // 异步流程里, 由入库 hook 消费。发送失败残留的标记会污染下一条消息,
    // 由下一次入库时 mode 判定兜底 (菜单模式下正常发送不注入)。
    @Volatile
    private var pendingSecSend = false

    // 消息入库方法 (文本发送 NetSceneSendMsg 构造时调用), (msgInfo, boolean) -> long
    private val methodInsertMessage by dexMethod {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingEqStrings("Error insert message msg:%s talker:%s")
            paramCount(2)
            returnType("long")
        }
    }

    // MsgSourceHelper 合并方法: static (msgInfo, String secXml, boolean) -> void
    private val methodMergeSecNode by dexMethod {
        matcher {
            usingEqStrings("(?s)<sec_msg_node[^>]*>.*?</sec_msg_node>")
            paramCount(3)
            returnType("void")
        }
    }

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        if (mode != MODE_ACTIVE_MENU) return@IActionItemsProvider emptyList()

        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "send_sec_msg",
                icon = MaterialSymbols.Outlined.Lock,
                label = ("发送安全消息"),
                onClick = { context, chatFooter ->
                    if (chatFooter.lastText.isEmpty()) {
                        showToast(context, ("输入框内容为空"))
                        return@ActionItem
                    }

                    // 走用户点击发送键时的原生路径 (见 WeChatInputBarMenuApi.performSend),
                    // 保留引用、@ 等全部原生能力; 标记由异步入库流程消费
                    pendingSecSend = true
                    WeChatInputBarMenuApi.performSend(chatFooter)
                }
            )
        )
    }

    override fun onEnable() {
        methodInsertMessage.hookBefore {
            val msgInfo = MessageInfo(args[0]!!)

            // 只处理自己发送的文本消息 (isSend=1, type=1)
            if (msgInfo.isSend != 1 || msgInfo.typeCode != 1) return@hookBefore

            val shouldInject = mode == MODE_PASSIVE || pendingSecSend

            // 消费标记, 避免发送失败后残留污染下一条消息
            pendingSecSend = false

            if (!shouldInject) return@hookBefore

            // z=false: 只改内存 msgsource, 随入库方法写入
            methodMergeSecNode.method.invoke(null, args[0], SEC_XML, false)
        }

        WeChatInputBarMenuApi.methodSendMessage.hookBefore {
            if (mode != MODE_ACTIVE_PREFIX) return@hookBefore

            val chatFooter = thisObject!!.reflekt().firstField {
                type = ChatFooter::class
            }.get()!! as ChatFooter

            val text = chatFooter.lastText

            // 上一次置位可能未被消费 (发送失败), 先清除
            pendingSecSend = false
            if (text.isEmpty() || !text.startsWith(triggerPrefix)) return@hookBefore

            // 剥掉前缀, 原生发送剩余内容; 消息入库发生在该流程内, 同步消费标记
            chatFooter.lastText = text.removePrefix(triggerPrefix)
            pendingSecSend = true
        }

        WeChatInputBarMenuApi.addProvider(provider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var currentMode by remember { mutableIntStateOf(mode) }

            AlertDialogContent(
                title = { Text("安全消息发送") },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = "被动模式",
                                description = "功能开启时，对所有自己发出的文本消息生效",
                                selected = currentMode == MODE_PASSIVE,
                                onClick = {
                                    currentMode = MODE_PASSIVE
                                    mode = MODE_PASSIVE
                                },
                            )
                        }
                        item {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = "主动模式 (加号菜单)",
                                description = "默认不生效；长按发送按钮点击「发送安全消息」，将当前输入框内容作为安全消息发送",
                                selected = currentMode == MODE_ACTIVE_MENU,
                                onClick = {
                                    currentMode = MODE_ACTIVE_MENU
                                    mode = MODE_ACTIVE_MENU
                                },
                            )
                        }
                        item {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = "主动模式 (触发前缀)",
                                description = "仅以触发前缀开头的文本消息作为安全消息发送，发送时剥去前缀",
                                selected = currentMode == MODE_ACTIVE_PREFIX,
                                onClick = {
                                    currentMode = MODE_ACTIVE_PREFIX
                                    mode = MODE_ACTIVE_PREFIX
                                },
                            )
                        }
                        item(key = "trigger_prefix", animatedVisibility = currentMode == MODE_ACTIVE_PREFIX) {
                            TextFieldDialogWidget(
                                title = "触发前缀",
                                value = triggerPrefix,
                                onValueChange = { triggerPrefix = it },
                                dialogTitle = "触发前缀",
                                confirmLabel = "确定",
                                dismissLabel = "取消",
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("关闭") }
                },
            )
        }
    }
}
