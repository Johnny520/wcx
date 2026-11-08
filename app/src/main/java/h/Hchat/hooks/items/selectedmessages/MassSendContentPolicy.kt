package h.Hchat.hooks.items.selectedmessages

import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskContentItem
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskSettings

internal object MassSendContentPolicy {
    const val OFFICIAL_SUPPORTED_TYPES_TEXT = "文字、图片、视频、语音、表情和视频号"

    fun supportsOfficial(snapshot: SelectedMessageSnapshot): Boolean {
        return when (snapshot.type and 0xffff) {
            1,
            3, 34, 43, 62, 47 -> true
            49 -> snapshot.isVideoNumber()
            else -> false
        }
    }

    fun supportsOfficial(item: ScheduledTaskContentItem): Boolean {
        return when (item.type) {
            ScheduledTaskSettings.TYPE_TEXT,
            ScheduledTaskSettings.TYPE_IMAGE,
            ScheduledTaskSettings.TYPE_VIDEO,
            ScheduledTaskSettings.TYPE_EMOJI,
            ScheduledTaskSettings.TYPE_VOICE -> true
            ScheduledTaskSettings.TYPE_XML -> WeChatMessage.isVideoNumberContent(item.value)
            else -> false
        }
    }

    fun customTypeLabel(type: Int): String = when (type) {
        ScheduledTaskSettings.TYPE_IMAGE -> "图片"
        ScheduledTaskSettings.TYPE_VIDEO -> "视频"
        ScheduledTaskSettings.TYPE_VOICE -> "语音"
        ScheduledTaskSettings.TYPE_EMOJI -> "表情"
        ScheduledTaskSettings.TYPE_XML -> "视频号"
        ScheduledTaskSettings.TYPE_TEXT -> "文本"
        ScheduledTaskSettings.TYPE_FILE -> "文件"
        ScheduledTaskSettings.TYPE_FAVORITE -> "收藏"
        else -> "内容"
    }
}
