package com.Johnny.wcx.features.items.chat

import android.text.SpannableString
import android.text.SpannableStringBuilder
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Edit
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.EditIcon
import com.Johnny.wcx.ui.utils.showComposeDialog

@Feature(name = "修改文本消息显示", categories = ["聊天"], description = "向消息长按菜单添加菜单项, 可修改本地消息显示内容（纯内存临时修改，重启微信自动恢复）")
object ModifyTextMessageDisplay : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)

        installSpannableBoundsProtection()
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    private fun installSpannableBoundsProtection() {
        val targetClasses = listOfNotNull(
            SpannableString::class.java,
            SpannableStringBuilder::class.java,
            runCatching { Class.forName("android.text.SpannableStringInternal") }.getOrNull()
        )

        for (clazz in targetClasses) {
            // 1. 保护 setSpan(Object what, int start, int end, int flags)
            runCatching {
                clazz.reflekt()
                    .firstMethodOrNull { name = "setSpan"; parameters(Any::class, Int::class, Int::class, Int::class) }
                    ?.hookBefore {
                        val ss = thisObject as? CharSequence ?: return@hookBefore
                        val len = ss.length
                        var start = args[1] as Int
                        var end = args[2] as Int
                        if (start < 0) start = 0
                        if (start > len) start = len
                        if (end < 0) end = 0
                        if (end > len) end = len
                        if (start > end) start = end
                        args[1] = start
                        args[2] = end
                    }
            }

            // 2. 保护 subSequence(int start, int end)
            runCatching {
                clazz.reflekt()
                    .firstMethodOrNull { name = "subSequence"; parameters(Int::class, Int::class) }
                    ?.hookBefore {
                        val ss = thisObject as? CharSequence ?: return@hookBefore
                        val len = ss.length
                        var start = args[0] as Int
                        var end = args[1] as Int
                        if (start < 0) start = 0
                        if (start > len) start = len
                        if (end < 0) end = 0
                        if (end > len) end = len
                        if (start > end) start = end
                        args[0] = start
                        args[1] = end
                    }
            }

            // 3. 保护 checkRange(String operation, int start, int end)
            runCatching {
                clazz.reflekt()
                    .firstMethodOrNull { name = "checkRange"; parameters(String::class, Int::class, Int::class) }
                    ?.hookBefore {
                        val ss = thisObject as? CharSequence ?: return@hookBefore
                        val len = ss.length
                        var start = args[1] as Int
                        var end = args[2] as Int
                        if (start < 0) start = 0
                        if (start > len) start = len
                        if (end < 0) end = 0
                        if (end > len) end = len
                        if (start > end) start = end
                        args[1] = start
                        args[2] = end
                    }
            }
        }
    }


    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777002,
                "修改内容",
                EditIcon,
                MaterialSymbols.Outlined.Edit,
                { msgInfo -> msgInfo.type?.isText ?: false },
                // operates on the single message's own View; can't apply to a batch
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported
            ) { view, _, msgInfo ->
                showComposeDialog(view.context) {
                    var input by remember {
                        mutableStateOf(
                            msgInfo.actualContent.ifEmpty {
                                view.reflekt()
                                    .firstField {
                                        type = CharSequence::class
                                        superclass()
                                    }.get().toString()
                            }
                        )
                    }

                    AlertDialogContent(
                        title = { Text("修改消息显示") },
                        text = {
                            TextField(
                                value = input,
                                onValueChange = { input = it },
                                label = { Text("显示内容") })
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                // 1. 同步更新内存中的 MsgInfo 实体，确保长按选区控制器获取一致的文本长度
                                val fullContent = if (msgInfo.isInGroupChat && msgInfo.isSend == 0 && msgInfo.content.contains(":\n")) {
                                    val prefix = msgInfo.content.substringBefore(":\n") + ":\n"
                                    prefix + input
                                } else {
                                    input
                                }

                                runCatching {
                                    msgInfo.instance.reflekt().firstFieldOrNull { name = "field_content" }?.set(fullContent)
                                    msgInfo.instance.reflekt().firstMethodOrNull { name = "setContent"; parameters(String::class) }?.invoke(fullContent)
                                }

                                // 2. 更新 View 文本显示并刷新布局
                                runCatching {
                                    view.reflekt().firstMethodOrNull { name = "setText"; parameters(CharSequence::class) }?.invoke(input)
                                        ?: view.reflekt().firstMethod { parameters(CharSequence::class) }.invoke(input)
                                }
                                view.requestLayout()
                                view.invalidate()

                                onDismiss()
                            }) {
                                Text("确定")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { onDismiss() }) {
                                Text("取消")
                            }
                        })
                }
            }
        )
    }
}

