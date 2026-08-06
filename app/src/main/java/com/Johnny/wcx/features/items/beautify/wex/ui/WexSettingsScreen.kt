package com.Johnny.wcx.features.items.beautify.wex.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.items.beautify.wex.WexBeautifyFeature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.utils.android.showToast

/**
 * Wex 美化设置页面 — 移植自 Wex 的 FeatureSettingsActivity
 * 使用 WCX 的 Compose UI 框架
 */
@Composable
fun WexSettingsScreen(onDismiss: () -> Unit) {
    var masterEnabled by remember { mutableStateOf(WexBeautifyFeature.masterEnabled) }
    var bottomBarEnabled by remember { mutableStateOf(WexBeautifyFeature.bottomBarEnabled) }
    var topSearchBarEnabled by remember { mutableStateOf(WexBeautifyFeature.topSearchBarEnabled) }
    var topProfileEnabled by remember { mutableStateOf(WexBeautifyFeature.topProfileEnabled) }
    var topTitle by remember { mutableStateOf(WexBeautifyFeature.topTitle) }
    var topNickname by remember { mutableStateOf(WexBeautifyFeature.topNickname) }
    var topStatus by remember { mutableStateOf(WexBeautifyFeature.topStatus) }
    var topAvatarPath by remember { mutableStateOf(WexBeautifyFeature.topAvatarPath) }
    var topSearchHint by remember { mutableStateOf(WexBeautifyFeature.topSearchHint) }
    var topDotColor by remember { mutableStateOf(WexBeautifyFeature.topDotColor) }
    var homeCalendarCardEnabled by remember { mutableStateOf(WexBeautifyFeature.homeCalendarCardEnabled) }
    var homeImageCardEnabled by remember { mutableStateOf(WexBeautifyFeature.homeImageCardEnabled) }
    var homeMusicCardEnabled by remember { mutableStateOf(WexBeautifyFeature.homeMusicCardEnabled) }
    var musicPlayerEnabled by remember { mutableStateOf(WexBeautifyFeature.musicPlayerEnabled) }
    var floatLyricEnabled by remember { mutableStateOf(WexBeautifyFeature.floatLyricEnabled) }
    var logEnabled by remember { mutableStateOf(WexBeautifyFeature.logEnabled) }

    AlertDialogContent(
        title = { Text("Wex 美化设置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // ==================== 总开关 ====================
                SwitchSettingItem(
                    title = "启用 Wex 美化",
                    description = "关闭后全部美化效果恢复微信原生样式",
                    checked = masterEnabled,
                    onCheckedChange = { masterEnabled = it }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                if (masterEnabled) {
                    // ==================== 底栏美化 ====================
                    Text(
                        "底栏美化",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    SwitchSettingItem(
                        title = "悬浮圆角底栏",
                        description = "将微信底部导航栏改为悬浮圆角样式",
                        checked = bottomBarEnabled,
                        onCheckedChange = { bottomBarEnabled = it }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // ==================== 顶栏美化 ====================
                    Text(
                        "顶栏美化",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    SwitchSettingItem(
                        title = "自定义搜索框",
                        description = "隐藏原生搜索按钮，替换为圆角搜索框卡片",
                        checked = topSearchBarEnabled,
                        onCheckedChange = { topSearchBarEnabled = it }
                    )
                    SwitchSettingItem(
                        title = "顶栏头像昵称",
                        description = "在顶栏左侧显示自定义头像、昵称、状态",
                        checked = topProfileEnabled,
                        onCheckedChange = { topProfileEnabled = it }
                    )

                    if (topProfileEnabled) {
                        OutlinedTextField(
                            value = topTitle,
                            onValueChange = { topTitle = it },
                            label = { Text("自定义标题（替换\"微信\"）") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            singleLine = true,
                            placeholder = { Text("留空则显示\"微信\"") }
                        )
                        OutlinedTextField(
                            value = topNickname,
                            onValueChange = { topNickname = it },
                            label = { Text("自定义昵称") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = topStatus,
                            onValueChange = { topStatus = it },
                            label = { Text("自定义状态") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = topAvatarPath,
                            onValueChange = { topAvatarPath = it },
                            label = { Text("自定义头像路径") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            singleLine = true,
                            placeholder = { Text("如 /sdcard/avatar.png") }
                        )
                        OutlinedTextField(
                            value = topSearchHint,
                            onValueChange = { topSearchHint = it },
                            label = { Text("搜索框提示文字") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            singleLine = true,
                            placeholder = { Text("默认\"搜索\"") }
                        )
                        // 状态圆点颜色
                        SwitchSettingItem(
                            title = "状态圆点颜色",
                            description = if (topDotColor == "red") "当前：红色" else "当前：绿色",
                            checked = topDotColor == "red",
                            onCheckedChange = { topDotColor = if (it) "red" else "green" }
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // ==================== 首页三卡 ====================
                    Text(
                        "首页三卡",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    SwitchSettingItem(
                        title = "日历卡片",
                        description = "在聊天列表顶部显示日历卡片",
                        checked = homeCalendarCardEnabled,
                        onCheckedChange = { homeCalendarCardEnabled = it }
                    )
                    SwitchSettingItem(
                        title = "图片卡片",
                        description = "在聊天列表顶部显示每日一图卡片",
                        checked = homeImageCardEnabled,
                        onCheckedChange = { homeImageCardEnabled = it }
                    )
                    SwitchSettingItem(
                        title = "音乐卡片",
                        description = "在聊天列表顶部显示音乐播放卡片",
                        checked = homeMusicCardEnabled,
                        onCheckedChange = { homeMusicCardEnabled = it }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // ==================== 音乐播放器 ====================
                    Text(
                        "音乐播放器",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    SwitchSettingItem(
                        title = "音乐播放器",
                        description = "在微信中显示音乐播放控制",
                        checked = musicPlayerEnabled,
                        onCheckedChange = { musicPlayerEnabled = it }
                    )
                    SwitchSettingItem(
                        title = "悬浮歌词",
                        description = "在首页显示悬浮歌词（默认关闭）",
                        checked = floatLyricEnabled,
                        onCheckedChange = { floatLyricEnabled = it }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // ==================== 日志 ====================
                    SwitchSettingItem(
                        title = "记录美化日志",
                        description = "记录美化功能的运行日志",
                        checked = logEnabled,
                        onCheckedChange = { logEnabled = it }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "本美化功能移植自 Wex 开源项目 (https://github.com/Ql1121/Wex)，属于二次修改集成",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        confirmButton = {
            Button(onClick = {
                // 保存所有配置
                WexBeautifyFeature.masterEnabled = masterEnabled
                WexBeautifyFeature.bottomBarEnabled = bottomBarEnabled
                WexBeautifyFeature.topSearchBarEnabled = topSearchBarEnabled
                WexBeautifyFeature.topProfileEnabled = topProfileEnabled
                WexBeautifyFeature.topTitle = topTitle
                WexBeautifyFeature.topNickname = topNickname
                WexBeautifyFeature.topStatus = topStatus
                WexBeautifyFeature.topAvatarPath = topAvatarPath
                WexBeautifyFeature.topSearchHint = topSearchHint
                WexBeautifyFeature.topDotColor = topDotColor
                WexBeautifyFeature.homeCalendarCardEnabled = homeCalendarCardEnabled
                WexBeautifyFeature.homeImageCardEnabled = homeImageCardEnabled
                WexBeautifyFeature.homeMusicCardEnabled = homeMusicCardEnabled
                WexBeautifyFeature.musicPlayerEnabled = musicPlayerEnabled
                WexBeautifyFeature.floatLyricEnabled = floatLyricEnabled
                WexBeautifyFeature.logEnabled = logEnabled

                showToast("Wex 美化设置已保存")
                onDismiss()
            }) { Text("保存") }
        }
    )
}

@Composable
private fun SwitchSettingItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    DefaultColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        androidx.compose.material3.ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(description) },
            trailingContent = {
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }
        )
    }
}