package com.Johnny.wcx.activity.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Build_circle
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Phone_android
import com.composables.icons.materialsymbols.outlined.Smartphone
import com.composables.icons.materialsymbols.outlined.Sports_esports
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.Intent
import com.Johnny.wcx.utils.formatEpoch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme


// ---------------------------------------------------------------------------
//  Page 0 — Home
// ---------------------------------------------------------------------------

/**
 * Opens the LSPosed manager from within a hooked process, replicating the two-pronged shell
 * routine LSPosed itself documents:
 *  1. Start `com.android.shell/.BugreportWarningActivity` with the manager's
 *     `LAUNCH_MANAGER` category — LSPosed's hook on the shell app intercepts this and swaps in
 *     the manager UI.
 *  2. Broadcast the `*#*#5776733#*#*` SECRET_CODE (action differs on API >= 29) as a fallback
 *     for setups where the activity trick is unavailable.
 */
private fun openLsposedManager(context: Context) {
    val managerPackage = "org.lsposed.manager"
    val injectedPackage = "com.android.shell"

    runCatching {
        context.startActivity(
            Intent {
                component = ComponentName(injectedPackage, "$injectedPackage.BugreportWarningActivity")
                addCategory("$managerPackage.LAUNCH_MANAGER")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }.onFailure { WeLogger.e("SettingsActivity", "failed to launch LSPosed manager activity", it) }

    runCatching {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "android.telephony.action.SECRET_CODE"
        } else {
            "android.provider.Telephony.SECRET_CODE"
        }
        context.sendBroadcast(
            Intent(action, "android_secret_code://5776733".toUri()).setPackage("android")
        )
    }.onFailure { WeLogger.e("SettingsActivity", "failed to broadcast LSPosed secret code", it) }
}

@Composable
fun HomePager(onOpenFeatures: () -> Unit) {
    val enabledCount = remember {
        FeaturesProvider.ALL_HOOK_ITEMS.count { WePrefs.getBoolOrFalse(it.name) }
    }
    val totalCount = remember { FeaturesProvider.ALL_HOOK_ITEMS.size }

    MiuixListScaffold(title = "") {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ---- 标题区域 ----
                Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text(
                        text = "WCX",
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "微信，解锁超能力，重构你的使用体验",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                // ---- 大状态卡片 ----
                ActivationCard()

                // ---- 统计卡片（两行并排） ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CountCard(
                        modifier = Modifier.weight(1f),
                        icon = {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Sports_esports,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                        },
                        value = enabledCount.toString(),
                        label = "已启用功能",
                        onClick = onOpenFeatures,
                    )
                    CountCard(
                        modifier = Modifier.weight(1f),
                        icon = {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Smartphone,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                        },
                        value = totalCount.toString(),
                        label = "全部功能",
                        onClick = onOpenFeatures,
                    )
                }

                // ---- 设备信息 ----
                Text(
                    text = "设备信息",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                SystemInfoCard()

                Spacer(Modifier.height(CONTENT_BOTTOM_INSET))
            }
        }
    }
}

@Composable
private fun ActivationCard() {
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val accentColor = if (MiuixTheme.isDynamicColor) {
        MiuixTheme.colorScheme.primary
    } else {
        if (isDark) Color(0xFF4A90FF) else Color(0xFF2563EB)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { openLsposedManager(context) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier.size(52.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Check_circle,
                        tint = accentColor,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "模块已激活",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = BuildConfig.VERSION_NAME,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.1f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "已激活",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TagChip(text = "API ${BuildConfig.VERSION_CODE}", color = accentColor)
                TagChip(text = "v${BuildConfig.VERSION_NAME}", color = accentColor)
            }
        }
    }
}

@Composable
private fun TagChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.1f)),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun CountCard(
    modifier: Modifier,
    icon: @Composable () -> Unit,
    value: String,
    label: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = value,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun SystemInfoCard() {
    Card {
        Column(modifier = Modifier.fillMaxWidth()) {
            InfoRow(
                icon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Build_circle,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                },
                title = "构建时间",
                content = formatEpoch(BuildConfig.BUILD_TIMESTAMP, true),
                showDivider = true,
            )
            InfoRow(
                icon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Phone_android,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                },
                title = "Android 版本",
                content = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                showDivider = true,
            )
            InfoRow(
                icon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Smartphone,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                },
                title = "设备型号",
                content = "${Build.MANUFACTURER} ${Build.MODEL}",
                showDivider = false,
            )
        }
    }
}

@Composable
private fun InfoRow(
    icon: @Composable () -> Unit,
    title: String,
    content: String,
    showDivider: Boolean,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(modifier = Modifier.size(24.dp)) {
                icon()
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = content,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 54.dp)
                    .height(0.5.dp)
                    .background(MiuixTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
            )
        }
    }
}
