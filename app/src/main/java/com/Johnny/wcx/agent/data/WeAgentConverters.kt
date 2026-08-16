package com.Johnny.wcx.agent.data

import androidx.room.TypeConverter
import com.Johnny.wcx.agent.data.entity.ApprovalStatus
import com.Johnny.wcx.agent.data.entity.McpTransport
import com.Johnny.wcx.agent.data.entity.MessageRole
import com.Johnny.wcx.agent.data.entity.ModelProviderType
import com.Johnny.wcx.agent.tool.ProviderKind
import com.Johnny.wcx.agent.tool.ToolMode
import com.Johnny.wcx.agent.trigger.ScheduleKind
import com.Johnny.wcx.agent.trigger.TriggerScope
import com.Johnny.wcx.agent.trigger.TriggerType
import java.time.Instant

/**
 * Room type converters for the WeAgent database. Enums are stored by name (stable across
 * refactors as long as constant names are preserved); [Instant] is stored as epoch millis.
 */
class WeAgentConverters {

    @TypeConverter
    fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()
    @TypeConverter
    fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun messageRoleToString(v: MessageRole?): String? = v?.name
    @TypeConverter
    fun stringToMessageRole(v: String?): MessageRole? = v?.let(MessageRole::valueOf)

    @TypeConverter
    fun approvalToString(v: ApprovalStatus?): String? = v?.name
    @TypeConverter
    fun stringToApproval(v: String?): ApprovalStatus? = v?.let(ApprovalStatus::valueOf)

    @TypeConverter
    fun providerKindToString(v: ProviderKind?): String? = v?.name
    @TypeConverter
    fun stringToProviderKind(v: String?): ProviderKind? = v?.let(ProviderKind::valueOf)

    @TypeConverter
    fun mcpTransportToString(v: McpTransport?): String? = v?.name
    @TypeConverter
    fun stringToMcpTransport(v: String?): McpTransport? = v?.let(McpTransport::valueOf)

    @TypeConverter
    fun toolModeToString(v: ToolMode?): String? = v?.name
    @TypeConverter
    fun stringToToolMode(v: String?): ToolMode? = v?.let(ToolMode::valueOf)

    @TypeConverter
    fun modelProviderTypeToString(v: ModelProviderType?): String? = v?.name
    @TypeConverter
    fun stringToModelProviderType(v: String?): ModelProviderType? = v?.let(ModelProviderType::valueOf)

    @TypeConverter
    fun triggerTypeToString(v: TriggerType?): String? = v?.name
    @TypeConverter
    fun stringToTriggerType(v: String?): TriggerType? = v?.let(TriggerType::valueOf)

    @TypeConverter
    fun triggerScopeToString(v: TriggerScope?): String? = v?.name
    @TypeConverter
    fun stringToTriggerScope(v: String?): TriggerScope? = v?.let(TriggerScope::valueOf)

    @TypeConverter
    fun scheduleKindToString(v: ScheduleKind?): String? = v?.name
    @TypeConverter
    fun stringToScheduleKind(v: String?): ScheduleKind? = v?.let(ScheduleKind::valueOf)
}
