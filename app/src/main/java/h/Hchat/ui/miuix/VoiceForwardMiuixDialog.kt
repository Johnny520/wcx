package h.Hchat.ui.miuix

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import h.Hchat.hooks.items.roundavatar.RoundAvatarSettings
import h.Hchat.hooks.items.conversationgroup.ConversationGroupPickerSupport
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

object VoiceForwardMiuixDialog {
    interface DialogHandle {
        fun close()
        fun isShowing(): Boolean
    }

    data class ContactItem(
        val id: String,
        val label: String,
        val group: Boolean,
        val avatarUrl: String = "",
        val avatarBackupUrl: String = "",
        val labels: List<String> = emptyList(),
        val official: Boolean = false,
        val searchAliases: List<String> = emptyList()
    ) {
        fun matchesSearch(lower: String): Boolean {
            return lower.isEmpty() ||
                label.lowercase(Locale.US).contains(lower) ||
                id.lowercase(Locale.US).contains(lower) ||
                labels.any { it.lowercase(Locale.US).contains(lower) } ||
                searchAliases.any { it.lowercase(Locale.US).contains(lower) }
        }
    }

    enum class DialogPosition {
        TOP,
        CENTER,
        BOTTOM;

        companion object {
            fun from(value: String?): DialogPosition {
                return when (value?.trim()?.lowercase(Locale.US)) {
                    "top", "顶部", "上方" -> TOP
                    "center", "centre", "middle", "居中", "中间" -> CENTER
                    else -> BOTTOM
                }
            }
        }
    }

    fun showMessage(
        activity: Activity,
        title: String,
        message: String,
        onDismiss: () -> Unit,
        position: DialogPosition = DialogPosition.BOTTOM
    ): DialogHandle {
        return show(activity, onDismiss) { close ->
            PositionedWindowDialog(
                show = true,
                title = title,
                onDismissRequest = close,
                position = position,
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (message.isNotBlank()) {
                            Text(
                                text = message,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 14.sp,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)
                            )
                        }
                        DialogActionButton(
                            text = "确定",
                            onClick = close,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                            primary = true
                        )
                    }
                }
            )
        }
    }

    fun showCopyableLog(
        activity: Activity,
        title: String,
        message: String,
        copyText: String,
        onDismiss: () -> Unit
    ): DialogHandle {
        return show(activity, onDismiss) { close ->
            val copyLog = {
                val copied = runCatching {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        ?: error("剪贴板服务不可用")
                    clipboard.setPrimaryClip(ClipData.newPlainText(title, copyText))
                }.isSuccess
                Toast.makeText(
                    activity,
                    if (copied) "异常日志已复制" else "复制失败，请稍后重试",
                    Toast.LENGTH_SHORT
                ).show()
            }
            PositionedWindowDialog(
                show = true,
                title = title,
                onDismissRequest = close,
                position = DialogPosition.CENTER,
                content = {
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                .clickable(onClick = copyLog)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(
                                text = message,
                                color = MiuixTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 17.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DialogActionButton(
                                text = "关闭",
                                onClick = close,
                                modifier = Modifier.weight(1f)
                            )
                            DialogActionButton(
                                text = "复制日志",
                                onClick = copyLog,
                                modifier = Modifier.weight(1f),
                                primary = true
                            )
                        }
                    }
                }
            )
        }
    }

    fun showConfirm(
        activity: Activity,
        title: String,
        message: String,
        onResult: (Boolean) -> Unit,
        onDismiss: () -> Unit,
        position: DialogPosition = DialogPosition.BOTTOM
    ): DialogHandle {
        val resolved = AtomicBoolean(false)
        return show(activity, {
            if (resolved.compareAndSet(false, true)) onResult(false)
            onDismiss()
        }) { close ->
            PositionedWindowDialog(
                show = true,
                title = title,
                onDismissRequest = close,
                position = position,
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (message.isNotBlank()) {
                            Text(
                                text = message,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 14.sp,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DialogActionButton(
                                text = "取消",
                                onClick = close,
                                modifier = Modifier.weight(1f)
                            )
                            DialogActionButton(
                                text = "确定",
                                onClick = {
                                    if (resolved.compareAndSet(false, true)) {
                                        close()
                                        postAfterDialogClose(activity) { onResult(true) }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                primary = true
                            )
                        }
                    }
                }
            )
        }
    }

    fun showLoading(
        activity: Activity,
        onDismiss: () -> Unit,
        title: String = "转发语音",
        message: String = "正在载入好友和群聊..."
    ): DialogHandle {
        return show(activity, onDismiss) { close ->
            WindowDialog(
                show = true,
                title = title,
                onDismissRequest = close,
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = message,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            textAlign = TextAlign.Center
                        )
                        TextButton(
                            text = "取消",
                            onClick = close,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                }
            )
        }
    }

    fun showContacts(
        activity: Activity,
        contacts: List<ContactItem>,
        onConfirm: (List<ContactItem>) -> Unit,
        onDismiss: () -> Unit,
        title: String = "选择转发对象",
        confirmText: String = "发送",
        showGroupFilter: Boolean = true,
        onConfirmRequest: (() -> Unit)? = null,
        initialSelectedIds: Set<String> = emptySet(),
        allowEmpty: Boolean = false,
        singleSelection: Boolean = false,
        showClearSelectionAction: Boolean = false
    ): DialogHandle {
        return show(activity, onDismiss) { close ->
            WindowDialog(
                show = true,
                title = title,
                onDismissRequest = close,
                content = {
                    var query by remember(contacts) { mutableStateOf("") }
                    var filter by remember(contacts) { mutableStateOf(ContactFilter.ALL) }
                    var selectedLabel by remember(contacts) { mutableStateOf("") }
                    var selectedConversationGroupId by remember(contacts) { mutableStateOf("") }
                    var selectedIds by remember(contacts, initialSelectedIds, singleSelection) {
                        val validIds = contacts.mapTo(hashSetOf()) { it.id }
                        val initial = initialSelectedIds.filterTo(linkedSetOf()) { validIds.contains(it) }
                        mutableStateOf(if (singleSelection) initial.take(1).toSet() else initial)
                    }
                    val labelNames = remember(contacts) {
                        contacts.flatMap { it.labels }.distinct().sortedWith(compareBy { it.lowercase(Locale.US) })
                    }
                    val conversationGroups = remember(activity, contacts) {
                        ConversationGroupPickerSupport.filters(activity, contacts.map { it.id })
                    }
                    val filters = remember(contacts, showGroupFilter, labelNames, conversationGroups) {
                        buildList {
                            add(ContactFilter.ALL)
                            add(ContactFilter.FRIENDS)
                            if (showGroupFilter && contacts.any { it.group }) add(ContactFilter.GROUPS)
                            if (contacts.any { it.official }) add(ContactFilter.OFFICIALS)
                            if (labelNames.isNotEmpty()) add(ContactFilter.LABELS)
                            if (conversationGroups.isNotEmpty()) add(ContactFilter.CHAT_GROUPS)
                        }
                    }
                    LaunchedEffect(filter, labelNames, conversationGroups) {
                        if (filter == ContactFilter.LABELS && selectedLabel.isBlank()) {
                            selectedLabel = labelNames.firstOrNull().orEmpty()
                        } else if (filter == ContactFilter.CHAT_GROUPS &&
                            conversationGroups.none { it.id == selectedConversationGroupId }
                        ) {
                            selectedConversationGroupId = conversationGroups.firstOrNull()?.id.orEmpty()
                        }
                    }
                    val lower = query.trim().lowercase(Locale.US)
                    val visible = contacts.filter { contact ->
                        val typeMatched = when (filter) {
                            ContactFilter.ALL -> true
                            ContactFilter.FRIENDS -> !contact.group && !contact.official
                            ContactFilter.GROUPS -> contact.group
                            ContactFilter.OFFICIALS -> contact.official
                            ContactFilter.LABELS -> !contact.group && !contact.official &&
                                selectedLabel.isNotBlank() &&
                                contact.labels.contains(selectedLabel)
                            ContactFilter.CHAT_GROUPS -> conversationGroups
                                .firstOrNull { it.id == selectedConversationGroupId }
                                ?.conversationIds
                                ?.contains(contact.id) == true
                        }
                        typeMatched && contact.matchesSearch(lower)
                    }
                    val visibleIds = visible.map { it.id }.toSet()
                    val visibleAllSelected = visibleIds.isNotEmpty() && visibleIds.all { selectedIds.contains(it) }
                    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
                    val dialogMaxHeight = (screenHeight * 0.78f).coerceAtMost(700.dp)
                    val reservedHeight = if (
                        filter == ContactFilter.LABELS || filter == ContactFilter.CHAT_GROUPS
                    ) 292.dp else 250.dp
                    val listMaxHeight = (dialogMaxHeight - reservedHeight).coerceIn(140.dp, 420.dp)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = dialogMaxHeight)
                    ) {
                        ContactFilterRow(
                            filters = filters,
                            selected = filter,
                            onSelected = {
                                filter = it
                                if (it == ContactFilter.LABELS && selectedLabel.isBlank()) {
                                    selectedLabel = labelNames.firstOrNull().orEmpty()
                                }
                            }
                        )
                        if (filter == ContactFilter.LABELS) {
                            LabelFilterRow(
                                labels = labelNames,
                                selected = selectedLabel,
                                onSelected = { selectedLabel = it }
                            )
                        }
                        if (filter == ContactFilter.CHAT_GROUPS) {
                            ConversationGroupFilterRow(
                                groups = conversationGroups.map { it.id to it.name },
                                selected = selectedConversationGroupId,
                                onSelected = { selectedConversationGroupId = it }
                            )
                        }
                        SearchBox(
                            value = query,
                            onValueChange = { query = it }
                        )
                        Text(
                            text = filter.title,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 6.dp)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = listMaxHeight)
                        ) {
                            if (visible.isEmpty()) {
                                Text(
                                    text = "没有匹配结果",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                    items(visible, key = { it.id }) { contact ->
                                        ContactRow(contact, selected = selectedIds.contains(contact.id)) {
                                            selectedIds = if (selectedIds.contains(contact.id)) {
                                                selectedIds - contact.id
                                            } else if (singleSelection) {
                                                setOf(contact.id)
                                            } else {
                                                selectedIds + contact.id
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DialogActionButton(
                                text = "取消",
                                onClick = close,
                                modifier = Modifier.weight(1f)
                            )
                            if (!singleSelection) {
                                DialogActionButton(
                                    text = if (showClearSelectionAction && selectedIds.isNotEmpty()) {
                                        "清空"
                                    } else if (visibleAllSelected) {
                                        "取消全选"
                                    } else {
                                        "全选"
                                    },
                                    onClick = {
                                        selectedIds = if (showClearSelectionAction && selectedIds.isNotEmpty()) {
                                            emptySet()
                                        } else if (visibleAllSelected) {
                                            selectedIds - visibleIds
                                        } else {
                                            selectedIds + visibleIds
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            DialogActionButton(
                                text = if (selectedIds.isEmpty()) confirmText else "$confirmText(${selectedIds.size})",
                                onClick = sendClick@{
                                    if (selectedIds.isEmpty() && !allowEmpty) return@sendClick
                                    val selected = contacts.filter { selectedIds.contains(it.id) }
                                    onConfirmRequest?.invoke()
                                    close()
                                    postAfterDialogClose(activity) { onConfirm(selected) }
                                },
                                modifier = Modifier.weight(1f),
                                primary = selectedIds.isNotEmpty() || allowEmpty
                            )
                        }
                    }
                }
            )
        }
    }

    fun showListChoices(
        activity: Activity,
        title: String,
        summary: String,
        choices: List<Pair<String, String>>,
        onSelected: (Int) -> Unit,
        onDismiss: () -> Unit,
        position: DialogPosition = DialogPosition.BOTTOM,
        searchable: Boolean = false,
        searchPlaceholder: String = "搜索"
    ): DialogHandle {
        return show(activity, onDismiss) { close ->
            PositionedWindowDialog(
                show = true,
                title = title,
                onDismissRequest = close,
                position = position,
                content = {
                    var query by remember(choices, searchable) { mutableStateOf("") }
                    val visibleChoices = remember(choices, query, searchable) {
                        if (!searchable || query.isBlank()) {
                            choices.withIndex().toList()
                        } else {
                            val lower = query.trim().lowercase(Locale.US)
                            choices.withIndex().filter { indexed ->
                                indexed.value.first.lowercase(Locale.US).contains(lower) ||
                                    indexed.value.second.lowercase(Locale.US).contains(lower)
                            }
                        }
                    }
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
                        if (summary.isNotBlank()) {
                            Text(
                                text = summary,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                            )
                        }
                        if (searchable) {
                            SearchBox(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = searchPlaceholder
                            )
                        }
                        if (visibleChoices.isEmpty()) {
                            Text(
                                text = "没有匹配结果",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                                textAlign = TextAlign.Center
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                            ) {
                                visibleChoices.forEach { indexed ->
                                    item(key = indexed.index) {
                                        val index = indexed.index
                                        val choice = indexed.value
                                        ModeRow(choice.first, choice.second) {
                                            close()
                                            postAfterDialogClose(activity) { onSelected(index) }
                                        }
                                    }
                                }
                            }
                        }
                        TextButton(
                            text = "取消",
                            onClick = close,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp).heightIn(min = 44.dp)
                        )
                    }
                }
            )
        }
    }

    fun showChoices(
        activity: Activity,
        title: String,
        summary: String,
        choices: List<Pair<String, String>>,
        onSelected: (Int) -> Unit,
        onDismiss: () -> Unit,
        position: DialogPosition = DialogPosition.BOTTOM
    ): DialogHandle {
        return show(activity, onDismiss) { close ->
            PositionedWindowDialog(
                show = true,
                title = title,
                onDismissRequest = close,
                position = position,
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (summary.isNotBlank()) {
                            Text(
                                text = summary,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                            )
                        }
                        choices.forEachIndexed { index, choice ->
                            ModeRow(choice.first, choice.second) {
                                close()
                                postAfterDialogClose(activity) { onSelected(index) }
                            }
                        }
                        TextButton(
                            text = "取消",
                            onClick = close,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp).heightIn(min = 44.dp)
                        )
                    }
                }
            )
        }
    }

    fun showMultiChoices(
        activity: Activity,
        title: String,
        summary: String,
        choices: List<Pair<String, String>>,
        initialSelected: Set<Int> = emptySet(),
        allowEmpty: Boolean = false,
        onConfirm: (Set<Int>) -> Unit,
        onDismiss: () -> Unit,
        position: DialogPosition = DialogPosition.BOTTOM
    ): DialogHandle {
        return show(activity, onDismiss) { close ->
            PositionedWindowDialog(
                show = true,
                title = title,
                onDismissRequest = close,
                position = position,
                content = {
                    var selected by remember(choices, initialSelected) {
                        mutableStateOf(initialSelected.filter { it in choices.indices }.toSet())
                    }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (summary.isNotBlank()) {
                            Text(
                                text = summary,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                            )
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                        ) {
                            choices.forEachIndexed { index, choice ->
                                item(key = index) {
                                    MultiChoiceRow(
                                        title = choice.first,
                                        summary = choice.second,
                                        selected = selected.contains(index)
                                    ) {
                                        selected = if (selected.contains(index)) {
                                            selected - index
                                        } else {
                                            selected + index
                                        }
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DialogActionButton(
                                text = "取消",
                                onClick = close,
                                modifier = Modifier.weight(1f)
                            )
                            DialogActionButton(
                                text = "确定",
                                onClick = confirm@ {
                                    if (selected.isEmpty() && !allowEmpty) return@confirm
                                    val result = selected
                                    close()
                                    postAfterDialogClose(activity) { onConfirm(result) }
                                },
                                modifier = Modifier.weight(1f),
                                primary = selected.isNotEmpty() || allowEmpty
                            )
                        }
                    }
                }
            )
        }
    }

    fun showTextInput(
        activity: Activity,
        title: String,
        summary: String,
        initialValue: String = "",
        placeholder: String = "",
        maxLength: Int = 100,
        singleLine: Boolean = true,
        allowEmpty: Boolean = false,
        onConfirm: (String) -> Unit,
        onDismiss: () -> Unit,
        position: DialogPosition = DialogPosition.BOTTOM
    ): DialogHandle {
        return show(activity, onDismiss) { close ->
            PositionedWindowDialog(
                show = true,
                title = title,
                onDismissRequest = close,
                position = position,
                content = {
                    val safeMaxLength = maxLength.coerceAtLeast(1)
                    val initialText = remember(initialValue, safeMaxLength) {
                        initialValue.take(safeMaxLength)
                    }
                    var value by remember(initialText) {
                        mutableStateOf(
                            TextFieldValue(
                                text = initialText,
                                selection = TextRange(initialText.length)
                            )
                        )
                    }
                    var error by remember { mutableStateOf("") }
                    val focusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (summary.isNotBlank()) {
                            Text(
                                text = summary,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = if (singleLine) 0.dp else 140.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MiuixTheme.colorScheme.secondaryVariant)
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            if (value.text.isEmpty() && placeholder.isNotBlank()) {
                                Text(
                                    text = placeholder,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = 16.sp
                                )
                            }
                            BasicTextField(
                                value = value,
                                onValueChange = { next ->
                                    val text = next.text.take(safeMaxLength)
                                    val start = next.selection.start.coerceAtMost(text.length)
                                    val end = next.selection.end.coerceAtMost(text.length)
                                    value = TextFieldValue(text, TextRange(start, end))
                                    error = ""
                                },
                                singleLine = singleLine,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                textStyle = TextStyle(
                                    color = MiuixTheme.colorScheme.onSurface,
                                    fontSize = 16.sp
                                ),
                                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                            )
                        }
                        if (error.isNotBlank()) {
                            Text(
                                text = error,
                                color = Color(0xFFD32F2F),
                                fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DialogActionButton(
                                text = "取消",
                                onClick = close,
                                modifier = Modifier.weight(1f)
                            )
                            DialogActionButton(
                                text = "确定",
                                onClick = confirm@ {
                                    val text = value.text.trim()
                                    if (text.isEmpty() && !allowEmpty) {
                                        error = "内容不能为空"
                                        return@confirm
                                    }
                                    close()
                                    postAfterDialogClose(activity) { onConfirm(text) }
                                },
                                modifier = Modifier.weight(1f),
                                primary = value.text.isNotBlank() || allowEmpty
                            )
                        }
                    }
                }
            )
        }
    }

    fun showNumberInput(
        activity: Activity,
        title: String,
        initialValue: Int,
        minValue: Int,
        maxValue: Int? = null,
        onConfirm: (Int) -> Unit,
        onDismiss: () -> Unit
    ): DialogHandle {
        return show(activity, onDismiss) { close ->
            WindowDialog(
                show = true,
                title = title,
                onDismissRequest = close,
                content = {
                    val initialText = remember(initialValue, minValue, maxValue) {
                        val atLeastMinimum = initialValue.coerceAtLeast(minValue)
                        (maxValue?.let { atLeastMinimum.coerceAtMost(it) } ?: atLeastMinimum).toString()
                    }
                    var value by remember(initialText) {
                        mutableStateOf(
                            TextFieldValue(
                                text = initialText,
                                selection = TextRange(0, initialText.length)
                            )
                        )
                    }
                    var error by remember { mutableStateOf("") }
                    val focusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (maxValue != null) {
                            var pickedNumber by remember(initialText) {
                                mutableStateOf(initialText.toIntOrNull() ?: minValue)
                            }
                            NumberPicker(
                                value = pickedNumber,
                                range = minValue..maxValue,
                                label = { it.toString() },
                                onValueChange = {
                                    pickedNumber = it
                                    value = TextFieldValue(it.toString(), TextRange(it.toString().length))
                                    error = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            BasicTextField(
                                value = value,
                                onValueChange = { next ->
                                    val digits = next.text.filter { it.isDigit() }.take(10)
                                    value = TextFieldValue(digits, TextRange(digits.length))
                                    error = ""
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                textStyle = TextStyle(
                                    color = MiuixTheme.colorScheme.onSurface,
                                    fontSize = 16.sp,
                                    textAlign = TextAlign.Center
                                ),
                                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MiuixTheme.colorScheme.secondaryVariant)
                                    .padding(horizontal = 12.dp, vertical = 12.dp)
                            )
                        }
                        Text(
                            text = error.ifBlank {
                                maxValue?.let { "请输入 $minValue-$it" } ?: "请输入不小于 $minValue 的整数"
                            },
                            color = if (error.isBlank()) {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            } else {
                                Color(0xFFD32F2F)
                            },
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            textAlign = TextAlign.Center
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DialogActionButton(
                                text = "取消",
                                onClick = close,
                                modifier = Modifier.weight(1f)
                            )
                            DialogActionButton(
                                text = "确定",
                                onClick = {
                                    val number = value.text.toIntOrNull()
                                    val outOfRange = number == null || number < minValue ||
                                        (maxValue != null && number > maxValue)
                                    if (outOfRange) {
                                        error = maxValue?.let { "请输入 $minValue-$it" }
                                            ?: "请输入不小于 $minValue 的整数"
                                    } else {
                                        close()
                                        postAfterDialogClose(activity) { onConfirm(number!!) }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                primary = true
                            )
                        }
                    }
                }
            )
        }
    }

    fun showDateTimeInput(
        activity: Activity,
        title: String,
        initialTimeMillis: Long,
        onConfirm: (Long) -> Unit,
        onDismiss: () -> Unit
    ): DialogHandle {
        return show(activity, onDismiss) { close ->
            WindowDialog(
                show = true,
                title = title,
                onDismissRequest = close,
                content = {
                    val initial = remember(initialTimeMillis) {
                        Calendar.getInstance().apply {
                            timeInMillis = initialTimeMillis.takeIf { it > 0L }
                                ?: System.currentTimeMillis()
                        }
                    }
                    var year by remember(initial) { mutableStateOf(initial.get(Calendar.YEAR).toString()) }
                    var month by remember(initial) { mutableStateOf((initial.get(Calendar.MONTH) + 1).toString()) }
                    var day by remember(initial) { mutableStateOf(initial.get(Calendar.DAY_OF_MONTH).toString()) }
                    var hour by remember(initial) { mutableStateOf(initial.get(Calendar.HOUR_OF_DAY).toString()) }
                    var minute by remember(initial) { mutableStateOf(initial.get(Calendar.MINUTE).toString()) }
                    var second by remember(initial) { mutableStateOf(initial.get(Calendar.SECOND).toString()) }
                    val pickedTime = resolveDateTimeMillis(year, month, day, hour, minute, second)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DateTimeNumberField("年", year, 4, 2038, Modifier.weight(1f)) { year = it }
                            DateTimeNumberField("月", month, 2, 12, Modifier.weight(1f)) { month = it }
                            DateTimeNumberField("日", day, 2, 31, Modifier.weight(1f)) { day = it }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DateTimeNumberField("时", hour, 2, 23, Modifier.weight(1f)) { hour = it }
                            DateTimeNumberField("分", minute, 2, 59, Modifier.weight(1f)) { minute = it }
                            DateTimeNumberField("秒", second, 2, 59, Modifier.weight(1f)) { second = it }
                        }
                        Text(
                            text = pickedTime?.let {
                                String.format(
                                    Locale.US,
                                    "%04d-%02d-%02d %02d:%02d:%02d",
                                    year.toInt(),
                                    month.toInt(),
                                    day.toInt(),
                                    hour.toInt(),
                                    minute.toInt(),
                                    second.toInt()
                                )
                            } ?: "请输入有效的年、月、日、时、分、秒",
                            color = if (pickedTime != null) {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            } else {
                                Color(0xFFD32F2F)
                            },
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            textAlign = TextAlign.Center
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DialogActionButton(
                                text = "取消",
                                onClick = close,
                                modifier = Modifier.weight(1f)
                            )
                            DialogActionButton(
                                text = "确定",
                                onClick = {
                                    pickedTime?.let {
                                        close()
                                        postAfterDialogClose(activity) { onConfirm(it) }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                primary = pickedTime != null
                            )
                        }
                    }
                }
            )
        }
    }

    @Composable
    private fun DateTimeNumberField(
        label: String,
        value: String,
        maxDigits: Int,
        maxValue: Int,
        modifier: Modifier,
        onValueChange: (String) -> Unit
    ) {
        Column(modifier = modifier) {
            Text(
                text = label,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                textAlign = TextAlign.Center
            )
            BasicTextField(
                value = value,
                onValueChange = { next ->
                    val digits = next.filter { it.isDigit() }.take(maxDigits)
                    val number = digits.toIntOrNull()
                    if (digits.isEmpty() || (number != null && number <= maxValue)) {
                        onValueChange(digits)
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = TextStyle(
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.secondaryVariant)
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            )
        }
    }

    private fun resolveDateTimeMillis(
        year: String,
        month: String,
        day: String,
        hour: String,
        minute: String,
        second: String
    ): Long? {
        val yearValue = year.toIntOrNull()?.takeIf { it in 1970..2038 } ?: return null
        val monthValue = month.toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        val dayValue = day.toIntOrNull()?.takeIf { it in 1..31 } ?: return null
        val hourValue = hour.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minuteValue = minute.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        val secondValue = second.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        return runCatching {
            Calendar.getInstance().apply {
                isLenient = false
                clear()
                set(yearValue, monthValue - 1, dayValue, hourValue, minuteValue, secondValue)
            }.timeInMillis
        }.getOrNull()?.takeIf { it / 1000L in 1L..Int.MAX_VALUE.toLong() }
    }

    fun showForwardMode(
        activity: Activity,
        voiceCount: Int,
        onMerge: () -> Unit,
        onSeparate: () -> Unit,
        onDismiss: () -> Unit
    ): DialogHandle {
        return show(activity, onDismiss) { close ->
            WindowDialog(
                show = true,
                title = "转发语音",
                onDismissRequest = close,
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "已选择 ${voiceCount.coerceAtLeast(1)} 条语音",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                        )
                        ModeRow("合并转发", "打包为聊天记录转发") {
                            close()
                            onMerge()
                        }
                        ModeRow("逐条转发", "按原语音逐条发送") {
                            close()
                            onSeparate()
                        }
                        TextButton(
                            text = "取消",
                            onClick = close,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 6.dp)
                                .heightIn(min = 44.dp)
                        )
                    }
                }
            )
        }
    }

    fun showFavoriteVoiceSendConfirm(
        activity: Activity,
        preparePreview: () -> String?,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit
    ): DialogHandle {
        return show(activity, onDismiss) { close ->
            WindowDialog(
                show = true,
                title = "发送收藏语音",
                onDismissRequest = close,
                content = {
                    var previewPath by remember { mutableStateOf<String?>(null) }
                    var previewVersion by remember { mutableStateOf(0) }
                    var previewLoading by remember { mutableStateOf(false) }
                    var previewError by remember { mutableStateOf("") }
                    val previewScope = rememberCoroutineScope()
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "发送到当前聊天？",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 10.dp),
                            textAlign = TextAlign.Center
                        )
                        FavoriteVoicePreviewPlayer(
                            path = previewPath,
                            version = previewVersion,
                            loading = previewLoading,
                            error = previewError
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DialogActionButton(
                                text = "取消",
                                onClick = close,
                                modifier = Modifier.weight(1f)
                            )
                            DialogActionButton(
                                text = if (previewPath == null) "预览" else "重载",
                                onClick = {
                                    previewScope.launch {
                                        previewLoading = true
                                        previewError = ""
                                        val path = withContext(Dispatchers.IO) { preparePreview() }
                                        previewLoading = false
                                        if (path.isNullOrBlank()) {
                                            previewPath = null
                                            previewError = "预览加载失败"
                                        } else {
                                            previewPath = path
                                            previewVersion++
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            DialogActionButton(
                                text = "发送",
                                onClick = {
                                    close()
                                    onConfirm()
                                },
                                modifier = Modifier.weight(1f),
                                primary = true
                            )
                        }
                    }
                }
            )
        }
    }

    fun showVoicePreview(
        activity: Activity,
        durationMillis: Int,
        preparePreview: () -> String?,
        onDismiss: () -> Unit
    ): DialogHandle {
        return show(activity, onDismiss) { close ->
            WindowDialog(
                show = true,
                title = "语音消息预览",
                onDismissRequest = close,
                content = {
                    var previewPath by remember { mutableStateOf<String?>(null) }
                    var previewVersion by remember { mutableStateOf(0) }
                    var previewLoading by remember { mutableStateOf(true) }
                    var previewError by remember { mutableStateOf("") }

                    LaunchedEffect(Unit) {
                        val path = withContext(Dispatchers.IO) {
                            runCatching { preparePreview() }.getOrNull()
                        }
                        previewLoading = false
                        if (path.isNullOrBlank()) {
                            previewError = "语音预览准备失败"
                        } else {
                            previewPath = path
                            previewVersion++
                        }
                    }

                    Column(modifier = Modifier.fillMaxWidth()) {
                        FavoriteVoicePreviewPlayer(
                            path = previewPath,
                            version = previewVersion,
                            loading = previewLoading,
                            error = previewError,
                            knownDurationMillis = durationMillis
                        )
                        DialogActionButton(
                            text = "关闭",
                            onClick = close,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                            primary = true
                        )
                    }
                }
            )
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
                color = if (primary) Color.White else MiuixTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }

    @Composable
    private fun FavoriteVoicePreviewPlayer(
        path: String?,
        version: Int,
        loading: Boolean,
        error: String,
        knownDurationMillis: Int = 0
    ) {
        var player by remember(path, version) { mutableStateOf<MediaPlayer?>(null) }
        var prepared by remember(path, version) { mutableStateOf(false) }
        var playing by remember(path, version) { mutableStateOf(false) }
        var duration by remember(path, version, knownDurationMillis) {
            mutableStateOf(knownDurationMillis.coerceAtLeast(0))
        }
        var position by remember(path, version) { mutableStateOf(0) }
        var localError by remember(path, version) { mutableStateOf("") }

        fun seekBy(deltaMs: Int) {
            val media = player ?: return
            if (!prepared || duration <= 0) return
            val target = (position + deltaMs).coerceIn(0, duration)
            runCatching {
                media.seekTo(target)
                position = target
            }
        }

        DisposableEffect(path, version) {
            val currentPath = path
            if (!currentPath.isNullOrBlank()) {
                runCatching {
                    MediaPlayer().also { media ->
                        media.setDataSource(currentPath)
                        media.setOnPreparedListener { preparedPlayer ->
                            duration = preparedPlayer.duration.takeIf { it > 0 }
                                ?: knownDurationMillis.coerceAtLeast(0)
                            prepared = true
                            playing = false
                            position = 0
                        }
                        media.setOnCompletionListener { completed ->
                            playing = false
                            runCatching { completed.seekTo(0) }
                            position = 0
                        }
                        media.setOnErrorListener { _, _, _ ->
                            prepared = false
                            playing = false
                            localError = "预览播放失败"
                            true
                        }
                        player = media
                        media.prepareAsync()
                    }
                }.onFailure {
                    prepared = false
                    playing = false
                    localError = "预览播放失败"
                }
            }
            onDispose {
                player?.let { media ->
                    runCatching {
                        if (media.isPlaying) media.stop()
                    }
                    runCatching { media.release() }
                }
                player = null
            }
        }

        LaunchedEffect(player, playing, prepared) {
            while (prepared && playing) {
                player?.let { media ->
                    position = runCatching { media.currentPosition.coerceAtLeast(0) }.getOrDefault(position)
                    duration = runCatching { media.duration.coerceAtLeast(0) }.getOrDefault(duration)
                }
                delay(250)
            }
        }

        val message = when {
            loading -> "正在准备预览..."
            error.isNotBlank() -> error
            localError.isNotBlank() -> localError
            path.isNullOrBlank() -> "点击预览后可播放、暂停和跳转"
            !prepared -> "正在加载播放器..."
            else -> ""
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.secondaryVariant)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .background(
                                if (prepared) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                }
                            )
                            .clickable(enabled = prepared) {
                                val media = player ?: return@clickable
                                if (playing) {
                                    runCatching { media.pause() }
                                    playing = false
                                } else {
                                    runCatching {
                                        media.start()
                                        playing = true
                                    }.onFailure {
                                        localError = "预览播放失败"
                                        playing = false
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (playing) "Ⅱ" else "▶",
                            color = if (prepared) Color.White else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                    Text(
                        text = "${formatTime(position)} / ${formatTime(duration)}",
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                    PreviewControlButton(text = "-5", enabled = prepared) {
                        seekBy(-5000)
                    }
                    PreviewControlButton(text = "+5", enabled = prepared) {
                        seekBy(5000)
                    }
                    if (message.isNotBlank()) {
                        Text(
                            text = message,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f).padding(start = 10.dp),
                            textAlign = TextAlign.End
                        )
                    }
                }
                PreviewProgressBar(
                    progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f,
                    enabled = prepared && duration > 0,
                    onSeek = { fraction ->
                        val media = player ?: return@PreviewProgressBar
                        val target = (duration * fraction.coerceIn(0f, 1f)).toInt()
                        runCatching {
                            media.seekTo(target)
                            position = target
                        }
                    }
                )
            }
        }
    }

    @Composable
    private fun PreviewControlButton(
        text: String,
        enabled: Boolean,
        onClick: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .padding(start = 6.dp)
                .size(width = 34.dp, height = 28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (enabled) {
                        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    } else {
                        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                    }
                )
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }

    @Composable
    private fun PreviewProgressBar(
        progress: Float,
        enabled: Boolean,
        onSeek: (Float) -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .padding(top = 12.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        onSeek(offset.x / size.width.toFloat())
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (enabled) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                        }
                    )
            )
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(Locale.US, minutes, seconds)
    }

    @Composable
    private fun ModeRow(title: String, summary: String, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun MultiChoiceRow(
        title: String,
        summary: String,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
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
    private fun ContactFilterRow(
        filters: List<ContactFilter>,
        selected: ContactFilter,
        onSelected: (ContactFilter) -> Unit
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filters, key = { it.name }) { item ->
                val active = item == selected
                Box(
                    modifier = Modifier
                        .widthIn(min = 54.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (active) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                            }
                        )
                        .clickable { onSelected(item) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.label,
                        color = if (active) Color.White else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun LabelFilterRow(labels: List<String>, selected: String, onSelected: (String) -> Unit) {
        if (labels.isEmpty()) {
            Text(
                text = "没有好友标签",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
            )
            return
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(labels, key = { it }) { label ->
                val active = label == selected
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (active) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                            }
                        )
                        .clickable { onSelected(label) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (active) Color.White else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        }
    }

    @Composable
    private fun ConversationGroupFilterRow(
        groups: List<Pair<String, String>>,
        selected: String,
        onSelected: (String) -> Unit
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(groups, key = { it.first }) { group ->
                val active = group.first == selected
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (active) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                        )
                        .clickable { onSelected(group.first) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = group.second,
                        color = if (active) Color.White else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }

    @Composable
    private fun SearchBox(
        value: String,
        onValueChange: (String) -> Unit,
        placeholder: String = "搜索昵称 / 群聊备注 / wxid"
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 14.sp),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.secondaryVariant)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp
                    )
                }
                innerTextField()
            }
        )
    }

    @Composable
    private fun ContactRow(contact: ContactItem, selected: Boolean, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ContactAvatar(contact)
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    text = contact.label,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                )
                val summary = if (contact.labels.isEmpty()) {
                    contact.id
                } else {
                    contact.id + " · " + contact.labels.joinToString(" / ")
                }
                Text(
                    text = summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp
                )
            }
            Checkbox(
                modifier = Modifier.size(22.dp),
                state = if (selected) ToggleableState.On else ToggleableState.Off,
                onClick = null
            )
        }
    }

    @Composable
    private fun ContactAvatar(contact: ContactItem) {
        val context = LocalContext.current
        var bitmap by remember(contact.id, contact.avatarUrl, contact.avatarBackupUrl) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(contact.id, contact.avatarUrl, contact.avatarBackupUrl) {
            bitmap = loadAvatarBitmap(contact.id, contact.avatarUrl, contact.avatarBackupUrl)
        }
        val image = bitmap
        val avatarBg = if (image == null && contact.group) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.secondaryVariant
        }
        val avatarTextColor = if (image == null && contact.group) {
            Color.White
        } else {
            MiuixTheme.colorScheme.onSecondaryVariant
        }
        val avatarShape = if (RoundAvatarSettings.enabled(context)) {
            RoundedCornerShape((42f * RoundAvatarSettings.radiusFactor(context)).dp)
        } else {
            RoundedCornerShape(12.dp)
        }
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(avatarShape)
                .background(avatarBg),
            contentAlignment = Alignment.Center
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = if (contact.group) "群" else contact.label.take(1).ifEmpty { "友" },
                    color = avatarTextColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    private enum class ContactFilter(val label: String, val title: String) {
        ALL("全部", "全部联系人"),
        FRIENDS("好友", "好友"),
        GROUPS("群聊", "群聊"),
        OFFICIALS("公众号", "公众号"),
        LABELS("标签", "标签好友"),
        CHAT_GROUPS("分组", "聊天分组")
    }

    @Composable
    private fun PositionedWindowDialog(
        show: Boolean,
        title: String,
        position: DialogPosition,
        onDismissRequest: () -> Unit,
        content: @Composable () -> Unit
    ) {
        if (position != DialogPosition.TOP) {
            WindowDialog(
                show = show,
                title = title,
                onDismissRequest = onDismissRequest,
                maxWidth = 560.dp,
                largeScreen = position == DialogPosition.CENTER,
                cornerRadius = if (position == DialogPosition.CENTER) 32.dp else null,
                content = content
            )
            return
        }
        if (!show) return

        val navigationState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
        NavigationBackHandler(
            state = navigationState,
            isBackEnabled = true,
            onBackCompleted = onDismissRequest
        )
        val alignment = when (position) {
            DialogPosition.TOP -> Alignment.TopCenter
            DialogPosition.CENTER -> Alignment.Center
            DialogPosition.BOTTOM -> Alignment.BottomCenter
        }
        val maxHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.8f).coerceAtLeast(240.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.windowDimming)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .pointerInput(onDismissRequest) {
                    detectTapGestures { onDismissRequest() }
                }
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .align(alignment)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .clip(RoundedCornerShape(32.dp))
                    .background(MiuixTheme.colorScheme.background)
                    .padding(24.dp)
            ) {
                if (title.isNotBlank()) {
                    Text(
                        text = title,
                        color = MiuixTheme.colorScheme.onBackground,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    )
                }
                content()
            }
        }
    }

    @Composable
    private fun DialogTheme(context: Context, content: @Composable () -> Unit) {
        val colors = if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES
        ) {
            darkColorScheme()
        } else {
            lightColorScheme()
        }
        MiuixTheme(colors = colors, content = content)
    }

    private fun show(
        activity: Activity,
        onDismiss: () -> Unit,
        content: @Composable (() -> Unit) -> Unit
    ): DialogHandle {
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
        val closeDialog = close@{
            if (!closed.compareAndSet(false, true)) return@close
            val cleanup = {
                runCatching { compose.disposeComposition() }
                runCatching { (root.parent as? ViewGroup)?.removeView(root) }
                runCatching { owner.clear(root) }
                runCatching { owner.clear(decor) }
                runCatching { owner.destroy() }
                runCatching { onDismiss() }
                Unit
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                cleanup()
            } else {
                activity.runOnUiThread { cleanup() }
            }
        }
        compose = ComposeView(activity).apply {
            owner.install(this)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                    DialogTheme(activity) {
                        content(closeDialog)
                    }
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
        decor.addView(root)
        root.requestFocus()
        return object : DialogHandle {
            override fun close() = closeDialog()
            override fun isShowing(): Boolean = !closed.get()
        }
    }

    private fun postAfterDialogClose(activity: Activity, action: () -> Unit) {
        val decor = activity.window?.decorView ?: return
        decor.postOnAnimation {
            if (!activity.isFinishing && !activity.isDestroyed) action()
        }
    }

    private object EmptyHandle : DialogHandle {
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

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        override val viewModelStore: ViewModelStore
            get() = store

        override val navigationEventDispatcher: NavigationEventDispatcher
            get() = navigationDispatcher

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
