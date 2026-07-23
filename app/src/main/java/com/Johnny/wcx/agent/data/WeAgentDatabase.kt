package com.Johnny.wcx.agent.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.Johnny.wcx.agent.data.dao.ConditionalPromptDao
import com.Johnny.wcx.agent.data.dao.ExternalServiceDao
import com.Johnny.wcx.agent.data.dao.MessageDao
import com.Johnny.wcx.agent.data.dao.ModelDao
import com.Johnny.wcx.agent.data.dao.ModelProviderDao
import com.Johnny.wcx.agent.data.dao.PerTurnPromptDao
import com.Johnny.wcx.agent.data.dao.PresetPromptDao
import com.Johnny.wcx.agent.data.dao.ProviderDao
import com.Johnny.wcx.agent.data.dao.SessionDao
import com.Johnny.wcx.agent.data.dao.SettingDao
import com.Johnny.wcx.agent.data.dao.SystemPromptDao
import com.Johnny.wcx.agent.data.dao.ToolCallDao
import com.Johnny.wcx.agent.data.dao.ToolPermissionDao
import com.Johnny.wcx.agent.data.dao.TriggerDao
import com.Johnny.wcx.agent.data.dao.WorkspaceDao
import com.Johnny.wcx.agent.data.entity.ConditionalPromptEntity
import com.Johnny.wcx.agent.data.entity.ExternalServiceEntity
import com.Johnny.wcx.agent.data.entity.MessageEntity
import com.Johnny.wcx.agent.data.entity.ModelEntity
import com.Johnny.wcx.agent.data.entity.ModelProviderEntity
import com.Johnny.wcx.agent.data.entity.PerTurnPromptEntity
import com.Johnny.wcx.agent.data.entity.PresetPromptEntity
import com.Johnny.wcx.agent.data.entity.ProviderEntity
import com.Johnny.wcx.agent.data.entity.SessionEntity
import com.Johnny.wcx.agent.data.entity.SettingEntity
import com.Johnny.wcx.agent.data.entity.SystemPromptEntity
import com.Johnny.wcx.agent.data.entity.ToolCallEntity
import com.Johnny.wcx.agent.data.entity.ToolPermissionEntity
import com.Johnny.wcx.agent.data.entity.TriggerEntity
import com.Johnny.wcx.agent.data.entity.WorkspaceEntity
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.fs.createDirsSafe

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        ToolCallEntity::class,
        ProviderEntity::class,
        ToolPermissionEntity::class,
        ModelProviderEntity::class,
        ModelEntity::class,
        SystemPromptEntity::class,
        PerTurnPromptEntity::class,
        ConditionalPromptEntity::class,
        PresetPromptEntity::class,
        WorkspaceEntity::class,
        SettingEntity::class,
        TriggerEntity::class,
        ExternalServiceEntity::class,
    ],
    version = 12,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 9, to = 10), // adds external_services table
        AutoMigration(from = 10, to = 11), // adds messages.reasoningSignature, tool_calls.providerSignature
    ],
)
@TypeConverters(WeAgentConverters::class)
abstract class WeAgentDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun toolCallDao(): ToolCallDao
    abstract fun providerDao(): ProviderDao
    abstract fun toolPermissionDao(): ToolPermissionDao
    abstract fun modelProviderDao(): ModelProviderDao
    abstract fun modelDao(): ModelDao
    abstract fun systemPromptDao(): SystemPromptDao
    abstract fun perTurnPromptDao(): PerTurnPromptDao
    abstract fun conditionalPromptDao(): ConditionalPromptDao
    abstract fun presetPromptDao(): PresetPromptDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun settingDao(): SettingDao
    abstract fun triggerDao(): TriggerDao
    abstract fun externalServiceDao(): ExternalServiceDao

    companion object {
        @Volatile
        private var INSTANCE: WeAgentDatabase? = null

        val instance: WeAgentDatabase
            get() = INSTANCE ?: synchronized(this) {
                INSTANCE ?: build().also { INSTANCE = it }
            }

        // 11 → 12: WEKIT_ROUTER enum value removed from ModelProviderType.
        // Any stored provider row with that type is now unreadable; delete them so the
        // converter no longer encounters an unknown enum name on startup.
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Remove models that referenced the now-deleted provider first to avoid
                // dangling providerId foreign keys, then drop the providers themselves.
                db.execSQL(
                    "DELETE FROM models WHERE providerId IN " +
                            "(SELECT id FROM model_providers WHERE type = 'WEKIT_ROUTER')"
                )
                db.execSQL("DELETE FROM model_providers WHERE type = 'WEKIT_ROUTER'")
            }
        }

        private fun build(): WeAgentDatabase {
            val dbFile = KnownPaths.moduleData
                .resolve("agent")
                .createDirsSafe()
                .resolve("weagent.db")
            return Room.databaseBuilder(
                HostInfo.application,
                WeAgentDatabase::class.java,
                dbFile.toString()
            )
                // WAL uses mmap'd -shm/-wal sidecars that misbehave on FUSE-emulated
                // external storage (moduleData lives on /sdcard); TRUNCATE is safe there.
                .setJournalMode(JournalMode.TRUNCATE)
                .addMigrations(MIGRATION_11_12)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
