package h.Hchat.ui.miuix

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import h.Hchat.hooks.items.membertitle.MemberTitleStore
import java.util.concurrent.atomic.AtomicBoolean
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

object MemberTitleMiuixDialog {
    fun show(
        anchor: View,
        title: String,
        color: String,
        textColor: String,
        summary: String,
        onSave: (String, String, String) -> Unit,
        onReset: () -> Unit
    ) {
        val activity = findActivity(anchor.context) ?: return
        val decor = activity.window?.decorView as? ViewGroup ?: return
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
        val closeDialog = {
            if (!closed.getAndSet(true)) {
                runCatching { compose.disposeComposition() }
                if (root.parent === decor) {
                    decor.removeView(root)
                }
                owner.clear(root)
                owner.clear(decor)
                owner.destroy()
            }
        }
        compose = ComposeView(activity).apply {
            owner.install(this)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                    DialogTheme(activity) {
                        MemberTitleDialogContent(
                            title = title,
                            color = color,
                            textColor = textColor,
                            summary = summary,
                            onClose = closeDialog,
                            onSave = { newTitle, newColor, newTextColor ->
                                onSave(newTitle, newColor, newTextColor)
                                closeDialog()
                            },
                            onReset = {
                                onReset()
                                closeDialog()
                            }
                        )
                    }
                }
            }
        }
        root.addView(compose, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        decor.addView(root)
        root.requestFocus()
    }

    @Composable
    private fun DialogTheme(context: Context, content: @Composable () -> Unit) {
        val colors = if ((context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES
        ) {
            darkColorScheme()
        } else {
            lightColorScheme()
        }
        MiuixTheme(colors = colors, content = content)
    }

    @Composable
    private fun MemberTitleDialogContent(
        title: String,
        color: String,
        textColor: String,
        summary: String,
        onClose: () -> Unit,
        onSave: (String, String, String) -> Unit,
        onReset: () -> Unit
    ) {
        var titleValue by remember { mutableStateOf(title) }
        var colorValue by remember { mutableStateOf(color) }
        var textColorValue by remember { mutableStateOf(textColor) }
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp
        val dialogMaxHeight = (screenHeight * 0.78f).coerceAtMost(560.dp)
        val contentMaxHeight = (screenHeight * 0.58f).coerceIn(240.dp, 430.dp)
        WindowDialog(
            show = true,
            title = "设置群员头衔",
            onDismissRequest = onClose,
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = dialogMaxHeight)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = contentMaxHeight)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (summary.isNotBlank()) {
                                Text(
                                    text = summary,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                                )
                            }
                            DialogInput(
                                label = "头衔名称",
                                value = titleValue,
                                placeholder = "留空使用群主/管理员/群员",
                                onValueChange = { titleValue = it.take(8) }
                            )
                            DialogColorPicker(
                                label = "头衔颜色",
                                summary = "支持单色和渐变",
                                value = colorValue,
                                onValueChange = { colorValue = it.take(19) }
                            )
                            DialogColorPicker(
                                label = "文字颜色",
                                summary = "默认白色，支持渐变",
                                value = textColorValue,
                                onValueChange = { textColorValue = it.take(19) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            text = "重置",
                            onClick = onReset,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = "保存",
                            onClick = {
                                onSave(
                                    titleValue,
                                    MemberTitleStore.cleanColorSpec(colorValue),
                                    MemberTitleStore.cleanColorSpec(textColorValue)
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = "取消",
                            onClick = onClose,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        )
    }

    @Composable
    private fun DialogInput(
        label: String,
        value: String,
        placeholder: String,
        onValueChange: (String) -> Unit
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Text(
                text = label,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.secondaryVariant)
                    .border(
                        width = 1.dp,
                        color = MiuixTheme.colorScheme.primary.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(8.dp)
                    )
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
    }

    @Composable
    private fun DialogColorPicker(
        label: String,
        summary: String,
        value: String,
        onValueChange: (String) -> Unit
    ) {
        var expanded by remember { mutableStateOf(false) }
        val currentValue by rememberUpdatedState(value)
        val parts = colorParts(value)
        var editEnd by remember { mutableStateOf(parts.second.isNotEmpty()) }
        val selectedColor = if (editEnd) parts.second.ifEmpty { parts.first } else parts.first
        Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = label,
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        ClickHintTag()
                    }
                    Text(
                        text = "$summary，支持 #RRGGBB / #AARRGGBB / #A,#B",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp
                    )
                }
                ColorPreviewDot(parts.first)
            }
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(it.take(19)) },
                singleLine = true,
                textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.secondaryVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ColorChip(
                    label = "起始色",
                    value = parts.first,
                    selected = !editEnd,
                    onClick = { editEnd = false },
                    modifier = Modifier.weight(1f)
                )
                ColorChip(
                    label = "结束色",
                    value = parts.second,
                    selected = editEnd,
                    onClick = { editEnd = true },
                    modifier = Modifier.weight(1f)
                )
            }
            PsColorPicker(
                value = selectedColor,
                onValueChange = { picked ->
                    val latestParts = colorParts(currentValue)
                    val next = if (editEnd) {
                        composeColorSpec(latestParts.first.ifEmpty { picked }, picked)
                    } else {
                        composeColorSpec(picked, latestParts.second)
                    }
                    onValueChange(next)
                },
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                text = "先选起始色或结束色，再用色盘取色；清空输入框可恢复默认",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    @Composable
    private fun ClickHintTag() {
        Text(
            text = "单击",
            color = MiuixTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }

    @Composable
    private fun ColorChip(
        label: String,
        value: String,
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val borderColor = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ColorPreviewDot(value)
            Column {
                Text(text = label, color = MiuixTheme.colorScheme.onSurface, fontSize = 12.sp)
                Text(
                    text = value.ifEmpty { "未设置" },
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 11.sp
                )
            }
        }
    }

    @Composable
    private fun ColorPreviewDot(value: String) {
        val color = composeColorFromHex(value) ?: MiuixTheme.colorScheme.surfaceVariant
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color)
                .border(1.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (value.isEmpty()) {
                Text(text = "-", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
            }
        }
    }

    @Composable
    private fun PsColorPicker(
        value: String,
        onValueChange: (String) -> Unit,
        modifier: Modifier = Modifier
    ) {
        val selected = colorPickerSelection(value)
        val markerColor = MiuixTheme.colorScheme.onSurface
        val density = LocalDensity.current
        val paletteSize = 204.dp
        val hueBarWidth = 36.dp
        val paletteSizePx = remember(density) { with(density) { paletteSize.roundToPx() } }
        val hueBarWidthPx = remember(density) { with(density) { hueBarWidth.roundToPx() } }
        val paletteBitmap = remember(selected.hue, paletteSizePx) {
            buildSvPaletteBitmap(selected.hue, paletteSizePx)
        }
        val hueBitmap = remember(paletteSizePx, hueBarWidthPx) {
            buildHuePaletteBitmap(hueBarWidthPx, paletteSizePx)
        }
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Canvas(
                modifier = Modifier
                    .size(paletteSize)
                    .pointerInput(selected.hue) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            onValueChange(colorFromSvOffset(selected.hue, down.position, size.width.toFloat(), size.height.toFloat()))
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                onValueChange(colorFromSvOffset(selected.hue, change.position, size.width.toFloat(), size.height.toFloat()))
                                change.consume()
                                if (!change.pressed) break
                            }
                        }
                    }
            ) {
                drawImage(paletteBitmap)
                val marker = Offset(selected.saturation * size.width, (1f - selected.value) * size.height)
                drawCircle(color = markerColor, radius = 8f, center = marker)
                drawCircle(color = Color.White, radius = 5f, center = marker)
            }
            Canvas(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(width = hueBarWidth, height = paletteSize)
                    .clip(RoundedCornerShape(12.dp))
                    .pointerInput(selected.saturation, selected.value) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            onValueChange(colorFromHueOffset(selected.saturation, selected.value, down.position.y, size.height.toFloat()))
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                onValueChange(colorFromHueOffset(selected.saturation, selected.value, change.position.y, size.height.toFloat()))
                                change.consume()
                                if (!change.pressed) break
                            }
                        }
                    }
            ) {
                drawImage(hueBitmap)
                val markerY = (selected.hue / 360f).coerceIn(0f, 1f) * size.height
                drawCircle(color = markerColor, radius = 9f, center = Offset(size.width / 2f, markerY))
                drawCircle(color = Color.White, radius = 5.5f, center = Offset(size.width / 2f, markerY))
            }
        }
    }

    private fun buildSvPaletteBitmap(hue: Float, sizePx: Int): ImageBitmap {
        val safeSize = sizePx.coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(safeSize * safeSize)
        var index = 0
        for (y in 0 until safeSize) {
            val value = (1f - (y.toFloat() / (safeSize - 1))).coerceIn(0f, 1f)
            for (x in 0 until safeSize) {
                val saturation = (x.toFloat() / (safeSize - 1)).coerceIn(0f, 1f)
                pixels[index++] = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))
            }
        }
        bitmap.setPixels(pixels, 0, safeSize, 0, 0, safeSize, safeSize)
        return bitmap.asImageBitmap()
    }

    private fun buildHuePaletteBitmap(widthPx: Int, heightPx: Int): ImageBitmap {
        val safeWidth = widthPx.coerceAtLeast(2)
        val safeHeight = heightPx.coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(safeWidth * safeHeight)
        var index = 0
        for (y in 0 until safeHeight) {
            val hue = (y.toFloat() / (safeHeight - 1)).coerceIn(0f, 1f) * 360f
            val color = AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f))
            repeat(safeWidth) {
                pixels[index++] = color
            }
        }
        bitmap.setPixels(pixels, 0, safeWidth, 0, 0, safeWidth, safeHeight)
        return bitmap.asImageBitmap()
    }

    private data class ColorPickerSelection(val hue: Float, val saturation: Float, val value: Float)

    private fun colorPickerSelection(value: String): ColorPickerSelection {
        val normalized = MemberTitleStore.cleanColor(value)
        if (normalized.isEmpty()) return ColorPickerSelection(0f, 1f, 1f)
        return runCatching {
            val hsv = FloatArray(3)
            AndroidColor.colorToHSV(AndroidColor.parseColor(normalized), hsv)
            ColorPickerSelection(hsv[0], hsv[1], hsv[2])
        }.getOrDefault(ColorPickerSelection(0f, 1f, 1f))
    }

    private fun colorFromSvOffset(hue: Float, offset: Offset, width: Float, height: Float): String {
        val saturation = (offset.x / width).coerceIn(0f, 1f)
        val value = (1f - (offset.y / height)).coerceIn(0f, 1f)
        val color = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))
        return String.format("#%06X", 0xFFFFFF and color)
    }

    private fun colorFromHueOffset(saturation: Float, value: Float, y: Float, height: Float): String {
        val hue = (y / height).coerceIn(0f, 1f) * 360f
        val color = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))
        return String.format("#%06X", 0xFFFFFF and color)
    }

    private fun colorParts(value: String): Pair<String, String> {
        val cleaned = MemberTitleStore.cleanColorSpec(value)
        if (cleaned.isEmpty()) return "" to ""
        val parts = cleaned.split(',').take(2)
        return parts.getOrElse(0) { "" } to parts.getOrElse(1) { "" }
    }

    private fun composeColorSpec(start: String, end: String): String {
        val first = MemberTitleStore.cleanColor(start)
        val second = MemberTitleStore.cleanColor(end)
        if (first.isEmpty()) return ""
        return if (second.isEmpty() || second == first) first else "$first,$second"
    }

    private fun composeColorFromHex(value: String): Color? {
        val normalized = MemberTitleStore.cleanColor(value)
        if (normalized.isEmpty()) return null
        return runCatching { Color(AndroidColor.parseColor(normalized)) }.getOrNull()
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

    private fun findActivity(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }
}
