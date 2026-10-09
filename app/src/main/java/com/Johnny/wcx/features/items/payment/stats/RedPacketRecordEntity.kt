package com.Johnny.wcx.features.items.payment.stats

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 本地抢红包统计记录（仅本机保存，不上传）。
 *
 * 字段与需求一一对应：时间、发送人、会话名、金额、红包类型。
 * 金额<=0 的记录在写入前由 [RedPacketStatsManager] 过滤，不入库。
 */
@Entity(tableName = "red_packet_records")
data class RedPacketRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 抢到红包的时间（毫秒时间戳）。 */
    val timestamp: Long,
    /** 发送人昵称；解析失败时为空字符串。 */
    val senderName: String,
    /** 会话名（群聊/私聊显示名）；解析失败时为空字符串。 */
    val chatName: String,
    /** 抢到金额（元，由回调中的“分”换算而来）。 */
    val money: Double,
    /** 红包类型展示文本（普通红包/拼手气红包等）。 */
    val msgType: String,
)
