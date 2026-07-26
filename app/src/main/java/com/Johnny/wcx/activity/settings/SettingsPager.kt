package com.Johnny.wcx.activity.settings


import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.compose.material3.CircularProgressIndicator
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Account_circle
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Auto_delete
import com.composables.icons.materialsymbols.outlined.Block
import com.composables.icons.materialsymbols.outlined.Brightness_medium
import com.composables.icons.materialsymbols.outlined.Build_circle
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Colorize
import com.composables.icons.materialsymbols.outlined.Contrast
import com.composables.icons.materialsymbols.outlined.Delete_forever
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Frame_bug
import com.composables.icons.materialsymbols.outlined.History
import com.composables.icons.materialsymbols.outlined.Label
import com.composables.icons.materialsymbols.outlined.License
import com.composables.icons.materialsymbols.outlined.Lightbulb_2
import com.composables.icons.materialsymbols.outlined.Notifications
import com.composables.icons.materialsymbols.outlined.Palette
import com.composables.icons.materialsymbols.outlined.Public
import com.composables.icons.materialsymbols.outlined.Rule_settings
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Style
import com.composables.icons.materialsymbols.outlined.Sync
import com.composables.icons.materialsymbols.outlined.Update
import com.composables.icons.materialsymbols.outlined.Upload
import com.composables.icons.materialsymbols.outlined.Volunteer_activism
import com.composables.icons.materialsymbols.outlined.Wallpaper
import com.composables.icons.materialsymbols.outlined.Qr_code
import com.composables.icons.materialsymbols.outlined.Send
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.tencent.mm.ui.LauncherUI
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.aboutlibraries.AboutLibrariesProvider
import com.Johnny.wcx.activity.TransparentActivity
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.items.debug.ResetDexCache
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.MiuixSmallTitle
import com.Johnny.wcx.ui.utils.GitHubIcon
import com.Johnny.wcx.ui.utils.theme.AppColorSpec
import com.Johnny.wcx.ui.utils.theme.AppPaletteStyle
import com.Johnny.wcx.ui.utils.theme.AppThemeMode
import com.Johnny.wcx.ui.utils.theme.ThemeSettings
import com.Johnny.wcx.utils.AppUpdater
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.ReleaseItem
import com.Johnny.wcx.utils.UpdateResult
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToastSuspend
import com.Johnny.wcx.utils.formatEpoch
import com.Johnny.wcx.utils.openInSystem
import com.Johnny.wcx.utils.serialization.DefaultJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

// ---------------------------------------------------------------------------
//  Page 2 — Settings
// ---------------------------------------------------------------------------

@Composable
fun SettingsPager(onOpenLicense: () -> Unit, onOpenAcknowledgements: () -> Unit) {
    val context = LocalComponentActivity.current

    var showClearConfirm by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateResult.UpdateAvailable?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var showChangelog by remember { mutableStateOf(false) }
    var showDonate by remember { mutableStateOf(false) }

    ClearConfigDialog(show = showClearConfirm, onDismiss = { showClearConfirm = false })
    UpdateAvailableDialog(info = updateInfo, onDismiss = { updateInfo = null }, context = context)
    UpdateErrorDialog(message = updateError, onDismiss = { updateError = null })
    ChangelogDialog(show = showChangelog, onDismiss = { showChangelog = false }, context = context)
    DonateDialog(show = showDonate, onDismiss = { showDonate = false }, context = context)

    MiuixListScaffold(title = "设置") {
        // Account info card — shown at top of Settings tab.
        item {
            Spacer(Modifier.height(12.dp))
            ProfileCard()
        }

        // 界面
        item {
            MiuixSmallTitle(text = "界面", modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                ThemeSection()
            }
        }

        // 调试
        item {
            MiuixSmallTitle(text = "调试", modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefSwitch(
                    key = Preferences.VERBOSE_LOG,
                    title = "详细日志",
                    summary = "输出高频日志 (这可能会暴露你的隐私信息）",
                    icon = MaterialSymbols.Outlined.Frame_bug,
                )
                PrefSwitch(
                    key = Preferences.SHOW_STARTUP_TOAST,
                    title = "显示加载完成 Toast",
                    summary = "全部功能加载完成后显示 Toast 提示",
                    icon = MaterialSymbols.Outlined.Notifications,
                )
                PrefSwitch(
                    key = Preferences.MATCH_GENERIC_WXID_EXP,
                    title = "清理消息内容微信 ID 前缀时允许非标准 ID",
                    summary = "允许处理不带 'wxid_' 前缀的微信 ID, 可能导致误伤消息原始内容 (实验性)",
                    icon = MaterialSymbols.Outlined.Rule_settings,
                )
            }
        }

        // 兼容
        item {
            MiuixSmallTitle(text = "兼容", modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefSwitch(
                    key = Preferences.NO_DEX_RESOLVE,
                    title = "禁用版本适配",
                    summary = "不弹出 DEX 查找对话框，未适配功能将不会被加载",
                    icon = MaterialSymbols.Outlined.Block,
                )
                PrefArrow(
                    title = "重置适配信息",
                    summary = "清除 DEX 缓存, 等待下次启动时重新适配",
                    icon = MaterialSymbols.Outlined.Build_circle,
                    onClick = { ResetDexCache.onClick(context) },
                )
                PrefSwitch(
                    key = Preferences.RESET_DEX_ON_HOT_UPDATE,
                    title = "宿主热更新时重新适配",
                    summary = "宿主热更新时是否重置 DEX 缓存, 可能导致频繁重新适配 (实验性)",
                    icon = MaterialSymbols.Outlined.Auto_delete,
                )
            }
        }

        // 配置
        item {
            MiuixSmallTitle(text = "配置", modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefArrow(
                    title = "导出配置",
                    summary = "将模块配置导出为 JSON",
                    icon = MaterialSymbols.Outlined.Upload,
                    onClick = { exportConfig(context) },
                )
                PrefArrow(
                    title = "导入配置",
                    summary = "从 JSON 导入模块配置; JSON 中的配置将会与现有配置合并, 覆盖所有已存在的配置",
                    icon = MaterialSymbols.Outlined.Download,
                    onClick = { importConfig(context) },
                )
                PrefArrow(
                    title = "清除配置",
                    summary = "清除全部模块配置 (警告: 此操作不可逆!)",
                    icon = MaterialSymbols.Outlined.Delete_forever,
                    onClick = { showClearConfirm = true },
                )
            }
        }

        // 更新
        item {
            MiuixSmallTitle(text = "更新", modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefArrow(
                    title = "检查更新",
                    summary = "立即检查模块是否有新版本并自动下载",
                    icon = MaterialSymbols.Outlined.Update,
                    onClick = {
                        checkForUpdate(
                            onAvailable = { updateInfo = it },
                            onError = { updateError = it },
                        )
                    },
                )
                PrefArrow(
                    title = "更新记录",
                    summary = "查看历史版本更新内容",
                    icon = MaterialSymbols.Outlined.History,
                    onClick = { showChangelog = true },
                )
            }
        }

        // 关于
        item {
            MiuixSmallTitle(text = "关于", modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefArrow(
                    title = "鸣谢名单",
                    summary = "感谢为本模块提供基础和灵感的项目与作者",
                    icon = MaterialSymbols.Outlined.Volunteer_activism,
                    onClick = onOpenAcknowledgements,
                )
                PrefArrow(title = "版本", summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", icon = MaterialSymbols.Outlined.Label)
                PrefArrow(title = "构建提交时间", summary = formatEpoch(BuildConfig.BUILD_TIMESTAMP, true), icon = MaterialSymbols.Outlined.Build_circle)
                PrefArrow(
                    title = "提示",
                    summary = "牙膏要一点一点挤, 显卡要一刀一刀切, PPT 要一张一张放, 代码要一行一行写, 单个功能预计自出现在 commit 之日起, 三年内开发完毕",
                    icon = MaterialSymbols.Outlined.Lightbulb_2,
                )
                PrefArrow(
                    title = "捐赠",
                    summary = "支持项目开发 (模块完全开源免费, 捐赠无特权)",
                    icon = MaterialSymbols.Outlined.Volunteer_activism,
                    onClick = { showDonate = true },
                )
                PrefArrow(
                    title = "开放源代码许可",
                    summary = "本项目使用的开放源代码库许可",
                    icon = MaterialSymbols.Outlined.License,
                    onClick = onOpenLicense,
                )
                PrefArrow(
                    title = "GitHub",
                    summary = "Johnny520/wcx",
                    icon = GitHubIcon,
                    onClick = { "https://github.com/Johnny520/wcx".toUri().openInSystem(context, true) })
                PrefArrow(
                    title = "TG 群组",
                    summary = "暂无群组",
                    icon = MaterialSymbols.Outlined.Send,
                )
                PrefArrow(
                    title = "官方网站",
                    summary = "https://johnny520.github.io/Johnny/",
                    icon = MaterialSymbols.Outlined.Public,
                    onClick = { "https://johnny520.github.io/Johnny/".toUri().openInSystem(context, true) })
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}

// ---------------------------------------------------------------------------
//  Profile card — account info at the top of the Settings tab
// ---------------------------------------------------------------------------

@Composable
private fun ProfileCard() {
    val wxId = remember { WeApi.selfWxId }

    // WeChat identity — loaded once from the local DB; doesn't change mid-session.
    data class WechatIdentity(val nickname: String, val avatarUrl: String)

    val identity by produceState(WechatIdentity("", "")) {
        withContext(Dispatchers.IO) {
            val db = com.Johnny.wcx.features.api.core.WeDatabaseApi
            val nickname = if (db.isReady) {
                db.getSelfProfileField(com.Johnny.wcx.features.api.core.models.SelfProfileField.NAME, "")
                    ?.toString().orEmpty()
            } else ""
            val avatarUrl = if (db.isReady && wxId.isNotEmpty()) db.getAvatarUrl(wxId) else ""
            value = WechatIdentity(nickname, avatarUrl)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (identity.avatarUrl.isNotEmpty()) {
                AsyncImage(
                    model = identity.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape),
                )
            } else {
                AvatarPlaceholder()
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = identity.nickname.ifEmpty { wxId.ifEmpty { "—" } },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                if (wxId.isNotEmpty()) {
                    Text(
                        text = wxId,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AvatarPlaceholder() {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MaterialSymbols.Outlined.Account_circle,
            contentDescription = null,
            modifier = Modifier.size(34.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** A miuix dropdown bound to an enum's entries. */
@Composable
private fun <T> EnumDropdown(
    title: String,
    entries: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelected: (T) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    WindowDropdownPreference(
        title = title,
        summary = summary,
        items = entries.map(labelOf),
        selectedIndex = entries.indexOf(selected).coerceAtLeast(0),
        enabled = enabled,
        startAction = icon?.let { { PrefIcon(it) } },
        onSelectedIndexChange = { onSelected(entries[it]) },
    )
}

@Composable
private fun ThemeSection() {
    EnumDropdown(
        title = "主题模式",
        entries = AppThemeMode.entries,
        selected = ThemeSettings.themeMode,
        labelOf = { it.displayName },
        onSelected = { ThemeSettings.updateThemeMode(it) },
        icon = MaterialSymbols.Outlined.Brightness_medium,
    )

    var customColor by remember { mutableStateOf(ThemeSettings.customColor) }
    SwitchPreference(
        title = "自定义颜色",
        summary = "使用调色板样式生成配色, 而非 Miuix 默认蓝",
        startAction = { PrefIcon(MaterialSymbols.Outlined.Palette) },
        checked = customColor,
        onCheckedChange = {
            customColor = it
            ThemeSettings.updateCustomColor(it)
        },
    )

    var showColorPicker by remember { mutableStateOf(false) }
    SeedColorPickerDialog(show = showColorPicker, onDismiss = { showColorPicker = false })

    AnimatedVisibility(visible = customColor) {
        Column {
            var dynamicWallpaper by remember { mutableStateOf(ThemeSettings.dynamicWallpaper) }
            SwitchPreference(
                title = "动态壁纸取色",
                summary = "使用系统壁纸的强调色作为种子\n需系统 Android SDK >= 31",
                startAction = { PrefIcon(MaterialSymbols.Outlined.Wallpaper) },
                checked = dynamicWallpaper,
                onCheckedChange = {
                    dynamicWallpaper = it
                    ThemeSettings.updateDynamicWallpaper(it)
                },
            )
            AnimatedVisibility(visible = !dynamicWallpaper) {
                BasicComponent(
                    title = "种子颜色",
                    summary = "点击选择配色的种子颜色",
                    startAction = { PrefIcon(MaterialSymbols.Outlined.Colorize) },
                    onClick = { showColorPicker = true },
                    endActions = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(ThemeSettings.seedColor)),
                        )
                    },
                )
            }
            EnumDropdown(
                title = "调色板样式",
                entries = AppPaletteStyle.entries,
                selected = ThemeSettings.paletteStyle,
                labelOf = { it.displayName },
                onSelected = {
                    ThemeSettings.updatePaletteStyle(it)
                    // Keep the stored spec valid for the new style.
                    if (!it.supportsSpec2025 && ThemeSettings.colorSpec == AppColorSpec.SPEC_2025) {
                        ThemeSettings.updateColorSpec(AppColorSpec.SPEC_2021)
                    }
                },
                icon = MaterialSymbols.Outlined.Style,
            )
            val spec2025Supported = ThemeSettings.paletteStyle.supportsSpec2025
            EnumDropdown(
                title = "颜色规格",
                entries = if (spec2025Supported) AppColorSpec.entries else listOf(AppColorSpec.SPEC_2021),
                selected = ThemeSettings.effectiveColorSpec,
                labelOf = { it.displayName },
                onSelected = { ThemeSettings.updateColorSpec(it) },
                enabled = spec2025Supported,
                summary = if (!spec2025Supported) "当前调色板样式仅支持 Material 3 (2021)" else null,
                icon = MaterialSymbols.Outlined.Contrast,
            )

            var applyToWechat by remember { mutableStateOf(ThemeSettings.applyToWechat) }
            SwitchPreference(
                title = "同时对微信生效",
                summary = "将自定义配色应用到微信本身",
                startAction = { PrefIcon(MaterialSymbols.Outlined.Sync) },
                checked = applyToWechat,
                onCheckedChange = {
                    applyToWechat = it
                    ThemeSettings.updateApplyToWechat(it)
                    CoroutineScope(Dispatchers.Main).launch { showToastSuspend("重启微信生效") }
                },
            )
        }
    }
}

/** miuix color-picker dialog for the custom seed color; commits to ThemeSettings on confirm. */
@Composable
private fun SeedColorPickerDialog(show: Boolean, onDismiss: () -> Unit) {
    var picked by remember(show) { mutableStateOf(Color(ThemeSettings.seedColor)) }

    WindowDialog(show = show, title = "自定义颜色", onDismissRequest = onDismiss) {
        Column {
            ColorPicker(
                color = picked,
                onColorChanged = { picked = it },
            )
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "重置",
                    onClick = { picked = Color(ThemeSettings.DEFAULT_SEED_COLOR) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "确定",
                    onClick = {
                        ThemeSettings.updateSeedColor(picked.toArgb())
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Preference helper composables
// ---------------------------------------------------------------------------

@Composable
private fun PrefSwitch(
    key: String,
    title: String,
    summary: String,
    icon: ImageVector,
) {
    var checked by remember { mutableStateOf(WePrefs.getBoolOrFalse(key)) }
    SwitchPreference(
        title = title,
        summary = summary,
        startAction = { PrefIcon(icon) },
        checked = checked,
        onCheckedChange = {
            checked = it
            WePrefs.putBool(key, it)
        },
    )
}

@Composable
private fun PrefArrow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    if (onClick == null) {
        // Informational row: no trailing arrow, no ripple.
        BasicComponent(
            title = title,
            summary = summary,
            startAction = icon?.let { { PrefIcon(it) } },
        )
    } else {
        ArrowPreference(
            title = title,
            summary = summary,
            startAction = icon?.let { { PrefIcon(it) } },
            onClick = onClick,
        )
    }
}

@Composable
private fun PrefIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.padding(end = 6.dp),
        tint = MiuixTheme.colorScheme.onBackground,
    )
}
// ---------------------------------------------------------------------------
//  Config import / export / clear / update / search (migrated verbatim)
// ---------------------------------------------------------------------------

private fun exportConfig(context: Context) {
    TransparentActivity.launch(context) {
        val exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri == null) {
                finish()
                return@registerForActivityResult
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val exportJson = run {
                    val map = WePrefs.default.getAll()
                    val jsonObject = buildJsonObject {
                        for ((key, value) in map) {
                            when (value) {
                                is Boolean -> put(key, value)
                                is Int -> put(key, value)
                                is Long -> put(key, value)
                                is Float -> put(key, value)
                                is Double -> put(key, value)
                                is String -> put(key, value)
                                is Set<*> -> put(key, buildJsonArray {
                                    @Suppress("UNCHECKED_CAST")
                                    (value as Set<String>).forEach { add(it) }
                                })

                                null -> put(key, JsonNull)
                            }
                        }
                    }
                    DefaultJson.encodeToString(jsonObject)
                }
                runCatching {
                    HostInfo.application.contentResolver.openOutputStream(uri, "w")!!.use { fos ->
                        fos.writer().use { it.write(exportJson) }
                    }
                }.onFailure {
                    showToastSuspend("导出失败!")
                    WeLogger.e("WePrefs", "failed to export", it)
                }.onSuccess { showToastSuspend("导出成功") }
                withContext(Dispatchers.Main) { finish() }
            }
        }
        exportLauncher.launch("wcx_prefs_backup.json")
    }
}

private fun importConfig(context: Context) {
    TransparentActivity.launch(context) {
        val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) {
                finish()
                return@registerForActivityResult
            }
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    val jsonString = LauncherUI.getInstance()!!.contentResolver.openInputStream(uri)?.use { fis ->
                        fis.reader().readText()
                    } ?: return@launch
                    val jsonObject = DefaultJson.parseToJsonElement(jsonString).jsonObject
                    for ((key, element) in jsonObject) {
                        when (element) {
                            is JsonNull -> WePrefs.default.remove(key)
                            is JsonPrimitive -> when {
                                element.isString -> WePrefs.default.putString(key, element.content)
                                element.booleanOrNull != null && (element.content == "true" || element.content == "false") ->
                                    WePrefs.putBool(key, element.boolean)

                                element.longOrNull != null && element.intOrNull == null ->
                                    WePrefs.putLong(key, element.long)

                                element.intOrNull != null -> WePrefs.putInt(key, element.int)
                                element.floatOrNull != null -> WePrefs.putFloat(key, element.float)
                            }

                            is JsonArray -> WePrefs.default.putStringSet(
                                key,
                                element.mapTo(HashSet()) { it.jsonPrimitive.content }
                            )

                            else -> Unit
                        }
                    }
                }.onFailure {
                    showToastSuspend("导入失败!")
                    WeLogger.e("WePrefs", "failed to import", it)
                }.onSuccess { showToastSuspend("导入成功") }
                withContext(Dispatchers.Main) { finish() }
            }
        }
        importLauncher.launch(arrayOf("application/json"))
    }
}

private fun checkForUpdate(
    onAvailable: (UpdateResult.UpdateAvailable) -> Unit,
    onError: (String) -> Unit,
) {
    CoroutineScope(Dispatchers.Main).launch {
        showToastSuspend("正在检查更新...")
        when (val result = AppUpdater.checkForUpdate()) {
            UpdateResult.UpToDate -> showToastSuspend("已是最新版本")
            is UpdateResult.UpdateAvailable -> onAvailable(result)
            is UpdateResult.Error -> {
                WeLogger.e("AppUpdater", "failed to check for updates", result.cause)
                onError(result.cause.message ?: "未知错误")
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Dialogs (miuix WindowDialog)
// ---------------------------------------------------------------------------

@Composable
private fun ClearConfigDialog(show: Boolean, onDismiss: () -> Unit) {
    MiuixConfirmDialog(
        show = show,
        title = "清除模块配置",
        message = "确定清除配置? (警告: 此操作不可逆!)",
        confirmText = "清除",
        onDismiss = onDismiss,
        onConfirm = {
            onDismiss()
            CoroutineScope(Dispatchers.IO).launch {
                showToastSuspend("正在清除...")
                WePrefs.default.clear()
                showToastSuspend("清除成功!")
            }
        },
    )
}

@Composable
private fun UpdateAvailableDialog(
    info: UpdateResult.UpdateAvailable?,
    onDismiss: () -> Unit,
    context: Context,
) {
    MiuixConfirmDialog(
        show = info != null,
        title = "检测到新版本",
        message = if (info != null) {
            "当前版本: ${BuildConfig.VERSION_NAME}\n新版本: ${info.info.versionName}\n是否下载并安装?"
        } else "",
        confirmText = "确定",
        onDismiss = onDismiss,
        onConfirm = {
            val target = info ?: return@MiuixConfirmDialog
            onDismiss()
            CoroutineScope(Dispatchers.Default).launch {
                AppUpdater.downloadAndInstall(context, target.info)
            }
        },
    )
}

@Composable
private fun UpdateErrorDialog(message: String?, onDismiss: () -> Unit) {
    MiuixMessageDialog(
        show = message != null,
        title = "检查更新失败",
        message = "错误信息: ${message.orEmpty()}",
        dismissText = "关闭",
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChangelogDialog(show: Boolean, onDismiss: () -> Unit, context: Context) {
    var releases by remember { mutableStateOf<List<ReleaseItem>?>(null) }
    var loadingError by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableStateOf(0) }

    LaunchedEffect(show, retryTick) {
        if (!show) return@LaunchedEffect
        releases = null
        loadingError = null
        AppUpdater.getReleaseHistory()
            .onSuccess { releases = it }
            .onFailure { loadingError = it.message ?: "未知错误" }
    }

    WindowDialog(show = show, title = "更新记录", onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            when {
                releases == null && loadingError == null -> {
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                loadingError != null -> {
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "加载失败", color = MiuixTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            Text(text = loadingError!!, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Spacer(Modifier.height(12.dp))
                            TextButton(text = "重试", onClick = { retryTick++ })
                        }
                    }
                }
                releases.isNullOrEmpty() -> {
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp), contentAlignment = Alignment.Center) {
                        Text(text = "暂无更新记录", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                else -> {
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(releases!!, key = { it.tag }) { release ->
                                ChangelogItem(release, onClick = {
                                    release.url.toUri().openInSystem(context, true)
                                })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(
                text = "关闭",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun ChangelogItem(release: ReleaseItem, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = release.name,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (release.isPrerelease) {
                    Text(
                        text = "预发布",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MiuixTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = formatReleaseDate(release.publishedAt),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (release.body.isNotBlank()) {
                Text(
                    text = release.body.take(200).let { if (release.body.length > 200) "$it..." else it },
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private fun formatReleaseDate(isoDate: String): String {
    return runCatching {
        val input = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault())
        input.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val date = input.parse(isoDate) ?: return@runCatching isoDate
        val output = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        output.format(date)
    }.getOrDefault(isoDate)
}

/** Two-button (cancel / confirm) miuix dialog. */
@Composable
private fun MiuixConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    dismissText: String = "取消",
) {
    WindowDialog(show = show, title = title, onDismissRequest = onDismiss) {
        Column {
            Text(text = message)
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(text = dismissText, onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = confirmText,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** Single-button (dismiss only) miuix dialog. */
@Composable
private fun MiuixMessageDialog(
    show: Boolean,
    title: String,
    message: String,
    dismissText: String,
    onDismiss: () -> Unit,
) {
    WindowDialog(show = show, title = title, onDismissRequest = onDismiss) {
        Column {
            Text(text = message)
            Spacer(Modifier.height(20.dp))
            TextButton(
                text = dismissText,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}


@Composable
private fun DonateDialog(show: Boolean, onDismiss: () -> Unit, context: Context) {
    WindowDialog(show = show, title = "捐赠支持", onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
        ) {

            Card(modifier = Modifier.fillMaxWidth(), onClick = {
                "https://ifdian.net/a/Johnny520".toUri().openInSystem(context, true)
            }) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFE85D04)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "爱", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(text = "爱发电", fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface)
                            Text(text = "点击跳转至爱发电页面", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            TextButton(
                text = "关闭",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
//  Open-source license screen
// ---------------------------------------------------------------------------

@Composable
fun LicenseScreen(onBack: () -> Unit) {
    val libraries = remember {
        Libs.Builder().withJson(AboutLibrariesProvider.ABOUT_LIBRARIES_JSON).build().libraries
    }

    val queryState = rememberTextFieldState()
    val query = queryState.text.toString()
    val filtered = remember(query, libraries) {
        if (query.isBlank()) libraries
        else libraries.filter { lib ->
            lib.name.contains(query, ignoreCase = true) ||
                    lib.developers.any { it.name?.contains(query, ignoreCase = true) == true } ||
                    lib.description?.contains(query, ignoreCase = true) == true
        }
    }

    MiuixListScaffold(
        title = "开放源代码库",
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Arrow_back,
                    contentDescription = "返回",
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
    ) {
        item {
            TextField(
                state = queryState,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth(),
                label = "搜索库",
                leadingIcon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { queryState.clearText() }) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Close,
                                contentDescription = "清除",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                },
            )
        }

        item {
            MiuixSmallTitle(
                text = if (query.isBlank()) "${libraries.size} 个库" else "${filtered.size}/${libraries.size} 个库",
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (filtered.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "找不到「$query」的结果",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            items(filtered, key = { it.uniqueId }) { library ->
                LibraryRow(library, modifier = Modifier.padding(top = 12.dp))
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryRow(library: Library, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = library.name,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                library.artifactVersion?.let { version ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = version,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            val author = library.developers.firstOrNull()?.name ?: library.organization?.name
            if (!author.isNullOrBlank()) {
                Text(
                    text = author,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            library.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(
                    text = desc,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (library.licenses.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    library.licenses.forEach { license ->
                        Text(
                            text = license.name,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Acknowledgements screen
// ---------------------------------------------------------------------------

@Composable
fun AcknowledgementsScreen(onBack: () -> Unit) {
    MiuixListScaffold(
        title = "鸣谢",
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Arrow_back,
                    contentDescription = "返回",
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
    ) {
        item {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "鸣谢名单",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(20.dp))
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "本模块大量功能实现源于 wekit 模块作者，已获作者授权。",
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        lineHeight = 22.sp,
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "wekit",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, "https://github.com/Ujhhgtg/WeKit".toUri())
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                LocalContext.current.startActivity(intent)
                            }
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "https://github.com/Ujhhgtg/WeKit",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "首先，我想明确一点：二次创作本身是一件非常好的事情。无论是新增实用功能，还是修复原有缺陷、优化使用体验，这些贡献都能切实提升项目的实用价值，让工具变得更好用、更完善。\n\n在遵守开源精神的前提下，发挥各自的创意与技术，打磨出更优秀、更强大的作品。如果这些改进能够继续以免费的形式开放分享给社区，让更多人受益，甚至可以说，技术进步的最终意义，本就是服务更多人（请允许我在此稍作夸大其词）。\n\n在二次发布或衍生项目中，保留原项目的开源信息、作者署名及相关声明。这既是对开源社区基本规则的尊重，也是对原作者的劳动成果的一种认可。\n当然，是否保留这些信息，最终取决于每位开发者的个人判断与选择。",
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        lineHeight = 22.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "· 开源的核心在于共享与进步，愿我们共同珍惜并维护这份来之不易的开放与信任。",
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 22.sp,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}
