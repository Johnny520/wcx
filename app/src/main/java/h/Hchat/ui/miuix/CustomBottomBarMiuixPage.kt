package h.Hchat.ui.miuix

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import h.Hchat.hooks.items.custombottombar.CustomBottomBarIconPickResult
import h.Hchat.hooks.items.custombottombar.CustomBottomBarIconPicker
import h.Hchat.hooks.items.custombottombar.CustomBottomBarIconStore
import h.Hchat.hooks.items.custombottombar.CustomBottomBarSettings
import h.Hchat.hooks.items.custombottombar.FloatingBottomBarSettings
import h.Hchat.preferences.HchatStorage
import h.Hchat.ui.FeatureSettingsProvider
import java.util.concurrent.atomic.AtomicBoolean
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CustomBottomBarMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, CustomBottomBarSettings.PREFS_NAME) }
    val floatingSp = remember { HchatStorage.preferences(context, FloatingBottomBarSettings.PREFS_NAME) }
    var enabled by remember {
        mutableStateOf(
            sp.getBoolean(CustomBottomBarSettings.KEY_ENABLE, CustomBottomBarSettings.DEFAULT_ENABLE)
        )
    }
    var modifyIcons by remember {
        mutableStateOf(sp.getBoolean(CustomBottomBarSettings.KEY_MODIFY_ICONS, CustomBottomBarSettings.DEFAULT_MODIFY_ICONS))
    }
    var modifyTitles by remember {
        mutableStateOf(sp.getBoolean(CustomBottomBarSettings.KEY_MODIFY_TITLES, CustomBottomBarSettings.DEFAULT_MODIFY_TITLES))
    }
    var hideTitles by remember {
        mutableStateOf(sp.getBoolean(CustomBottomBarSettings.KEY_HIDE_TITLES, CustomBottomBarSettings.DEFAULT_HIDE_TITLES))
    }
    var hideBar by remember {
        mutableStateOf(sp.getBoolean(CustomBottomBarSettings.KEY_HIDE_BAR, CustomBottomBarSettings.DEFAULT_HIDE_BAR))
    }
    var titles by remember {
        mutableStateOf(CustomBottomBarSettings.TITLE_KEYS.mapIndexed { index, key ->
            CustomBottomBarSettings.normalizeTitle(index, sp.getString(key, null))
        })
    }
    var savedIconPaths by remember {
        mutableStateOf(CustomBottomBarSettings.ICON_KEYS.map { sp.getString(it, null)?.trim().orEmpty() })
    }
    var iconPaths by remember { mutableStateOf(savedIconPaths) }
    val latestIconPaths by rememberUpdatedState(iconPaths)
    val latestSavedIconPaths by rememberUpdatedState(savedIconPaths)
    val pageActive = remember { AtomicBoolean(true) }
    val activity = context as? Activity
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    DisposableEffect(context) {
        pageActive.set(true)
        onDispose {
            pageActive.set(false)
            latestIconPaths.forEach { path ->
                if (path.isNotBlank() && path !in latestSavedIconPaths) {
                    CustomBottomBarIconStore.delete(context, path)
                }
            }
        }
    }

    fun resetDraft() {
        iconPaths.forEach { path ->
            if (path.isNotBlank() && path !in savedIconPaths) {
                CustomBottomBarIconStore.delete(context, path)
            }
        }
        enabled = false
        modifyIcons = CustomBottomBarSettings.DEFAULT_MODIFY_ICONS
        modifyTitles = CustomBottomBarSettings.DEFAULT_MODIFY_TITLES
        hideTitles = CustomBottomBarSettings.DEFAULT_HIDE_TITLES
        hideBar = CustomBottomBarSettings.DEFAULT_HIDE_BAR
        titles = CustomBottomBarSettings.DEFAULT_TITLES
        iconPaths = List(4) { "" }
    }

    fun saveDraft() {
        val normalizedTitles = titles.mapIndexed { index, title ->
            CustomBottomBarSettings.normalizeTitle(index, title)
        }
        val editor = sp.edit()
            .putBoolean(CustomBottomBarSettings.KEY_ENABLE, enabled)
            .putBoolean(CustomBottomBarSettings.KEY_MODIFY_ICONS, modifyIcons)
            .putBoolean(CustomBottomBarSettings.KEY_MODIFY_TITLES, modifyTitles)
            .putBoolean(CustomBottomBarSettings.KEY_HIDE_TITLES, hideTitles)
            .putBoolean(CustomBottomBarSettings.KEY_HIDE_BAR, hideBar)
        CustomBottomBarSettings.TITLE_KEYS.forEachIndexed { index, key ->
            editor.putString(key, normalizedTitles[index])
        }
        CustomBottomBarSettings.ICON_KEYS.forEachIndexed { index, key ->
            editor.putString(key, iconPaths.getOrElse(index) { "" })
        }
        if (enabled) {
            floatingSp.edit().putBoolean(FloatingBottomBarSettings.KEY_ENABLE, false).commit()
        }
        editor.apply()
        savedIconPaths.forEach { oldPath ->
            if (oldPath.isNotBlank() && oldPath !in iconPaths) {
                CustomBottomBarIconStore.delete(context, oldPath)
            }
        }
        titles = normalizedTitles
        savedIconPaths = iconPaths
        Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
    }

    fun updateDraftIcon(index: Int, path: String) {
        val oldDraft = iconPaths.getOrNull(index).orEmpty()
        if (oldDraft.isNotBlank() && oldDraft !in savedIconPaths) {
            CustomBottomBarIconStore.delete(context, oldDraft)
        }
        iconPaths = iconPaths.toMutableList().also { it[index] = path }
    }

    fun pickIcon(index: Int) {
        if (activity == null) {
            Toast.makeText(context, "当前页面无法打开图片选择器", Toast.LENGTH_SHORT).show()
            return
        }
        CustomBottomBarIconPicker.launch(
            activity,
            CustomBottomBarSettings.TAB_KEYS[index]
        ) { result ->
            when (result) {
                is CustomBottomBarIconPickResult.Saved -> {
                    if (pageActive.get()) {
                        updateDraftIcon(index, result.path)
                    } else {
                        CustomBottomBarIconStore.delete(context, result.path)
                    }
                }
                CustomBottomBarIconPickResult.FAILED -> Toast.makeText(
                    context,
                    "图标图片无效或读取失败",
                    Toast.LENGTH_SHORT
                ).show()
                CustomBottomBarIconPickResult.CANCELLED -> Unit
            }
        }
    }

    fun manageIcon(index: Int, label: String) {
        if (iconPaths.getOrNull(index).isNullOrBlank()) {
            pickIcon(index)
            return
        }
        if (activity == null) {
            Toast.makeText(context, "当前页面无法打开图片选择器", Toast.LENGTH_SHORT).show()
            return
        }
        VoiceForwardMiuixDialog.showChoices(
            activity = activity,
            title = "$label 图标",
            summary = "管理当前自定义图标",
            choices = listOf(
                "更换图标" to "重新选择一张本地图片",
                "恢复微信图标" to "移除当前自定义图片"
            ),
            onSelected = { choice ->
                if (choice == 0) pickIcon(index) else updateDraftIcon(index, "")
            },
            onDismiss = {}
        )
    }

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "保存",
                onPrimaryClick = ::saveDraft,
                secondaryText = "重置",
                onSecondaryClick = ::resetDraft,
                middleText = "取消",
                onMiddleClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "底部导航") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "启用自定义底栏",
                        summary = "修改微信首页原生底栏",
                        onCheckedChange = { enabled = it }
                    )
                    if (enabled) {
                        InsetDivider()
                        SwitchRow(
                            checked = modifyIcons,
                            title = "修改图标",
                            summary = "为四个底栏标签分别选择图片",
                            onCheckedChange = { modifyIcons = it }
                        )
                        if (modifyIcons) {
                            BOTTOM_BAR_TAB_LABELS.forEachIndexed { index, label ->
                                InsetDivider()
                                ActionRow(
                                    title = "$label 图标",
                                    summary = if (iconPaths.getOrNull(index).isNullOrBlank()) "使用微信原图标" else "已选择自定义图片",
                                    onClick = { manageIcon(index, label) }
                                )
                            }
                        }
                        InsetDivider()
                        SwitchRow(
                            checked = modifyTitles,
                            title = "修改标题",
                            summary = "自定义四个底栏标签的文字",
                            onCheckedChange = { modifyTitles = it }
                        )
                        if (modifyTitles) {
                            BOTTOM_BAR_TAB_LABELS.forEachIndexed { index, label ->
                                InsetDivider()
                                InputRow(
                                    title = "$label 标题",
                                    summary = "请输入底栏显示文字",
                                    value = titles.getOrElse(index) { CustomBottomBarSettings.DEFAULT_TITLES[index] },
                                    onValueChange = { value ->
                                        titles = titles.toMutableList().also { it[index] = value }
                                    }
                                )
                            }
                        }
                        InsetDivider()
                        SwitchRow(
                            checked = hideTitles,
                            title = "隐藏标题",
                            summary = "只保留底栏图标",
                            onCheckedChange = { hideTitles = it }
                        )
                        InsetDivider()
                        SwitchRow(
                            checked = hideBar,
                            title = "隐藏底栏",
                            summary = "隐藏微信首页整个底栏",
                            onCheckedChange = { hideBar = it }
                        )
                    }
                }
            }
        }
    }
}

private val BOTTOM_BAR_TAB_LABELS = listOf("微信", "通讯", "发现", "我的")

@Composable
internal fun FloatingBottomBarMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, FloatingBottomBarSettings.PREFS_NAME) }
    val customSp = remember { HchatStorage.preferences(context, CustomBottomBarSettings.PREFS_NAME) }
    var enabled by remember {
        mutableStateOf(
            sp.getBoolean(FloatingBottomBarSettings.KEY_ENABLE, FloatingBottomBarSettings.DEFAULT_ENABLE) &&
                sp.getString(FloatingBottomBarSettings.KEY_STYLE, null) ==
                FloatingBottomBarSettings.LEGACY_STYLE_FLOATING
        )
    }
    var glassEnabled by remember {
        mutableStateOf(sp.getBoolean(FloatingBottomBarSettings.KEY_GLASS, FloatingBottomBarSettings.DEFAULT_GLASS))
    }
    var blurRadius by remember {
        mutableStateOf(
            FloatingBottomBarSettings.normalizeBlurRadius(
                sp.getInt(FloatingBottomBarSettings.KEY_BLUR_RADIUS, FloatingBottomBarSettings.DEFAULT_BLUR_RADIUS)
            )
        )
    }
    var hideLabels by remember {
        mutableStateOf(sp.getBoolean(FloatingBottomBarSettings.KEY_HIDE_LABELS, FloatingBottomBarSettings.DEFAULT_HIDE_LABELS))
    }
    var showBadges by remember {
        mutableStateOf(
            sp.getBoolean(
                FloatingBottomBarSettings.KEY_SHOW_BADGES,
                FloatingBottomBarSettings.DEFAULT_SHOW_BADGES
            )
        )
    }
    var vibrationEnabled by remember {
        mutableStateOf(
            sp.getBoolean(
                FloatingBottomBarSettings.KEY_VIBRATION_ENABLED,
                FloatingBottomBarSettings.DEFAULT_VIBRATION_ENABLED
            )
        )
    }
    var vibrationStrength by remember {
        mutableStateOf(
            FloatingBottomBarSettings.normalizeVibrationStrength(
                sp.getInt(
                    FloatingBottomBarSettings.KEY_VIBRATION_STRENGTH,
                    FloatingBottomBarSettings.DEFAULT_VIBRATION_STRENGTH
                )
            )
        )
    }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "悬浮样式") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "启用悬浮底栏",
                        summary = "将微信首页底栏悬浮显示在页面底部",
                        onCheckedChange = {
                            enabled = it
                            if (it) {
                                customSp.edit().putBoolean(CustomBottomBarSettings.KEY_ENABLE, false).commit()
                            }
                            sp.edit()
                                .putBoolean(FloatingBottomBarSettings.KEY_ENABLE, it)
                                .putString(
                                    FloatingBottomBarSettings.KEY_STYLE,
                                    FloatingBottomBarSettings.LEGACY_STYLE_FLOATING
                                )
                                .apply()
                        }
                    )
                    if (enabled) {
                        InsetDivider()
                        SwitchRow(
                            checked = glassEnabled,
                            title = "液态玻璃",
                            summary = "启用玻璃模糊效果",
                            onCheckedChange = {
                                glassEnabled = it
                                sp.edit().putBoolean(FloatingBottomBarSettings.KEY_GLASS, it).apply()
                            }
                        )
                        if (glassEnabled) {
                            InsetDivider()
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                Text(
                                    text = "模糊半径 ${blurRadius}dp",
                                    color = MiuixTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                                Slider(
                                    value = blurRadius.toFloat(),
                                    onValueChange = {
                                        blurRadius = FloatingBottomBarSettings.normalizeBlurRadius(it.toInt())
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    valueRange = FloatingBottomBarSettings.MIN_BLUR_RADIUS.toFloat()..
                                        FloatingBottomBarSettings.MAX_BLUR_RADIUS.toFloat(),
                                    steps = FloatingBottomBarSettings.MAX_BLUR_RADIUS -
                                        FloatingBottomBarSettings.MIN_BLUR_RADIUS - 1,
                                    onValueChangeFinished = {
                                        sp.edit().putInt(FloatingBottomBarSettings.KEY_BLUR_RADIUS, blurRadius).apply()
                                    },
                                    showKeyPoints = true,
                                    keyPoints = listOf(0f, 8f, 16f, 24f, 32f, 40f)
                                )
                            }
                        }
                        InsetDivider()
                        SwitchRow(
                            checked = hideLabels,
                            title = "隐藏标签文字",
                            summary = "只显示微信、通讯、发现和我的图标",
                            onCheckedChange = {
                                hideLabels = it
                                sp.edit().putBoolean(FloatingBottomBarSettings.KEY_HIDE_LABELS, it).apply()
                            }
                        )
                        InsetDivider()
                        SwitchRow(
                            checked = showBadges,
                            title = "显示角标",
                            summary = "显示微信、通讯录、发现和我的未读数字或红点",
                            onCheckedChange = {
                                showBadges = it
                                sp.edit().putBoolean(FloatingBottomBarSettings.KEY_SHOW_BADGES, it).apply()
                            }
                        )
                        InsetDivider()
                        SwitchRow(
                            checked = vibrationEnabled,
                            title = "振动反馈",
                            summary = "点击或切换底栏标签时振动",
                            onCheckedChange = {
                                vibrationEnabled = it
                                sp.edit()
                                    .putBoolean(FloatingBottomBarSettings.KEY_VIBRATION_ENABLED, it)
                                    .apply()
                            }
                        )
                        if (vibrationEnabled) {
                            InsetDivider()
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                Text(
                                    text = "振动强度 ${vibrationStrength}%",
                                    color = MiuixTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                                Slider(
                                    value = vibrationStrength.toFloat(),
                                    onValueChange = {
                                        vibrationStrength = FloatingBottomBarSettings
                                            .normalizeVibrationStrength(it.toInt())
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    valueRange = FloatingBottomBarSettings.MIN_VIBRATION_STRENGTH.toFloat()..
                                        FloatingBottomBarSettings.MAX_VIBRATION_STRENGTH.toFloat(),
                                    steps = FloatingBottomBarSettings.MAX_VIBRATION_STRENGTH -
                                        FloatingBottomBarSettings.MIN_VIBRATION_STRENGTH - 1,
                                    onValueChangeFinished = {
                                        sp.edit()
                                            .putInt(
                                                FloatingBottomBarSettings.KEY_VIBRATION_STRENGTH,
                                                vibrationStrength
                                            )
                                            .apply()
                                    },
                                    showKeyPoints = true,
                                    keyPoints = listOf(1f, 25f, 50f, 75f, 100f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
