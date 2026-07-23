package com.Johnny.wcx.features.items.chat

import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Edit
import com.Johnny.reflekt.reflekt
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.EditIcon
import com.Johnny.wcx.ui.utils.showComposeDialog

@Feature(name = "修改文本消息显示", categories = ["聊天"], description = "向消息长按菜单添加菜单项, 可修改本地消息显示内容")
object ModifyTextMessageDisplay : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
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
            ) { view, _, _ ->
                showComposeDialog(view.context) {
                    var input by remember {
                        mutableStateOf(
                            view.reflekt()
                                .firstField {
                                    type = CharSequence::class
                                    superclass()
                                }.get().toString()
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
                                view.reflekt()
                                    .firstMethod {
                                        parameters(CharSequence::class)
                                    }
                                    .invoke(input)
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
