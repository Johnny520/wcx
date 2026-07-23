package com.Johnny.wcx.agent.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.Johnny.wcx.agent.data.entity.ConditionalPromptEntity
import com.Johnny.wcx.agent.data.entity.ExternalServiceEntity
import com.Johnny.wcx.agent.data.entity.ModelEntity
import com.Johnny.wcx.agent.data.entity.ModelProviderEntity
import com.Johnny.wcx.agent.data.entity.PerTurnPromptEntity
import com.Johnny.wcx.agent.data.entity.PresetPromptEntity
import com.Johnny.wcx.agent.data.entity.SettingEntity
import com.Johnny.wcx.agent.data.entity.SystemPromptEntity
import com.Johnny.wcx.agent.data.entity.WorkspaceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelProviderDao {
    @Query("SELECT * FROM model_providers")
    fun observeAll(): Flow<List<ModelProviderEntity>>

    @Query("SELECT * FROM model_providers WHERE id = :id")
    suspend fun getById(id: String): ModelProviderEntity?

    @Upsert
    suspend fun upsert(provider: ModelProviderEntity)

    @Query("DELETE FROM model_providers WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM models")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE providerId = :providerId")
    fun observeForProvider(providerId: String): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun getById(id: String): ModelEntity?

    @Query("SELECT * FROM models LIMIT 1")
    suspend fun first(): ModelEntity?

    @Query("SELECT * FROM models")
    suspend fun getAllOnce(): List<ModelEntity>

    @Upsert
    suspend fun upsert(model: ModelEntity)

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface SystemPromptDao {
    @Query("SELECT * FROM system_prompts")
    fun observeAll(): Flow<List<SystemPromptEntity>>

    @Query("SELECT * FROM system_prompts")
    suspend fun getAllOnce(): List<SystemPromptEntity>

    @Query("SELECT * FROM system_prompts WHERE id = :id")
    suspend fun getById(id: String): SystemPromptEntity?

    @Upsert
    suspend fun upsert(prompt: SystemPromptEntity)

    @Query("DELETE FROM system_prompts WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface PerTurnPromptDao {
    @Query("SELECT * FROM per_turn_prompts")
    fun observeAll(): Flow<List<PerTurnPromptEntity>>

    @Query("SELECT * FROM per_turn_prompts WHERE enabled = 1")
    suspend fun getEnabled(): List<PerTurnPromptEntity>

    @Upsert
    suspend fun upsert(prompt: PerTurnPromptEntity)

    @Query("DELETE FROM per_turn_prompts WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ConditionalPromptDao {
    @Query("SELECT * FROM conditional_prompts")
    fun observeAll(): Flow<List<ConditionalPromptEntity>>

    @Query("SELECT * FROM conditional_prompts WHERE enabled = 1")
    suspend fun getEnabled(): List<ConditionalPromptEntity>

    @Upsert
    suspend fun upsert(prompt: ConditionalPromptEntity)

    @Query("DELETE FROM conditional_prompts WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface PresetPromptDao {
    @Query("SELECT * FROM preset_prompts")
    fun observeAll(): Flow<List<PresetPromptEntity>>

    @Query("SELECT * FROM preset_prompts")
    suspend fun getAllOnce(): List<PresetPromptEntity>

    @Upsert
    suspend fun upsert(preset: PresetPromptEntity)

    @Query("DELETE FROM preset_prompts WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces")
    fun observeAll(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces")
    suspend fun getAllOnce(): List<WorkspaceEntity>

    @Query("SELECT * FROM workspaces WHERE id = :id")
    suspend fun getById(id: String): WorkspaceEntity?

    @Upsert
    suspend fun upsert(workspace: WorkspaceEntity)

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun getValue(key: String): String?

    @Upsert
    suspend fun upsert(setting: SettingEntity)
}

@Dao
interface ExternalServiceDao {
    @Query("SELECT * FROM external_services")
    fun observeAll(): Flow<List<ExternalServiceEntity>>

    @Query("SELECT apiKey FROM external_services WHERE serviceId = :serviceId")
    suspend fun getApiKey(serviceId: String): String?

    @Upsert
    suspend fun upsert(service: ExternalServiceEntity)

    @Query("DELETE FROM external_services WHERE serviceId = :serviceId")
    suspend fun deleteById(serviceId: String)
}
