package com.Johnny.wcx.features.items.payment.stats

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Room 聚合查询结果：笔数与总额。 */
data class RedPacketAgg(
    val count: Long = 0L,
    val total: Double = 0.0,
)

@Dao
interface RedPacketStatsDao {

    @Insert
    suspend fun insert(record: RedPacketRecordEntity)

    /** 累计统计（全部记录）。 */
    @Query(
        "SELECT COUNT(*) AS count, COALESCE(SUM(money), 0) AS total " +
            "FROM red_packet_records"
    )
    suspend fun totalStats(): RedPacketAgg

    /** 今日统计（自 [startMillis] 起的记录）。 */
    @Query(
        "SELECT COUNT(*) AS count, COALESCE(SUM(money), 0) AS total " +
            "FROM red_packet_records WHERE timestamp >= :startMillis"
    )
    suspend fun sinceStats(startMillis: Long): RedPacketAgg

    /** 最近的记录（统计页列表展示用，按时间倒序）。 */
    @Query("SELECT * FROM red_packet_records ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentRecords(limit: Int): List<RedPacketRecordEntity>

    /** 全部记录（导出用，按时间倒序）。 */
    @Query("SELECT * FROM red_packet_records ORDER BY timestamp DESC")
    suspend fun allRecords(): List<RedPacketRecordEntity>

    /** 清空全部记录。 */
    @Query("DELETE FROM red_packet_records")
    suspend fun clearAll()
}
