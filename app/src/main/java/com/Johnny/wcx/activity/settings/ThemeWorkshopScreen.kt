package com.Johnny.wcx.activity.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
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
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.beautify.ThemeExtras
import com.Johnny.wcx.features.items.beautify.ThemePack
import com.Johnny.wcx.features.items.beautify.ThemeStore
import com.Johnny.wcx.preferences.WePrefs
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
 * 布局参照 MDWechat 的分组逻辑：每组一个独立圆角卡片 + 卡片外左上角小标题，
 * 圆角 22dp、柔和阴影、行间 0.6dp 细分割线，整体与 WCX 现有页面同一套配色。
 *
 * 交互硬约束：
 * 1. 改素材 / 改名称 / 改描述只在「保存主题」时落盘，切换主题会丢弃未保存改动
 * 2. 只有「应用主题」才重启微信
 */
@Composable
fun ThemeWorkshopScreen(onBack: () -> Unit) {
    val context = LocalContext.current

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
    var longPressTarget by remember { mutableStateOf<ThemePack.Pack?>(null) }
    var colorTarget by remember { mutableStateOf(false) }

    // 编辑区草稿：未点保存前只存在内存里
    var draftName by remember { mutableStateOf("") }
    var draftDesc by remember { mutableStateOf("") }
    var descVisible by remember { mutableStateOf(false) }

    // 开关刷新令牌：切换主题 / 应用快照后自增，驱动所有行重新读值
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
            showToast(context, "已选择：${roleLabel(pendingRole)}，记得点「保存主题」")
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
                            importLauncher.launch("application/zip")
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
                        text = { Text("分享主题") },
                        onClick = {
                            menuOpen = false
                            val file = ThemeStore.exportActiveTheme()
                            showToast(
                                context,
                                if (file != null) "已导出到 ${file.parent}，把这个 zip 发给别人即可"
                                else "先选一个主题再分享",
                            )
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
                        summary = "每次进入主页从主题的多张背景中随机选一张",
                        tick = tick,
                    )
                    RowDivider()
                    FeatureSwitchRow(
                        featureName = "主题商城",
                        title = "主题",
                        summary = "总开关：关闭后立即还原壁纸与气泡",
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
                }
            }

            // ── 卡片2 快捷功能 ───────────────────────────────
            item {
                GroupCard(title = "快捷功能") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PillButton("悬浮按钮", Modifier.weight(1f)) {
                            toggleFeature("主屏幕添加 FAB", "悬浮按钮", context) { tick++ }
                        }
                        PillButton("导航抽屉", Modifier.weight(1f)) {
                            toggleFeature("微信主页侧边栏", "导航抽屉", context) { tick++ }
                        }
                    }
                }
            }

            // ── 卡片3 聊天美化 ───────────────────────────────
            item {
                GroupCard(title = "聊天美化") {
                    PrefSwitchRow(
                        key = ThemeExtras.KEY_ROUND_AVATAR,
                        title = "圆形头像",
                        summary = "聊天界面头像裁成圆形（重新进入聊天生效）",
                        tick = tick,
                    )
                    RowDivider()
                    FeatureSwitchRow(
                        featureName = "自定义消息气泡",
                        title = "修改聊天气泡",
                        summary = "替换气泡图片与聊天文字颜色",
                        tick = tick,
                        onChanged = { refreshAll() },
                    )
                    RowDivider()
                    FeatureSwitchRow(
                        featureName = "隐藏主页下滑「最近」页",
                        title = "隐藏下拉小程序",
                        summary = "禁止主页下滑进入「最近」页",
                        tick = tick,
                        onChanged = { refreshAll() },
                    )
                }
            }

            // ── 卡片4 Tab 栏美化 ─────────────────────────────
            item {
                GroupCard(title = "Tab 栏美化") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PillButton("替换底栏 Tab", Modifier.weight(1f)) {
                            toggleFeature("美化首页底部导航栏", "替换底栏 Tab", context) { tick++ }
                        }
                    }
                }
            }

            // ── 卡片5 标题栏美化 ─────────────────────────────
            item {
                GroupCard(title = "标题栏 ActionBar 美化") {
                    PrefSwitchRow(
                        key = ThemeExtras.KEY_HIDE_ACTIONBAR,
                        title = "隐藏标题栏",
                        summary = "隐藏微信主界面顶部标题栏",
                        tick = tick,
                    )
                    RowDivider()
                    PrefSwitchRow(
                        key = ThemeExtras.KEY_HIDE_ACTIONBAR_SEARCH,
                        title = "隐藏搜索",
                        summary = "隐藏标题栏右侧搜索按钮",
                        tick = tick,
                    )
                    RowDivider()
                    PrefSwitchRow(
                        key = ThemeExtras.KEY_HIDE_ACTIONBAR_MENU,
                        title = "隐藏菜单",
                        summary = "隐藏标题栏右侧「+」按钮",
                        tick = tick,
                    )
                    RowDivider()
                    PrefSwitchRow(
                        key = ThemeExtras.KEY_ACTIONBAR_COLOR_ENABLED,
                        title = "标题栏改色",
                        summary = "使用自定义颜色作为标题栏背景",
                        tick = tick,
                    )
                    RowDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PillButton("设置标题栏颜色", Modifier.weight(1f)) { colorTarget = true }
                    }
                }
            }

            // ── 卡片6 主题编辑区 ─────────────────────────────
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
                    if (editing == null) {
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
                        ThemePreview(editing)
                        RowDivider()
                        MaterialRow("消息气泡素材", "bubble_left", editing, pendingRole,
                            { role -> pendingRole = role; pickImageLauncher.launch("image/*") },
                            { updated -> loadDraft(updated) },
                        )
                        MaterialRow("聊天背景素材", "wallpaper_chats", editing, pendingRole,
                            { role -> pendingRole = role; pickImageLauncher.launch("image/*") },
                            { updated -> loadDraft(updated) },
                        )
                        MaterialRow("顶部标题栏素材", "wallpaper_panel", editing, pendingRole,
                            { role -> pendingRole = role; pickImageLauncher.launch("image/*") },
                            { updated -> loadDraft(updated) },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PillButton("保存主题", Modifier.weight(1f)) {
                                val pack = editing
                                if (pack == null) {
                                    showToast(context, "没有正在编辑的主题")
                                } else {
                                    ThemePack.updateMeta(pack, draftName, draftDesc.trim())
                                    ThemePack.saveSwitches(pack, ThemeExtras.currentSwitches())
                                    refreshAll()
                                    ThemePack.find(pack.id)?.let { loadDraft(it) }
                                    showToast(context, "已保存：$draftName")
                                }
                            }
                            PillButton("应用主题", Modifier.weight(1f), primary = true) {
                                if (editing == null) showToast(context, "没有正在编辑的主题")
                                else confirmApply = true
                            }
                        }
                    }
                }
            }

            // ── 卡片7 主题预览网格 ───────────────────────────
            item {
                GroupTitleRow("已保存主题", if (packs.isEmpty()) "" else "${packs.size} 个")
            }
            if (packs.isEmpty()) {
                item {
                    GroupCard(title = null) {
                        HintRow("还没有主题包。用右上角「⋮ → 导入主题」导入 zip，或直接新建。")
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
                    GuideLine("1", "选主题", "「主题基础 → 点主题那行」从已保存主题里挑一个，立即生效。")
                    GuideLine("2", "导入", "右上角 ⋮ → 导入主题，选一个 zip 包。")
                    GuideLine("3", "自己做", "新建主题 → 选素材 → 保存主题 → 应用主题。")
                    GuideLine("4", "分享", "⋮ → 导出主题 / 分享主题，把 zip 发给别人导入。")
                    GuideLine("5", "重要", "改素材、改名字不会生效，必须「保存主题」；只有「应用主题」会重启微信。")
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
                    ThemeStore.builtinThemeItems().forEach { (id, name) ->
                        PickerRow(name, active == id) {
                            showPicker = false
                            ThemeStore.applyThemeById(id)
                            ThemePack.find(id)?.let { loadDraft(it) }
                            refreshAll()
                            showToast(context, "已应用：$name")
                        }
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

    if (colorTarget) {
        AlertDialog(
            onDismissRequest = { colorTarget = false },
            title = { Text("标题栏颜色") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val presets = listOf(
                        "深灰" to 0xFF1E1E1E.toInt(),
                        "纯黑" to 0xFF000000.toInt(),
                        "微信绿" to 0xFF07C160.toInt(),
                        "品牌蓝" to 0xFF2E7DFF.toInt(),
                        "米白" to 0xFFF7F7F7.toInt(),
                        "淡紫" to 0xFF6F5BD6.toInt(),
                    )
                    presets.chunked(3).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowItems.forEach { (name, value) ->
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(value))
                                        .clickable {
                                            WePrefs.putInt(ThemeExtras.KEY_ACTIONBAR_COLOR, value)
                                            WePrefs.putBool(ThemeExtras.KEY_ACTIONBAR_COLOR_ENABLED, true)
                                            colorTarget = false
                                            tick++
                                            showToast(context, "已设为$name，重进主页生效")
                                        }
                                        .padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        text = name,
                                        fontSize = 12.sp,
                                        color = if (value == 0xFFF7F7F7.toInt()) Color.Black else Color.White,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { colorTarget = false }) { Text("关闭") } },
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

    if (confirmApply) {
        AlertDialog(
            onDismissRequest = { confirmApply = false },
            title = { Text("应用主题") },
            text = { Text("应用主题后需要重启微信才能完全生效，是否继续？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmApply = false
                    editing?.let { pack ->
                        ThemePack.saveSwitches(pack, ThemeExtras.currentSwitches())
                        ThemeStore.applyThemeById(pack.id)
                    }
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

private fun summaryColor(): Color = BasicComponentDefaults.summaryColor().color
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
            val day = ThemePrefsConfig.dayTheme
            val night = ThemePrefsConfig.nightTheme
            if (day.isBlank() || night.isBlank()) {
                // 未配置就默认用当前主题，避免开关开了却什么都不发生
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
    SwitchRow(
        title = title,
        summary = reason ?: summary,
        checked = checked,
    ) { on ->
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
        Spacer(Modifier.size(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
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

/** 主题预览缩略图（用主题自己的聊天背景图，没有就显示占位）。 */
@Composable
private fun ThemePreview(pack: ThemePack.Pack) {
    val file = pack.wallpaperFor("wallpaper_chats") ?: pack.wallpaper()
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
            Text("预览图（选一张聊天背景素材后显示）", fontSize = 12.sp, color = summaryColor())
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
                    val updated = ThemePack.resetRole(pack, role)
                    if (updated != null) onReset(updated)
                    showToast(context, "已恢复默认：$title")
                }
            }
        }
    }
    RowDivider()
}

@Composable
private fun ThemeGridCard(
    pack: ThemePack.Pack,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val thumb = pack.wallpaperFor("wallpaper_chats") ?: pack.wallpaper()
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
    "wallpaper" -> "全局壁纸"
    "wallpaper_chats" -> "聊天背景"
    "wallpaper_panel" -> "顶部标题栏"
    "bubble_left" -> "消息气泡"
    "bubble_right" -> "自己的气泡"
    else -> role
}

private fun findFeature(name: String): SwitchFeature? =
    FeaturesProvider.ALL_HOOK_ITEMS.filterIsInstance<SwitchFeature>().firstOrNull { it.name == name }

private fun toggleFeature(
    featureName: String,
    label: String,
    context: android.content.Context,
    onChanged: () -> Unit,
) {
    val feature = findFeature(featureName)
    if (feature == null) {
        showToast(context, "当前版本没有「$label」功能")
        return
    }
    val reason = feature.unavailableReason
    if (reason != null && !feature.isEnabled) {
        showToast(context, reason)
        return
    }
    val target = !feature.isEnabled
    runCatching {
        WePrefs.putBool(feature.name, target)
        feature.isEnabled = target
    }.onFailure {
        WeLogger.w("ThemeWorkshop", "切换 $label 失败：${it.message}")
        showToast(context, "切换失败：${it.message}")
        return
    }
    onChanged()
    showToast(context, "$label：${if (target) "已开启" else "已关闭"}")
}

/** 按时间切换主题用的读写（默认取当前主题，避免空配置）。 */
private object ThemePrefsConfig {
    var dayTheme: String
        get() = WePrefs.getStringOrDef(ThemeExtras.KEY_AUTO_DAY_THEME, "")
        set(value) = WePrefs.putString(ThemeExtras.KEY_AUTO_DAY_THEME, value)

    var nightTheme: String
        get() = WePrefs.getStringOrDef(ThemeExtras.KEY_AUTO_NIGHT_THEME, "")
        set(value) = WePrefs.putString(ThemeExtras.KEY_AUTO_NIGHT_THEME, value)
}
