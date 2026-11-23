package com.Johnny.wcx.activity.settings.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.ai.group.GroupDigestConfig
import com.Johnny.wcx.ai.group.GroupDigestStore
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.WeGroup
import com.Johnny.wcx.ui.content.ScrimDialog
import com.Johnny.wcx.ui.style.WxPalette
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 添加群聊：从微信的群列表里多选。
 *
 * 新加的群**默认关闭**；`anchorAt` 设为当前时刻、`lastSlotEnd` 保持 0 ——
 * 打开后第一次窗口取 `now - interval`，不补添加之前的聊天记录。
 */
@Composable
internal fun AiAddGroupSheet(
    existingIds: Set<String>,
    onDismiss: () -> Unit,
    /** 未配置时点提示条 -> 打开 AI 配置面板。 */
    onRequestConfig: () -> Unit = {},
    onAdded: (Int) -> Unit,
) {
    var all by remember { mutableStateOf<List<WeGroup>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        all = withContext(Dispatchers.IO) {
            runCatching {
                WeDatabaseApi.getGroups()
                    .filter { it.wxId.isGroupChatWxId }
                    .sortedBy { it.nickname }
            }.getOrDefault(emptyList())
        }
        loading = false
    }

    val visible = remember(all, query, existingIds) {
        val keyword = query.trim()
        all.asSequence()
            .filter { it.wxId !in existingIds }
            .filter { keyword.isEmpty() || it.nickname.contains(keyword, ignoreCase = true) }
            .toList()
    }

    ScrimDialog(show = true, onDismissRequest = onDismiss, title = "添加群聊") {
        AiTextInput(
            value = query,
            onValueChange = { query = it },
            placeholder = "搜索群名",
        )

        when {
            loading -> Text("载入中…", fontSize = 13.sp, color = WxPalette.textSecondary())
            visible.isEmpty() -> Text(
                text = if (query.isBlank()) "没有可添加的群" else "没有匹配的群",
                fontSize = 13.sp,
                color = WxPalette.textSecondary(),
            )
            else -> LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(visible, key = { it.wxId }) { group ->
                    val checked = group.wxId in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (checked) selected - group.wxId else selected + group.wxId
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = WxPalette.Primary),
                        )
                        AiAvatar(model = group.avatarUrl, name = group.nickname, size = 36)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = group.nickname.ifBlank { group.wxId },
                            fontSize = 14.sp,
                            color = WxPalette.textPrimary(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        AiConfigHintBar(
            onClick = onRequestConfig,
            text = "还没填 AI 配置：添加后不会生成总结，先去填写",
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AiSecondaryButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            AiPrimaryButton(
                text = if (selected.isEmpty()) "添加" else "添加 ${selected.size} 个",
                enabled = selected.isNotEmpty() && !saving,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (selected.isEmpty() || saving) return@AiPrimaryButton
                    saving = true
                    val ids = selected
                    scope.launch {
                        val now = System.currentTimeMillis()
                        val added = withContext(Dispatchers.IO) {
                            ids.count { id ->
                                val group = all.firstOrNull { it.wxId == id } ?: return@count false
                                GroupDigestStore.saveConfig(
                                    GroupDigestConfig(
                                        groupId = id,
                                        name = group.nickname,
                                        avatarUrl = group.avatarUrl,
                                        enabled = false,
                                        anchorAt = now,
                                        // 0 = 从未跑过。首次开启时窗口取 now - interval，
                                        // 用户打开就能看到最近一段，不用等一个周期。
                                        lastSlotEnd = 0L,
                                    )
                                )
                            }
                        }
                        saving = false
                        onAdded(added)
                    }
                },
            )
        }
    }
}
