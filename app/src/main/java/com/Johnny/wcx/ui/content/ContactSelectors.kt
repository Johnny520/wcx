package com.Johnny.wcx.ui.content

import android.icu.text.Transliterator
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Compare_arrows
import com.composables.icons.materialsymbols.outlined.Deselect
import com.composables.icons.materialsymbols.outlined.Expand_less
import com.composables.icons.materialsymbols.outlined.Expand_more
import com.composables.icons.materialsymbols.outlined.Folder
import com.composables.icons.materialsymbols.outlined.Groups
import com.composables.icons.materialsymbols.outlined.Label
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlined.Schedule
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Select_all
import com.composables.icons.materialsymbols.outlined.Sort_by_alpha
import com.composables.icons.materialsymbols.outlined.Swap_vert
import com.composables.icons.materialsymbols.outlined.Tag


import com.Johnny.wcx.features.api.core.WeContactLabelApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.IWeContact
import com.Johnny.wcx.features.api.core.models.WeContact
import com.Johnny.wcx.features.api.core.models.WeGroup
import com.Johnny.wcx.features.api.core.models.WeOfficialAccount
import com.Johnny.wcx.features.items.chat.ConversationAggregation
import com.Johnny.wcx.features.items.chat.ConversationGrouping
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.CollationKey
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val SELECTED_SECTION_KEY = "\u0000selected"
private const val NEWEST_SECTION_KEY = "\u0000newest"
private const val OLDEST_SECTION_KEY = "\u0000oldest"

enum class FilterType(val displayNameRes: String) {
    ALL("全部"),
    FRIENDS("好友"),
    GROUPS("群聊"),
    OFFICIAL_ACCOUNTS("公众号"),
    OTHERS("其他"),
}

private enum class ContactFilterMode(val icon: ImageVector, val nameRes: String) {
    LABELS(MaterialSymbols.Outlined.Label, "标签"),
    AGGREGATION(MaterialSymbols.Outlined.Folder, "归拢"),
    GROUPING(MaterialSymbols.Outlined.Groups, "分组"),
}

private var persistedContactFilterMode by WePrefs.prefOption(
    "contact_selector_filter_mode",
    ContactFilterMode.LABELS.name,
)

private data class ContactFilterOption(
    val id: String,
    val name: String,
    val wxIds: Set<String>,
)

enum class SortMode(val icon: ImageVector) {
    ALPHABETICAL(MaterialSymbols.Outlined.Sort_by_alpha),
    LAST_MESSAGE_TIME(MaterialSymbols.Outlined.Schedule);

    fun displayNameRes(reversed: Boolean): String = when (this) {
        ALPHABETICAL -> if (reversed) "Z–A"
        else "A–Z"
        LAST_MESSAGE_TIME -> if (reversed) "旧-新"
        else "新-旧"
    }
}

@Composable
fun BaseContactSelector(
    title: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    filteredContacts: List<IWeContact>,
    allContacts: List<IWeContact> = filteredContacts,
    confirmButtonText: String,
    confirmButtonEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    selectionKey: Any,
    isSelected: (IWeContact) -> Boolean,
    showConfirmButton: Boolean = true,
    dismissButtonText: String? = null,
    avatarModelProvider: ((IWeContact) -> Any)? = { it.avatarUrl },
    subtitleProvider: ((IWeContact) -> String)? = { it.wxId },
    leadingControl: @Composable (LazyItemScope.(IWeContact) -> Unit)? = null,
    trailingControl: @Composable (LazyItemScope.(IWeContact) -> Unit)? = null,
    onItemClick: (IWeContact) -> Unit,
    onSelectAll: ((List<IWeContact>) -> Unit)? = null,
    onDeselectAll: ((List<IWeContact>) -> Unit)? = null,
    onInvertSelection: ((List<IWeContact>) -> Unit)? = null
) {
    val context = LocalContext.current
    val localizedContext = LocalContext.current
    val currentLocalizedContext = rememberUpdatedState(localizedContext)
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val alphabet = remember { listOf(SELECTED_SECTION_KEY) + ('A'..'Z').map { it.toString() } + "#" }

    val transliterator = remember {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Transliterator.getInstance("Han-Latin; Any-Latin; Latin-ASCII")
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // 分组字母缓存 (首字符 -> A-Z / #)。
    // ICU 的 Transliterator 很慢, 而 groupedContacts 会在每次搜索按键时对整个列表重跑一遍;
    // 不同的首字符数量远小于联系人数量, 按首字符缓存后主线程基本只是查表。
    val initialCache = remember { ConcurrentHashMap<Char, String>() }

    fun initialOf(displayName: String): String {
        val name = displayName.trim()
        if (name.isEmpty()) return "#"
        val firstChar = name.first()
        initialCache[firstChar]?.let { return it }

        val upper = firstChar.uppercaseChar()
        val initial = if (upper in 'A'..'Z') {
            upper.toString()
        } else if (transliterator != null) {
            // safe to ignore since transliterator is null when SDK too low
            // ICU Transliterator 不是线程安全的, 预热协程与主线程可能同时进来。
            val pinyin = synchronized(transliterator) { transliterator.transliterate(firstChar.toString()) }
            val c = pinyin.firstOrNull()?.uppercaseChar() ?: '#'
            if (c in 'A'..'Z') c.toString() else "#"
        } else {
            "#"
        }
        initialCache[firstChar] = initial
        return initial
    }

    var friendWxIds by remember { mutableStateOf(emptySet<String>()) }
    var groupWxIds by remember { mutableStateOf(emptySet<String>()) }
    var officialAccountWxIds by remember { mutableStateOf(emptySet<String>()) }
    var allLabels by remember { mutableStateOf(emptyList<WeContactLabelApi.ContactLabel>()) }
    var labelContactsMap by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    var aggregationOptions by remember { mutableStateOf(emptyList<ContactFilterOption>()) }
    var groupingOptions by remember { mutableStateOf(emptyList<ContactFilterOption>()) }
    var isFiltersLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // 先在 IO 线程把分组字母算好, 主线程之后每次按键都只命中缓存。
            // (之后新增的联系人会在 initialOf 里按需补算, 结果一致。)
            runCatching { allContacts.forEach { initialOf(it.displayName) } }

            try {
                if (WeDatabaseApi.isReady) {
                    val friends = WeDatabaseApi.getFriends().map { it.wxId }.toSet()
                    val groups = WeDatabaseApi.getGroups().map { it.wxId }.toSet()
                    val officialAccounts = WeDatabaseApi.getOfficialAccounts().map { it.wxId }.toSet()
                    val labels = WeContactLabelApi.getAllLabels()
                    val labelMap = labels.associate { label ->
                        label.labelName to WeContactLabelApi.getContactsByLabelId(label.labelId).toSet()
                    }
                    val aggregation = if (ConversationAggregation.isEnabled) {
                        ConversationAggregation.aggregationFolders().map { folder ->
                            ContactFilterOption(
                                id = folder.id,
                                name = folder.name,
                                wxIds = ConversationAggregation.folderMembers(folder.id).toSet(),
                            )
                        }
                    } else {
                        emptyList()
                    }
                    val grouping = if (ConversationGrouping.isEnabled) {
                        ConversationGrouping.groupFilterOptions(currentLocalizedContext.value).map { group ->
                            ContactFilterOption(group.id, group.name, group.members.toSet())
                        }
                    } else {
                        emptyList()
                    }

                    withContext(Dispatchers.Main) {
                        friendWxIds = friends
                        groupWxIds = groups
                        officialAccountWxIds = officialAccounts
                        allLabels = labels
                        labelContactsMap = labelMap
                        aggregationOptions = aggregation
                        groupingOptions = grouping
                        isFiltersLoaded = true
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        val currentResources = currentLocalizedContext.value
                        showToast(
                            context,
                            "数据库尚未初始化, 筛选将不可用!",
                        )
                        isFiltersLoaded = true
                    }
                }
            } catch (e: Exception) {
                WeLogger.e("ContactSelectors", "Failed to load filters in coroutine", e)
                withContext(Dispatchers.Main) {
                    isFiltersLoaded = true
                }
            }
        }
    }

    var selectedType by remember { mutableStateOf(FilterType.ALL) }
    var filterMode by remember {
        val persistedMode = ContactFilterMode.entries.firstOrNull {
            it.name == persistedContactFilterMode
        }
        mutableStateOf(
            when (persistedMode) {
                ContactFilterMode.AGGREGATION -> if (ConversationAggregation.isEnabled) persistedMode else ContactFilterMode.LABELS
                ContactFilterMode.GROUPING -> if (ConversationGrouping.isEnabled) persistedMode else ContactFilterMode.LABELS
                ContactFilterMode.LABELS, null -> ContactFilterMode.LABELS
            }
        )
    }
    var selectedLabelName by remember { mutableStateOf<String?>(null) }
    var selectedAggregationId by remember { mutableStateOf<String?>(null) }
    var selectedGroupingId by remember { mutableStateOf<String?>(null) }

    var filtersExpanded by remember { mutableStateOf(true) }

    var sortMode by remember { mutableStateOf(SortMode.ALPHABETICAL) }
    var sortReversed by remember { mutableStateOf(false) }
    var lastMessageTimes by remember { mutableStateOf<Map<String, Long>?>(null) }
    var isSortLoading by remember { mutableStateOf(false) }

    fun switchSortMode(target: SortMode) {
        if (target == sortMode || isSortLoading) return
        if (target == SortMode.ALPHABETICAL) {
            sortMode = SortMode.ALPHABETICAL
            return
        }
        // 切换到按最近消息时间排序
        if (lastMessageTimes != null) {
            sortMode = SortMode.LAST_MESSAGE_TIME
            return
        }
        isSortLoading = true
        coroutineScope.launch {
            val times = withContext(Dispatchers.IO) {
                if (WeDatabaseApi.isReady) WeDatabaseApi.getLastMessageTimes() else null
            }
            // getLastMessageTimes() 内部吞掉异常后返回空表, 所以空结果也当作失败:
            // 否则会静默按"所有会话时间相同"排出一个随意的顺序, 而且缓存住之后再也不会重试。
            if (times.isNullOrEmpty()) {
                val currentResources = currentLocalizedContext.value
                showToast(
                    context,
                    "数据库尚未初始化, 无法按时间排序!",
                )
            } else {
                lastMessageTimes = times
                sortMode = SortMode.LAST_MESSAGE_TIME
            }
            isSortLoading = false
        }
    }

    val typeCounts = remember(filteredContacts, friendWxIds, groupWxIds, officialAccountWxIds) {
        var friends = 0
        var groups = 0
        var officialAccounts = 0
        var others = 0
        for (contact in filteredContacts) {
            val isGroup = contact is WeGroup || contact.wxId.endsWith("@chatroom") || contact.wxId in groupWxIds
            val isOfficial = contact is WeOfficialAccount || contact.wxId.startsWith("gh_") || contact.wxId in officialAccountWxIds
            val isFriend = contact.wxId in friendWxIds || contact is WeContact && !isGroup && !isOfficial && contact.type and 1 != 0
            when {
                isGroup -> groups++
                isOfficial -> officialAccounts++
                isFriend -> friends++
                else -> others++
            }
        }
        mapOf(
            FilterType.ALL to filteredContacts.size,
            FilterType.FRIENDS to friends,
            FilterType.GROUPS to groups,
            FilterType.OFFICIAL_ACCOUNTS to officialAccounts,
            FilterType.OTHERS to others
        )
    }

    // Row visibility is decided from the full contact list so that filter rows do not
    // disappear while a search query is active (the filters still apply, hiding them is confusing).
    val allTypeCounts = remember(allContacts, friendWxIds, groupWxIds, officialAccountWxIds) {
        var friends = 0
        var groups = 0
        var officialAccounts = 0
        var others = 0
        for (contact in allContacts) {
            val isGroup = contact is WeGroup || contact.wxId.endsWith("@chatroom") || contact.wxId in groupWxIds
            val isOfficial = contact is WeOfficialAccount || contact.wxId.startsWith("gh_") || contact.wxId in officialAccountWxIds
            val isFriend = contact.wxId in friendWxIds || contact is WeContact && !isGroup && !isOfficial && contact.type and 1 != 0
            when {
                isGroup -> groups++
                isOfficial -> officialAccounts++
                isFriend -> friends++
                else -> others++
            }
        }
        mapOf(
            FilterType.ALL to allContacts.size,
            FilterType.FRIENDS to friends,
            FilterType.GROUPS to groups,
            FilterType.OFFICIAL_ACCOUNTS to officialAccounts,
            FilterType.OTHERS to others
        )
    }

    val availableTypes = remember(allTypeCounts) {
        FilterType.entries.filter { type ->
            type == FilterType.ALL || allTypeCounts[type] ?: 0 > 0
        }
    }
    val showTypeFilterRow = remember(availableTypes, isFiltersLoaded) { isFiltersLoaded && availableTypes.size > 2 }


    val labelCounts = remember(filteredContacts, labelContactsMap) {
        labelContactsMap.mapValues { (_, wxIds) ->
            filteredContacts.count { it.wxId in wxIds }
        }
    }
    val availableLabels = remember(allContacts, allLabels, labelContactsMap) {
        allLabels.filter { label ->
            val wxIds = labelContactsMap[label.labelName] ?: emptySet()
            allContacts.any { it.wxId in wxIds }
        }
    }
    val availableAggregationOptions = remember(allContacts, aggregationOptions) {
        aggregationOptions.filter { option -> allContacts.any { it.wxId in option.wxIds } }
    }
    val availableGroupingOptions = remember(allContacts, groupingOptions) {
        groupingOptions.filter { option -> allContacts.any { it.wxId in option.wxIds } }
    }
    val availableFilterModes = remember(isFiltersLoaded) {
        if (!isFiltersLoaded) emptyList()
        else ContactFilterMode.entries.filter { mode ->
            mode == ContactFilterMode.LABELS || when (mode) {
                ContactFilterMode.AGGREGATION -> ConversationAggregation.isEnabled
                ContactFilterMode.GROUPING -> ConversationGrouping.isEnabled
                ContactFilterMode.LABELS -> true
            }
        }
    }
    val showFilterModeRow = availableFilterModes.isNotEmpty()

    val displayedContacts = remember(filteredContacts, selectedType, filterMode, selectedLabelName, selectedAggregationId, selectedGroupingId, friendWxIds, groupWxIds, officialAccountWxIds, labelContactsMap, aggregationOptions, groupingOptions) {
        filteredContacts.filter { contact ->
            val isGroup = contact is WeGroup || contact.wxId.endsWith("@chatroom") || contact.wxId in groupWxIds
            val isOfficial = contact is WeOfficialAccount || contact.wxId.startsWith("gh_") || contact.wxId in officialAccountWxIds
            val isFriend = contact.wxId in friendWxIds || contact is WeContact && !isGroup && !isOfficial && contact.type and 1 != 0

            val matchesType = when (selectedType) {
                FilterType.ALL -> true
                FilterType.FRIENDS -> isFriend
                FilterType.GROUPS -> isGroup
                FilterType.OFFICIAL_ACCOUNTS -> isOfficial
                FilterType.OTHERS -> !isFriend && !isGroup && !isOfficial
            }

            val matchesMode = when (filterMode) {
                ContactFilterMode.LABELS -> selectedLabelName == null || contact.wxId in (labelContactsMap[selectedLabelName] ?: emptySet())
                ContactFilterMode.AGGREGATION -> selectedAggregationId == null || contact.wxId in (aggregationOptions.firstOrNull { it.id == selectedAggregationId }?.wxIds ?: emptySet())
                ContactFilterMode.GROUPING -> selectedGroupingId == null || contact.wxId in (groupingOptions.firstOrNull { it.id == selectedGroupingId }?.wxIds ?: emptySet())
            }

            matchesType && matchesMode
        }
    }

    val groupedContacts = remember(displayedContacts, initialCache, selectionKey, sortMode, sortReversed, lastMessageTimes) {
        if (sortMode == SortMode.LAST_MESSAGE_TIME) {
            val times = lastMessageTimes ?: emptyMap()
            val sorted = if (sortReversed) {
                displayedContacts.sortedBy { times[it.wxId] ?: Long.MIN_VALUE }
            } else {
                displayedContacts.sortedByDescending { times[it.wxId] ?: Long.MIN_VALUE }
            }
            val (selected, rest) = sorted.partition { isSelected(it) }
            linkedMapOf<String, List<IWeContact>>().apply {
                if (selected.isNotEmpty()) put(SELECTED_SECTION_KEY, selected)
                if (rest.isNotEmpty()) {
                    put(if (sortReversed) OLDEST_SECTION_KEY else NEWEST_SECTION_KEY, rest)
                }
            }
        } else {
            displayedContacts.groupBy { contact ->
                if (isSelected(contact)) SELECTED_SECTION_KEY else initialOf(contact.displayName)
            }.toSortedMap { c1, c2 ->
                when {
                    c1 == c2 -> 0
                    c1 == SELECTED_SECTION_KEY -> -1
                    c2 == SELECTED_SECTION_KEY -> 1
                    c1 == "#" -> 1
                    c2 == "#" -> -1
                    else -> if (sortReversed) c2.compareTo(c1) else c1.compareTo(c2)
                }
            } as Map<String, List<IWeContact>>
        }
    }

    val sectionIndices = remember(groupedContacts) {
        val mapping = mutableMapOf<String, Int>()
        var currentFlatIndex = 0
        groupedContacts.forEach { (letter, contactsInGroup) ->
            mapping[letter] = currentFlatIndex
            currentFlatIndex += 1
            currentFlatIndex += contactsInGroup.size
        }
        mapping
    }

    AlertDialogContent(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("搜索昵称或微信号") },
                        leadingIcon = {
                            Icon(
                                MaterialSymbols.Outlined.Search,
                                contentDescription = "搜索联系人",
                            )
                        },
                        singleLine = true
                    )
                    IconButton(onClick = { filtersExpanded = !filtersExpanded }) {
                        Icon(
                            imageVector = if (filtersExpanded) {
                                MaterialSymbols.Outlined.Expand_less
                            } else {
                                MaterialSymbols.Outlined.Expand_more
                            },
                            contentDescription = if (filtersExpanded) "折叠筛选" else "展开筛选",
                        )
                    }
                }

                AnimatedVisibility(
                    visible = filtersExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (showTypeFilterRow) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                items(availableTypes) { type ->
                                    val isSelected = selectedType == type
                                    val count = typeCounts[type] ?: 0
                                    val displayName = type.displayNameRes
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedType = type },
                                        label = {
                                            Text(
                                                "%1\$s (%2\$d)".format(displayName, count),
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = when (type) {
                                                    FilterType.ALL -> MaterialSymbols.Outlined.Search
                                                    FilterType.FRIENDS -> MaterialSymbols.Outlined.Person
                                                    FilterType.GROUPS -> MaterialSymbols.Outlined.Groups
                                                    FilterType.OFFICIAL_ACCOUNTS -> MaterialSymbols.Outlined.Chat
                                                    FilterType.OTHERS -> MaterialSymbols.Outlined.Tag
                                                },
                                                contentDescription = displayName,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        if (showFilterModeRow) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                item {
                                    val modeIndex = availableFilterModes.indexOf(filterMode).coerceAtLeast(0)
                                    val hasMoreModes = availableFilterModes.size > 1
                                    FilterChip(
                                        selected = true,
                                        onClick = {
                                            if (!hasMoreModes) {
                                                showToast(
                                                    context,
                                                    "可启用「对话归拢」或「对话分组」以使用更多筛选方式",
                                                )
                                            } else {
                                                filterMode = availableFilterModes[(modeIndex + 1) % availableFilterModes.size]
                                                persistedContactFilterMode = filterMode.name
                                                selectedLabelName = null
                                                selectedAggregationId = null
                                                selectedGroupingId = null
                                            }
                                        },
                                        label = { Text(filterMode.nameRes) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = filterMode.icon,
                                                contentDescription = filterMode.nameRes,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        },
                                    )
                                }

                                if (filterMode == ContactFilterMode.LABELS) {
                                    item {
                                        val isSelected = selectedLabelName == null
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { selectedLabelName = null },
                                            label = { Text("全部") }
                                        )
                                    }

                                    items(availableLabels) { label ->
                                        val isSelected = selectedLabelName == label.labelName
                                        val labelCount = labelCounts[label.labelName] ?: 0
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { selectedLabelName = if (isSelected) null else label.labelName },
                                            label = {
                                                Text("%1\$s (".format(label.labelName, labelCount))
                                            }
                                        )
                                    }
                                } else {
                                    val options = if (filterMode == ContactFilterMode.AGGREGATION) {
                                        availableAggregationOptions
                                    } else {
                                        availableGroupingOptions
                                    }
                                    item {
                                        val isSelected = if (filterMode == ContactFilterMode.AGGREGATION) {
                                            selectedAggregationId == null
                                        } else {
                                            selectedGroupingId == null
                                        }
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                if (filterMode == ContactFilterMode.AGGREGATION) {
                                                    selectedAggregationId = null
                                                } else {
                                                    selectedGroupingId = null
                                                }
                                            },
                                            label = { Text("全部") },
                                        )
                                    }
                                    items(options, key = { it.id }) { option ->
                                        val selectedId = if (filterMode == ContactFilterMode.AGGREGATION) selectedAggregationId else selectedGroupingId
                                        val isSelected = selectedId == option.id
                                        val count = filteredContacts.count { it.wxId in option.wxIds }
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                if (filterMode == ContactFilterMode.AGGREGATION) {
                                                    selectedAggregationId = if (isSelected) null else option.id
                                                } else {
                                                    selectedGroupingId = if (isSelected) null else option.id
                                                }
                                            },
                                            label = { Text("%1\$s (".format(option.name, count)) },
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                                .padding(bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SortMode.entries.forEach { mode ->
                                val displayName = mode.displayNameRes(sortReversed)
                                FilterChip(
                                    selected = sortMode == mode,
                                    enabled = !isSortLoading,
                                    onClick = { switchSortMode(mode) },
                                    label = { Text(displayName) },
                                    leadingIcon = {
                                        if (isSortLoading && mode == SortMode.LAST_MESSAGE_TIME) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(
                                                imageVector = mode.icon,
                                                contentDescription = displayName,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                )
                            }

                            FilterChip(
                                selected = sortReversed,
                                enabled = !isSortLoading,
                                onClick = { sortReversed = !sortReversed },
                                label = {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Swap_vert,
                                        contentDescription = "切换排序方向",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }

                        if (onSelectAll != null || onDeselectAll != null || onInvertSelection != null) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp)
                                    .padding(bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                onSelectAll?.let {
                                    FilterChip(
                                        selected = false,
                                        onClick = { it(displayedContacts) },
                                        label = { Text("全选") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Select_all,
                                                contentDescription = "全选",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                                onDeselectAll?.let {
                                    FilterChip(
                                        selected = false,
                                        onClick = { it(displayedContacts) },
                                        label = { Text("全不选") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Deselect,
                                                contentDescription = "全不选",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                                onInvertSelection?.let {
                                    FilterChip(
                                        selected = false,
                                        onClick = { it(displayedContacts) },
                                        label = { Text("反选") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Compare_arrows,
                                                contentDescription = "反选",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                if (displayedContacts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "无匹配的联系人",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            groupedContacts.forEach { (letter, contactsInGroup) ->
                                stickyHeader(key = "header_$letter") {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                                    ) {
                                        Text(
                                            text = when (letter) {
                                                SELECTED_SECTION_KEY -> "已选"
                                                NEWEST_SECTION_KEY -> "新-旧"
                                                OLDEST_SECTION_KEY -> "旧-新"
                                                else -> letter
                                            },
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                items(
                                    items = contactsInGroup,
                                    key = { it.wxId }
                                ) { contact ->
                                    Row(
                                        modifier = Modifier
                                            .animateItem()
                                            .fillMaxWidth()
                                            .clickable { onItemClick(contact) }
                                            .padding(vertical = 12.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (leadingControl != null) {
                                            leadingControl(contact)
                                            Spacer(modifier = Modifier.width(12.dp))
                                        }

                                        AsyncImage(
                                            model = avatarModelProvider?.invoke(contact) ?: contact.avatarUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(6.dp)),
                                            imageLoader = GlobalImageLoader
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = contact.displayName,
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                            Text(
                                                text = subtitleProvider?.invoke(contact) ?: contact.wxId,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        if (trailingControl != null) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            trailingControl(contact)
                                        }
                                    }
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(start = 8.dp, end = 4.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val displayAlphabet = if (sortReversed) {
                                listOf(SELECTED_SECTION_KEY) + ('A'..'Z').map { it.toString() }.reversed() + "#"
                            } else {
                                alphabet
                            }
                            if (sortMode == SortMode.ALPHABETICAL) displayAlphabet.forEach { letter ->
                                val isAvailable = groupedContacts.containsKey(letter)
                                Text(
                                    text = if (letter == SELECTED_SECTION_KEY) "✓" else letter,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isAvailable) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                    },
                                    modifier = Modifier
                                        .clickable {
                                            val targetIndex = if (letter == SELECTED_SECTION_KEY) {
                                                sectionIndices[SELECTED_SECTION_KEY]
                                            } else {
                                                val letterKeys = sectionIndices.keys.filter { it != SELECTED_SECTION_KEY }
                                                val targetLetter = if (sortReversed) {
                                                    letterKeys.firstOrNull { it.first() <= letter.first() }
                                                } else {
                                                    letterKeys.firstOrNull { it.first() >= letter.first() }
                                                } ?: sectionIndices.keys.lastOrNull()
                                                targetLetter?.let { sectionIndices[it] }
                                            }
                                            targetIndex?.let { index ->
                                                coroutineScope.launch {
                                                    listState.scrollToItem(index)
                                                }
                                            }
                                        }
                                        .padding(vertical = 2.dp, horizontal = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onDismiss) {
                Text(dismissButtonText ?: "取消")
            }
        },
        confirmButton = if (showConfirmButton) {
            {
                Button(
                    onClick = onConfirm,
                    enabled = confirmButtonEnabled
                ) {
                    Text(confirmButtonText)
                }
            }
        } else null
    )
}

@Composable
fun SingleContactSelector(
    title: String,
    contacts: List<IWeContact>,
    initialSelectedWxId: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedWxId by remember { mutableStateOf(initialSelectedWxId) }

    val sortedContacts = remember(contacts) { sortContactsByDisplayName(contacts) }

    val filteredContacts = remember(searchQuery, sortedContacts) {
        filterSortedContacts(sortedContacts, searchQuery)
    }

    BaseContactSelector(
        title = title,
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        filteredContacts = filteredContacts,
        allContacts = contacts,
        confirmButtonText = "确定",
        confirmButtonEnabled = selectedWxId != null,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(selectedWxId!!) },
        selectionKey = selectedWxId ?: "",
        isSelected = { it.wxId == selectedWxId },
        leadingControl = { contact ->
            RadioButton(
                selected = contact.wxId == selectedWxId,
                onClick = null
            )
        },
        onItemClick = { contact ->
            selectedWxId = contact.wxId
        }
    )
}

@Composable
fun ContactsSelector(
    title: String,
    contacts: List<IWeContact>,
    initialSelectedWxIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedWxIds by remember { mutableStateOf(initialSelectedWxIds) }

    val sortedContacts = remember(contacts) { sortContactsByDisplayName(contacts) }

    val filteredContacts = remember(searchQuery, sortedContacts) {
        filterSortedContacts(sortedContacts, searchQuery)
    }

    BaseContactSelector(
        title = title,
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        filteredContacts = filteredContacts,
        allContacts = contacts,
        confirmButtonText = "确定 (".format(selectedWxIds.size),
        confirmButtonEnabled = true,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(selectedWxIds) },
        selectionKey = selectedWxIds,
        isSelected = { it.wxId in selectedWxIds },
        leadingControl = { contact ->
            Checkbox(
                checked = contact.wxId in selectedWxIds,
                onCheckedChange = null
            )
        },
        onItemClick = { contact ->
            selectedWxIds = if (contact.wxId in selectedWxIds) {
                selectedWxIds - contact.wxId
            } else {
                selectedWxIds + contact.wxId
            }
        },
        onSelectAll = { displayed ->
            selectedWxIds = selectedWxIds + displayed.map { it.wxId }
        },
        onDeselectAll = { displayed ->
            selectedWxIds = selectedWxIds - displayed.map { it.wxId }.toSet()
        },
        onInvertSelection = { displayed ->
            val displayedWxIds = displayed.map { it.wxId }.toSet()
            val newSelection = selectedWxIds.toMutableSet()
            for (wxId in displayedWxIds) {
                if (wxId in newSelection) {
                    newSelection.remove(wxId)
                } else {
                    newSelection.add(wxId)
                }
            }
            selectedWxIds = newSelection
        }
    )
}

private class SortableContact(
    val contact: IWeContact,
    val isBlankName: Boolean,
    val key: CollationKey,
)

/**
 * 按显示名排序 (与 `Collator.compare` 的顺序完全一致)。
 *
 * ICU 的 [Collator] 每次 compare 都要重新分析两个字符串, 排序过程里同一个名字会被反复分析;
 * 这里先给每个不同的显示名各算一份 [CollationKey] (每个名字只分析一次), 排序时只比较键。
 * `displayName` 是 getter (WeContact 每次都会重新拼字符串), 所以也只取一次。
 *
 * 调用方只在联系人列表变化时调用一次, 搜索时改用 [filterSortedContacts] 在已排好序的
 * 列表上做纯字符串过滤, 避免每敲一个字就在主线程上重跑一遍 ICU 排序。
 */
private fun sortContactsByDisplayName(contacts: List<IWeContact>): List<IWeContact> {
    if (contacts.size < 2) return contacts
    val collator = Collator.getInstance(Locale.CHINA)
    val keyCache = HashMap<String, CollationKey>()
    return contacts
        .map { contact ->
            val name = contact.displayName
            SortableContact(
                contact = contact,
                isBlankName = name.isBlank(),
                key = keyCache.getOrPut(name) { collator.getCollationKey(name) },
            )
        }
        .sortedWith(compareBy<SortableContact> { it.isBlankName }.thenBy { it.key })
        .map { it.contact }
}

/**
 * 在已排好序的列表上过滤。过滤保序, 所以结果与"先过滤再排序"完全一致。
 */
private fun filterSortedContacts(sorted: List<IWeContact>, query: String): List<IWeContact> {
    if (query.isEmpty()) return sorted
    return sorted.filter {
        it.displayName.contains(query, ignoreCase = true) ||
                it.wxId.contains(query, ignoreCase = true)
    }
}
