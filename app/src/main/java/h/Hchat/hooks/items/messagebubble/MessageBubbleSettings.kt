package h.Hchat.hooks.items.messagebubble

object MessageBubbleSettings {
    const val PREFS_NAME = "Hchat_message_bubble_config"

    const val KEY_ENABLE = "message_bubble_enable"
    const val KEY_SEPARATE_DARK_MODE = "message_bubble_separate_dark_mode"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_SEPARATE_DARK_MODE = false
}

enum class MessageBubbleSlot(
    val fileName: String,
    val displayName: String,
    val kind: MessageBubbleKind,
    val outgoing: Boolean,
    val darkMode: Boolean
) {
    LEFT_LIGHT("left_light.image", "左侧浅色气泡", MessageBubbleKind.GENERAL, false, false),
    RIGHT_LIGHT("right_light.image", "右侧浅色气泡", MessageBubbleKind.GENERAL, true, false),
    LEFT_DARK("left_dark.image", "左侧深色气泡", MessageBubbleKind.GENERAL, false, true),
    RIGHT_DARK("right_dark.image", "右侧深色气泡", MessageBubbleKind.GENERAL, true, true),
    RED_PACKET_LEFT_LIGHT("red_packet_left_light.image", "红包左侧浅色气泡", MessageBubbleKind.RED_PACKET, false, false),
    RED_PACKET_RIGHT_LIGHT("red_packet_right_light.image", "红包右侧浅色气泡", MessageBubbleKind.RED_PACKET, true, false),
    RED_PACKET_LEFT_DARK("red_packet_left_dark.image", "红包左侧深色气泡", MessageBubbleKind.RED_PACKET, false, true),
    RED_PACKET_RIGHT_DARK("red_packet_right_dark.image", "红包右侧深色气泡", MessageBubbleKind.RED_PACKET, true, true),
    TRANSFER_LEFT_LIGHT("transfer_left_light.image", "转账左侧浅色气泡", MessageBubbleKind.TRANSFER, false, false),
    TRANSFER_RIGHT_LIGHT("transfer_right_light.image", "转账右侧浅色气泡", MessageBubbleKind.TRANSFER, true, false),
    TRANSFER_LEFT_DARK("transfer_left_dark.image", "转账左侧深色气泡", MessageBubbleKind.TRANSFER, false, true),
    TRANSFER_RIGHT_DARK("transfer_right_dark.image", "转账右侧深色气泡", MessageBubbleKind.TRANSFER, true, true),
    SYSTEM_LIGHT("system_light.image", "系统消息浅色气泡", MessageBubbleKind.SYSTEM, false, false),
    SYSTEM_DARK("system_dark.image", "系统消息深色气泡", MessageBubbleKind.SYSTEM, false, true);

    companion object {
        fun resolve(kind: MessageBubbleKind, outgoing: Boolean, darkMode: Boolean): MessageBubbleSlot {
            return values().first {
                it.kind == kind && it.darkMode == darkMode &&
                    (kind == MessageBubbleKind.SYSTEM || it.outgoing == outgoing)
            }
        }

        fun resolve(outgoing: Boolean, darkMode: Boolean): MessageBubbleSlot {
            return resolve(MessageBubbleKind.GENERAL, outgoing, darkMode)
        }
    }
}

enum class MessageBubbleKind(val displayName: String) {
    GENERAL("普通消息"),
    RED_PACKET("红包消息"),
    TRANSFER("转账消息"),
    SYSTEM("系统消息")
}
