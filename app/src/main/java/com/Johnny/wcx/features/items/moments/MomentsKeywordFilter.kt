package com.Johnny.wcx.features.items.moments

import android.annotation.SuppressLint
import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.ui.WeMomentsApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import com.tencent.mm.plugin.sns.ui.SnsUserUI
import com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI
import com.tencent.mm.view.recyclerview.WxRecyclerView
import dev.ujhhgtg.reflekt.reflekt
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Collections
import java.util.WeakHashMap

@SuppressLint("SetTextI18n")
@Feature(
    name = "朋友圈关键词屏蔽",
    categories = ["朋友圈"],
    description = "按关键词屏蔽朋友圈动态，支持好友范围、白名单、关键词分组、匹配模式"
)
object MomentsKeywordFilter : ClickableFeature() {

    private const val TAG = "MomentsKeywordFilter"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // ==================== 持久化配置 ====================
    private var masterEnabled by prefOption("mkf_master_enabled", false)
    private var keywordGroupsJson by prefOption("mkf_keyword_groups", "[]")
    private var whitelistFriendsJson by prefOption("mkf_whitelist_friends", "[]")
    private var enableLog by prefOption("mkf_enable_log", false)

    // 已挂载的根视图集合
    private val attachedRoots: MutableSet<ViewGroup> = Collections.newSetFromMap(WeakHashMap())

    @Volatile
    private var timelineHooksInstalled = false

    // ==================== 数据模型 ====================

    enum class FriendScope(val value: Int, val description: String) {
        ALL_FRIENDS(0, "全局所有好友"),
        SELECTED_FRIENDS(1, "仅选中好友"),
        EXCLUDED_FRIENDS(2, "排除选中好友")
    }

    enum class MatchMode(val value: Int, val description: String) {
        FUZZY(0, "模糊包含匹配"),
        EXACT(1, "完整精准匹配"),
        REGEX(2, "正则匹配")
    }

    @Serializable
    data class KeywordGroup(
        val id: String = System.currentTimeMillis().toString(),
        val name: String = "默认分组",
        val keywords: Set<String> = emptySet(),
        val friendScope: Int = FriendScope.ALL_FRIENDS.value,
        val scopeFriends: Set<String> = emptySet(),
        val matchMode: Int = MatchMode.FUZZY.value,
        val enabled: Boolean = true
    )

    data class KeywordMatchResult(
        val matched: Boolean,
        val groupName: String = "",
        val keyword: String = "",
        val friendWxId: String = ""
    )

    private fun getKeywordGroups(): List<KeywordGroup> {
        return runCatching {
            json.decodeFromString<List<KeywordGroup>>(keywordGroupsJson)
        }.getOrDefault(emptyList())
    }

    private fun getWhitelistFriends(): Set<String> {
        return runCatching {
            json.decodeFromString<Set<String>>(whitelistFriendsJson)
        }.getOrDefault(emptySet())
    }

    // ==================== 生命周期 ====================

    override fun onEnable() {
        WeLogger.i(TAG, "========== 朋友圈关键词屏蔽: 已开启 ==========")
        installTimelineHooks()
    }

    override fun onDisable() {
        WeLogger.i(TAG, "朋友圈关键词屏蔽: 已关闭")
        attachedRoots.clear()
    }

    private fun installTimelineHooks() {
        if (timelineHooksInstalled) return
        timelineHooksInstalled = true

        try {
            listOf(
                ImproveSnsTimelineUI::class.java,
                SnsUserUI::class.java
            ).forEach { clazz ->
                try {
                    clazz.reflekt()
                        .firstMethod { name = "onCreate" }
                        .hookAfter { scheduleAttach(thisObject as Activity) }
                    clazz.reflekt()
                        .firstMethod { name = "onResume" }
                        .hookAfter { scheduleAttach(thisObject as Activity) }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "Hook ${clazz.simpleName} 生命周期失败", e)
                }
            }
            WeLogger.d(TAG, "时间线页面生命周期 Hook 注册成功")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "时间线 Hook 注册失败", e)
        }
    }

    private fun scheduleAttach(activity: Activity) {
        val root = activity.window?.decorView as? ViewGroup ?: return
        intArrayOf(0, 200, 800, 2_000).forEach { delayMs ->
            root.postDelayed({
                runCatching { attachToTimelineList(root) }
                    .onFailure { WeLogger.w(TAG, "View层挂载失败, delay=${delayMs}ms", it) }
            }, delayMs.toLong())
        }
    }

    private fun attachToTimelineList(root: ViewGroup) {
        val list = root.findViewWhich<ViewGroup> { it is WxRecyclerView } ?: return
        synchronized(attachedRoots) {
            if (!attachedRoots.add(root)) return
        }
        WeLogger.d(TAG, "View层: 挂载时间线 RecyclerView 监听")

        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            filterVisibleFeeds(list)
        }
        list.viewTreeObserver.addOnGlobalLayoutListener {
            filterVisibleFeeds(list)
        }
        list.post { filterVisibleFeeds(list) }
    }

    // ==================== 核心过滤逻辑 ====================

    private fun filterVisibleFeeds(list: ViewGroup) {
        if (!masterEnabled) return
        try {
            var blockedCount = 0
            var checkedCount = 0
            val groups = getKeywordGroups().filter { it.enabled }
            val whitelist = getWhitelistFriends()

            if (groups.isEmpty()) return

            for (i in 0 until list.childCount) {
                val itemView = list.getChildAt(i) ?: continue
                checkedCount++
                try {
                    val snsInfo = locateSnsInfo(itemView) ?: continue
                    if (WeMomentsApi.isAd(snsInfo)) continue // 广告由 RemoveMomentsAds 处理

                    val friendWxId = getFriendWxId(snsInfo) ?: ""
                    val feedContent = getFeedContent(snsInfo)

                    // 白名单检查
                    if (friendWxId in whitelist) {
                        WeLogger.d(TAG, "白名单好友 $friendWxId 跳过屏蔽")
                        continue
                    }

                    // 关键词匹配
                    val result = matchKeywords(groups, feedContent, friendWxId)
                    if (result.matched) {
                        itemView.visibility = View.GONE
                        val lp = itemView.layoutParams
                        if (lp != null) {
                            lp.height = 0
                            itemView.layoutParams = lp
                        }
                        blockedCount++
                        if (enableLog) {
                            WeLogger.i(TAG, "屏蔽朋友圈: 好友=$friendWxId, 分组=${result.groupName}, 命中关键词=${result.keyword}")
                        }
                    }
                } catch (e: Throwable) {
                    WeLogger.d(TAG, "检查 item[$i] 异常，跳过: ${e.message}")
                }
            }
            if (checkedCount > 0) {
                WeLogger.d(TAG, "View层: 检查 $checkedCount 个可见项, 屏蔽 $blockedCount 个")
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "View层过滤异常", e)
        }
    }

    private fun matchKeywords(
        groups: List<KeywordGroup>,
        content: String,
        friendWxId: String
    ): KeywordMatchResult {
        for (group in groups) {
            // 好友范围检查
            if (!checkFriendScope(group, friendWxId)) continue

            // 关键词匹配
            for (keyword in group.keywords) {
                if (keyword.isBlank()) continue
                val matched = when (MatchMode.entries.find { it.value == group.matchMode } ?: MatchMode.FUZZY) {
                    MatchMode.FUZZY -> content.contains(keyword, ignoreCase = true)
                    MatchMode.EXACT -> content.equals(keyword, ignoreCase = true)
                    MatchMode.REGEX -> runCatching {
                        Regex(keyword, setOf(RegexOption.IGNORE_CASE)).containsMatchIn(content)
                    }.getOrDefault(false)
                }
                if (matched) {
                    return KeywordMatchResult(true, group.name, keyword, friendWxId)
                }
            }
        }
        return KeywordMatchResult(false)
    }

    private fun checkFriendScope(group: KeywordGroup, friendWxId: String): Boolean {
        val scope = FriendScope.entries.find { it.value == group.friendScope } ?: FriendScope.ALL_FRIENDS
        return when (scope) {
            FriendScope.ALL_FRIENDS -> true
            FriendScope.SELECTED_FRIENDS -> friendWxId in group.scopeFriends
            FriendScope.EXCLUDED_FRIENDS -> friendWxId !in group.scopeFriends
        }
    }

    // ==================== SnsInfo 工具方法 ====================

    private fun locateSnsInfo(itemView: View): Any? {
        return try {
            val interactionView = itemView.findViewWhich<View> {
                WeMomentsApi.classImproveInteractionLayout.clazz.isInstance(it)
            }
            if (interactionView != null) {
                WeMomentsApi.fieldInteractionSnsInfo.field.get(interactionView)
            } else null
        } catch (e: Throwable) {
            null
        }
    }

    private fun getFriendWxId(snsInfo: Any): String? {
        return try {
            val field = snsInfo.javaClass.declaredFields.find {
                it.name.contains("userName", ignoreCase = true) ||
                        it.name.contains("talker", ignoreCase = true)
            }
            field?.isAccessible = true
            field?.get(snsInfo)?.toString()
        } catch (e: Throwable) {
            null
        }
    }

    private fun getFeedContent(snsInfo: Any): String {
        return try {
            val sb = StringBuilder()
            // 尝试获取 feedDesc
            try {
                val descField = snsInfo.javaClass.declaredFields.find {
                    it.name.contains("feedDesc", ignoreCase = true) ||
                            it.name.contains("content", ignoreCase = true) ||
                            it.name.contains("text", ignoreCase = true)
                }
                descField?.isAccessible = true
                descField?.get(snsInfo)?.toString()?.let { sb.append(it).append(" ") }
            } catch (_: Throwable) {}

            // 尝试获取昵称
            try {
                val nickField = snsInfo.javaClass.declaredFields.find {
                    it.name.contains("nickName", ignoreCase = true)
                }
                nickField?.isAccessible = true
                nickField?.get(snsInfo)?.toString()?.let { sb.append(it).append(" ") }
            } catch (_: Throwable) {}

            sb.toString().trim()
        } catch (e: Throwable) {
            ""
        }
    }

    // ==================== UI ====================

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var localMasterEnabled by remember { mutableStateOf(masterEnabled) }
            var localGroups by remember { mutableStateOf(getKeywordGroups().toMutableList()) }
            var localWhitelist by remember { mutableStateOf(getWhitelistFriends().toMutableSet()) }
            var localEnableLog by remember { mutableStateOf(enableLog) }

            var showGroupEditor by remember { mutableStateOf(false) }
            var editingGroup by remember { mutableStateOf<KeywordGroup?>(null) }
            var showWhitelistSelector by remember { mutableStateOf(false) }
            var showFriendSelector by remember { mutableStateOf(false) }
            var friendSelectorForGroup by remember { mutableStateOf<KeywordGroup?>(null) }
            var friendSelectorOnSave by remember { mutableStateOf<((Set<String>) -> Unit)?>(null) }
            var showImportExport by remember { mutableStateOf(false) }

            if (showGroupEditor) {
                KeywordGroupEditorDialog(
                    existing = editingGroup,
                    onDismiss = { showGroupEditor = false; editingGroup = null },
                    onSave = { group ->
                        val idx = localGroups.indexOfFirst { it.id == group.id }
                        if (idx >= 0) localGroups[idx] = group
                        else localGroups.add(group)
                        showGroupEditor = false
                        editingGroup = null
                    },
                    onOpenFriendSelector = { group, onSaveCb ->
                        friendSelectorForGroup = group
                        friendSelectorOnSave = onSaveCb
                        showFriendSelector = true
                    }
                )
            } else if (showFriendSelector && friendSelectorForGroup != null) {
                FriendSelectorScreen(
                    title = "选择好友",
                    onDismiss = { showFriendSelector = false },
                    onSave = { selectedFriends ->
                        friendSelectorOnSave?.invoke(selectedFriends)
                        showFriendSelector = false
                    }
                )
            } else if (showWhitelistSelector) {
                FriendSelectorScreen(
                    title = "选择白名单好友",
                    onDismiss = { showWhitelistSelector = false },
                    onSave = { selectedFriends ->
                        localWhitelist = selectedFriends
                        showWhitelistSelector = false
                    }
                )
            } else if (showImportExport) {
                ImportExportDialog(
                    groups = localGroups,
                    onDismiss = { showImportExport = false },
                    onImport = { imported ->
                        localGroups = imported.toMutableList()
                        showImportExport = false
                    }
                )
            } else {
                AlertDialogContent(
                    title = { Text("朋友圈关键词屏蔽") },
                    text = {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        ) {
                            // 总开关
                            ListItem(
                                modifier = Modifier.clickable { localMasterEnabled = !localMasterEnabled },
                                trailingContent = {
                                    Switch(checked = localMasterEnabled, onCheckedChange = null)
                                },
                                headlineContent = { Text("启用朋友圈关键词屏蔽", fontWeight = FontWeight.SemiBold) },
                                supportingContent = { Text("关闭后所有关键词过滤规则失效") }
                            )

                            if (localMasterEnabled) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // 关键词分组
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Text("关键词分组", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Row {
                                        TextButton(onClick = { showImportExport = true }) {
                                            Text("导入/导出")
                                        }
                                        TextButton(onClick = {
                                            editingGroup = null
                                            showGroupEditor = true
                                        }) {
                                            Text("+ 添加")
                                        }
                                    }
                                }

                                if (localGroups.isEmpty()) {
                                    Text(
                                        "暂无关键词分组，点击上方按钮添加",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                } else {
                                    localGroups.forEach { group ->
                                        ListItem(
                                            modifier = Modifier.clickable {
                                                editingGroup = group
                                                showGroupEditor = true
                                            },
                                            headlineContent = { Text(group.name) },
                                            supportingContent = {
                                                Text(
                                                    "${group.keywords.size} 个关键词 · " +
                                                            (FriendScope.entries.find { it.value == group.friendScope }?.description ?: "全局") +
                                                            " · ${if (group.enabled) "启用" else "禁用"}"
                                                )
                                            },
                                            trailingContent = {
                                                Row {
                                                    Switch(
                                                        checked = group.enabled,
                                                        onCheckedChange = { enabled ->
                                                            val idx = localGroups.indexOf(group)
                                                            if (idx >= 0) {
                                                                localGroups[idx] = group.copy(enabled = enabled)
                                                            }
                                                        }
                                                    )
                                                    IconButton(onClick = {
                                                        localGroups.remove(group)
                                                    }) {
                                                        Icon(
                                                            imageVector = MaterialSymbols.Outlined.Delete,
                                                            contentDescription = "删除",
                                                            tint = MaterialTheme.colorScheme.error
                                                        )
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // 一键去重
                                TextButton(
                                    onClick = {
                                        localGroups = localGroups.map { group ->
                                            group.copy(keywords = group.keywords.map { it.trim() }.filter { it.isNotBlank() }.toSet())
                                        }.toMutableList()
                                        showToast("已去重所有分组关键词")
                                    }
                                ) {
                                    Text("一键去重冗余关键词")
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // 白名单
                                ListItem(
                                    modifier = Modifier.clickable { showWhitelistSelector = true },
                                    headlineContent = { Text("好友白名单") },
                                    supportingContent = {
                                        Text(
                                            if (localWhitelist.isEmpty()) "白名单为空，所有好友均受屏蔽规则影响"
                                            else "已选 ${localWhitelist.size} 个好友，白名单好友内容不屏蔽"
                                        )
                                    }
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // 日志
                                ListItem(
                                    modifier = Modifier.clickable { localEnableLog = !localEnableLog },
                                    trailingContent = {
                                        Switch(checked = localEnableLog, onCheckedChange = null)
                                    },
                                    headlineContent = { Text("记录屏蔽日志") },
                                    supportingContent = { Text("开启后记录屏蔽触发的好友、命中关键词、规则生效范围") }
                                )
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismiss) { Text("取消") }
                    },
                    confirmButton = {
                        Button(onClick = {
                            masterEnabled = localMasterEnabled
                            keywordGroupsJson = json.encodeToString(localGroups)
                            whitelistFriendsJson = json.encodeToString(localWhitelist)
                            enableLog = localEnableLog
                            showToast("设置已保存")
                            onDismiss()
                        }) { Text("保存") }
                    }
                )
            }
        }
    }

    // ==================== 关键词分组编辑弹窗 ====================

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun KeywordGroupEditorDialog(
        existing: KeywordGroup?,
        onDismiss: () -> Unit,
        onSave: (KeywordGroup) -> Unit,
        onOpenFriendSelector: (KeywordGroup, (Set<String>) -> Unit) -> Unit
    ) {
        val isEditing = existing != null
        var groupName by remember { mutableStateOf(existing?.name ?: "") }
        var keywordsText by remember {
            mutableStateOf(existing?.keywords?.joinToString("\n") ?: "")
        }
        var friendScope by remember { mutableStateOf(existing?.friendScope ?: FriendScope.ALL_FRIENDS.value) }
        var scopeFriends by remember { mutableStateOf(existing?.scopeFriends?.toMutableSet() ?: mutableSetOf()) }
        var matchMode by remember { mutableStateOf(existing?.matchMode ?: MatchMode.FUZZY.value) }
        var enabled by remember { mutableStateOf(existing?.enabled ?: true) }

        AlertDialogContent(
            title = { Text(if (isEditing) "编辑关键词分组" else "添加关键词分组") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = { Text("分组名称") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = keywordsText,
                        onValueChange = { keywordsText = it },
                        label = { Text("关键词（每行一个）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        placeholder = { Text("每行输入一个关键词") }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 匹配模式
                    Text("匹配模式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    MatchMode.entries.forEach { mode ->
                        ListItem(
                            modifier = Modifier.clickable { matchMode = mode.value },
                            trailingContent = {
                                Text(if (matchMode == mode.value) "✓" else "")
                            },
                            headlineContent = { Text(mode.description) }
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 好友范围
                    Text("好友范围", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FriendScope.entries.forEach { scope ->
                        ListItem(
                            modifier = Modifier.clickable { friendScope = scope.value },
                            trailingContent = {
                                Text(if (friendScope == scope.value) "✓" else "")
                            },
                            headlineContent = { Text(scope.description) },
                            supportingContent = {
                                when (scope) {
                                    FriendScope.SELECTED_FRIENDS -> Text("已选 ${scopeFriends.size} 个好友")
                                    FriendScope.EXCLUDED_FRIENDS -> Text("已选 ${scopeFriends.size} 个好友")
                                    else -> Text("")
                                }
                            }
                        )
                    }

                    if (friendScope == FriendScope.SELECTED_FRIENDS.value || friendScope == FriendScope.EXCLUDED_FRIENDS.value) {
                        ListItem(
                            modifier = Modifier.clickable {
                                val tempGroup = KeywordGroup(
                                    id = existing?.id ?: System.currentTimeMillis().toString(),
                                    name = groupName,
                                    friendScope = friendScope,
                                    scopeFriends = scopeFriends,
                                    matchMode = matchMode,
                                    enabled = enabled
                                )
                                onOpenFriendSelector(tempGroup) { selected ->
                                    scopeFriends = selected.toMutableSet()
                                }
                            },
                            headlineContent = {
                                Text(
                                    if (friendScope == FriendScope.SELECTED_FRIENDS.value) "选择好友"
                                    else "选择排除好友"
                                )
                            },
                            supportingContent = { Text("当前已选 ${scopeFriends.size} 个好友") }
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 启用开关
                    ListItem(
                        modifier = Modifier.clickable { enabled = !enabled },
                        trailingContent = {
                            Switch(checked = enabled, onCheckedChange = null)
                        },
                        headlineContent = { Text("启用此分组") }
                    )
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            confirmButton = {
                Button(onClick = {
                    if (groupName.isBlank()) {
                        showToast("请输入分组名称")
                        return@Button
                    }
                    val keywords = keywordsText.lines()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .toSet()
                    if (keywords.isEmpty()) {
                        showToast("请至少输入一个关键词")
                        return@Button
                    }
                    val group = KeywordGroup(
                        id = existing?.id ?: System.currentTimeMillis().toString(),
                        name = groupName,
                        keywords = keywords,
                        friendScope = friendScope,
                        scopeFriends = scopeFriends,
                        matchMode = matchMode,
                        enabled = enabled
                    )
                    onSave(group)
                }) { Text(if (isEditing) "保存" else "添加") }
            }
        )
    }

    // ==================== 好友选择弹窗（带搜索） ====================

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun FriendSelectorScreen(
        title: String,
        onDismiss: () -> Unit,
        onSave: (Set<String>) -> Unit
    ) {
        val friends = remember {
            WeDatabaseApi.getFriends()
        }
        val selected = remember { mutableStateOf(mutableSetOf<String>()) }
        val listState = rememberLazyListState()
        var searchQuery by remember { mutableStateOf("") }

        val filteredFriends = remember(searchQuery, friends) {
            if (searchQuery.isBlank()) friends
            else friends.filter { friend ->
                friend.nickname.contains(searchQuery, ignoreCase = true) ||
                        (friend.remark?.contains(searchQuery, ignoreCase = true) == true) ||
                        friend.wxId.contains(searchQuery, ignoreCase = true)
            }
        }

        AlertDialogContent(
            title = { Text(title) },
            text = {
                DefaultColumn {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜索昵称/备注/微信号") },
                        singleLine = true
                    )

                    if (filteredFriends.isEmpty()) {
                        Text(
                            "无匹配的好友",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.heightIn(max = 400.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(filteredFriends, key = { it.wxId }) { friend ->
                                val isSelected = remember(selected.value) {
                                    mutableStateOf(selected.value.contains(friend.wxId))
                                }
                                ListItem(
                                    modifier = Modifier.clickable {
                                        isSelected.value = !isSelected.value
                                        if (isSelected.value) {
                                            selected.value.add(friend.wxId)
                                        } else {
                                            selected.value.remove(friend.wxId)
                                        }
                                    },
                                    headlineContent = {
                                        Text(friend.remark?.takeIf { it.isNotBlank() } ?: friend.nickname)
                                    },
                                    supportingContent = {
                                        Text(
                                            if (friend.remark?.takeIf { it.isNotBlank() } != null) "昵称: ${friend.nickname} · ${friend.wxId}"
                                            else friend.wxId
                                        )
                                    },
                                    trailingContent = {
                                        Text(if (isSelected.value) "✓" else "")
                                    }
                                )
                            }
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            confirmButton = {
                Button(onClick = {
                    onSave(selected.value)
                }) { Text("保存") }
            }
        )
    }

    // ==================== 导入导出弹窗 ====================

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ImportExportDialog(
        groups: List<KeywordGroup>,
        onDismiss: () -> Unit,
        onImport: (List<KeywordGroup>) -> Unit
    ) {
        var exportText by remember {
            mutableStateOf(json.encodeToString(groups))
        }
        var importText by remember { mutableStateOf("") }

        AlertDialogContent(
            title = { Text("导入/导出关键词") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text("导出", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = exportText,
                        onValueChange = { exportText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        readOnly = false,
                        label = { Text("当前配置 JSON") }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("导入", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        placeholder = { Text("粘贴 JSON 配置") }
                    )
                    TextButton(
                        onClick = {
                            val imported = runCatching {
                                json.decodeFromString<List<KeywordGroup>>(importText)
                            }.getOrNull()
                            if (imported != null) {
                                onImport(imported)
                                showToast("导入成功")
                            } else {
                                showToast("JSON 格式错误")
                            }
                        }
                    ) {
                        Text("执行导入")
                    }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            confirmButton = {
                Button(onClick = onDismiss) { Text("关闭") }
            }
        )
    }
}

// 辅助方法，用于在 ViewGroup 中查找指定类型的子 View
private fun ViewGroup.findViewWhich(predicate: (View) -> Boolean): View? {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (predicate(child)) return child
        if (child is ViewGroup) {
            val found = child.findViewWhich(predicate)
            if (found != null) return found
        }
    }
    return null
}