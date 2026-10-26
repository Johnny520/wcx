package com.Johnny.wcx.activity.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.Johnny.wcx.features.items.beautify.ThemePack
import com.Johnny.wcx.features.items.beautify.ThemeStore
import com.Johnny.wcx.utils.android.showToast
import h.Hchat.ui.miuix.ActionRow
import h.Hchat.ui.miuix.InsetDivider
import h.Hchat.ui.miuix.PageScaffold
import h.Hchat.ui.miuix.SettingsCard
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * 主题工坊（独立子页面）。
 *
 * 设计原则：用户只需要做一件事——**往目录里放图片/zip**，回来点一下。
 * 所有动作都在这一页完成，不跳对话框、不需要理解 manifest。
 */
@Composable
fun ThemeWorkshopScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    var packs by remember { mutableStateOf(ThemePack.list()) }
    var active by remember { mutableStateOf(ThemeStore.activeThemeIdOrEmpty()) }
    var opacity by remember { mutableStateOf(ThemeStore.overlayOpacity()) }
    // 正在编辑的主题包（新建后逐个换图，最后再保存/应用）
    var editing by remember { mutableStateOf<ThemePack.Pack?>(null) }

    // 注意：Kotlin 局部函数必须先声明后使用，这里直接刷新状态，不调用后面的 refresh()。
    fun applyEditing() {
        val pack = editing ?: return
        ThemeStore.applyThemeById(pack.id)
        packs = ThemePack.list()
        active = ThemeStore.activeThemeIdOrEmpty()
    }

    var pendingRole by remember { mutableStateOf("") }
    val pickPageImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val pack = editing
        if (uri != null && pack != null && pendingRole.isNotEmpty()) {
            ThemePack.writeImage(pack, context, uri, pendingRole)?.let { editing = it }
            showToast(context, "已写入：${pageRoleLabel(pendingRole)}")
        }
    }
    val pickWallpaper = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val pack = editing
        if (uri != null && pack != null) {
            ThemePack.writeImage(pack, context, uri, "wallpaper")?.let { editing = it }
            showToast(context, "壁纸已写入，点「应用这个主题」生效")
        }
    }
    val pickBubbleOther = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val pack = editing
        if (uri != null && pack != null) {
            ThemePack.writeImage(pack, context, uri, "bubble_left")?.let { editing = it }
            showToast(context, "对方气泡已写入")
        }
    }
    val pickBubbleSelf = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val pack = editing
        if (uri != null && pack != null) {
            ThemePack.writeImage(pack, context, uri, "bubble_right")?.let { editing = it }
            showToast(context, "自己气泡已写入")
        }
    }

    fun refresh() {
        packs = ThemePack.list()
        active = ThemeStore.activeThemeIdOrEmpty()
    }

    PageScaffold(
        title = "主题工坊",
        largeTitle = "主题工坊",
        scrollBehavior = scrollBehavior,
        onBack = onBack,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp,
            ),
        ) {
            // ── 上手说明 ────────────────────────────────────────────
            item { SmallTitle(modifier = Modifier.padding(top = 6.dp), text = "怎么用（三步）") }
            item {
                SettingsCard {
                    GuideStep("1", "换主题", "下面「内置主题」点任意一款即可立即生效（壁纸 + 气泡 + 全局沉浸）")
                    InsetDivider()
                    GuideStep("2", "用别人的主题", "把别人给的 zip 放进 themes 目录，回来点「扫描新主题包」")
                    InsetDivider()
                    GuideStep("3", "做自己的主题", "改 themes 目录里任意主题的图片（wallpaper.png / bubble_*.9.png），回来重新应用")
                    InsetDivider()
                    ActionRow(
                        title = "打开主题目录路径",
                        summary = ThemePack.themesDir().absolutePath,
                        onClick = { showToast(context, "复制路径到文件管理器打开即可") },
                    )
                }
            }

            // ── 内置主题 ────────────────────────────────────────────
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "内置主题") }
            item {
                SettingsCard {
                    val items = ThemeStore.builtinThemeItems()
                    items.forEachIndexed { index, (id, name) ->
                        if (index > 0) InsetDivider()
                        ActionRow(
                            title = name + if (active == id) "（使用中）" else "",
                            summary = if (active == id) "已生效" else "点一下立即应用",
                            onClick = {
                                ThemeStore.applyThemeById(id)
                                refresh()
                                showToast(context, "已应用：$name")
                            },
                        )
                    }
                }
            }

            // ── 外部主题包 ──────────────────────────────────────────
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "我的主题包（${packs.size}）") }
            item {
                SettingsCard {
                    if (packs.isEmpty()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                text = "还没有主题包。把别人分享的 zip 放进下面这个目录，然后点最下面的「扫描新主题包」。",
                                fontSize = 12.sp,
                                color = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.summaryColor().color,
                            )
                            Text(
                                text = ThemePack.themesDir().absolutePath,
                                fontSize = 11.sp,
                                color = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.summaryColor().color,
                            )
                        }
                    } else {
                        packs.forEachIndexed { index, pack ->
                            if (index > 0) InsetDivider()
                            ActionRow(
                                title = pack.name + if (active == pack.id) "（使用中）" else "",
                                summary = buildString {
                                    append(if (active == pack.id) "已生效" else "点一下立即应用")
                                    if (pack.author.isNotBlank()) append(" · 作者 ${pack.author}")
                                },
                                onClick = {
                                    ThemeStore.applyThemeById(pack.id)
                                    refresh()
                                    showToast(context, "已应用：${pack.name}")
                                },
                            )
                        }
                    }
                    InsetDivider()
                    ActionRow(
                        title = "扫描新主题包",
                        summary = "把 zip 放进目录后点这里",
                        onClick = {
                            val added = ThemePack.importPendingZips()
                            refresh()
                            showToast(
                                context,
                                if (added.isEmpty()) "没发现新的 zip（现有 ${packs.size} 个）"
                                else "已导入 ${added.size} 个主题包",
                            )
                        },
                    )
                }
            }

            // ── 新建 / 编辑主题 ─────────────────────────────────────
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "新建主题（自由度最高）") }
            item {
                SettingsCard {
                    ActionRow(
                        title = if (editing == null) "新建一个空主题" else "当前编辑：${editing?.name ?: ""}",
                        summary = "只想换两个气泡也可以，其它项留空就保持微信原样",
                        onClick = {
                            ThemePack.createEmptyPack("我的主题").onSuccess {
                                editing = it
                                packs = ThemePack.list()
                                showToast(context, "已新建：${it.name}，接着选图吧")
                            }.onFailure {
                                showToast(context, "新建失败：${it.message}")
                            }
                        },
                    )
                    InsetDivider()
                    ActionRow(
                        title = "① 选壁纸",
                        summary = editing?.wallpaper()?.name ?: "未选择（点这里从相册选）",
                        onClick = {
                            if (editing == null) showToast(context, "先点上面「新建一个空主题」")
                            else pickWallpaper.launch("image/*")
                        },
                    )
                    InsetDivider()
                    PageWallpaperRow("消息页背景", "wallpaper_chats", editing, pendingRole) { role ->
                        pendingRole = role
                        pickPageImage.launch("image/*")
                    }
                    PageWallpaperRow("通讯录背景", "wallpaper_contacts", editing, pendingRole) { role ->
                        pendingRole = role
                        pickPageImage.launch("image/*")
                    }
                    PageWallpaperRow("发现页背景", "wallpaper_discover", editing, pendingRole) { role ->
                        pendingRole = role
                        pickPageImage.launch("image/*")
                    }
                    PageWallpaperRow("「我」页背景", "wallpaper_me", editing, pendingRole) { role ->
                        pendingRole = role
                        pickPageImage.launch("image/*")
                    }
                    PageWallpaperRow("聊天页背景", "wallpaper_chatting", editing, pendingRole) { role ->
                        pendingRole = role
                        pickPageImage.launch("image/*")
                    }
                    InsetDivider()
                    ActionRow(
                        title = "② 选「对方」气泡",
                        summary = editing?.bubbleOther()?.name ?: "未选择",
                        onClick = {
                            if (editing == null) showToast(context, "先点上面「新建一个空主题」")
                            else pickBubbleOther.launch("image/*")
                        },
                    )
                    InsetDivider()
                    ActionRow(
                        title = "③ 选「自己」气泡",
                        summary = editing?.bubbleSelf()?.name ?: "未选择",
                        onClick = {
                            if (editing == null) showToast(context, "先点上面「新建一个空主题」")
                            else pickBubbleSelf.launch("image/*")
                        },
                    )
                    InsetDivider()
                    ActionRow(
                        title = "保存（只保存，不应用）",
                        summary = "图已经写进主题里了，点这里刷新列表",
                        onClick = {
                            packs = ThemePack.list()
                            refresh()
                            showToast(context, "已保存 ${packs.size} 个主题")
                        },
                    )
                    InsetDivider()
                    ActionRow(
                        title = "应用这个主题",
                        summary = "立即生效；气泡类改动可能要重启微信才完全刷新",
                        onClick = {
                            if (editing == null) {
                                showToast(context, "还没有正在编辑的主题")
                            } else {
                                applyEditing()
                                showToast(context, "已应用：${editing?.name}")
                            }
                        },
                    )
                }
            }

            // ── 壁纸透明度 ──────────────────────────────────────────
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "壁纸透明度") }
            item {
                SettingsCard {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(
                            text = "当前 ${opacity}%（越低越透出微信原背景）",
                            fontSize = 13.sp,
                            color = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor().color,
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
                    }
                }
            }

            // ── 分享 / 维护 ─────────────────────────────────────────
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "分享与维护") }
            item {
                SettingsCard {
                    ActionRow(
                        title = "导出当前主题为 zip",
                        summary = "生成后可直接发给别人，对方导入即可使用",
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
                        title = "导出目录",
                        summary = ThemePack.exportDir().absolutePath,
                        onClick = { showToast(context, "从文件管理器进入该目录取 zip") },
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
                    text = "主题包就是一堆图片 + 一个 manifest.json：wallpaper.png（壁纸）、bubble_left.9.png / bubble_right.9.png（左右气泡）。只放想换的图即可，没放的保持原样。",
                    fontSize = 12.sp,
                    color = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.summaryColor().color,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

private fun pageRoleLabel(role: String): String = when (role) {
    "wallpaper" -> "全局壁纸"
    "wallpaper_chats" -> "消息页背景"
    "wallpaper_contacts" -> "通讯录背景"
    "wallpaper_discover" -> "发现页背景"
    "wallpaper_me" -> "「我」页背景"
    "wallpaper_chatting" -> "聊天页背景"
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
            else -> "未设置（跟随全局壁纸）",
        },
        onClick = {
            if (editing == null) {
                showToast(context, "先点上面「新建一个空主题」")
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
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor().color,
        )
        Text(
            text = detail,
            fontSize = 12.sp,
            color = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.summaryColor().color,
        )
    }
}
