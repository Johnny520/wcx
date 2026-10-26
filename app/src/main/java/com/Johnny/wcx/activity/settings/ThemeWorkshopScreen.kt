package com.Johnny.wcx.activity.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.features.items.beautify.ThemePack
import com.Johnny.wcx.features.items.beautify.ThemeStore
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import h.Hchat.ui.miuix.ActionRow
import h.Hchat.ui.miuix.InsetDivider
import h.Hchat.ui.miuix.PageScaffold
import h.Hchat.ui.miuix.SettingsCard
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import java.io.File

/**
 * 主题工坊（独立子页面）。
 *
 * 交互按用户要求：右上角「⋮」收起全部入口（切换 / 导入 / 新建 / 打开目录），
 * 内置主题与已装主题包统一走一个选择框，不再在页面上一条条铺开。
 */
@Composable
fun ThemeWorkshopScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()

    var packs by remember { mutableStateOf(ThemePack.list()) }
    var active by remember { mutableStateOf(ThemeStore.activeThemeIdOrEmpty()) }
    var menuOpen by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ThemePack.Pack?>(null) }
    var pendingRole by remember { mutableStateOf("") }

    var opacity by remember { mutableStateOf(ThemeStore.overlayOpacity()) }
    var panelAlpha by remember { mutableStateOf(WePrefs.getIntOrDef("theme_panel_alpha", 100)) }

    fun refresh() {
        packs = ThemePack.list()
        active = ThemeStore.activeThemeIdOrEmpty()
    }

    fun applyTheme(id: String, name: String) {
        ThemeStore.applyThemeById(id)
        refresh()
        showToast(context, "已应用：$name")
    }

    // 导入：真实的文件选择器，用户不用自己去文件管理器
    val importZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                val tmp = File(context.cacheDir, "theme_import.zip")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取该文件")
                ThemePack.importZip(tmp).getOrThrow()
            }.onSuccess { pack ->
                refresh()
                editing = pack
                showToast(context, "已导入：${pack.name}")
            }.onFailure {
                WeLogger.w("ThemeWorkshop", "导入失败：${it.message}")
                showToast(context, "导入失败：${it.message}")
            }
            File(context.cacheDir, "theme_import.zip").delete()
        }
    }

    // 选图（分页面背景 / 气泡 / 侧滑栏背景共用）
    val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val pack = editing
        if (uri != null && pack != null && pendingRole.isNotEmpty()) {
            // 气泡沿用原来的「点九」方案：文件名保持 .9.png，由微信按九宫格拉伸
            val ninePatch = pendingRole.startsWith("bubble_")
            ThemePack.writeImage(pack, context, uri, pendingRole, ninePatch)?.let { editing = it }
            showToast(context, "已写入：${pageRoleLabel(pendingRole)}")
        }
    }

    PageScaffold(
        title = "主题工坊",
        largeTitle = "主题工坊",
        scrollBehavior = scrollBehavior,
        onBack = onBack,
        topBarActions = {
            androidx.compose.foundation.layout.Box {
                Text(
                    text = "⋮",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { menuOpen = true }
                        .padding(horizontal = 14.dp, vertical = 2.dp),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("切换主题") },
                        onClick = {
                            menuOpen = false
                            showPicker = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导入主题") },
                        onClick = {
                            menuOpen = false
                            importZipLauncher.launch("application/zip")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("新建主题") },
                        onClick = {
                            menuOpen = false
                            ThemePack.createEmptyPack("我的主题").onSuccess {
                                editing = it
                                refresh()
                                showToast(context, "已新建：${it.name}，往下选图即可")
                            }.onFailure {
                                showToast(context, "新建失败：${it.message}")
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("打开主题目录") },
                        onClick = {
                            menuOpen = false
                            showToast(context, "目录：${ThemePack.themesDir().absolutePath}")
                        },
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp,
            ),
        ) {
            // ── 当前主题 ────────────────────────────────────────────
            item { SmallTitle(modifier = Modifier.padding(top = 6.dp), text = "当前主题") }
            item {
                SettingsCard {
                    val currentName = ThemePack.find(active)?.name
                        ?: ThemeStore.builtinThemeItems().firstOrNull { it.first == active }?.second
                        ?: "未应用"
                    ActionRow(
                        title = currentName,
                        summary = when {
                            active.isBlank() -> "还没应用任何主题，点右上角「⋮ → 切换主题」"
                            else -> "正在生效 · 点这里切换"
                        },
                        onClick = { showPicker = true },
                    )
                }
            }

            // ── 上手说明 ────────────────────────────────────────────
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "怎么用") }
            item {
                SettingsCard {
                    GuideStep("1", "切换主题", "右上角「⋮ → 切换主题」，在弹出的列表里点一款")
                    InsetDivider()
                    GuideStep("2", "用别人给的主题", "右上角「⋮ → 导入主题」，直接选 zip 文件即可")
                    InsetDivider()
                    GuideStep("3", "做自己的主题", "右上角「⋮ → 新建主题」，再往下逐项选图，最后点「应用」")
                    InsetDivider()
                    GuideStep("4", "分享给别人", "最下面「导出当前主题 zip」，把生成的文件发给对方")
                }
            }

            // ── 编辑中的主题 ────────────────────────────────────────
            if (editing != null) {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "正在编辑：${editing?.name ?: ""}") }
                item {
                    SettingsCard {
                        ActionRow(
                            title = "① 全局壁纸",
                            summary = editing?.wallpaper()?.name ?: "未设置",
                            onClick = { pendingRole = "wallpaper"; pickImageLauncher.launch("image/*") },
                        )
                        InsetDivider()
                        PageWallpaperRow("消息页背景", "wallpaper_chats", editing, pendingRole) { role ->
                            pendingRole = role
                            pickImageLauncher.launch("image/*")
                        }
                        PageWallpaperRow("通讯录背景", "wallpaper_contacts", editing, pendingRole) { role ->
                            pendingRole = role
                            pickImageLauncher.launch("image/*")
                        }
                        PageWallpaperRow("发现页背景", "wallpaper_discover", editing, pendingRole) { role ->
                            pendingRole = role
                            pickImageLauncher.launch("image/*")
                        }
                        PageWallpaperRow("「我」页背景", "wallpaper_me", editing, pendingRole) { role ->
                            pendingRole = role
                            pickImageLauncher.launch("image/*")
                        }
                        PageWallpaperRow("侧滑栏背景", "wallpaper_panel", editing, pendingRole) { role ->
                            pendingRole = role
                            pickImageLauncher.launch("image/*")
                        }
                        ActionRow(
                            title = "② 对方气泡（点九）",
                            summary = editing?.bubbleOther()?.name ?: "未设置 · 建议用 .9.png，普通 png 会被拉伸",
                            onClick = { pendingRole = "bubble_left"; pickImageLauncher.launch("image/*") },
                        )
                        InsetDivider()
                        ActionRow(
                            title = "③ 自己气泡（点九）",
                            summary = editing?.bubbleSelf()?.name ?: "未设置 · 建议用 .9.png，普通 png 会被拉伸",
                            onClick = { pendingRole = "bubble_right"; pickImageLauncher.launch("image/*") },
                        )
                        InsetDivider()
                        ActionRow(
                            title = "保存（只保存，不应用）",
                            summary = "随时可以回来继续改",
                            onClick = {
                                refresh()
                                showToast(context, "已保存：${editing?.name ?: ""}")
                            },
                        )
                        InsetDivider()
                        ActionRow(
                            title = "应用这个主题",
                            summary = "立即生效；气泡类改动可能要重启微信",
                            onClick = {
                                val pack = editing
                                if (pack == null) showToast(context, "还没有正在编辑的主题")
                                else applyTheme(pack.id, pack.name)
                            },
                        )
                        InsetDivider()
                        ActionRow(
                            title = "完成编辑",
                            summary = "收起编辑区，回到主题列表",
                            onClick = {
                                editing = null
                                refresh()
                            },
                        )
                    }
                }
            }

            // ── 透明度 ──────────────────────────────────────────────
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "透明度") }
            item {
                SettingsCard {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(
                            text = "壁纸透明度 $opacity%",
                            fontSize = 13.sp,
                            color = BasicComponentDefaults.titleColor().color,
                        )
                        androidx.compose.material3.Slider(
                            value = opacity.toFloat(),
                            onValueChange = {
                                val value = it.toInt().coerceIn(5, 100)
                                opacity = value
                                ThemeStore.setOverlayOpacity(value)
                            },
                            valueRange = 5f..100f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "侧滑栏背景透明度 $panelAlpha%（0 = 不显示）",
                            fontSize = 13.sp,
                            color = BasicComponentDefaults.titleColor().color,
                        )
                        androidx.compose.material3.Slider(
                            value = panelAlpha.toFloat(),
                            onValueChange = {
                                val value = it.toInt().coerceIn(0, 100)
                                panelAlpha = value
                                WePrefs.putInt("theme_panel_alpha", value)
                            },
                            valueRange = 0f..100f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // ── 其它美化 ────────────────────────────────────────────
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "其它美化") }
            item {
                SettingsCard {
                    val names = remember {
                        listOf(
                            "主屏美化",
                            "美化首页底部导航栏",
                            "美化对话列表",
                            "自定义消息气泡",
                            "美化活动过渡动画",
                            "美化组件按下效果",
                            "对话框窗口级背景模糊",
                        )
                    }
                    val features = remember(names) {
                        FeaturesProvider.ALL_HOOK_ITEMS.filter { it.name in names }
                    }
                    if (features.isEmpty()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(text = "当前版本没有可用的美化开关", fontSize = 12.sp)
                        }
                    } else {
                        features.forEachIndexed { index, feature ->
                            if (index > 0) InsetDivider()
                            var checked by remember(feature) { mutableStateOf(feature.isEnabled) }
                            SwitchPreference(
                                title = feature.name,
                                summary = feature.description,
                                checked = checked,
                                onCheckedChange = { on ->
                                    runCatching {
                                        WePrefs.putBool(feature.name, on)
                                        feature.isEnabled = on
                                    }.onFailure { showToast(context, "切换失败：${it.message}") }
                                    checked = on
                                },
                            )
                        }
                    }
                }
            }

            // ── 分享与维护 ──────────────────────────────────────────
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "分享与维护") }
            item {
                SettingsCard {
                    ActionRow(
                        title = "导出当前主题 zip",
                        summary = "发给别人，对方「导入主题」即可使用",
                        onClick = {
                            val file = ThemeStore.exportActiveTheme()
                            showToast(
                                context,
                                if (file != null) "已导出：${file.absolutePath}" else "导出失败，详见日志",
                            )
                        },
                    )
                    InsetDivider()
                    ActionRow(
                        title = "主题文件目录",
                        summary = ThemePack.themesDir().absolutePath,
                        onClick = { showToast(context, "用文件管理器打开该目录可手动管理") },
                    )
                    InsetDivider()
                    ActionRow(
                        title = "恢复默认主题",
                        summary = "移除壁纸与气泡，回到微信原样",
                        onClick = {
                            ThemeStore.restoreDefaultTheme()
                            refresh()
                            showToast(context, "已恢复默认主题")
                        },
                    )
                }
            }

            item {
                Text(
                    text = "主题就是一组图片：全局/分页面壁纸、左右气泡、侧滑栏背景。只放想换的图即可，没放的保持微信原样。",
                    fontSize = 12.sp,
                    color = BasicComponentDefaults.summaryColor().color,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }

    if (showPicker) {
        ThemePickerDialog(
            builtins = ThemeStore.builtinThemeItems(),
            packs = packs,
            active = active,
            onPick = { id, name ->
                showPicker = false
                applyTheme(id, name)
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun ThemePickerDialog(
    builtins: List<Pair<String, String>>,
    packs: List<ThemePack.Pack>,
    active: String,
    onPick: (id: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换主题") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (builtins.isNotEmpty()) {
                    Text(text = "内置主题", fontSize = 12.sp, color = BasicComponentDefaults.summaryColor().color)
                    builtins.forEach { (id, name) ->
                        PickerRow(name, active == id) { onPick(id, name) }
                    }
                }
                if (packs.isNotEmpty()) {
                    Text(
                        text = "我的主题（${packs.size}）",
                        fontSize = 12.sp,
                        color = BasicComponentDefaults.summaryColor().color,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    packs.forEach { pack ->
                        PickerRow(pack.name, active == pack.id) { onPick(pack.id, pack.name) }
                    }
                }
                if (builtins.isEmpty() && packs.isEmpty()) {
                    Text(text = "还没有任何主题", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun PickerRow(name: String, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name + if (active) "（使用中）" else "",
            fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun pageRoleLabel(role: String): String = when (role) {
    "wallpaper" -> "全局壁纸"
    "wallpaper_chats" -> "消息页背景"
    "wallpaper_contacts" -> "通讯录背景"
    "wallpaper_discover" -> "发现页背景"
    "wallpaper_me" -> "「我」页背景"
    "wallpaper_chatting" -> "聊天页背景"
    "wallpaper_panel" -> "侧滑栏背景"
    "bubble_left" -> "对方气泡"
    "bubble_right" -> "自己气泡"
    else -> role
}

/** 分页面壁纸一行：已设置显示文件名，未设置提示「跟随全局壁纸」。 */
@Composable
private fun PageWallpaperRow(
    title: String,
    role: String,
    editing: ThemePack.Pack?,
    pendingRole: String,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    val set = editing?.manifest?.optString(role)?.takeIf { it.isNotBlank() }
    ActionRow(
        title = title,
        summary = when {
            set != null -> "已设置：$set"
            pendingRole == role -> "正在选择…"
            else -> "未设置（跟随全局壁纸）"
        },
        onClick = {
            if (editing == null) {
                showToast(context, "先在右上角「⋮ → 新建主题」")
            } else {
                onPick(role)
            }
        },
    )
    InsetDivider()
}

@Composable
private fun GuideStep(index: String, title: String, detail: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = "$index. $title",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = BasicComponentDefaults.titleColor().color,
        )
        Text(
            text = detail,
            fontSize = 12.sp,
            color = BasicComponentDefaults.summaryColor().color,
        )
    }
}
