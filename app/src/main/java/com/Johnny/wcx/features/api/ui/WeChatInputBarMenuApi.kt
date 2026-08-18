package com.Johnny.wcx.features.api.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast

@Feature(
    name = "聊天输入栏增强 API",
    categories = ["API"],
    description = "为聊天输入栏长按菜单提供扩展功能注册接口"
)
object WeChatInputBarMenuApi : ApiFeature(), IResolveDex {

    fun interface IActionItemsProvider {
        fun getActionItems(): List<ActionItem>
    }

    data class ActionItem(
        val id: String,
        val icon: ImageVector,
        val label: String,
        val isSupported: (Context, ChatFooter) -> Boolean = { _, _ -> true },
        val onClick: (Context, ChatFooter) -> Unit,
        val onLongClick: ((Context, ChatFooter) -> Unit)? = null
    )

    private const val TAG = "WeChatInputBarMenuApi"
    private val providers = mutableMapOf<String, IActionItemsProvider>()

    fun addProvider(provider: IActionItemsProvider) {
        providers[provider.javaClass.name] = provider
    }

    fun removeProvider(provider: IActionItemsProvider) {
        providers.remove(provider.javaClass.name)
    }

    fun hasItems(context: Context, chatFooter: ChatFooter): Boolean {
        return providers.values
            .flatMap { it.getActionItems() }
            .any { it.isSupported(context, chatFooter) }
    }

    val methodSendMessage by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings("MicroMsg.ChatFooter", "send msg onClick")
        }
    }

    fun showMenu(context: Context, chatFooter: ChatFooter) {
        val applicableItems = providers.values
            .flatMap { it.getActionItems() }
            .filter { it.isSupported(context, chatFooter) }

        if (applicableItems.isEmpty()) {
            showToast("没有可用的聊天输入栏功能!")
            return
        }

        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("聊天功能") },
                text = {
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                    ) {
                        items(applicableItems) { item ->
                            ActionItemRow(
                                item = item,
                                context = context,
                                chatFooter = chatFooter,
                                onDismiss = { onDismiss() }
                            )
                        }
                    }
                },
                confirmButton = { Button(onDismiss) { Text("关闭") } }
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ActionItemRow(
        item: ActionItem,
        context: Context,
        chatFooter: ChatFooter,
        onDismiss: () -> Unit
    ) {
        ListItem(
            modifier = Modifier.combinedClickable(
                onClick = {
                    onDismiss()
                    try {
                        item.onClick(context, chatFooter)
                    } catch (ex: Throwable) {
                        WeLogger.e(TAG, "exception occurred while handling click event for ${item.id}", ex)
                    }
                },
                onLongClick = item.onLongClick?.let { longClick ->
                    {
                        try {
                            longClick(context, chatFooter)
                        } catch (ex: Throwable) {
                            WeLogger.e(TAG, "exception occurred while handling long-click event for ${item.id}", ex)
                        }
                    }
                }
            ),
            leadingContent = {
                Icon(imageVector = item.icon, contentDescription = item.label)
            },
            headlineContent = { Text(item.label) },
        )
    }
}
