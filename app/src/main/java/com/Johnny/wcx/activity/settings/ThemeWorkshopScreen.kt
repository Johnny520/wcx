package com.Johnny.wcx.activity.settings

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
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
 * 交互约定（用户明确要求）：
 * - 点卡片/下拉只是「选中」，不生效；必须点右上角「应用」才算应用，且要划掉微信重开
 * - 内置主题只能调透明度，不能改名称/素材；长按可重命名/改描述，但不能分享
 * - 壁纸永远在最底层，文字永远在上层，透明度拉满也不会挡字
 */
@Composable
fun ThemeWorkshopScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val builtinThemes = remember { ThemeStore.builtinThemeItems() }
    var packs by remember { mutableStateOf(ThemePack.list()) }
    var active by remember { mutableStateOf(ThemeStore.activeThemeIdOrEmpty()) }
    // 待应用的主题（内置 id 或主题包 id）；点「应用」才写进 ThemeStore
    var selectedId by remember { mutableStateOf(ThemeStore.activeThemeIdOrEmpty()) }
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
    var builtinRenameId by remember { mutableStateOf("") }
    var builtinRenameText by remember { mutableStateOf("") }
    var builtinDescId by remember { mutableStateOf("") }
    var builtinDescText by remember { mutableStateOf("") }
    var builtinLongPress by remember { mutableStateOf("") }
    var longPressTarget by remember { mutableStateOf<ThemePack.Pack?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var appliedDialog by remember { mutableStateOf(false) }
    var showRandomPool by remember { mutableStateOf(false) }
    var poolFiles by remember { mutableStateOf(ThemePack.randomPoolFiles()) }

    var draftName by remember { mutableStateOf("") }
    var draftDesc by remember { mutableStateOf("") }
    var descVisible by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }

    fun refreshAll() {
        packs = ThemePack.list()
        active = ThemeStore.activeThemeIdOrEmpty()
        poolFiles = ThemePack.randomPoolFiles()
        tick++
    }

    fun loadDraft(pack: ThemePack.Pack?) {
        editing = pack
        draftName = pack?.name.orEmpty()
        draftDesc = pack?.manifest?.optString("description").orEmpty()
        descVisible = draftDesc.isNotBlank()
    }

    /** 选中主题（不应用）。 */
    fun select(id: String) {
        selectedId = id
        val pack = ThemePack.find(id)
        loadDraft(pack)
        if (pack == null) {
            val name = builtinThemes.firstOrNull { it.first == id }?.second
            showToast(context, "已选中：${name?.let { ThemeStore.builtinDisplayName(id, it) } ?: id}，点右上角「应用」生效")
        } else {
            showToast(context, "已选中：${pack.name}，点右上角「应用」生效")
        }
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

    /** 右上角「应用」：写配置 → 提示划掉微信重开。 */
    fun applySelected() {
        val id = selectedId
        if (id.isBlank()) {
            showToast(context, "先选一个主题")
            return
        }
        val pack = ThemePack.find(id)
        if (pack != null) {
            if (!saveCurrent()) {
                showToast(context, "保存失败")
                return
            }
            // 主题包里存的开关快照，应用时一起同步
            runCatching { ThemeExtras.applySwitches(ThemePack.switches(pack)) }
                .onFailure { WeLogger.w("ThemeWorkshop", "同步开关失败：${it.message}") }
        }
        runCatching { ThemeStore.applyThemeById(id) }
            .onFailure {
                WeLogger.w("ThemeWorkshop", "应用主题失败：${it.message}")
                showToast(context, "应用失败：${it.message}")
                return
            }
        refreshAll()
        appliedDialog = true
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
            select(pack.id)
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

    val pickPoolImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ThemePack.addRandomPoolImage(context, uri)
            .onSuccess {
                refreshAll()
                showToast(context, "已添加（共 ${ThemePack.randomPoolFiles().size} 张）")
            }
            .onFailure { showToast(context, "添加失败：${it.message}") }
    }

    PageScaffold(
        title = "主题设置",
        largeTitle = "主题设置",
        onBack = onBack,
        topBarActions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 应用：右上角、⋮ 左边。只有点它才真正生效（并提示划掉微信重开）
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .clickable { applySelected() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "应用",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box {
                    Text(
                        text = "⋮",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = BasicComponentDefaults.titleColor().color,
                        modifier = Modifier
                            .clickable { menuOpen = true }
                            .padding(horizontal = 10.dp, vertical = 2.dp),
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
                                val pack = ThemePack.find(selectedId)
                                if (pack == null) {
                                    showToast(context, "内置主题不能导出/分享，请先选一个自己的主题")
                                } else {
                                    val file = ThemePack.exportZip(pack).getOrNull()
                                    showToast(
                                        context,
                                        if (file != null) "已导出：${file.absolutePath}" else "导出失败",
                                    )
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("备份主题") },
                            onClick = {
                                menuOpen = false
                                val pack = ThemePack.find(selectedId)
                                if (pack == null) {
                                    showToast(context, "内置主题不需要备份，请选自己的主题")
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
                    // 随机聊天背景：开关 + 齿轮（管理图池，最多 5 张）
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("随机聊天背景", fontSize = 15.sp, color = titleColor())
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "每次进聊天界面从图池随机换一张，只作用于聊天页" +
                                    "（已选 ${poolFiles.size}/${ThemePack.RANDOM_POOL_MAX} 张）",
                                fontSize = 12.sp,
                                color = summaryColor(),
                            )
                        }
                        var randomOn by remember(tick) {
                            mutableStateOf(WePrefs.getBoolOrFalse(ThemeExtras.KEY_RANDOM_WALLPAPER))
                        }
                        Switch(
                            checked = randomOn,
                            onCheckedChange = { on ->
                                WePrefs.putBool(ThemeExtras.KEY_RANDOM_WALLPAPER, on)
                                randomOn = on
                                ThemeExtras.onPrefChanged(ThemeExtras.KEY_RANDOM_WALLPAPER, on)
                            },
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = "⚙",
                            fontSize = 18.sp,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable {
                                    poolFiles = ThemePack.randomPoolFiles()
                                    showRandomPool = true
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
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
                        title = if (selectedId.isBlank()) "未选择" else themeName(selectedId),
                        summary = "只列你自己新建/导入的主题，点这里选择",
                        onClick = { showPicker = true },
                    )
                    RowDivider()
                    // 当前真正生效的主题（点「应用」并划掉微信重开后才会变）
                    InfoRow(
                        label = "当前主题",
                        value = if (active.isBlank()) "未应用（微信原生）" else themeName(active),
                    )
                    RowDivider()
                    OpacityRow(tick)
                }
            }

            // ── 卡片2 快捷功能 ───────────────────────────────
            item {
                GroupCard(title = "快捷功能") {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton("保存", Modifier.weight(1f)) {
                                if (editing == null) showToast(context, "内置主题不可编辑，只能调透明度")
                                else if (saveCurrent()) showToast(context, "已保存：$draftName")
                            }
                            PillButton("分享", Modifier.weight(1f)) {
                                shareSelectedTheme(context, selectedId)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        PillButton("恢复默认", Modifier.fillMaxWidth()) { confirmReset = true }
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
                    when {
                        selectedId.isBlank() -> HintRow("先在下面「默认主题」或「我的主题」里点一张卡片选中它。")
                        // 内置主题：只能调透明度，其余只读
                        pack == null -> {
                            HintRow(
                                "这是内置主题（全局）。内置主题只能调「壁纸透明度」，" +
                                    "不能改名称/素材；长按卡片可重命名或改描述。"
                            )
                            BuiltinPreviewStrip(selectedId)
                        }
                        else -> {
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
            }

            // ── 卡片4 默认主题（内置 6 款，全局）───────────────
            item { GroupTitleRow("默认主题", "全局 · 不可分享") }
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
                                name = ThemeStore.builtinDisplayName(id, name),
                                active = active == id,
                                selected = selectedId == id,
                                modifier = Modifier.weight(1f),
                                onClick = { select(id) },
                                onLongClick = { builtinLongPress = id },
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // ── 卡片5 我的主题（自己新建/导入）──────────────────
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
                                    selected = selectedId == pack.id,
                                    modifier = Modifier.weight(1f),
                                    onClick = { select(pack.id) },
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

    if (appliedDialog) {
        AlertDialog(
            onDismissRequest = { appliedDialog = false },
            title = { Text("已应用") },
            text = {
                Text(
                    "主题已写入。请从最近任务（多任务界面）把微信**划掉**，再重新打开微信，" +
                        "背景与气泡才会完全生效。"
                )
            },
            confirmButton = { TextButton(onClick = { appliedDialog = false }) { Text("知道了") } },
        )
    }

    if (showRandomPool) {
        AlertDialog(
            onDismissRequest = { showRandomPool = false },
            title = { Text("随机背景图（${poolFiles.size}/${ThemePack.RANDOM_POOL_MAX}）") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (poolFiles.isEmpty()) {
                        Text(
                            text = "还没有图片。点下面「添加图片」选一张（最多 5 张，每次进聊天页随机取一张）。",
                            fontSize = 12.sp,
                            color = summaryColor(),
                        )
                    } else {
                        poolFiles.chunked(3).forEach { rowFiles ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                rowFiles.forEach { file ->
                                    PoolThumb(file) {
                                        ThemePack.removeRandomPoolImage(file)
                                        refreshAll()
                                    }
                                }
                                repeat(3 - rowFiles.size) { Spacer(Modifier.size(72.dp)) }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    PillButton(
                        text = if (poolFiles.size >= ThemePack.RANDOM_POOL_MAX) "已满 5 张" else "＋ 添加图片",
                        modifier = Modifier.fillMaxWidth(),
                        primary = true,
                    ) {
                        if (poolFiles.size >= ThemePack.RANDOM_POOL_MAX) {
                            showToast(context, "最多 ${ThemePack.RANDOM_POOL_MAX} 张，先删一张")
                        } else {
                            pickPoolImageLauncher.launch("image/*")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRandomPool = false }) { Text("关闭") } },
        )
    }

    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text("使用说明") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    GuideLine("1", "一套主题", "4 张界面背景（主页/通讯录/发现/我）+ 左右两个点九气泡。")
                    GuideLine("2", "必须点「应用」", "无论是内置主题还是自己新建的主题，选中后都要点右上角「应用」才会生效。")
                    GuideLine("3", "应用后要划掉微信", "应用后请从最近任务里把微信划掉，再重新打开微信，才会完全生效。")
                    GuideLine("4", "内置主题", "内置主题只能调透明度，不能改素材；长按卡片可以重命名、改描述；内置主题不能分享。")
                    GuideLine("5", "自己做/分享", "新建主题 → 选图片 → 保存 → 右上角应用；分享按钮只分享自己的主题。")
                    GuideLine("6", "聊天页规则", "主题只作用于主页/通讯录/发现/我 4 个界面，永远不影响聊天页；聊天页背景由微信自带，或用「随机聊天背景」图池。")
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
                            refreshAll()
                            select(pack.id)
                            showToast(context, "已创建：${pack.name}，选好素材后点右上角「应用」")
                        }
                        .onFailure { showToast(context, "创建失败：${it.message}") }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } },
        )
    }

    // 主题下拉：只列自己新建/导入的主题（内置主题在下面网格里选）
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("选择我的主题") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (packs.isEmpty()) {
                        Text(
                            text = "还没有自己新建的主题。点「新建主题」或从右上角导入。",
                            fontSize = 12.sp,
                            color = summaryColor(),
                        )
                    }
                    packs.forEach { pack ->
                        PickerRow(pack.name, selectedId == pack.id, active == pack.id) {
                            showPicker = false
                            select(pack.id)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("关闭") } },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复默认") },
            text = { Text("移除所有自定义背景，气泡恢复微信原生样式。背景会立即恢复，气泡要重进聊天界面才生效。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    runCatching { ThemeStore.restoreDefaultTheme() }
                        .onSuccess {
                            selectedId = ""
                            loadDraft(null)
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

    // 内置主题长按：只能重命名 / 改描述，没有分享
    if (builtinLongPress.isNotEmpty()) {
        val originalName = builtinThemes.firstOrNull { it.first == builtinLongPress }?.second ?: builtinLongPress
        AlertDialog(
            onDismissRequest = { builtinLongPress = "" },
            title = { Text(ThemeStore.builtinDisplayName(builtinLongPress, originalName)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    PickerRow("重命名", false, false) {
                        builtinRenameText = ThemeStore.builtinDisplayName(builtinLongPress, originalName)
                        builtinRenameId = builtinLongPress
                        builtinLongPress = ""
                    }
                    PickerRow("修改描述", false, false) {
                        builtinDescText = ThemeStore.builtinDesc(builtinLongPress)
                        builtinDescId = builtinLongPress
                        builtinLongPress = ""
                    }
                    Text(
                        text = "内置主题不能分享、不能改素材，只能调壁纸透明度。",
                        fontSize = 12.sp,
                        color = summaryColor(),
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { builtinLongPress = "" }) { Text("关闭") } },
        )
    }

    if (builtinRenameId.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { builtinRenameId = "" },
            title = { Text("重命名内置主题") },
            text = {
                OutlinedTextField(
                    value = builtinRenameText,
                    onValueChange = { builtinRenameText = it },
                    label = { Text("名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ThemeStore.setBuiltinAlias(builtinRenameId, builtinRenameText)
                    builtinRenameId = ""
                    tick++
                    showToast(context, "已重命名")
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { builtinRenameId = "" }) { Text("取消") } },
        )
    }

    if (builtinDescId.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { builtinDescId = "" },
            title = { Text("修改描述") },
            text = {
                OutlinedTextField(
                    value = builtinDescText,
                    onValueChange = { builtinDescText = it },
                    label = { Text("描述（可留空）") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ThemeStore.setBuiltinDesc(builtinDescId, builtinDescText)
                    builtinDescId = ""
                    tick++
                    showToast(context, "已修改描述")
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { builtinDescId = "" }) { Text("取消") } },
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
                    PickerRow("重命名", false, selectedId == target.id) {
                        renameText = target.name
                        renameTarget = target
                        longPressTarget = null
                    }
                    PickerRow("修改描述", false, selectedId == target.id) {
                        descText = target.manifest.optString("description").orEmpty()
                        descTarget = target
                        longPressTarget = null
                    }
                    PickerRow("分享主题", false, selectedId == target.id) {
                        longPressTarget = null
                        shareSelectedTheme(context, target.id)
                    }
                    PickerRow("删除主题", false, selectedId == target.id) {
                        ThemePack.delete(target.id)
                        if (editing?.id == target.id) loadDraft(null)
                        if (selectedId == target.id) selectedId = ""
                        longPressTarget = null
                        refreshAll()
                        showToast(context, "已删除：${target.name}")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { longPressTarget = null }) { Text("关闭") } },
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

@Composable
private fun PrefSwitchRow(key: String, title: String, summary: String, tick: Int) {
    var checked by remember(key, tick) { mutableStateOf(WePrefs.getBoolOrFalse(key)) }
    SwitchRow(title = title, summary = summary, checked = checked) { on ->
        WePrefs.putBool(key, on)
        checked = on
        ThemeExtras.onPrefChanged(key, on)
    }
}

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
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun OpacityRow(tick: Int) {
    var pct by remember(tick) { mutableStateOf(ThemeStore.overlayOpacity()) }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("壁纸透明度", fontSize = 15.sp, color = titleColor(), modifier = Modifier.weight(1f))
            Text("$pct%", fontSize = 13.sp, color = summaryColor())
        }
        Text(
            text = "拉满也只是壁纸本身不透明，文字永远在最上层，不会被挡住",
            fontSize = 11.sp,
            color = summaryColor(),
        )
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

/** 只读信息行（右侧不显示箭头，表示不可点）。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = titleColor(), modifier = Modifier.weight(1f))
        Text(
            text = value,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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

/** 主题包预览（用主页背景）。 */
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

/** 内置主题的只读预览条。 */
@Composable
private fun BuiltinPreviewStrip(id: String) {
    val thumb = remember(id) { ThemeStore.builtinThumbnail(id) }
    val bitmap = remember(id, thumb?.absolutePath) {
        thumb?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
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
            Text("内置主题预览", fontSize = 12.sp, color = summaryColor())
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

@Composable
private fun PoolThumb(file: File, onRemove: () -> Unit) {
    Box(modifier = Modifier.size(72.dp)) {
        val bitmap = remember(file.absolutePath, file.lastModified()) {
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
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
            }
        }
        Text(
            text = "✕",
            fontSize = 12.sp,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onRemove)
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun BuiltinGridCard(
    id: String,
    name: String,
    active: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
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
            .then(
                if (selected) Modifier.border(2.dp, MiuixTheme.colorScheme.primary, shape)
                else Modifier
            )
            .background(MiuixTheme.colorScheme.surface)
            .pointerInput(id) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() },
                )
            },
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
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    fontSize = 13.sp,
                    color = titleColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(" 全局", fontSize = 10.sp, color = summaryColor())
            }
            val badge = when {
                selected && active -> "使用中"
                selected -> "待应用"
                active -> "使用中"
                else -> ""
            }
            if (badge.isNotEmpty()) {
                Text(badge, fontSize = 10.sp, color = MiuixTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ThemeGridCard(
    pack: ThemePack.Pack,
    active: Boolean,
    selected: Boolean,
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
            .then(
                if (selected) Modifier.border(2.dp, MiuixTheme.colorScheme.primary, shape)
                else Modifier
            )
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
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = pack.name,
                fontSize = 13.sp,
                color = titleColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val badge = when {
                selected && active -> "使用中"
                selected -> "待应用"
                active -> "使用中"
                else -> ""
            }
            if (badge.isNotEmpty()) {
                Text(badge, fontSize = 10.sp, color = MiuixTheme.colorScheme.primary)
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
private fun PickerRow(name: String, selected: Boolean, active: Boolean, onClick: () -> Unit) {
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
        val badge = when {
            selected && active -> "使用中"
            selected -> "已选"
            active -> "使用中"
            else -> ""
        }
        if (badge.isNotEmpty()) {
            Text(badge, fontSize = 11.sp, color = MiuixTheme.colorScheme.primary)
        }
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

private fun themeName(id: String): String {
    if (id.isBlank()) return "未设置"
    ThemeStore.builtinThemeItems().firstOrNull { it.first == id }?.let {
        return ThemeStore.builtinDisplayName(id, it.second)
    }
    ThemePack.find(id)?.let { return it.name }
    return id
}

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
 * 分享指定主题（只有自己的主题包能分享；内置主题明确拒绝）。
 */
private fun shareSelectedTheme(context: Context, id: String) {
    val pack = ThemePack.find(id)
    if (pack == null) {
        showToast(context, "内置主题不能分享")
        return
    }
    val file = ThemePack.exportZip(pack).getOrNull()
    if (file == null) {
        showToast(context, "导出失败，无法分享")
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
