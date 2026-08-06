package com.Johnny.wcx.activity.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Expand_less
import com.composables.icons.materialsymbols.outlined.Expand_more
import com.composables.icons.materialsymbols.outlined.New_releases
import com.composables.icons.materialsymbols.outlined.Search
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.easter_egg.AprilFools
import com.Johnny.wcx.features.items.easter_egg.isAprilFools
import com.Johnny.wcx.features.items.system.RECENT_UPDATES
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate


// ---------------------------------------------------------------------------
//  Page 1 — Features (search bar + category list)
// ---------------------------------------------------------------------------

@Composable
fun FeaturesPager(onOpenCategory: (String) -> Unit) {
    val showAprilFools = remember { LocalDate.now().isAprilFools }

    val queryState = rememberTextFieldState()
    val query = queryState.text.toString()
    val searching = query.isNotBlank()

    val searchableItems = remember { FeaturesProvider.ALL_HOOK_ITEMS.filterIsInstance<SwitchFeature>() }
    val filteredItems = remember(query) {
        if (!searching) emptyList()
        else searchableItems.filter {
            it.name.contains(query, ignoreCase = true) ||
                    it.description.contains(query, ignoreCase = true)
        }
    }
    val switchStates = remember { mutableStateMapOf<String, Boolean>() }

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
                            checked = switchStates[item.name] ?: WePrefs.getBoolOrFalse(item.name),
                            onCheckedChange = { switchStates[item.name] = it },
                        )
                    }
                }
            }
        } else {
            // 近期更新板块
            if (RECENT_UPDATES.isNotEmpty()) {
                item {
                    RecentUpdatesSection { featureKey ->
                        // 点击后切换到对应功能分类
                        val targetItem = FeaturesProvider.ALL_HOOK_ITEMS.find { it.name == featureKey }
                        if (targetItem != null) {
                            val category = targetItem.categories.firstOrNull()
                            if (category != null) {
                                onOpenCategory(category)
                            }
                        }
                    }
                }
            }

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

            item {
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                ) {
                    FEATURE_CATEGORIES.forEach { (name, icon) ->
                        ArrowPreference(
                            title = name,
                            startAction = {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            },
                            onClick = { onOpenCategory(name) },
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}

// ---------------------------------------------------------------------------
//  Recent Updates section — collapsible, between search bar and category list
// ---------------------------------------------------------------------------

@Composable
private fun RecentUpdatesSection(onItemClick: (featureKey: String) -> Unit) {
    var expanded by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
    ) {
        Column {
            // 标题栏：图标 + 文字 + 折叠/展开按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.New_releases,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "✨近期更新",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) MaterialSymbols.Outlined.Expand_less
                        else MaterialSymbols.Outlined.Expand_more,
                        contentDescription = if (expanded) "折叠" else "展开",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            if (expanded) {
                // 更新条目列表
                RECENT_UPDATES.forEachIndexed { index, item ->
                    val isLast = index == RECENT_UPDATES.lastIndex
                    RecentUpdateItemRow(
                        item = item,
                        isLast = isLast,
                        onClick = { onItemClick(item.featureKey) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentUpdateItemRow(
    item: com.Johnny.wcx.features.items.system.RecentUpdateItem,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp)
    ) {
        // 分隔线（非第一条）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f)),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = item.description,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // 点击箭头
            Icon(
                imageVector = MaterialSymbols.Outlined.Arrow_back,
                contentDescription = "跳转",
                modifier = Modifier
                    .size(16.dp)
                    .padding(start = 4.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        if (isLast) {
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------
//  Category detail (replaces CategorySettingsScreen)
// ---------------------------------------------------------------------------

@Composable
fun CategoryDetailScreen(categoryName: String, onBack: () -> Unit) {
    val items = remember(categoryName) {
        FeaturesProvider.ALL_HOOK_ITEMS.filter { categoryName in it.categories }
    }
    val switchStates = remember(categoryName) {
        mutableStateMapOf<String, Boolean>().apply {
            items.forEach { put(it.name, WePrefs.getBoolOrFalse(it.name)) }
        }
    }

    MiuixListScaffold(
        title = categoryName,
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
        if (items.isEmpty()) return@MiuixListScaffold

        itemsIndexed(items, key = { _, item -> item.name }) { index, item ->
            Column(
                modifier = Modifier
                    .then(if (index == 0) Modifier.padding(top = 12.dp) else Modifier)
                    .groupedCardItem(index, items.size),
            ) {
                FeatureRow(
                    item = item,
                    checked = switchStates[item.name] ?: false,
                    onCheckedChange = { switchStates[item.name] = it },
                )
                item.Ui()
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}
