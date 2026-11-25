package com.Johnny.wcx.features.items.contacts

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.ai.AiProfile
import com.Johnny.wcx.ai.AiProfileState
import com.Johnny.wcx.ai.AiProfileStore
import com.Johnny.wcx.features.api.ui.WeContactPrefsScreenApi
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.style.WxPalette
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import com.Johnny.wcx.utils.android.currentWxId
import com.Johnny.wcx.utils.android.showToast

/**
 * 好友资料页里的两个入口：**AI 标签**、**AI 记忆**。
 *
 * 只在「点开的那个人有档案」时出现 —— 没建档的人不显示，避免每个资料页都多两行灰字。
 * 这里是**只读**视图：改标签 / 删记忆都在「AI 页 → 该好友」里做，资料页只解决
 * 「聊天时想确认对方喜欢什么」这一瞬间的需求。
 */
@Feature(
    name = "AI 档案入口",
    categories = ["API"],
    description = "在好友资料页显示「AI 标签」「AI 记忆」两个只读入口",
)
object AiProfileContactEntries : ApiFeature(), WeContactPrefsScreenApi.IContactInfoProvider {

    private const val KEY_TAGS = "wcx_ai_tags"
    private const val KEY_MEMORIES = "wcx_ai_memories"

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }

    override fun getContactInfoItem(activity: Activity): List<WeContactPrefsScreenApi.PreferenceItem> {
        val wxId = activity.currentWxId?.takeIf { !it.isGroupChatWxId } ?: return emptyList()
        val profile = AiProfileStore.readProfile(wxId) ?: return emptyList()
        if (profile.state != AiProfileState.READY) return emptyList()

        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(
                key = KEY_TAGS,
                title = "AI 标签",
                summary = profile.tags.size.toString() + " 个",
            ),
            WeContactPrefsScreenApi.PreferenceItem(
                key = KEY_MEMORIES,
                title = "AI 记忆",
                summary = profile.memories.size.toString() + " 条",
            ),
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != KEY_TAGS && key != KEY_MEMORIES) return false
        val wxId = activity.currentWxId ?: return true
        val profile = AiProfileStore.readProfile(wxId)
        if (profile == null) {
            showToast(activity, "这位好友还没有 AI 档案")
            return true
        }
        if (key == KEY_TAGS) showTags(activity, profile) else showMemories(activity, profile)
        return true
    }

    private fun showTags(activity: Activity, profile: AiProfile) {
        showComposeDialog(activity) {
            AlertDialogContent(
                title = { Text("AI 标签") },
                text = {
                    if (profile.tags.isEmpty()) {
                        Text("还没有标签", fontSize = 13.sp, color = WxPalette.textSecondary())
                    } else {
                        TagList(profile)
                    }
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
            )
        }
    }

    private fun showMemories(activity: Activity, profile: AiProfile) {
        showComposeDialog(activity) {
            AlertDialogContent(
                title = { Text("AI 记忆") },
                text = {
                    if (profile.memories.isEmpty()) {
                        Text("还没有记忆", fontSize = 13.sp, color = WxPalette.textSecondary())
                    } else {
                        MemoryList(profile)
                    }
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
            )
        }
    }

    @Composable
    private fun TagList(profile: AiProfile) {
        val grouped = remember(profile.tags) { profile.tags.groupBy { it.category } }
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            grouped.forEach { (category, tags) ->
                Text(category, fontSize = 11.sp, color = WxPalette.textSecondary())
                tags.forEach { tag ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            tag.key,
                            fontSize = 13.sp,
                            color = WxPalette.textSecondary(),
                            modifier = Modifier.width(72.dp),
                        )
                        Text(
                            tag.value,
                            fontSize = 13.sp,
                            color = WxPalette.textPrimary(),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MemoryList(profile: AiProfile) {
        val sorted = remember(profile.memories) {
            profile.memories.sortedByDescending { com.Johnny.wcx.ai.AiDates.sortKey(it.occurredAt) }
        }
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            sorted.forEach { memory ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        com.Johnny.wcx.ai.AiDates.display(memory.occurredAt),
                        fontSize = 11.sp,
                        color = WxPalette.textTertiary(),
                    )
                    Text(
                        memory.title,
                        fontSize = 13.sp,
                        color = WxPalette.textPrimary(),
                        fontWeight = FontWeight.Medium,
                    )
                    if (memory.detail.isNotBlank()) {
                        Text(
                            memory.detail,
                            fontSize = 12.sp,
                            color = WxPalette.textSecondary(),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
