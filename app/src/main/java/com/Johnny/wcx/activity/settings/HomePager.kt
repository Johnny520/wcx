package com.Johnny.wcx.activity.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.AppUpdater
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.UpdateResult
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
    }.onFailure { WeLogger.e("HomePager", "failed to launch LSPosed manager activity", it) }

    runCatching {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "android.telephony.action.SECRET_CODE"
        } else {
            "android.provider.Telephony.SECRET_CODE"
        }
        context.sendBroadcast(
            Intent(action, "android_secret_code://5776733".toUri()).setPackage("android")
        )
    }.onFailure { WeLogger.e("HomePager", "failed to broadcast LSPosed secret code", it) }
}

/** 安全判断当前是否运行在微信宿主进程内 */
private fun safeIsHost(): Boolean {
    return runCatching { HostInfo.isHost }.getOrDefault(false)
}

/**
 * 安全获取微信版本信息。
 * - 主体App进程：通过 PackageManager 查询（需 Android 11+ queries 声明）
 * - 微信进程内：直接从 HostInfo 读取当前宿主版本
 * 返回 null 表示未检测到微信。
 */
private fun safeGetWeChatVersionInfo(context: Context): String? {
    // 微信进程内：直接从 HostInfo 读取
    if (safeIsHost()) {
        return runCatching {
            val name = HostInfo.versionName.ifEmpty { return null }
            val code = HostInfo.versionCode
            "微信 $name ($code)"
        }.getOrNull()
    }

    // 主体App进程：通过 PackageManager 查询
    return runCatching {
        val pm = context.packageManager ?: return null
        val info = pm.getPackageInfo(PackageNames.WECHAT, 0)
        val name = info.versionName?.ifEmpty { null } ?: return null
        val code = info.longVersionCode
        "微信 $name ($code)"
    }.getOrNull()
}

/**
 * 识别运行环境：LSPosed / LSPatch / 未知。
 * - 主体App进程：主动探测并缓存到 WePrefs（跨进程共享）
 * - 微信进程内：从 WePrefs 读取缓存值，禁止在微信进程内探测
 */
private fun detectOrReadLspEnvironment(context: Context): String {
    // 微信进程内：从跨进程缓存读取
    if (safeIsHost()) {
        return runCatching {
            WePrefs.getStringOrDef(Preferences.CACHED_LSP_ENVIRONMENT, "未知")
        }.getOrDefault("未知")
    }

    // 主体App进程：主动探测并缓存
    val result = runCatching {
        val pm = context.packageManager ?: return@runCatching "未知"

        val hasLsposed = runCatching {
            pm.getPackageInfo("org.lsposed.manager", 0)
        }.isSuccess
        if (hasLsposed) return@runCatching "LSPosed"

        val hasLspatch = runCatching {
            pm.getPackageInfo("org.lspatch.manager", 0)
        }.isSuccess
        if (hasLspatch) return@runCatching "LSPatch"

        "未知"
    }.getOrDefault("未知")

    // 缓存到 WePrefs 供微信进程读取
    runCatching {
        WePrefs.putString(Preferences.CACHED_LSP_ENVIRONMENT, result)
    }.onFailure {
        WeLogger.e("HomePager", "failed to cache LSP environment", it)
    }

    return result
}

/** 版本字符串格式化：git+4fcbb76 (73) */
private fun formatLocalVersion(): String {
    val name = BuildConfig.VERSION_NAME.ifEmpty { "未知" }
    return "$name (${BuildConfig.VERSION_CODE})"
}

/** 安全打开外部链接，处理无可用浏览器场景 */
private fun safeOpenUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.onFailure {
        WeLogger.e("HomePager", "failed to open url: $url", it)
    }
}

@Composable
fun HomePager(onOpenFeatures: () -> Unit) {
    val context = LocalContext.current
    val enabledCount = remember {
        FeaturesProvider.ALL_HOOK_ITEMS.count { WePrefs.getBoolOrFalse(it.name) }
    }
    val totalCount = remember { FeaturesProvider.ALL_HOOK_ITEMS.size }

    var latestVersion by remember { mutableStateOf<String?>(null) }
    var isLatest by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(true) }

    // 设备信息：微信版本和运行环境在重组间保持稳定
    val wechatVersion = remember { safeGetWeChatVersionInfo(context) }
    val lspEnvironment = remember { detectOrReadLspEnvironment(context) }

    LaunchedEffect(Unit) {
        isChecking = true
        runCatching {
            when (val result = AppUpdater.checkForUpdate()) {
                is UpdateResult.UpdateAvailable -> {
                    val tag = result.info.releaseTag.removePrefix("v").ifEmpty { null }
                    latestVersion = tag
                    isLatest = false
                }
                is UpdateResult.UpToDate -> {
                    latestVersion = null
                    isLatest = true
                }
                is UpdateResult.Error -> {
                    WeLogger.e("HomePager", "Failed to check update", result.cause)
                    latestVersion = null
                    isLatest = false
                }
            }
        }.onFailure {
            WeLogger.e("HomePager", "Update check exception", it)
            latestVersion = null
            isLatest = false
        }
        isChecking = false
    }

    MiuixListScaffold(title = "") {
        // ---- 标题区域 ----
        // fillMaxWidth + wrapContentHeight 防止无限高度约束导致灰色渲染
        // background 设置与 TopAppBar 毛玻璃相同的背景色，避免滚动时出现灰色色块
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .background(MiuixTheme.colorScheme.surface)
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp),
            ) {
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
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        // ---- 大状态卡片 ----
        item {
            Spacer(Modifier.height(8.dp))
            ActivationCard(latestVersion, isLatest, isChecking)
        }

        // ---- 统计卡片 ----
        item {
            Spacer(Modifier.height(8.dp))
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
        }

        // ---- 设备信息标题 ----
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "设备信息",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ---- 设备信息卡片 ----
        item {
            SystemInfoCard(wechatVersion, lspEnvironment, !safeIsHost())
        }

        // ---- 底部留白 ----
        item {
            Spacer(Modifier.height(CONTENT_BOTTOM_INSET))
        }
    }
}

@Composable
private fun ActivationCard(latestVersion: String?, isLatest: Boolean, isChecking: Boolean) {
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
                        text = formatLocalVersion(),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(accentColor.copy(alpha = 0.1f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = when {
                            isChecking -> "检查中..."
                            isLatest -> "已是最新版本"
                            latestVersion != null -> "有更新"
                            else -> "检查失败"
                        },
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
                TagChip(
                    text = "当前版本 ${formatLocalVersion()}",
                    color = accentColor,
                )
                if (!isChecking && latestVersion != null) {
                    TagChip(
                        text = "最新版本 $latestVersion",
                        color = accentColor,
                    )
                }
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
private fun SystemInfoCard(wechatVersion: String?, lspEnvironment: String, showLspEnvironment: Boolean) {
    Card {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ① 微信版本
            InfoRow(
                icon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Smartphone,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                },
                title = "微信版本",
                content = wechatVersion ?: "未检测到微信",
                showDivider = true,
            )
            // ② 运行环境 — 仅在主体App进程显示，微信进程内不显示
            if (showLspEnvironment) {
                InfoRow(
                    icon = {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Check_circle,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    title = "运行环境",
                    content = lspEnvironment.ifEmpty { "未知" },
                    showDivider = true,
                )
            }
            // ③ 构建时间
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
                content = runCatching { formatEpoch(BuildConfig.BUILD_TIMESTAMP, true) }.getOrDefault("未知"),
                showDivider = true,
            )
            // ④ Android 版本
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
                content = "${Build.VERSION.RELEASE.orEmpty().ifEmpty { "未知" }} (API ${Build.VERSION.SDK_INT})",
                showDivider = true,
            )
            // ⑤ 设备型号
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
                content = "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim().ifEmpty { "未知" },
                showDivider = true,
            )
            // ⑥ 系统架构
            InfoRow(
                icon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Smartphone,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                },
                title = "系统架构",
                content = Build.SUPPORTED_ABIS?.joinToString(", ")?.ifEmpty { "未知" } ?: "未知",
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
                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f)),
            )
        }
    }
}
