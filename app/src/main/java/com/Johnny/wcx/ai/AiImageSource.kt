package com.Johnny.wcx.ai

import android.util.Base64
import com.Johnny.wcx.features.api.core.WeMessageImageStore

/**
 * 把聊天里的图片转成模型能吃的 data URL。
 *
 * **判断依据是「对方这一轮有没有发图」**，不是「最近几条里有没有图」：
 * 从最后一条往前扫，遇到自己发的消息就停 —— 这段是对方连续说的一串。
 * 里面有图就挑最近的一张传上去；全是文字就完全不传，走正常文字聊天。
 *
 * 这样三种情形都对：
 * - 对方只发图 → 这串只有图 → 传；
 * - 对方连发几条、中间夹着图（「你看这个」+ 图 + 「贵不贵」）→ 这串有图 → 传；
 * - 单纯文字聊天 → 这串没图 → 不传。
 *
 * **不触发下载**：CDN 下载要轮询 120 秒，等回来对方早聊到下一句了。
 * 图还没落盘就放弃，transcript 里仍是 `[图片]` 占位。
 */
object AiImageSource {

    /**
     * 对方这一轮里最近一张图的 data URL；没发图、或图还没落盘时返回 null。
     */
    fun latestDataUrl(lines: List<AiChatHistory.Line>): String? {
        if (lines.isEmpty()) return null
        // 倒序取到「自己上一条消息」为止：这就是对方这一轮连续发的全部内容。
        val burst = lines.asReversed().takeWhile { !it.fromMe }
        for (line in burst) {
            if (!line.isImage || line.mediaId <= 0) continue
            val jpeg = runCatching { WeMessageImageStore.readImageJpeg(line.mediaId) }
                .getOrNull()
            // 最近那张读不出来（还没落盘）就继续往前找一张：同一条消息串里的图，
            // 大概率说的是同一件事，退一张比整段瞎猜好。
            if (jpeg != null) {
                return "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
            }
        }
        return null
    }
}
