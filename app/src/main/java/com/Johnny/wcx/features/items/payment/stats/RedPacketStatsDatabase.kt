package com.Johnny.wcx.features.items.payment.stats

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 抢红包金额统计的本地数据库。
 *
 * 与 agent 的 WeAgentDatabase 相互独立（不同库文件），互不影响。
 * exportSchema=false：v1 无迁移需求，避免额外生成 schema 文件。
 * fallbackToDestructiveMigration：schema 意外变化时兜底重建，绝不让微信崩溃。
 */
@Database(
    entities = [RedPacketRecordEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class RedPacketStatsDatabase : RoomDatabase() {

    abstract fun dao(): RedPacketStatsDao

    companion object {
        @Volatile
        private var instance: RedPacketStatsDatabase? = null

        fun get(context: Context): RedPacketStatsDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RedPacketStatsDatabase::class.java,
                    "red_packet_stats.db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
