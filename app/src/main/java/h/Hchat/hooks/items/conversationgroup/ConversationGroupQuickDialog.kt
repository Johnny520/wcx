package h.Hchat.hooks.items.conversationgroup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import h.Hchat.ui.miuix.EmbeddedComposeOwnerInstaller
import h.Hchat.utils.HLog
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

internal object ConversationGroupQuickDialog {
    private const val TAG = "[Hchat:ConversationGroup]"
    private val active = Collections.synchronizedMap(WeakHashMap<Activity, WeakReference<OverlayHandle>>())
    private val main = Handler(Looper.getMainLooper())

    fun show(activity: Activity, talker: String, onChanged: () -> Unit) {
        val normalizedTalker = talker.trim()
        if (normalizedTalker.isBlank() || activity.isFinishing || activity.isDestroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread { show(activity, normalizedTalker, onChanged) }
            return
        }
        val target = resolveTarget(activity, normalizedTalker) ?: return
        active.remove(activity)?.get()?.close()
        lateinit var handle: OverlayHandle
        handle = showOverlay(activity) { close ->
            QuickDialogContent(
                activity = activity,
                target = target,
                close = close,
                onChanged = onChanged,
                onOpenAgain = { show(activity, normalizedTalker, onChanged) }
            )
        }
        if (handle.isShowing()) active[activity] = WeakReference(handle)
    }

    fun close(activity: Activity) {
        val action = {
            active.remove(activity)?.get()?.close()
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else activity.runOnUiThread(action)
    }

    fun cancelPendingDocuments() {
        ConversationGroupDocumentBridge.cancelAll()
    }

    fun closeAll() {
        val action = {
            val handles = synchronized(active) {
                active.values.mapNotNull { it.get() }.also { active.clear() }
            }
            handles.forEach(OverlayHandle::close)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private fun resolveTarget(activity: Activity, talker: String): Target? {
        if (!ConversationGroupRuntime.isVirtualTalker(talker)) return Target.Conversation(talker)
        val group = ConversationGroupStore.load(activity)
            .firstOrNull { ConversationGroupRuntime.virtualTalker(it.id) == talker }
        if (group == null) {
            Toast.makeText(activity, "聊天分组不存在", Toast.LENGTH_SHORT).show()
            return null
        }
        return Target.Group(group.id)
    }

    @Composable
    private fun QuickDialogContent(
        activity: Activity,
        target: Target,
        close: () -> Unit,
        onChanged: () -> Unit,
        onOpenAgain: () -> Unit
    ) {
        var groups by remember(target) { mutableStateOf(ConversationGroupStore.load(activity)) }
        var page by remember(target) { mutableStateOf(Page.MAIN) }
        var query by remember(target) { mutableStateOf("") }
        var selectedId by remember(target, groups) {
            mutableStateOf(target.currentGroupId(groups))
        }
        var addName by remember(target) { mutableStateOf("") }
        var addParentId by remember(target) { mutableStateOf<String?>(null) }
        var addPinned by remember(target) { mutableStateOf(false) }
        var deleteIds by remember(target) { mutableStateOf<Set<String>>(emptySet()) }

        fun notifyChanged() {
            runCatching(onChanged).onFailure {
                HLog.e("$TAG 刷新聊天分组失败: ${it.message}", it)
            }
        }

        fun reload() {
            groups = ConversationGroupStore.load(activity)
            selectedId = target.currentGroupId(groups)
            deleteIds = deleteIds.filterTo(linkedSetOf()) { id -> groups.any { it.id == id } }
        }

        fun toast(message: String) {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }

        fun delete(selected: Set<String>) {
            val targetRemoved = target is Target.Group && target.groupId in selected
            val success = ConversationGroupStore.deleteGroups(activity, selected)
            toast(if (success) "聊天分组已删除" else "删除聊天分组失败")
            if (!success) return
            notifyChanged()
            if (targetRemoved) {
                close()
            } else {
                reload()
                page = Page.MANAGE
            }
        }

        WindowDialog(
            show = true,
            title = "聊天分组",
            onDismissRequest = close,
            content = {
                when (page) {
                    Page.MAIN -> MainPage(
                        groups = groups,
                        target = target,
                        query = query,
                        selectedId = selectedId,
                        onQueryChange = { query = it },
                        onSelect = { selectedId = it },
                        onManage = { page = Page.MANAGE },
                        onCancel = close,
                        onConfirm = {
                            val success = when (target) {
                                is Target.Conversation -> ConversationGroupStore.setConversationGroup(
                                    activity,
                                    target.talker,
                                    selectedId
                                )
                                is Target.Group -> ConversationGroupStore.moveGroup(
                                    activity,
                                    target.groupId,
                                    selectedId
                                )
                            }
                            toast(if (success) "聊天分组已更新" else "更新聊天分组失败")
                            if (success) {
                                notifyChanged()
                                close()
                            }
                        }
                    )
                    Page.MANAGE -> ManagePage(
                        groups = groups,
                        onAdd = {
                            addName = ""
                            addParentId = null
                            addPinned = false
                            page = Page.ADD
                        },
                        onDeleteAll = { if (groups.isNotEmpty()) page = Page.CONFIRM_DELETE_ALL },
                        onDeleteMultiple = {
                            deleteIds = emptySet()
                            page = Page.DELETE_MULTIPLE
                        },
                        onImport = { page = Page.CONFIRM_IMPORT },
                        onExport = {
                            val json = ConversationGroupStore.exportCurrentAccount(activity)
                            if (json == null) {
                                toast("导出聊天分组失败")
                            } else {
                                close()
                                ConversationGroupDocumentBridge.launchExport(activity, json) { result ->
                                    if (result.message.isNotBlank()) toast(result.message)
                                    onOpenAgain()
                                }
                            }
                        },
                        onBack = { page = Page.MAIN }
                    )
                    Page.ADD -> AddPage(
                        groups = groups,
                        name = addName,
                        parentId = addParentId,
                        pinned = addPinned,
                        onNameChange = { addName = it.take(50) },
                        onParentSelected = { addParentId = it },
                        onPinnedChange = { addPinned = it },
                        onCancel = { page = Page.MANAGE },
                        onAdd = add@{
                            val name = addName.trim()
                            if (name.isBlank()) {
                                toast("请输入分组名称")
                                return@add
                            }
                            if (groups.any {
                                    it.parentId == addParentId && it.name.equals(name, ignoreCase = true)
                                }
                            ) {
                                toast("同一层级已存在同名分组")
                                return@add
                            }
                            val group = ConversationGroupStore.newGroup(addParentId).copy(
                                name = name,
                                pinned = addPinned
                            )
                            val success = ConversationGroupStore.addGroup(activity, group)
                            toast(if (success) "聊天分组已添加" else "添加聊天分组失败")
                            if (success) {
                                notifyChanged()
                                reload()
                                page = Page.MANAGE
                            }
                        }
                    )
                    Page.DELETE_MULTIPLE -> DeleteMultiplePage(
                        groups = groups,
                        selectedIds = deleteIds,
                        onToggle = { id ->
                            deleteIds = if (id in deleteIds) deleteIds - id else deleteIds + id
                        },
                        onCancel = { page = Page.MANAGE },
                        onContinue = {
                            if (deleteIds.isEmpty()) toast("请至少选择一个分组")
                            else page = Page.CONFIRM_DELETE_MULTIPLE
                        }
                    )
                    Page.CONFIRM_DELETE_ALL -> ConfirmPage(
                        message = "将删除当前账号的全部聊天分组。归拢的会话会恢复到微信首页。",
                        confirmText = "全部删除",
                        onCancel = { page = Page.MANAGE },
                        onConfirm = { delete(groups.mapTo(linkedSetOf()) { it.id }) }
                    )
                    Page.CONFIRM_DELETE_MULTIPLE -> ConfirmPage(
                        message = "将删除选中的 ${deleteIds.size} 个分组。子分组会上移，直属会话会移交给最近的上级分组；没有上级时恢复到微信首页。",
                        confirmText = "删除",
                        onCancel = { page = Page.DELETE_MULTIPLE },
                        onConfirm = { delete(deleteIds) }
                    )
                    Page.CONFIRM_IMPORT -> ConfirmPage(
                        message = "导入会先校验文件，再覆盖当前账号的聊天分组；其他微信账号不受影响。",
                        confirmText = "选择文件",
                        onCancel = { page = Page.MANAGE },
                        onConfirm = {
                            close()
                            ConversationGroupDocumentBridge.launchImport(activity) { result ->
                                if (result.message.isNotBlank()) toast(result.message)
                                if (result.changed) notifyChanged()
                                onOpenAgain()
                            }
                        }
                    )
                }
            }
        )
    }

    @Composable
    private fun MainPage(
        groups: List<ConversationGroup>,
        target: Target,
        query: String,
        selectedId: String?,
        onQueryChange: (String) -> Unit,
        onSelect: (String?) -> Unit,
        onManage: () -> Unit,
        onCancel: () -> Unit,
        onConfirm: () -> Unit
    ) {
        val candidates = remember(groups, target) { target.candidates(groups) }
        val lower = query.trim().lowercase(Locale.US)
        val visible = candidates.filter { row ->
            lower.isEmpty() || row.name.lowercase(Locale.US).contains(lower) ||
                row.path.lowercase(Locale.US).contains(lower)
        }
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = screenHeight * 0.78f)) {
            Text(
                text = when (target) {
                    is Target.Conversation -> "选择当前会话所属分组"
                    is Target.Group -> "移动分组：${groups.firstOrNull { it.id == target.groupId }?.name.orEmpty()}"
                },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            )
            ActionRow(
                title = "管理聊天分组",
                summary = "新建、删除、导入与导出",
                onClick = onManage
            )
            SearchBox(query, onQueryChange)
            if (visible.isEmpty()) {
                Text(
                    text = if (groups.isEmpty()) "暂无聊天分组" else "没有匹配的聊天分组",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 360.dp)) {
                    visible.forEach { row ->
                        item(key = row.key) {
                            GroupChoiceRow(
                                title = row.name,
                                summary = row.path,
                                depth = row.depth,
                                selected = selectedId == row.id,
                                onClick = {
                                    if (target is Target.Conversation && selectedId == row.id) {
                                        onSelect(null)
                                    } else {
                                        onSelect(row.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
            DialogButtons("取消", onCancel, "确定", onConfirm, primary = true)
        }
    }

    @Composable
    private fun ManagePage(
        groups: List<ConversationGroup>,
        onAdd: () -> Unit,
        onDeleteAll: () -> Unit,
        onDeleteMultiple: () -> Unit,
        onImport: () -> Unit,
        onExport: () -> Unit,
        onBack: () -> Unit
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            SectionTitle("管理聊天分组")
            ActionRow("新建分组", "创建一级或多级聊天分组", onAdd)
            ActionRow(
                "删除多个",
                "选择一个或多个聊天分组删除",
                onDeleteMultiple,
                enabled = groups.isNotEmpty()
            )
            ActionRow("导入", "从聊天分组文件恢复配置", onImport)
            ActionRow("导出", "将当前账号的聊天分组保存到文件", onExport)
            ActionRow(
                "全部删除",
                "删除当前账号的全部聊天分组",
                onDeleteAll,
                enabled = groups.isNotEmpty()
            )
            TextButton(
                text = "返回",
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
    }

    @Composable
    private fun AddPage(
        groups: List<ConversationGroup>,
        name: String,
        parentId: String?,
        pinned: Boolean,
        onNameChange: (String) -> Unit,
        onParentSelected: (String?) -> Unit,
        onPinnedChange: (Boolean) -> Unit,
        onCancel: () -> Unit,
        onAdd: () -> Unit
    ) {
        val rows = remember(groups) { flattenGroups(groups) }
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 580.dp)) {
            SectionTitle("添加聊天分组")
            DialogInput(name, "分组名称", onNameChange)
            GroupChoiceRow(
                title = "主页置顶",
                summary = "新建后显示在当前层级的置顶分组区域",
                depth = 0,
                selected = pinned
            ) { onPinnedChange(!pinned) }
            Text(
                text = "上级分组",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 280.dp)) {
                item(key = "root") {
                    GroupChoiceRow("微信首页", "作为一级分组", 0, parentId == null) {
                        onParentSelected(null)
                    }
                }
                rows.forEach { row ->
                    item(key = row.key) {
                        GroupChoiceRow(row.name, row.path, row.depth, parentId == row.id) {
                            onParentSelected(row.id)
                        }
                    }
                }
            }
            DialogButtons("取消", onCancel, "添加", onAdd)
        }
    }

    @Composable
    private fun DeleteMultiplePage(
        groups: List<ConversationGroup>,
        selectedIds: Set<String>,
        onToggle: (String) -> Unit,
        onCancel: () -> Unit,
        onContinue: () -> Unit
    ) {
        val rows = remember(groups) { flattenGroups(groups) }
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 580.dp)) {
            SectionTitle("删除多个")
            Text(
                text = "已选择 ${selectedIds.size} 个分组",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 380.dp)) {
                rows.forEach { row ->
                    item(key = row.key) {
                        GroupChoiceRow(
                            row.name,
                            row.path,
                            row.depth,
                            row.id in selectedIds
                        ) { onToggle(row.id.orEmpty()) }
                    }
                }
            }
            DialogButtons("取消", onCancel, "继续", onContinue)
        }
    }

    @Composable
    private fun ConfirmPage(
        message: String,
        confirmText: String,
        onCancel: () -> Unit,
        onConfirm: () -> Unit
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = message,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
            DialogButtons("取消", onCancel, confirmText, onConfirm)
        }
    }

    @Composable
    private fun SearchBox(value: String, onValueChange: (String) -> Unit) {
        DialogInput(value, "搜索聊天分组", onValueChange, Modifier.padding(vertical = 8.dp))
    }

    @Composable
    private fun DialogInput(
        value: String,
        placeholder: String,
        onValueChange: (String) -> Unit,
        modifier: Modifier = Modifier
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 15.sp),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.secondaryVariant)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            decorationBox = { field ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 15.sp
                        )
                    }
                    field()
                }
            }
        )
    }

    @Composable
    private fun SectionTitle(value: String) {
        Text(
            text = value,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        )
    }

    @Composable
    private fun GroupChoiceRow(
        title: String,
        summary: String,
        depth: Int,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(start = (10 + depth.coerceAtMost(6) * 14).dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                if (summary.isNotBlank() && summary != title) {
                    Text(
                        text = summary,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp
                    )
                }
            }
            Checkbox(
                modifier = Modifier.size(22.dp),
                state = if (selected) ToggleableState.On else ToggleableState.Off,
                onClick = null
            )
        }
    }

    @Composable
    private fun ActionRow(
        title: String,
        summary: String,
        onClick: () -> Unit,
        enabled: Boolean = true
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (enabled) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(
                        alpha = if (enabled) 1f else 0.45f
                    ),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }

    @Composable
    private fun DialogButtons(
        secondaryText: String,
        onSecondary: () -> Unit,
        primaryText: String,
        onPrimary: () -> Unit,
        primary: Boolean = true
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DialogActionButton(secondaryText, onSecondary, Modifier.weight(1f))
            DialogActionButton(primaryText, onPrimary, Modifier.weight(1f), primary)
        }
    }

    @Composable
    private fun DialogActionButton(
        text: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        primary: Boolean = false
    ) {
        Box(
            modifier = modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (primary) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.secondaryVariant
                    }
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (primary) {
                    androidx.compose.ui.graphics.Color.White
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }

    private fun Target.currentGroupId(groups: List<ConversationGroup>): String? {
        return when (this) {
            is Target.Conversation -> ConversationGroupStore.conversationOwner(groups, talker)
            is Target.Group -> groups.firstOrNull { it.id == groupId }?.parentId
        }
    }

    private fun Target.candidates(groups: List<ConversationGroup>): List<GroupRow> {
        val rows = flattenGroups(groups)
        if (this is Target.Conversation) return rows
        val targetGroupId = (this as Target.Group).groupId
        val excluded = ConversationGroupStore.descendantIds(groups, targetGroupId) + targetGroupId
        return listOf(GroupRow(null, "微信首页", "作为一级分组", 0)) +
            rows.filterNot { row -> row.id != null && row.id in excluded }
    }

    private fun flattenGroups(groups: List<ConversationGroup>): List<GroupRow> {
        val normalized = ConversationGroupStore.normalize(groups)
        val children = normalized.groupBy { it.parentId }
        val result = arrayListOf<GroupRow>()
        val visited = hashSetOf<String>()

        fun visit(parentId: String?, depth: Int, path: List<String>) {
            children[parentId].orEmpty().sortedBy { it.order }.forEach { group ->
                if (!visited.add(group.id)) return@forEach
                val nextPath = path + group.name
                result += GroupRow(group.id, group.name, nextPath.joinToString(" / "), depth)
                visit(group.id, depth + 1, nextPath)
            }
        }
        visit(null, 0, emptyList())
        return result
    }

    private fun showOverlay(
        activity: Activity,
        content: @Composable (() -> Unit) -> Unit
    ): OverlayHandle {
        val decor = activity.window?.decorView as? ViewGroup ?: return EmptyHandle
        val owner = DialogComposeOwner()
        val closed = AtomicBoolean(false)
        val root = FrameLayout(activity).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        owner.install(decor)
        owner.install(root)
        owner.attach()
        lateinit var compose: ComposeView
        val closeDialog: () -> Unit = close@{
            if (!closed.compareAndSet(false, true)) return@close
            val cleanup: () -> Unit = {
                runCatching { compose.disposeComposition() }
                runCatching { (root.parent as? ViewGroup)?.removeView(root) }
                root.tag = null
                runCatching { owner.clear(root) }
                runCatching { owner.clear(compose) }
                runCatching { owner.clear(decor) }
                runCatching { owner.destroy() }
                active.remove(activity)
            }
            if (Looper.myLooper() == Looper.getMainLooper()) cleanup()
            else activity.runOnUiThread(cleanup)
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                closeDialog()
            }
        })
        compose = ComposeView(activity).apply {
            owner.install(this)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                    DialogTheme(activity) { content(closeDialog) }
                }
            }
        }
        root.addView(
            compose,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val handle = object : OverlayHandle {
            override fun close() = closeDialog()
            override fun isShowing(): Boolean = !closed.get()
        }
        root.tag = handle
        decor.addView(root)
        root.requestFocus()
        return handle
    }

    @Composable
    private fun DialogTheme(context: Context, content: @Composable () -> Unit) {
        val colors = if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES
        ) darkColorScheme() else lightColorScheme()
        MiuixTheme(colors = colors, content = content)
    }

    private sealed class Target {
        data class Conversation(val talker: String) : Target()
        data class Group(val groupId: String) : Target()
    }

    private enum class Page {
        MAIN,
        MANAGE,
        ADD,
        DELETE_MULTIPLE,
        CONFIRM_DELETE_ALL,
        CONFIRM_DELETE_MULTIPLE,
        CONFIRM_IMPORT
    }

    private data class GroupRow(
        val id: String?,
        val name: String,
        val path: String,
        val depth: Int
    ) {
        val key: String get() = id ?: "__wechat_home__"
    }

    private interface OverlayHandle {
        fun close()
        fun isShowing(): Boolean
    }

    private object EmptyHandle : OverlayHandle {
        override fun close() = Unit
        override fun isShowing(): Boolean = false
    }

    private class DialogComposeOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner,
        NavigationEventDispatcherOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()
        private val navigationDispatcher = NavigationEventDispatcher()
        private var restored = false

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store
        override val navigationEventDispatcher: NavigationEventDispatcher get() = navigationDispatcher

        fun install(view: View) {
            EmbeddedComposeOwnerInstaller.install(view, this, this, this, this)
        }

        fun clear(view: View) {
            EmbeddedComposeOwnerInstaller.clear(view)
        }

        fun attach() {
            if (!restored) {
                savedStateRegistryController.performRestore(Bundle.EMPTY)
                restored = true
            }
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            }
            navigationDispatcher.dispose()
            store.clear()
        }
    }
}

private object ConversationGroupDocumentBridge {
    private const val TAG = "[Hchat:ConversationGroup]"
    private const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
    private const val REQUEST_CODE_START = 0x7510
    private const val REQUEST_CODE_END = 0x75ff
    private val nextRequestCode = AtomicInteger(REQUEST_CODE_START)
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val requests = ConcurrentHashMap<Int, ConversationGroupFileRequest<DocumentResult>>()
    private val hooks = ConversationGroupFileResultHooks(::onResult, ::onHostFailure)

    fun launchExport(activity: Activity, json: String, callback: (DocumentResult) -> Unit) {
        val fileName = "Hchat_chat_groups_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".json"
        launch(activity, Operation.Export(json), Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.preferSystemDocumentsUi(activity), callback)
    }

    fun launchImport(activity: Activity, callback: (DocumentResult) -> Unit) {
        launch(activity, Operation.Import(ConversationGroupStore.accountKey()), Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.preferSystemDocumentsUi(activity), callback) {
            Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "选择聊天分组文件")
        }
    }

    private fun launch(
        activity: Activity,
        operation: Operation,
        intent: Intent,
        callback: (DocumentResult) -> Unit,
        fallback: (() -> Intent)? = null
    ): Unit = synchronized(hooks) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!hooks.ensure(activity.javaClass)) {
            cancelAll()
            callback(DocumentResult("无法接收文件选择结果"))
            return
        }
        val requestCode = allocateRequestCode()
        val request = ConversationGroupFileRequest.create(activity, callback) {
            synchronized(hooks) {
                pending.remove(requestCode)
                requests.remove(requestCode)
                if (pending.isEmpty()) hooks.clear()
            }
        }
        if (request == null) {
            if (pending.isEmpty()) hooks.clear()
            callback(DocumentResult("聊天分组文件任务较多，请稍后重试"))
            return
        }
        requests[requestCode] = request
        pending[requestCode] = Pending(request, operation)
        runCatching { activity.startActivityForResult(intent, requestCode) }.onFailure { firstError ->
            val fallbackIntent = runCatching { fallback?.invoke() }.getOrNull()
            val secondError = if (fallbackIntent == null) firstError
                else runCatching { activity.startActivityForResult(fallbackIntent, requestCode) }.exceptionOrNull()
            if (secondError != null) {
                pending.remove(requestCode)
                request.deliver(DocumentResult("当前系统不支持选择文件"))
                HLog.e("$TAG 启动文档选择器失败: ${secondError.message}", secondError)
            }
        }
    }

    fun cancelAll() = synchronized(hooks) {
        requests.values.toList().forEach { it.cancel() }
        pending.clear()
        hooks.clear()
    }

    private fun allocateRequestCode(): Int {
        repeat(REQUEST_CODE_END - REQUEST_CODE_START + 1) {
            val candidate = nextRequestCode.updateAndGet { current ->
                if (current >= REQUEST_CODE_END) REQUEST_CODE_START else current + 1
            }
            if (!requests.containsKey(candidate)) return candidate
        }
        error("文件选择请求已满")
    }

    private fun onHostFailure(activity: Activity, code: Int) {
        val value = pending[code] ?: return
        if (value.request.activity.get() === activity && pending.remove(code, value)) value.request.cancel()
    }

    private fun onResult(activity: Activity, code: Int, resultCode: Int, data: Intent?) {
        val value = pending[code] ?: return
        val request = value.request
        if (request.activity.get() !== activity || !pending.remove(code, value)) return
        try {
            val uri = data?.data
            if (resultCode != Activity.RESULT_OK || uri == null) {
                request.deliver(DocumentResult(""))
                return
            }
            val context = activity.applicationContext
            val operation = value.operation
            request.execute(
                task = { canceled -> process(context, operation, uri, canceled) },
                failure = { error ->
                    HLog.e("$TAG 处理聊天分组文件失败: ${error.message}", error)
                    DocumentResult(error.message ?: "处理聊天分组文件失败")
                }
            )
        } catch (error: Throwable) {
            request.cancel()
            HLog.e("$TAG 处理文件选择结果失败", error)
        }
    }

    private fun process(context: Context, operation: Operation, uri: android.net.Uri, canceled: AtomicBoolean): DocumentResult {
        checkActive(canceled)
        return when (operation) {
            is Operation.Export -> {
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    checkActive(canceled)
                    output.write(operation.json.toByteArray(Charsets.UTF_8))
                } ?: error("无法写入所选文件")
                checkActive(canceled)
                DocumentResult("聊天分组已导出")
            }
            is Operation.Import -> {
                val json = readText(context, uri, canceled)
                checkActive(canceled)
                val imported = ConversationGroupStore.importCurrentAccount(context, json, operation.account) { canceled.get() }
                DocumentResult(imported.message, imported.success)
            }
        }
    }

    private fun checkActive(canceled: AtomicBoolean) {
        if (canceled.get()) throw java.util.concurrent.CancellationException()
    }

    private fun readText(context: Context, uri: android.net.Uri, canceled: AtomicBoolean): String {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                checkActive(canceled)
                val count = input.read(buffer)
                checkActive(canceled)
                if (count < 0) break
                total += count
                require(total <= MAX_IMPORT_BYTES) { "聊天分组文件不能超过 8 MB" }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        } ?: error("无法读取所选文件")
    }

    private fun Intent.preferSystemDocumentsUi(context: Context): Intent {
        for (packageName in listOf("com.google.android.documentsui", "com.android.documentsui")) {
            val copy = Intent(this).setPackage(packageName)
            if (runCatching { context.packageManager.queryIntentActivities(copy, 0) }.getOrDefault(emptyList()).isNotEmpty()) {
                setPackage(packageName)
                break
            }
        }
        return this
    }

    private data class Pending(val request: ConversationGroupFileRequest<DocumentResult>, val operation: Operation)
    private sealed class Operation {
        data class Export(val json: String) : Operation()
        data class Import(val account: String) : Operation()
    }
}

private data class DocumentResult(
    val message: String,
    val changed: Boolean = false
)
