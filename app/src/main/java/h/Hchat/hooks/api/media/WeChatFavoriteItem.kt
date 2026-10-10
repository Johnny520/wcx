package h.Hchat.hooks.api.media

class WeChatFavoriteItem(
    @JvmField val localId: Long,
    @JvmField val type: Int,
    @JvmField val title: String,
    @JvmField val summary: String,
    @JvmField val totalSizeBytes: Long,
    @JvmField val updateTimeMillis: Long = 0L,
    @JvmField val tags: List<String> = emptyList()
) {
    fun typeLabel(): String = when (type) {
        1 -> "文字"
        2 -> "图片"
        3 -> "语音"
        4 -> "视频"
        5 -> "链接"
        6 -> "位置"
        7 -> "音乐"
        8 -> "文件"
        10 -> "笔记"
        14 -> "聊天记录"
        18 -> "笔记"
        19 -> "小程序"
        else -> "类型$type"
    }

    fun displayTitle(): String = title.ifBlank { typeLabel() }

    fun displaySummary(): String {
        val parts = ArrayList<String>()
        summary.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
        if (parts.isEmpty()) parts += typeLabel()
        if (updateTimeMillis > 0L) {
            parts += java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date(updateTimeMillis))
        }
        if (totalSizeBytes > 0L) parts += formatSize(totalSizeBytes)
        return parts.joinToString(" · ")
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        val text = if (value >= 100 || value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", value)
        }
        return "$text ${units[index]}"
    }
}
