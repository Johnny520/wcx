package com.Johnny.wcx.activity.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Fiber_new
import com.composables.icons.materialsymbols.outlined.Search
import com.Johnny.wcx.features.core.BaseFeature
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.features.core.NewFeatures
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme


// ---------------------------------------------------------------------------
//  Shared switch state
// ---------------------------------------------------------------------------

/**
 * Bumped on every feature toggle. Rows key their MMKV read on it, so the search list and a
 * category screen — which the navigator keeps composed at the same time — can never drift apart,
 * nor away from what MMKV actually holds.
 */
private var featureToggleRevision by mutableIntStateOf(0)

/**
 * Current switch state of [item], read straight from MMKV using the feature's own
 * [SwitchFeature.defaultEnabled] — the very default `SwitchFeature.startup()` applies.
 */
@Composable
private fun featureChecked(item: BaseFeature): Boolean {
    val revision = featureToggleRevision
    return remember(item.name, revision) {
        WePrefs.getBoolOrDef(item.name, (item as? SwitchFeature)?.defaultEnabled == true)
    }
}

// ---------------------------------------------------------------------------
//  Page 1 — Features (search bar + category list)
// ---------------------------------------------------------------------------

@Composable
fun FeaturesPager(onOpenCategory: (String) -> Unit) {
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
                itemsIndexed(filteredItems, key = { _, item -> item::class.qualifiedName ?: item.name }) { index, item ->
                    Column(
                        modifier = Modifier
                            .then(if (index == 0) Modifier.padding(top = 12.dp) else Modifier)
                            .groupedCardItem(index, filteredItems.size),
                    ) {
                        FeatureRow(
                            item = item,
                            checked = featureChecked(item),
                            onCheckedChange = { featureToggleRevision++ },
                        )
                    }
                }
            }
        } else {
            // Its own card, so it reads as separate from the real categories below.
            if (NEW_FEATURE_ITEMS.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                    ) {
                        ArrowPreference(
                            title = NEW_FEATURES_CATEGORY,
                            summary = "最近 ${NewFeatures.WINDOW_DAYS} 天新增 ${NEW_FEATURE_ITEMS.size} 项",
                            startAction = {
                                Icon(
                                    imageVector = MaterialSymbols.Outlined.Fiber_new,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            },
                            onClick = { onOpenCategory(NEW_FEATURES_CATEGORY) },
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
//  Category detail (replaces CategorySettingsScreen)
// ---------------------------------------------------------------------------

@Composable
fun CategoryDetailScreen(categoryName: String, onBack: () -> Unit) {
    val items = remember(categoryName) {
        if (categoryName == NEW_FEATURES_CATEGORY) NEW_FEATURE_ITEMS
        else FeaturesProvider.ALL_HOOK_ITEMS.filter { categoryName in it.categories }
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

        itemsIndexed(items, key = { _, item -> item::class.qualifiedName ?: item.name }) { index, item ->
            Column(
                modifier = Modifier
                    .then(if (index == 0) Modifier.padding(top = 12.dp) else Modifier)
                    .groupedCardItem(index, items.size),
            ) {
                FeatureRow(
                    item = item,
                    checked = featureChecked(item),
                    onCheckedChange = { featureToggleRevision++ },
                )
                item.Ui()
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}
