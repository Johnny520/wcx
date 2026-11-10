package com.Johnny.wcx.activity.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Color
import com.Johnny.wcx.ui.style.RoundIconBox
import com.Johnny.wcx.ui.style.SoftCard
import com.Johnny.wcx.ui.style.WxPalette
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Search
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.easter_egg.AprilFools
import com.Johnny.wcx.features.items.easter_egg.isAprilFools
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.constants.Preferences
import top.yukonga.miuix.kmp.basic.Switch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.Johnny.wcx.ui.style.SoftDivider
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import com.Johnny.wcx.ui.style.SectionLabel
import com.composables.icons.materialsymbols.outlined.Category
import com.Johnny.wcx.features.core.ClickableFeature
import com.composables.icons.materialsymbols.outlined.Settings
import com.Johnny.wcx.ui.style.fxReveal
import com.Johnny.wcx.ui.style.rememberGrowNumber
import kotlin.math.roundToInt


// ---------------------------------------------------------------------------
//  Page 1 — Features (search bar + category list)
// ---------------------------------------------------------------------------

private val CATEGORY_GRADIENTS = listOf(
    WxPalette.GradBlue,
    WxPalette.GradMint,
    WxPalette.GradPurple,
    WxPalette.GradPink,
)

/** 分类专属色：让每个分类在列表里一眼可辨（不是靠文字读出来的）。 */
internal val CATEGORY_COLORS = listOf(
    Color(0xFF3D7EFF),
    Color(0xFF00D4A0),
    Color(0xFF8B5CF6),
    Color(0xFFFF8A3D),
    Color(0xFF00B8D9),
    Color(0xFFFF5B8A),
    Color(0xFF12D18E),
    Color(0xFFFFB020),
)

@Composable
fun FeaturesPager(onOpenCategory: (String) -> Unit) {
    val showAprilFools = remember { LocalDate.now().isAprilFools }

    val queryState = rememberTextFieldState()
    val query = queryState.text.toString()
    val searching = query.isNotBlank()

    // 设置页搜索**显式排除「密友功能」分类**：避免随便搜一个词就把密友入口暴露出来。
    // 密友入口固定在分类列表最后一项；直接进入分类仍可看到其中的开关。
    val searchableItems = remember {
        FeaturesProvider.ALL_HOOK_ITEMS
            .filterIsInstance<SwitchFeature>()
            .filter { item -> "密友功能" !in item.categories }
            // 当前微信版本上已被微信移除/改版、开了也不会生效的功能：直接不出现，
            // 不显示「失效/需适配」之类提示（用户看到只会以为模块坏了、想点修复）。
            .filter { item -> item.unavailableReason == null }
    }
    val filteredItems = remember(query) {
        if (!searching) emptyList()
        else searchableItems.filter {
            it.name.contains(query, ignoreCase = true) ||
                    it.description.contains(query, ignoreCase = true)
        }
    }
    val switchStates = remember { mutableStateMapOf<String, Boolean>() }

    // 每个分类的启用概况（已启用 / 总数），用于分类卡片副标题
    val categoryStat = remember {
        FEATURE_CATEGORIES.associate { entry ->
            val all = FeaturesProvider.ALL_HOOK_ITEMS
                .filter { entry.first in it.categories && it.unavailableReason == null }
            entry.first to (all.count { WePrefs.getBoolOrFalse(it.name) } to all.size)
        }
    }

    // A back press while searching clears the query first (after the IME's own
    // back has dismissed the keyboard) rather than exiting the module settings.
    BackHandler(enabled = searching) { queryState.clearText() }

    MiuixListScaffold(title = "功能") {
        item {
            TextField(
                state = queryState,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth(),
                label = "搜索功能",
                leadingIcon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
                trailingIcon = {
                    if (searching) {
                        IconButton(onClick = { queryState.clearText() }) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Close,
                                contentDescription = "Clear query",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                },
            )
        }

        if (!searching) {
            item {
                val total = FeaturesProvider.ALL_HOOK_ITEMS.size
                val enabled = FeaturesProvider.ALL_HOOK_ITEMS.count { WePrefs.getBoolOrFalse(it.name) }
                val ratio = if (total > 0) enabled.toFloat() / total else 0f
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    SoftCard(contentPadding = 16, glow = true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("已启用功能", fontSize = 12.sp, color = WxPalette.textSecondary())
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = enabled.toString(),
                                        style = TextStyle(
                                            brush = WxPalette.accentBrush(),
                                            fontSize = 30.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                    )
                                    Text(
                                        text = " / " + total,
                                        fontSize = 13.sp,
                                        color = WxPalette.textSecondary(),
                                        modifier = Modifier.padding(bottom = 5.dp, start = 2.dp),
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("分类", fontSize = 12.sp, color = WxPalette.textSecondary())
                                Text(
                                    text = FEATURE_CATEGORIES.size.toString() + " 个",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = WxPalette.textPrimary(),
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(WxPalette.cardBorder()),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(ratio.coerceIn(0f, 1f))
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(WxPalette.accentBrush()),
                            )
                        }
                    }
                }
            }
        }

        if (searching) {
            // Search results replace the category list while a query is active
            if (filteredItems.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "未匹配到任何相关功能",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            } else {
                itemsIndexed(filteredItems, key = { _, item -> item.name }) { index, item ->
                    Column(
                        modifier = Modifier
                            .then(if (index == 0) Modifier.padding(top = 12.dp) else Modifier)
                            .groupedCardItem(index, filteredItems.size),
                    ) {
                        FeatureRow(
                            item = item,
                            // 开关显示以运行时真值为准（SwitchFeature.isEnabled 即 WePrefs 的封装），
                            // 本地 map 只缓存本次会话的点击结果，避免「显示关了实际开着」的错位。
                            checked = switchStates[item.name] ?: ((item as? SwitchFeature)?.isEnabled == true),
                            onCheckedChange = { switchStates[item.name] = it },
                        )
                    }
                }
            }
        } else {
            if (showAprilFools) {
                item {
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                    ) {
                        ArrowPreference(
                            title = "🏳",
                            summary = "投降喵投降喵",
                            onClick = {
                                WePrefs.putBool(AprilFools.KEY_SURRENDER, true)
                                CoroutineScope(Dispatchers.Main).launch { showToastSuspend("重启生效") }
                            },
                        )
                    }
                }
            }

            // 分类列表：一张白卡内多行 + 细分隔线（iOS 分组列表）
            item {
                val shape = RoundedCornerShape(WxPalette.CardRadius)
                Column(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .clip(shape)
                        .background(WxPalette.cardBrush())
                        .border(1.dp, WxPalette.cardBorderBrush(), shape),
                ) {
                    FEATURE_CATEGORIES.forEachIndexed { index, entry ->
                        val name = entry.first
                        val stat = categoryStat[name]
                        val enabledCount = stat?.first ?: 0
                        val totalCount = stat?.second ?: 0
                        CategoryRow(
                            icon = entry.second,
                            title = name,
                            subtitle = if (totalCount > 0) {
                                "已启用 " + enabledCount + "/" + totalCount
                            } else {
                                "暂无功能"
                            },
                            tint = CATEGORY_COLORS[index % CATEGORY_COLORS.size],
                            progress = rememberGrowNumber(
                                if (totalCount > 0) enabledCount.toFloat() / totalCount else 0f,
                                durationMs = 600 + index * 60,
                            ),
                            onClick = { onOpenCategory(name) },
                        )
                        if (index != FEATURE_CATEGORIES.lastIndex) SoftDivider()
                    }
                }
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}

// ---------------------------------------------------------------------------
//  Category detail (replaces CategorySettingsScreen)
// ---------------------------------------------------------------------------

/** 分类行：圆角图标 + 分类名 + 启用概况 + 右箭头。 */
@Composable
private fun CategoryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color = WxPalette.Primary,
    progress: Float = 0f,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = WxPalette.textPrimary(),
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = WxPalette.TextSecondary,
            )
        }
        if (progress > 0f) {
            // 小进度条：不用读数字也能看出这个分类开了多少
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(WxPalette.cardBorder()),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tint),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "\u203A",
            fontSize = 20.sp,
            color = WxPalette.TextTertiary,
        )
    }
}

@Composable
fun CategoryDetailScreen(
    categoryName: String,
    onBack: () -> Unit,
    onOpenHiddenContactsDiagnostics: () -> Unit,
) {
    val items = remember(categoryName) {
        // 当前微信版本已不支持的功能不进列表（隐藏，而不是显示「失效」）
        FeaturesProvider.ALL_HOOK_ITEMS
            .filter { categoryName in it.categories && it.unavailableReason == null }
    }
    val switchStates = remember(categoryName) {
        mutableStateMapOf<String, Boolean>().apply {
            items.forEach { put(it.name, WePrefs.getBoolOrFalse(it.name)) }
        }
    }

    val isMasterSwitchCategory = categoryName == SwitchFeature.CATEGORY_WITH_MASTER_SWITCH
    var masterEnabled by remember(categoryName) {
        mutableStateOf(Preferences.hiddenContactsMasterEnabled)
    }

    // 开关一律以运行时真值为准（读 item.isEnabled），避免配置/状态不同步导致「显示开了其实没开」。
    fun syncFromRuntime() {
        items.forEach { switchStates[it.name] = (it as? SwitchFeature)?.isEnabled == true }
    }

    // 总开关：切换后立即对该分类下所有开关生效（各功能自身开关状态保留）。
    fun applyMasterSwitch(next: Boolean) {
        masterEnabled = next
        Preferences.hiddenContactsMasterEnabled = next
        items.filterIsInstance<SwitchFeature>().forEach { feature ->
            feature.isEnabled = next && WePrefs.getBoolOrFalse(feature.name)
        }
        syncFromRuntime()
    }

    val enabledCount = items.count { (it as? SwitchFeature)?.isEnabled == true }
    val accent = CATEGORY_COLORS[
        (FEATURE_CATEGORIES.indexOfFirst { it.first == categoryName }.takeIf { it >= 0 } ?: 0) % CATEGORY_COLORS.size
    ]

    PageScaffold(
        title = categoryName,
        largeTitle = categoryName,
        subtitle = if (items.isEmpty()) "本分类暂无功能" else "$enabledCount/${items.size} 已启用",
        onBack = onBack,
    ) { _ ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = CONTENT_BOTTOM_INSET),
        ) {
            // ---- 概览卡 ----
            item {
                SoftCard(modifier = Modifier.padding(top = 6.dp).fxReveal(0)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoundIconBox(
                            icon = MaterialSymbols.Outlined.Category,
                            tint = accent,
                            size = 44,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = categoryName,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = WxPalette.textPrimary(),
                            )
                            Text(
                                text = if (items.isEmpty()) "暂无功能" else "已启用 $enabledCount · 共 ${items.size} 项",
                                fontSize = 12.sp,
                                color = WxPalette.textSecondary(),
                            )
                        }
                        if (items.isNotEmpty()) {
                            val growPct = rememberGrowNumber(if (items.isEmpty()) 0f else enabledCount * 100f / items.size)
                            Text(
                                text = "${growPct.roundToInt()}%",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = accent,
                            )
                        }
                    }
                    if (items.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(accent.copy(alpha = 0.14f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(
                                        rememberGrowNumber(
                                            if (items.isEmpty()) 0f else enabledCount.toFloat() / items.size,
                                        )
                                    )
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(50))
                                    .background(accent),
                            )
                        }
                    }
                }
            }

            // ---- 密友总开关 ----
            if (isMasterSwitchCategory && items.isNotEmpty()) {
                item {
                    SoftCard(modifier = Modifier.padding(top = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "密友功能总开关",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = WxPalette.textPrimary(),
                                )
                                Text(
                                    text = "关闭后本分类下所有功能都不生效（各开关状态保留）",
                                    fontSize = 11.sp,
                                    color = WxPalette.textSecondary(),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Switch(checked = masterEnabled, onCheckedChange = ::applyMasterSwitch)
                        }
                    }
                }
                item {
                    SoftCard(
                        modifier = Modifier.padding(top = 10.dp),
                        gradient = WxPalette.GradPink,
                        onClick = { onOpenHiddenContactsDiagnostics() },
                    ) {
                        Text(
                            text = "密友功能诊断",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WxPalette.textPrimary(),
                        )
                        Text(
                            text = "检查隐藏面开关与关键锚点状态",
                            fontSize = 12.sp,
                            color = WxPalette.textSecondary(),
                        )
                    }
                }
            }

            if (items.isEmpty()) {
                item {
                    SoftCard(modifier = Modifier.padding(top = 10.dp)) {
                        Text(
                            text = "本分类下暂无功能",
                            fontSize = 14.sp,
                            color = WxPalette.textSecondary(),
                        )
                    }
                }
                return@LazyColumn
            }

            item {
                SectionLabel("功能", modifier = Modifier.padding(start = 6.dp, top = 16.dp, bottom = 6.dp))
            }

            // 功能列表：每行一个 Lazy item（真正虚拟化）+ 稳定 key。
            // 之前把整段列表塞在一个 item 里，进入「聊天」这种几十项的分类要一次性组合全部行
            // （每行都注册开关回调、读配置、画 squircle 卡片），上下滑动明显掉帧。
            itemsIndexed(items, key = { _, feature -> feature.name }) { index, feature ->
                Column(modifier = Modifier.groupedCardItem(index, items.size)) {
                    CatRow(
                        item = feature,
                        checked = switchStates[feature.name] ?: ((feature as? SwitchFeature)?.isEnabled == true),
                        onCheckedChange = { switchStates[feature.name] = it },
                    )
                    if (index != items.lastIndex) {
                        SoftDivider(modifier = Modifier.padding(start = 14.dp))
                    } else {
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            // 部分功能带内嵌设置面板（item.Ui()）：多数功能是空实现，合并到一个 item 里渲染，
            // 不再给几十个空面板各占一个 Lazy item 槽位。
            item(key = "__category_ui_panels__") {
                Column {
                    items.forEach { feature -> feature.Ui() }
                }
            }
        }
    }
}

/**
 * 分类详情页的紧凑功能行（自绘，替代 miuix BasicComponent/SwitchPreference 的高行）：
 * 标题 + 描述两行内完成，开关右贴齐；带配置面板的功能显示齿轮图标。
 * 开关逻辑与 SettingsActivity.FeatureRow 完全一致（unavailable 拦截 / onBeforeToggle / ClickableFeature）。
 */
@Composable
private fun CatRow(
    item: com.Johnny.wcx.features.core.BaseFeature,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val context = LocalComponentActivity.current
    val configKey = item.name

    // 非 SwitchFeature：纯信息行（API 服务/常驻项），无开关
    if (item !is com.Johnny.wcx.features.core.SwitchFeature) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
            Text(
                text = item.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = WxPalette.textPrimary(),
            )
            if (item.description.isNotBlank()) {
                Text(
                    text = item.description,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = WxPalette.textSecondary(),
                )
            }
        }
        return
    }

    androidx.compose.runtime.DisposableEffect(configKey) {
        item.setToggleCompletionCallback { onCheckedChange(item.isEnabled) }
        onDispose { }
    }

    fun toggle(requested: Boolean) {
        val unavailable = if (requested) item.unavailableReason else null
        if (unavailable != null) {
            com.Johnny.wcx.utils.WeLogger.w("CatRow", "拒绝开启 ${item.name}：$unavailable")
            com.Johnny.wcx.utils.android.showToast(unavailable)
            return
        }
        if (item.onBeforeToggle(requested, context)) {
            com.Johnny.wcx.preferences.WePrefs.putBool(configKey, requested)
            item.isEnabled = requested
            onCheckedChange(requested)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (item is ClickableFeature) {
                    runCatching { item.onClick(context) }
                        .onFailure { com.Johnny.wcx.utils.WeLogger.e("CatRow", "onClick failed: ${item.name}", it) }
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WxPalette.textPrimary(),
                )
                if (item is ClickableFeature) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Settings,
                        contentDescription = "可配置",
                        modifier = Modifier.size(15.dp),
                        tint = WxPalette.textSecondary(),
                    )
                }
            }
            if (item.description.isNotBlank()) {
                Text(
                    text = item.description,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = WxPalette.textSecondary(),
                )
            }
        }
        if (!(item is ClickableFeature && item.noSwitchWidget)) {
            Spacer(Modifier.width(10.dp))
            Switch(checked = checked, onCheckedChange = { toggle(it) })
        }
    }
}
