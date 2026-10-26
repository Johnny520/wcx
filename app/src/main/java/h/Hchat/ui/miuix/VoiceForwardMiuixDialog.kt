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
                                        runCatching { onResult(true) }
                                        close()
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
                                            runCatching { onSelected(index) }
                                            close()
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
                        // 选项可能很多：限制高度并允许上下滑动，避免超出窗口后无法滚动。
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            choices.forEachIndexed { index, choice ->
                                ModeRow(choice.first, choice.second) {
                                    runCatching { onSelected(index) }
                                    close()
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
                                    runCatching { onConfirm(result) }
                                    close()
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
                                    runCatching { onConfirm(text) }
                                    close()
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
    private fun PositionedWindowDialog(
        show: Boolean,
        title: String,
        position: DialogPosition,
        onDismissRequest: () -> Unit,
        content: @Composable () -> Unit
    ) {
        // 所有位置都注册返回处理：返回键 / 侧滑返回均可关闭弹窗（close 幂等）。
        val navigationState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
        NavigationBackHandler(
            state = navigationState,
            isBackEnabled = show,
            onBackCompleted = onDismissRequest
        )
        if (position != DialogPosition.TOP) {
            WindowDialog(
                show = show,
                title = title,
                onDismissRequest = onDismissRequest,
                content = content
            )
            return
        }
        if (!show) return
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

    /**
     * 弹窗关闭后再执行（旧的时序工具，保留给需要"等窗口消失"的场景）。
     *
     * 注意：确认类按钮**不能**用这个 —— `close()` 会回调 onDismiss，插件侧的
     * DialogCallbackGate 会被清掉，之后再回调插件就永远收不到结果
     * （表现：插件设置菜单点一下毫无反应）。确认类走「先回调再 close」。
     */
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
