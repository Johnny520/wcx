package com.Johnny.wcx.activity.settings.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.ai.AutoIncrementalScheduler
import com.Johnny.wcx.features.items.chat.AiCueSound
import com.Johnny.wcx.features.items.chat.ChatAiSummary
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AiConfigStatusRow
import com.Johnny.wcx.ui.style.SoftCard
import com.Johnny.wcx.ui.content.ScrimDialog
import com.Johnny.wcx.ui.style.WxPalette

/**
 * AI 页的「设置」页 —— AI 相关开关的唯一落点。
 *
 * 接口配置、双击提示音、能力入口说明都在这里，功能列表里不再出现 AI 的子功能条目，
 * 用户不需要在「API / 聊天」分类里逐个找。
 */
@Composable
internal fun AiCueSettings(
    onOpenConfig: () -> Unit = {},
    onMomentsCopy: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val styles = AiCueSound.Style.values()
    var enabled by remember { mutableStateOf(AiConfig.isCueSoundEnabled) }
    var selectedId by remember { mutableStateOf(AiConfig.cueSoundId) }
    val selectedIndex = AiCueSound.Style.indexOf(selectedId)
    var masterEnabled by remember { mutableStateOf(AiConfig.isMasterEnabled) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // AI 助手总开关：一关全关 —— 双击建议、长按菜单里的 AI 工具、自动回复、归档提醒、
        // 定时整理全部停用，也不再向接口发任何请求。各项自己的开关保留原值，重新打开后照旧生效。
        SoftCard(contentPadding = 0) {
            AiSwitchRow(
                title = "AI 助手总开关",
                subtitle = "关掉后双击建议、长按菜单里的 AI 工具、自动回复、归档提醒与定时整理全部停用，也不再发任何请求",
                checked = masterEnabled,
                onCheckedChange = {
                    masterEnabled = it
                    AiConfig.setMasterEnabled(it)
                },
                showDivider = false,
            )
        }

        // AI 接口配置：全模块只有这一处入口，功能自己的设置页不再重复放置地址/Key 输入框。
        SoftCard(contentPadding = 0) {
            AiConfigStatusRow(onClick = onOpenConfig)
        }

        // 创作工具：朋友圈文案。说明页只讲怎么用，实际入口放在这里。
        SoftCard(contentPadding = 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onMomentsCopy() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("朋友圈文案", fontSize = 15.sp, color = WxPalette.textPrimary())
                    Text(
                        text = "给个关键词，出 3 条可直接发的文案",
                        fontSize = 11.sp,
                        color = WxPalette.textSecondary(),
                    )
                }
                Text("  ›", fontSize = 13.sp, color = WxPalette.textTertiary())
            }
        }

        // 自动回复延迟：六档固定值，默认 8 秒。
        var showDelayPicker by remember { mutableStateOf(false) }
        var delaySeconds by remember { mutableStateOf(AiConfig.replyDelaySeconds) }
        SoftCard(contentPadding = 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDelayPicker = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("回复延迟", fontSize = 15.sp, color = WxPalette.textPrimary())
                    Text(
                        text = "对方最后一条之后等这么久没新消息才回",
                        fontSize = 11.sp,
                        color = WxPalette.textSecondary(),
                    )
                }
                Text("${delaySeconds} 秒", fontSize = 13.sp, color = WxPalette.Accent)
                Text("  ›", fontSize = 13.sp, color = WxPalette.textTertiary())
            }
        }

        if (showDelayPicker) {
            ScrimDialog(
                show = true,
                onDismissRequest = { showDelayPicker = false },
                title = "回复延迟",
            ) {
                AiConfig.REPLY_DELAY_OPTIONS.forEach { seconds ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (seconds == delaySeconds) WxPalette.primarySoft()
                                else androidx.compose.ui.graphics.Color.Transparent,
                            )
                            .clickable {
                                delaySeconds = seconds
                                AiConfig.setReplyDelaySeconds(seconds)
                                showDelayPicker = false
                            }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "$seconds 秒",
                            fontSize = 14.sp,
                            color = if (seconds == delaySeconds) WxPalette.Primary else WxPalette.textPrimary(),
                        )
                        if (seconds == AiConfig.DEFAULT_REPLY_DELAY_SECONDS) {
                            Text(
                                text = "  默认",
                                fontSize = 11.sp,
                                color = WxPalette.textTertiary(),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (seconds == delaySeconds) {
                            Text("已选", fontSize = 11.sp, color = WxPalette.Primary)
                        }
                    }
                }
            }
        }

        // 档案自动整理：每天补一次新增聊天（默认关，会花额度）。
        var autoIncremental by remember { mutableStateOf(AutoIncrementalScheduler.isEnabled) }
        var autoHour by remember { mutableStateOf(AutoIncrementalScheduler.hour) }
        var incrementalRunning by remember { mutableStateOf(false) }
        SoftCard(contentPadding = 0) {
            AiSwitchRow(
                title = "档案自动整理",
                subtitle = "每天到点给已就绪的好友档案补一次新增聊天（默认关，会消耗额度）",
                enabled = masterEnabled,
                checked = autoIncremental,
                onCheckedChange = {
                    autoIncremental = it
                    AutoIncrementalScheduler.setEnabled(it)
                },
                showDivider = autoIncremental,
            )
            if (autoIncremental) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("时间", fontSize = 13.sp, color = WxPalette.textPrimary())
                    Spacer(Modifier.weight(1f))
                    AutoIncrementalScheduler.HOUR_OPTIONS.forEach { option ->
                        val selected = option == autoHour
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (selected) WxPalette.primarySoft() else WxPalette.insetSurface())
                                .clickable {
                                    autoHour = option
                                    AutoIncrementalScheduler.setHour(option)
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                text = "${option}点",
                                fontSize = 12.sp,
                                color = if (selected) WxPalette.Primary else WxPalette.textSecondary(),
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (incrementalRunning) "正在整理，进度看「好友」页" else "也可以现在补一次",
                        fontSize = 12.sp,
                        color = WxPalette.textSecondary(),
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(WxPalette.primarySoft())
                            .clickable(enabled = !incrementalRunning) {
                                incrementalRunning = true
                                AutoIncrementalScheduler.runNow { message ->
                                    incrementalRunning = false
                                    com.Johnny.wcx.utils.android.showToast(context, message)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text("立即整理", fontSize = 12.sp, color = WxPalette.Primary)
                    }
                }
            }
        }

        // 需要用户决定的开关都在这一张卡里，不再散落在功能列表。
        var gestureEnabled by remember { mutableStateOf(AiConfig.isSuggestGestureEnabled) }
        var summaryEnabled by remember { mutableStateOf(ChatAiSummary.isEnabled) }
        SoftCard(contentPadding = 0) {
            AiSwitchRow(
                title = "双击建议",
                subtitle = "聊天页双击屏幕弹出 AI 建议卡（关掉后双击不响应）",
                enabled = masterEnabled,
                checked = gestureEnabled,
                onCheckedChange = {
                    gestureEnabled = it
                    AiConfig.setSuggestGestureEnabled(it)
                },
            )
            AiSwitchRow(
                title = "聊天记录 AI 总结",
                subtitle = "聊天输入栏长按菜单里总结最近消息，接口同上",
                enabled = masterEnabled,
                checked = summaryEnabled,
                onCheckedChange = {
                    summaryEnabled = it
                    WePrefs.putBool(ChatAiSummary.name, it)
                    ChatAiSummary.isEnabled = it
                },
                showDivider = false,
            )
        }

        SoftCard(contentPadding = 0) {
            AiSwitchRow(
                title = "双击提示音",
                subtitle = "聊天页双击触发 AI 建议时响一声",
                enabled = masterEnabled,
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    AiConfig.setCueSoundEnabled(it)
                    // 打开时顺便放一声，让用户知道「开」是什么效果。
                    if (it) AiCueSound.preview(selectedIndex)
                },
                // 收起时不要留一条孤零零的分隔线。
                showDivider = enabled,
            )

            // 音色跟着开关走：开着才展开，关掉整块收起。
            AnimatedVisibility(
                visible = enabled,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    styles.forEachIndexed { index, item ->
                        CueStyleRow(
                            label = item.label,
                            hint = item.hint,
                            selected = index == selectedIndex,
                            onClick = {
                                selectedId = item.id
                                AiConfig.setCueSoundId(item.id)
                                // 选中的同时试听：不用再单独点一次「播放」。
                                AiCueSound.preview(index)
                            },
                            showDivider = index != styles.lastIndex,
                        )
                    }
                }
            }
        }

        Text(
            text = "点一下试听并选中。关掉开关后双击不再发声，卡片照常弹出。",
            fontSize = 11.sp,
            color = WxPalette.textTertiary(),
            lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun CueStyleRow(
    label: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (selected) WxPalette.Primary else WxPalette.divider())
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    color = WxPalette.textPrimary(),
                )
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    color = WxPalette.textSecondary(),
                )
            }
            Text(
                text = "试听",
                fontSize = 12.sp,
                color = WxPalette.Primary,
            )
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
                    .height(0.5.dp)
                    .background(WxPalette.divider())
            )
        }
    }
}
