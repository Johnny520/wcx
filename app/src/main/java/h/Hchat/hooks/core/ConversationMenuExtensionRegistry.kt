package h.Hchat.hooks.core

import android.app.Activity
import h.Hchat.utils.HLog
import java.util.concurrent.ConcurrentHashMap

internal data class ConversationMenuExtensionTarget(
    val activity: Activity,
    val talker: String
)

internal data class ConversationMenuExtensionItem(
    val itemId: Int,
    val title: String
)

internal interface ConversationMenuExtension {
    val itemId: Int
    val order: Int

    fun title(target: ConversationMenuExtensionTarget): String

    fun isVisible(target: ConversationMenuExtensionTarget): Boolean

    fun onClick(target: ConversationMenuExtensionTarget)
}

internal object ConversationMenuExtensionRegistry {
    private const val TAG = "[Hchat:ConversationMenuExtension]"
    private val extensions = ConcurrentHashMap<Int, ConversationMenuExtension>()

    fun register(extension: ConversationMenuExtension) {
        val previous = extensions.put(extension.itemId, extension)
        if (previous != null && previous !== extension) {
            HLog.e("$TAG 会话长按菜单 ID 重复，已使用最新注册项: id=${extension.itemId}")
        }
    }

    fun unregister(extension: ConversationMenuExtension) {
        extensions.remove(extension.itemId, extension)
    }

    fun visibleItems(
        target: ConversationMenuExtensionTarget
    ): List<ConversationMenuExtensionItem> {
        return extensions.values
            .asSequence()
            .filter { extension ->
                runCatching { extension.isVisible(target) }
                    .onFailure {
                        HLog.e(
                            "$TAG 判断会话长按菜单显示状态失败: id=${extension.itemId}",
                            it
                        )
                    }
                    .getOrDefault(false)
            }
            .sortedWith(compareBy<ConversationMenuExtension> { it.order }.thenBy { it.itemId })
            .mapNotNull { extension ->
                runCatching {
                    ConversationMenuExtensionItem(extension.itemId, extension.title(target))
                }.onFailure {
                    HLog.e("$TAG 读取会话长按菜单标题失败: id=${extension.itemId}", it)
                }.getOrNull()
            }
            .toList()
    }

    fun perform(itemId: Int, target: ConversationMenuExtensionTarget): Boolean {
        val extension = extensions[itemId] ?: return false
        val visible = runCatching { extension.isVisible(target) }
            .onFailure { HLog.e("$TAG 点击前校验会话长按菜单失败: id=$itemId", it) }
            .getOrDefault(false)
        if (!visible) return true
        runCatching { extension.onClick(target) }
            .onFailure { HLog.e("$TAG 执行会话长按菜单失败: id=$itemId", it) }
        return true
    }
}
