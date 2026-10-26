package com.Johnny.wcx.activity.settings

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.beautify.ThemeExtras
import com.Johnny.wcx.features.items.beautify.ThemePack
import com.Johnny.wcx.features.items.beautify.ThemeStore
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.restartHost
import h.Hchat.ui.miuix.PageScaffold
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 主题设置（独立子页面）。
 *
 * 一套主题 = 4 张界面背景（主页 / 通讯录 / 发现 / 我）+ 2 个点九气泡（左·对方 / 右·自己）。
 *
 * 布局参照 MDWechat 的分组逻辑：每组一个独立圆角卡片 + 卡片外左上角小标题，
 * 圆角 22dp、柔和阴影、行间细分割线，与 WCX 现有页面同一套配色。
 *
 * 交互硬约束：
 * 1. 改素材 / 改名称 / 改描述只在「保存主题」时落盘，切换主题会丢弃未保存改动
 * 2. 只有「应用主题」才重启微信
 */
@Composable
fun ThemeWorkshopScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // 默认主题（内置 6 款）与「我的主题」严格分开，不混在一个网格里
    val builtinThemes = remember { ThemeStore.builtinThemeItems() }
    var packs by remember { mutableStateOf(ThemePack.list()) }
    var active by remember { mutableStateOf(ThemeStore.activeThemeIdOrEmpty()) }
    var editing by remember { mutableStateOf<ThemePack.Pack?>(null) }
    var pendingRole by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ThemePack.Pack?>(null) }
    var renameText by remember { mutableStateOf("") }
    var descTarget by remember { mutableStateOf<ThemePack.Pack?>(null) }
    var descText by remember { mutableStateOf("") }
    var confirmApply by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var longPressTarget by remember { mutableStateOf<ThemePack.Pack?>(null) }

    // 编辑区草稿：未点保存前只存在内存里
    var draftName by remember { mutableStateOf("") }
    var draftDesc by remember { mutableStateOf("") }
    var descVisible by remember { mutableStateOf(false) }

    // 开关刷新令牌：切换主题 / 应用快照后自增，驱动开关行重新读值
    var tick by remember { mutableStateOf(0) }

    fun refreshAll() {
        packs = ThemePack.list()
        active = ThemeStore.activeThemeIdOrEmpty()
        tick++
    }

    fun loadDraft(pack: ThemePack.Pack?) {
        editing = pack
        draftName = pack?.name.orEmpty()
        draftDesc = pack?.manifest?.optString("description").orEmpty()
        descVisible = draftDesc.isNotBlank()
    }

    fun applySwitchesOf(pack: ThemePack.Pack?) {
        if (pack == null) return
        val switches = ThemePack.switches(pack)
        if (switches.isEmpty()) return
        runCatching { ThemeExtras.applySwitches(switches) }
            .onFailure { WeLogger.w("ThemeWorkshop", "同步开关失败：${it.message}") }
        tick++
    }

    fun saveCurrent(): Boolean {
        val pack = editing ?: return false
        ThemePack.updateMeta(pack, draftName, draftDesc.trim())
        ThemePack.saveSwitches(pack, ThemeExtras.currentSwitches())
        refreshAll()
        ThemePack.find(pack.id)?.let { loadDraft(it) }
        return true
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val tmp = File(context.cacheDir, "theme_import.zip")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取该文件")
            ThemePack.importZip(tmp).getOrThrow()
        }.onSuccess { pack ->
            File(context.cacheDir, "theme_import.zip").delete()
            refreshAll()
            loadDraft(pack)
            applySwitchesOf(pack)
            showToast(context, "已导入：${pack.name}")
        }.onFailure {
            WeLogger.w("ThemeWorkshop", "导入失败：${it.message}")
            showToast(context, "导入失败：${it.message}")
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val pack = editing
        if (pack == null || pendingRole.isEmpty()) return@rememberLauncherForActivityResult
        val ninePatch = pendingRole.startsWith("bubble_")
        ThemePack.writeImage(pack, context, uri, pendingRole, ninePatch)?.let { updated ->
            loadDraft(updated)
            showToast(context, "已选择：${roleLabel(pendingRole)}，记得点「保存」")
        }
    }

    PageScaffold(
        title = "主题设置",
        largeTitle = "主题设置",
        onBack = onBack,
        topBarActions = {
            Box {
                Text(
                    text = "⋮",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = BasicComponentDefaults.titleColor().color,
                    modifier = Modifier
                        .clickable { menuOpen = true }
                        .padding(horizontal = 14.dp, vertical = 2.dp),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("导入主题") },
                        onClick = {
                            menuOpen = false
                            importLauncher.launch("*/*")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导出主题") },
                        onClick = {
                            menuOpen = false
                            val file = ThemeStore.exportActiveTheme()
                            showToast(
                                context,
                                if (file != null) "已导出：${file.absolutePath}" else "先选一个主题再导出",
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("备份主题") },
                        onClick = {
                            menuOpen = false
                            val pack = ThemePack.find(ThemeStore.activeThemeIdOrEmpty())
                            if (pack == null) {
                                showToast(context, "当前主题不是可备份的主题包")
                            } else {
                                ThemePack.backup(pack)
                                    .onSuccess { showToast(context, "已备份：${it.absolutePath}") }
                                    .onFailure { showToast(context, "备份失败：${it.message}") }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("恢复默认") },
                        onClick = {
                            menuOpen = false
                            confirmReset = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("使用说明") },
                        onClick = {
                            menuOpen = false
                            showGuide = true
                        },
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 90.dp,
            ),
        ) {
            // ── 卡片1 主题基础 ───────────────────────────────
            item {
                GroupCard(title = "主题基础") {
                    PrefSwitchRow(
                        key = ThemeExtras.KEY_RANDOM_WALLPAPER,
                        title = "随机聊天背景",
                        summary = "每次进入主页从已有的几张背景里随机挑一张",
                        tick = tick,
                    )
                    RowDivider()
                    FeatureSwitchRow(
                        featureName = "主题工坊",
                        title = "主题",
                        summary = "总开关：内置 6 款全局主题，关闭后立即还原背景与气泡",
                        tick = tick,
                        onChanged = { refreshAll() },
                    )
                    RowDivider()
                    NavRow(
                        title = ThemePack.find(active)?.name
                            ?: ThemeStore.builtinThemeItems().firstOrNull { it.first == active }?.second
                            ?: "未选择",
                        summary = "点这里从已保存的主题里挑一个",
                        onClick = { showPicker = true },
                    )
                    RowDivider()
                    PrefSwitchRow(
                        key = ThemeExtras.KEY_AUTO_BY_TIME,
                        title = "按时间切换主题",
                        summary = "进入主页时白天/夜间自动切到对应主题",
                        tick = tick,
                    )
                    RowDivider()
                    OpacityRow(tick)
                }
            }

            // ── 卡片2 快捷功能：保存 / 应用 ──────────────────
            item {
                GroupCard(title = "快捷功能") {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton("保存", Modifier.weight(1f)) {
                                if (saveCurrent()) showToast(context, "已保存：$draftName")
                                else showToast(context, "先新建或选择一个主题")
                            }
                            PillButton("应用", Modifier.weight(1f), primary = true) {
                                if (editing == null) showToast(context, "先新建或选择一个主题")
                                else confirmApply = true
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        // 分享=调系统分享面板，把「当前主题」导出的 zip 直接发出去
                        PillButton("分享", Modifier.fillMaxWidth()) {
                            shareActiveTheme(context)
                        }
                    }
                }
            }

            // ── 卡片3 主题编辑 ───────────────────────────────
            item {
                GroupCard(title = "主题编辑") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PillButton("新建主题", Modifier.weight(1f)) {
                            createName = "我的主题"
                            showCreate = true
                        }
                    }
                    RowDivider()
                    val pack = editing
                    if (pack == null) {
                        HintRow("还没有正在编辑的主题：点「新建主题」，或在下方网格点一张卡片加载。")
                    } else {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                            OutlinedTextField(
                                value = draftName,
                                onValueChange = { draftName = it },
                                label = { Text("主题名称") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (descVisible) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = draftDesc,
                                    onValueChange = { draftDesc = it },
                                    label = { Text("主题描述") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "＋ 添加描述（选填）",
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { descVisible = true },
                                )
                            }
                        }
                        RowDivider()
                        ThemePreview(pack)
                        RowDivider()
                        MaterialRow("主页背景", "wallpaper_chats", pack, pendingRole,
                            { role -> pendingRole = role; pickImageLauncher.launch("image/*") },
                            { updated -> loadDraft(updated) },
                        )
                        MaterialRow("通讯录背景", "wallpaper_contacts", pack, pendingRole,
                            { role -> pendingRole = role; pickImageLauncher.launch("image/*") },
                            { updated -> loadDraft(updated) },
                        )
                        MaterialRow("发现页背景", "wallpaper_discover", pack, pendingRole,
                            { role -> pendingRole = role; pickImageLauncher.launch("image/*") },
                            { updated -> loadDraft(updated) },
                        )
                        MaterialRow("我的背景", "wallpaper_me", pack, pendingRole,
                            { role -> pendingRole = role; pickImageLauncher.launch("image/*") },
                            { updated -> loadDraft(updated) },
                        )
                        MaterialRow("消息气泡 · 左（对方）", "bubble_left", pack, pendingRole,
                            { role -> pendingRole = role; pickImageLauncher.launch("image/*") },
                            { updated -> loadDraft(updated) },
                        )
                        MaterialRow("消息气泡 · 右（自己）", "bubble_right", pack, pendingRole,
                            { role -> pendingRole = role; pickImageLauncher.launch("image/*") },
                            { updated -> loadDraft(updated) },
                        )
                    }
                }
            }

            // ── 卡片4 默认主题（内置 6 款，全局壁纸）───────────
            item {
                GroupTitleRow("默认主题", "全局")
            }
            items((builtinThemes.size + 1) / 2) { rowIndex ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (col in 0..1) {
                        val index = rowIndex * 2 + col
                        if (index < builtinThemes.size) {
                            val (id, name) = builtinThemes[index]
                            BuiltinGridCard(
                                id = id,
                                name = name,
                                active = active == id,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    ThemeStore.applyThemeById(id)
                                    refreshAll()
                                    showToast(context, "已应用：$name（进主页生效）")
                                },
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // ── 卡片5 我的主题（自己新建/导入的主题包）──────────
            item {
                GroupTitleRow("我的主题", if (packs.isEmpty()) "" else "${packs.size} 个")
            }
            if (packs.isEmpty()) {
                item {
                    GroupCard(title = null) {
                        HintRow("还没有自己的主题。点上面「新建主题」，或右上角「⋮ → 导入主题」。")
                    }
                }
            } else {
                items((packs.size + 1) / 2) { rowIndex ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        for (col in 0..1) {
                            val index = rowIndex * 2 + col
                            if (index < packs.size) {
                                val pack = packs[index]
                                ThemeGridCard(
                                    pack = pack,
                                    active = active == pack.id,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        loadDraft(pack)
                                        applySwitchesOf(pack)
                                        showToast(context, "已加载：${pack.name}（未保存的改动已丢弃）")
                                    },
                                    onLongClick = { longPressTarget = pack },
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 弹窗区 ──────────────────────────────────────────────
    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text("使用说明") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    GuideLine("1", "一套主题", "4 张界面背景（主页/通讯录/发现/我）+ 左右两个点九气泡。")
                    GuideLine("2", "自己做", "新建主题 → 逐项选图片 → 点「保存」→ 点「应用」。")
                    GuideLine("3", "导入", "右上角 ⋮ → 导入主题，选一个 zip 包。")
                    GuideLine("4", "分享", "⋮ → 导出主题 / 分享主题，把 zip 发给别人导入。")
                    GuideLine("5", "重要", "只选图不保存不生效；只有「应用」会重启微信。")
                }
            },
            confirmButton = { TextButton(onClick = { showGuide = false }) { Text("知道了") } },
        )
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建主题") },
            text = {
                OutlinedTextField(
                    value = createName,
                    onValueChange = { createName = it },
                    label = { Text("主题名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCreate = false
                    ThemePack.createEmptyPack(createName.ifBlank { "我的主题" })
                        .onSuccess { pack ->
                            ThemeStore.applyThemeById(pack.id)
                            loadDraft(pack)
                            refreshAll()
                            showToast(context, "已创建并切换为当前主题：${pack.name}")
                        }
                        .onFailure { showToast(context, "创建失败：${it.message}") }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } },
        )
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("选择主题") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    PickerSectionTitle("默认主题（全局）")
                    builtinThemes.forEach { (id, name) ->
                        PickerRow("$name · 全局", active == id) {
                            showPicker = false
                            ThemeStore.applyThemeById(id)
                            refreshAll()
                            showToast(context, "已应用：$name")
                        }
                    }
                    PickerSectionTitle("我的主题")
                    if (packs.isEmpty()) {
                        Text(
                            text = "还没有自己的主题",
                            fontSize = 12.sp,
                            color = summaryColor(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                    packs.forEach { pack ->
                        PickerRow(pack.name, active == pack.id) {
                            showPicker = false
                            ThemeStore.applyThemeById(pack.id)
                            loadDraft(pack)
                            applySwitchesOf(pack)
                            showToast(context, "已应用：${pack.name}")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("关闭") } },
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("主题名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ThemePack.updateMeta(target, renameText, null)
                    renameTarget = null
                    refreshAll()
                    if (editing?.id == target.id) ThemePack.find(target.id)?.let { loadDraft(it) }
                    showToast(context, "已重命名")
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    descTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { descTarget = null },
            title = { Text("修改描述") },
            text = {
                OutlinedTextField(
                    value = descText,
                    onValueChange = { descText = it },
                    label = { Text("描述（可留空）") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ThemePack.updateMeta(target, null, descText)
                    descTarget = null
                    refreshAll()
                    if (editing?.id == target.id) ThemePack.find(target.id)?.let { loadDraft(it) }
                    showToast(context, "已修改描述")
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { descTarget = null }) { Text("取消") } },
        )
    }

    longPressTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { longPressTarget = null },
            title = { Text(target.name) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    PickerRow("重命名", false) {
                        renameText = target.name
                        renameTarget = target
                        longPressTarget = null
                    }
                    PickerRow("修改描述", false) {
                        descText = target.manifest.optString("description").orEmpty()
                        descTarget = target
                        longPressTarget = null
                    }
                    PickerRow("删除主题", false) {
                        ThemePack.delete(target.id)
                        if (editing?.id == target.id) loadDraft(null)
                        longPressTarget = null
                        refreshAll()
                        showToast(context, "已删除：${target.name}")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { longPressTarget = null }) { Text("关闭") } },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复默认") },
            text = {
                Text(
                    "将移除所有自定义背景，气泡也恢复成微信原生样式。" +
                        "背景会立即恢复；气泡要重新进入聊天界面才生效。是否继续？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    runCatching { ThemeStore.restoreDefaultTheme() }
                        .onSuccess {
                            refreshAll()
                            showToast(context, "已恢复微信原生背景与气泡")
                        }
                        .onFailure {
                            WeLogger.w("ThemeWorkshop", "恢复默认失败：${it.message}")
                            showToast(context, "恢复失败：${it.message}")
                        }
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }

    if (confirmApply) {
        AlertDialog(
            onDismissRequest = { confirmApply = false },
            title = { Text("应用主题") },
            text = {
                Text(
                    "会先保存当前主题，再重启微信。\n" +
                        "重启后自动加载这套主题的背景与气泡，确认重启吗？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmApply = false
                    saveCurrent()
                    editing?.let { ThemeStore.applyThemeById(it.id) }
                    showToast(context, "正在重启微信…")
                    runCatching { restartHost() }
                        .onFailure { showToast(context, "重启失败：${it.message}") }
                }) { Text("重启微信") }
            },
            dismissButton = { TextButton(onClick = { confirmApply = false }) { Text("取消") } },
        )
    }
}

// ══════════════════════════════════════════════════════════
// 通用组件
// ══════════════════════════════════════════════════════════

@Composable
private fun summaryColor(): Color = BasicComponentDefaults.summaryColor().color

@Composable
private fun titleColor(): Color = BasicComponentDefaults.titleColor().color

/** 分组卡片：外层左上角小标题 + 圆角 22dp 柔和阴影卡片。 */
@Composable
private fun GroupCard(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = summaryColor(),
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        val shape = RoundedCornerShape(22.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 2.dp, shape = shape, ambientColor = Color.Black.copy(alpha = 0.06f))
                .clip(shape)
                .background(MiuixTheme.colorScheme.surface),
            content = content,
        )
    }
}

@Composable
private fun GroupTitleRow(title: String, trailing: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = summaryColor())
        Spacer(Modifier.weight(1f))
        if (trailing.isNotBlank()) {
            Text(trailing, fontSize = 12.sp, color = summaryColor())
        }
    }
}

@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(0.6.dp)
            .background(titleColor().copy(alpha = 0.08f)),
    )
}

/** 走 WePrefs 的开关行（非 Feature 的美化项，由 [ThemeExtras] 消费）。 */
@Composable
private fun PrefSwitchRow(key: String, title: String, summary: String, tick: Int) {
    var checked by remember(key, tick) { mutableStateOf(WePrefs.getBoolOrFalse(key)) }
    SwitchRow(title = title, summary = summary, checked = checked) { on ->
        WePrefs.putBool(key, on)
        checked = on
        ThemeExtras.onPrefChanged(key, on)
        if (key == ThemeExtras.KEY_AUTO_BY_TIME && on) {
            if (ThemeExtras.autoThemeForNow() == null) {
                val cur = ThemeStore.activeThemeIdOrEmpty()
                if (cur.isNotBlank()) ThemeExtras.setAutoThemes(cur, cur)
            }
        }
    }
}

/** 映射到真实 Feature 的开关行；Feature 不存在时明确说「当前版本未提供」。 */
@Composable
private fun FeatureSwitchRow(
    featureName: String,
    title: String,
    summary: String,
    tick: Int,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val feature = remember(featureName) { findFeature(featureName) }
    if (feature == null) {
        HintRow("$title：当前版本未提供该功能")
        return
    }
    var checked by remember(feature, tick) { mutableStateOf(feature.isEnabled) }
    val reason = feature.unavailableReason
    SwitchRow(title = title, summary = reason ?: summary, checked = checked) { on ->
        if (reason != null) {
            showToast(context, reason)
            return@SwitchRow
        }
        runCatching {
            WePrefs.putBool(feature.name, on)
            feature.isEnabled = on
        }.onFailure { WeLogger.w("ThemeWorkshop", "切换 $title 失败：${it.message}") }
        checked = on
        onChanged()
    }
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = titleColor())
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(summary, fontSize = 12.sp, color = summaryColor())
            }
        }
        Spacer(Modifier.padding(start = 10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 壁纸透明度：拖动结束才落盘，避免拖的过程反复重绘所有页面。 */
@Composable
private fun OpacityRow(tick: Int) {
    var pct by remember(tick) { mutableStateOf(ThemeStore.overlayOpacity()) }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("壁纸透明度", fontSize = 15.sp, color = titleColor(), modifier = Modifier.weight(1f))
            Text("$pct%", fontSize = 13.sp, color = summaryColor())
        }
        Slider(
            value = pct.toFloat(),
            onValueChange = { pct = it.toInt().coerceIn(5, 100) },
            onValueChangeFinished = { ThemeStore.setOverlayOpacity(pct) },
            valueRange = 5f..100f,
            colors = SliderDefaults.colors(
                thumbColor = MiuixTheme.colorScheme.primary,
                activeTrackColor = MiuixTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun NavRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = titleColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(summary, fontSize = 12.sp, color = summaryColor())
            }
        }
        Text("›", fontSize = 20.sp, color = summaryColor())
    }
}

@Composable
private fun HintRow(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = summaryColor(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

/** 横向圆角大按钮。 */
@Composable
private fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (primary) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
    else titleColor().copy(alpha = 0.07f)
    val fg = if (primary) MiuixTheme.colorScheme.primary else titleColor()
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

/** 主题预览缩略图：优先主页背景，退回全局壁纸。 */
@Composable
private fun ThemePreview(pack: ThemePack.Pack) {
    val file = pack.wallpaperFor("chats")
    val bitmap = remember(pack.id, file?.absolutePath) {
        file?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(120.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(titleColor().copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("预览图（选一张主页背景后显示）", fontSize = 12.sp, color = summaryColor())
        }
    }
}

@Composable
private fun MaterialRow(
    title: String,
    role: String,
    editing: ThemePack.Pack?,
    pendingRole: String,
    onPick: (String) -> Unit,
    onReset: (ThemePack.Pack) -> Unit,
) {
    val context = LocalContext.current
    val set = editing?.manifest?.optString(role)?.takeIf { it.isNotBlank() }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, fontSize = 15.sp, color = titleColor())
        Spacer(Modifier.height(2.dp))
        Text(
            text = when {
                set != null -> "已选择：$set"
                pendingRole == role -> "正在选择…"
                else -> "未选择（保持微信原样）"
            },
            fontSize = 12.sp,
            color = summaryColor(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("选择图片", Modifier.weight(1f)) {
                if (editing == null) showToast(context, "先新建或选择一个主题") else onPick(role)
            }
            PillButton("重置默认", Modifier.weight(1f)) {
                val pack = editing
                if (pack == null) {
                    showToast(context, "先新建或选择一个主题")
                } else {
                    ThemePack.resetRole(pack, role)?.let(onReset)
                    showToast(context, "已恢复默认：$title")
                }
            }
        }
    }
    RowDivider()
}

/** 默认（内置）主题卡片：只有预览 + 名称 + 「全局」标注，不可重命名/删除。 */
@Composable
private fun BuiltinGridCard(
    id: String,
    name: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val thumb = remember(id) { ThemeStore.builtinThumbnail(id) }
    val bitmap = remember(id, thumb?.absolutePath) {
        thumb?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
    }
    val fallback = remember(id) { Color(ThemeStore.builtinColor(id).toInt()) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .shadow(elevation = 1.dp, shape = shape, ambientColor = Color.Black.copy(alpha = 0.06f))
            .clip(shape)
            .background(MiuixTheme.colorScheme.surface)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(84.dp).background(fallback),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                fontSize = 13.sp,
                color = titleColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(" 全局", fontSize = 10.sp, color = summaryColor())
            Spacer(Modifier.weight(1f))
            if (active) {
                Text("使用中", fontSize = 10.sp, color = MiuixTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ThemeGridCard(
    pack: ThemePack.Pack,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val thumb = pack.wallpaperFor("chats")
    val bitmap = remember(pack.id, thumb?.absolutePath) {
        thumb?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
    }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .shadow(elevation = 1.dp, shape = shape, ambientColor = Color.Black.copy(alpha = 0.06f))
            .clip(shape)
            .background(MiuixTheme.colorScheme.surface)
            .pointerInput(pack.id) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .background(titleColor().copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("无预览", fontSize = 11.sp, color = summaryColor())
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pack.name,
                fontSize = 13.sp,
                color = titleColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (active) {
                Text("使用中", fontSize = 10.sp, color = MiuixTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun PickerSectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = summaryColor(),
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun PickerRow(name: String, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            fontSize = 15.sp,
            color = titleColor(),
            modifier = Modifier.weight(1f),
        )
        if (active) Text("使用中", fontSize = 11.sp, color = MiuixTheme.colorScheme.primary)
    }
}

@Composable
private fun GuideLine(index: String, title: String, detail: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("$index. $title", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = titleColor())
        Text(detail, fontSize = 12.sp, color = summaryColor())
    }
}

// ══════════════════════════════════════════════════════════
// 小工具
// ══════════════════════════════════════════════════════════

private fun roleLabel(role: String): String = when (role) {
    "wallpaper_chats" -> "主页背景"
    "wallpaper_contacts" -> "通讯录背景"
    "wallpaper_discover" -> "发现页背景"
    "wallpaper_me" -> "我的背景"
    "bubble_left" -> "消息气泡·左"
    "bubble_right" -> "消息气泡·右"
    else -> role
}

/**
 * 分享当前主题：导出 zip → FileProvider 取 uri → 系统分享面板。
 * 主题目录在模块数据目录下，与日志同树，所以能复用微信自带的 external.fileprovider。
 */
private fun shareActiveTheme(context: Context) {
    val file = ThemeStore.exportActiveTheme()
    if (file == null) {
        showToast(context, "当前没有可分享的主题，先选一个")
        return
    }
    val authority = "${HostInfo.packageName}.external.fileprovider"
    val uri = runCatching { FileProvider.getUriForFile(context, authority, file) }.getOrNull()
    if (uri == null) {
        WeLogger.w("ThemeWorkshop", "FileProvider 取不到 uri：${file.absolutePath}")
        showToast(context, "分享失败：无法读取该文件（已导出到 ${file.absolutePath}）")
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "分享主题").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
        .onFailure {
            WeLogger.w("ThemeWorkshop", "拉起分享面板失败：${it.message}")
            showToast(context, "打开分享面板失败：${it.message}")
        }
}

private fun findFeature(name: String): SwitchFeature? =
    FeaturesProvider.ALL_HOOK_ITEMS.filterIsInstance<SwitchFeature>().firstOrNull { it.name == name }
