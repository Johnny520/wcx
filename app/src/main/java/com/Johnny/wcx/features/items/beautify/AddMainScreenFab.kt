package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import com.Johnny.wcx.constants.PackageNames
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Drag_handle
import com.composables.icons.materialsymbols.outlinedfilled.Add
import com.composables.icons.materialsymbols.outlinedfilled.Bookmark
import com.composables.icons.materialsymbols.outlinedfilled.Camera
import com.composables.icons.materialsymbols.outlinedfilled.Cancel
import com.composables.icons.materialsymbols.outlinedfilled.Check_circle
import com.composables.icons.materialsymbols.outlinedfilled.Extension
import com.composables.icons.materialsymbols.outlinedfilled.Favorite
import com.composables.icons.materialsymbols.outlinedfilled.Movie
import com.composables.icons.materialsymbols.outlinedfilled.Qr_code_scanner
import com.composables.icons.materialsymbols.outlinedfilled.Settings
import com.composables.icons.materialsymbols.outlinedfilled.Update
import com.composables.icons.materialsymbols.outlinedfilled.Wallet
import dev.ujhhgtg.reflekt.firstMethod
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.activity.settings.SettingsActivity
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.ui.WeMainActivityBeautifyApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.IconButton
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.InjectedUiTheme
import com.Johnny.wcx.ui.utils.LifecycleOwnerProvider
import com.Johnny.wcx.ui.utils.rootView
import com.Johnny.wcx.ui.utils.setLifecycleOwner
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.ui.utils.findViewWhich
import com.tencent.mm.view.recyclerview.WxRecyclerView
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.killHost
import com.Johnny.wcx.utils.restartHost
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Feature(name = "主屏幕添加 FAB", categories = ["界面美化"], description = "向微信主屏幕添加浮动操作按钮")
object AddMainScreenFab : ClickableFeature() {

    private const val TAG = "AddMainScreenFab"
    private const val KEY_FAB_CONFIG = "fab_button_configs_json"

    @Serializable
    enum class FabType {
        START_ACTIVITY,
        MARK_ALL_READ,
        MODULE_SETTINGS,
        RESTART_HOST,
        FORCE_STOP
    }

    @Serializable
    data class FabItemConfig(
        val id: String,
        val type: FabType,
        val name: String,
        val iconName: String,
        val targetActivity: String? = null
    )

    // 可选图标池映射
    private val iconPool by lazy {
        mapOf(
            "Qr_code_scanner" to MaterialSymbols.OutlinedFilled.Qr_code_scanner,
            "Camera" to MaterialSymbols.OutlinedFilled.Camera,
            "Wallet" to MaterialSymbols.OutlinedFilled.Wallet,
            "Movie" to MaterialSymbols.OutlinedFilled.Movie,
            "Settings" to MaterialSymbols.OutlinedFilled.Settings,
            "Extension" to MaterialSymbols.OutlinedFilled.Extension,
            "Cancel" to MaterialSymbols.OutlinedFilled.Cancel,
            "Update" to MaterialSymbols.OutlinedFilled.Update,
            "Bookmark" to MaterialSymbols.OutlinedFilled.Bookmark,
            "Favorite" to MaterialSymbols.OutlinedFilled.Favorite,
            "Check_circle" to MaterialSymbols.OutlinedFilled.Check_circle
        )
    }

    // 预设 Activity 映射
    private val presets = mapOf(
        "扫一扫" to "com.tencent.mm.plugin.scanner.ui.BaseScanUI",
        "朋友圈" to "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI",
        "钱包" to "com.tencent.mm.plugin.mall.ui.MallIndexUIv2",
        "视频号" to "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI",
        "设置" to "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI",
        "收藏夹" to "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI"
    )

    // 默认配置列表
    private val defaultList = listOf(
        FabItemConfig("1", FabType.START_ACTIVITY, "扫一扫", "Qr_code_scanner", "com.tencent.mm.plugin.scanner.ui.BaseScanUI"),
        FabItemConfig("2", FabType.START_ACTIVITY, "朋友圈", "Camera", "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI"),
        FabItemConfig("3", FabType.START_ACTIVITY, "钱包", "Wallet", "com.tencent.mm.plugin.mall.ui.MallIndexUIv2"),
        FabItemConfig("4", FabType.START_ACTIVITY, "视频号", "Movie", "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI"),
        FabItemConfig("5", FabType.START_ACTIVITY, "设置", "Settings", "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"),
        FabItemConfig("6", FabType.MODULE_SETTINGS, "模块设置", "Extension"),
        FabItemConfig("9", FabType.RESTART_HOST, "重启微信", "Update"),
        FabItemConfig("7", FabType.FORCE_STOP, "强行停止", "Cancel"),
        FabItemConfig("8", FabType.MARK_ALL_READ, "清空未读", "Check_circle")
    )

    private fun loadConfig(): List<FabItemConfig> {
        val jsonStr = WePrefs.getString(KEY_FAB_CONFIG) ?: return defaultList
        return try {
            Json.decodeFromString<List<FabItemConfig>>(jsonStr)
        } catch (e: Exception) {
            WeLogger.e(TAG, "解析依赖失败，还原默认配置", e)
            defaultList
        }
    }

    private fun saveConfig(list: List<FabItemConfig>) {
        try {
            val jsonStr = Json.encodeToString(list)
            WePrefs.putString(KEY_FAB_CONFIG, jsonStr)
        } catch (e: Exception) {
            WeLogger.e(TAG, "保存配置失败", e)
        }
    }

    private fun startActivityByName(context: Context, className: String) {
        val intent = Intent().apply {
            setClassName(context.packageName, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override fun onEnable() {
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            try {
            val activity = thisObject.reflekt()
                .firstField {
                    type = "com.tencent.mm.ui.MMFragmentActivity"
                }
                .get()!! as Activity

            val viewPager = thisObject.reflekt()
                .firstField {
                    name = "mViewPager"
                }
                .get()!! as android.view.ViewGroup
            val tabsAdapter = thisObject.reflekt()
                .firstField {
                    name = "mTabsAdapter"
                }
                .get()!!

            val currentTabState = mutableIntStateOf(0)
            tabsAdapter.reflekt()
                .firstMethod { name = "onPageScrolled" }
                .hookBefore {
                    val position = args[0] as Int
                    val positionOffset = args[1] as Float
                    if (positionOffset == 0f) {
                        currentTabState.intValue = position
                    }
                }

            // 监听聊天列表滚动：向下滑动时隐藏 FAB，向上滑动时恢复显示
            val scrolledAwayState = mutableStateOf(false)
            val fabRoot = activity.rootView
            var scrollObserverAttached = false
            val attachScrollListener = lambda@{
                if (scrollObserverAttached) return@lambda
                val list = fabRoot.findViewWhich<android.view.View> { it is WxRecyclerView }
                    ?: error("chat list not found")
                scrollObserverAttached = true
                fun getScrollY(): Int {
                    return runCatching {
                        list.reflekt().firstMethod { name = "computeVerticalScrollOffset"; superclass() }.invoke(list) as Int
                    }.getOrDefault(0)
                }
                var lastOffset = getScrollY()
                val scrollListener = android.view.ViewTreeObserver.OnScrollChangedListener {
                    runCatching {
                        val currentOffset = getScrollY()
                        val dy = currentOffset - lastOffset
                        if (dy > 20) {
                            scrolledAwayState.value = true
                        } else if (dy < -20) {
                            scrolledAwayState.value = false
                        }
                        lastOffset = currentOffset
                    }
                }
                list.viewTreeObserver.addOnScrollChangedListener(scrollListener)
                // 在 Activity 销毁时移除监听器，防止内存泄漏
                fabRoot.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}
                    override fun onViewDetachedFromWindow(v: View) {
                        runCatching {
                            if (list.viewTreeObserver.isAlive) {
                                list.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
                            }
                        }
                        fabRoot.removeOnAttachStateChangeListener(this)
                    }
                })
            }
            intArrayOf(0, 200, 800, 2_000).forEach { delayMs ->
                fabRoot.postDelayed({
                    if (scrollObserverAttached) return@postDelayed
                    runCatching(attachScrollListener)
                        .onFailure { WeLogger.w("AddMainScreenFab", "failed to attach chat list scroll observer", it) }
                }, delayMs.toLong())
            }

            val configList = loadConfig()
            val menuItems = mutableMapOf<String, Pair<ImageVector, () -> Unit>>()

            configList.forEach { item ->
                val icon = iconPool[item.iconName] ?: MaterialSymbols.OutlinedFilled.Add
                val action: () -> Unit = when (item.type) {
                    FabType.START_ACTIVITY -> {
                        { item.targetActivity?.let {
                            try {
                                startActivityByName(activity, it)
                            } catch (e: Throwable) {
                                WeLogger.e(TAG, "START_ACTIVITY 启动失败: $it", e)
                            }
                        } }
                    }

                    FabType.MARK_ALL_READ -> {
                        {
                            try {
                                WeConversationApi.markAllAsRead()
                                showToast("已将全部未读消息标为已读")
                            } catch (e: Throwable) {
                                WeLogger.e(TAG, "清空未读失败", e)
                            }
                        }
                    }

                    FabType.MODULE_SETTINGS -> {
                        {
                            try {
                                activity.startActivity(Intent(activity, SettingsActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    setPackage(PackageNames.MODULE)
                                })
                            } catch (e: Throwable) {
                                WeLogger.e(TAG, "MODULE_SETTINGS 启动失败", e)
                            }
                        }
                    }

                    FabType.RESTART_HOST -> {
                        { restartHost() }
                    }

                    FabType.FORCE_STOP -> {
                        { killHost() }
                    }
                }
                menuItems[item.name] = icon to action
            }

            val lifecycleOwner = LifecycleOwnerProvider.lifecycleOwner
            val root = activity.rootView

            root.addView(
                ComposeView(activity).apply {
                    setLifecycleOwner(lifecycleOwner)

                    setContent {
                        InjectedUiTheme {
                            val backgroundColor = if (isSystemInDarkTheme()) Color(0xFF191919) else Color(0xFFF7F7F7)
                            val activeColor = MaterialTheme.colorScheme.primary

                            var expanded by remember { mutableStateOf(false) }
                            val currentTab by currentTabState
                            val isHomeTab = currentTab == 0
                            val scrolledAway by scrolledAwayState

                            AnimatedVisibility(
                                visible = isHomeTab && !scrolledAway,
                                enter = fadeIn(animationSpec = tween(durationMillis = 150)),
                                exit = fadeOut(animationSpec = tween(durationMillis = 150))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(bottom = 60.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(16.dp),
                                        horizontalAlignment = Alignment.End,
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                            horizontalAlignment = Alignment.End
                                        ) {
                                            menuItems.entries.forEachIndexed { index, (name, pair) ->
                                                val itemDelay = index * 35
                                                val reverseDelay = (menuItems.size - 1 - index) * 35

                                                AnimatedVisibility(
                                                    visible = expanded,
                                                    enter = fadeIn(
                                                        animationSpec = tween(durationMillis = 160, delayMillis = reverseDelay, easing = EaseOut)
                                                    ) + slideInVertically(
                                                        animationSpec = tween(durationMillis = 180, delayMillis = reverseDelay, easing = EaseOutCubic),
                                                        initialOffsetY = { it / 2 }
                                                    ),
                                                    exit = fadeOut(
                                                        animationSpec = tween(durationMillis = 100, delayMillis = itemDelay, easing = EaseIn)
                                                    ) + slideOutVertically(
                                                        animationSpec = tween(durationMillis = 100, delayMillis = itemDelay, easing = EaseInCubic),
                                                        targetOffsetY = { it / 2 }
                                                    )
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                    ) {
                                                        Surface(
                                                            shape = MaterialTheme.shapes.large,
                                                            color = backgroundColor,
                                                            tonalElevation = 2.dp,
                                                            shadowElevation = 2.dp
                                                        ) {
                                                            Text(
                                                                text = name,
                                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                                color = activeColor,
                                                                fontSize = 14.sp,
                                                                fontWeight = FontWeight.Medium
                                                            )
                                                        }

                                                        SmallFloatingActionButton(
                                                            onClick = {
                                                                pair.second()
                                                                expanded = false
                                                            },
                                                            containerColor = backgroundColor,
                                                            shape = CircleShape,
                                                            elevation = FloatingActionButtonDefaults.elevation(2.dp)
                                                        ) {
                                                            Icon(pair.first, contentDescription = null, tint = activeColor)
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        FloatingActionButton(
                                            onClick = { expanded = !expanded },
                                            containerColor = backgroundColor,
                                            shape = CircleShape
                                        ) {
                                            val rotation by animateFloatAsState(if (expanded) 45f else 0f)
                                            Icon(
                                                MaterialSymbols.OutlinedFilled.Add,
                                                contentDescription = null,
                                                tint = activeColor,
                                                modifier = Modifier.rotate(rotation)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            )
            } catch (e: Throwable) {
                WeLogger.e(TAG, "onEnable hookAfter 异常", e)
            }
        }
    }

    private fun showAddFabDialog(
        context: Context,
        existingItems: List<FabItemConfig>,
        onAdd: (FabItemConfig) -> Unit,
    ) {
        showComposeDialog(context) {
            var newType by remember { mutableStateOf(FabType.START_ACTIVITY) }
            var newName by remember { mutableStateOf("") }
            var newActivity by remember { mutableStateOf("") }
            var newIconName by remember { mutableStateOf("Qr_code_scanner") }

            val hasType = { type: FabType -> existingItems.any { it.type == type } }
            val canAdd = newName.isNotBlank() &&
                    (newType != FabType.START_ACTIVITY || newActivity.isNotBlank())

            AlertDialogContent(
                title = { Text("添加快捷按钮") },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            "选择要放到主屏幕 FAB 菜单里的功能。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "功能类型",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp)
                        )

                        val typeOptions = listOf(
                            FabType.START_ACTIVITY to "启动 Activity",
                            FabType.MARK_ALL_READ to "清空未读",
                            FabType.MODULE_SETTINGS to "模块设置",
                            FabType.RESTART_HOST to "重启微信",
                            FabType.FORCE_STOP to "强行停止",
                        )
                        typeOptions.forEach { (type, label) ->
                            val unavailable = type != FabType.START_ACTIVITY && hasType(type)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !unavailable) { newType = type },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = newType == type,
                                    onClick = { newType = type },
                                    enabled = !unavailable,
                                )
                                Text(
                                    text = if (unavailable) "$label（已添加）" else label,
                                    color = if (unavailable) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                                )
                            }
                        }

                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("按钮名称") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )

                        if (newType == FabType.START_ACTIVITY) {
                            OutlinedTextField(
                                value = newActivity,
                                onValueChange = { newActivity = it },
                                label = { Text("Activity 完整类名") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            )
                            Text(
                                "预设入口",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                            presets.forEach { (presetName, presetClass) ->
                                Text(
                                    text = "$presetName  ·  $presetClass",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            newActivity = presetClass
                                            if (newName.isBlank()) newName = presetName
                                        }
                                        .padding(vertical = 6.dp),
                                )
                            }
                        }

                        Text(
                            "图标",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            iconPool.forEach { (iconName, icon) ->
                                val selected = newIconName == iconName
                                Surface(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clickable { newIconName = iconName },
                                    shape = CircleShape,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    tonalElevation = if (selected) 2.dp else 0.dp,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = iconName,
                                            tint = if (selected) {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (!canAdd) return@TextButton
                            onAdd(
                                FabItemConfig(
                                    id = UUID.randomUUID().toString(),
                                    type = newType,
                                    name = newName.trim(),
                                    iconName = newIconName,
                                    targetActivity = if (newType == FabType.START_ACTIVITY) newActivity.trim() else null,
                                )
                            )
                            onDismiss()
                        },
                        enabled = canAdd,
                    ) { Text("添加") }
                },
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var currentItems by remember { mutableStateOf(loadConfig()) }
            var draggingIndex by remember { mutableStateOf<Int?>(null) }
            var dragOffset by remember { mutableStateOf(0f) }
            val listState = rememberLazyListState()
            val coroutineScope = rememberCoroutineScope()

            fun moveItem(from: Int, to: Int) {
                if (from == to || from !in currentItems.indices || to !in currentItems.indices) return
                val updated = currentItems.toMutableList().apply {
                    add(to, removeAt(from))
                }
                currentItems = updated
                saveConfig(updated)
            }

            AlertDialogContent(
                title = { Text("FAB 悬浮按钮") },
                text = {
                    DefaultColumn {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("主屏幕快捷入口", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "长按拖动手柄调整顺序，改动会立即生效",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    showAddFabDialog(context, currentItems) { newItem ->
                                        val updated = currentItems + newItem
                                        currentItems = updated
                                        saveConfig(updated)
                                    }
                                }
                            ) {
                                Icon(MaterialSymbols.OutlinedFilled.Add, contentDescription = null)
                                Text("添加")
                            }
                        }

                        if (currentItems.isEmpty()) {
                            Text(
                                "还没有快捷入口，点击右上角“添加”开始配置。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 28.dp),
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp)
                                    .padding(top = 8.dp),
                                userScrollEnabled = draggingIndex == null,
                            ) {
                                itemsIndexed(
                                    items = currentItems,
                                    key = { _, item -> item.id },
                                ) { index, item ->
                                    val isDragging = index == draggingIndex
                                    val description = when (item.type) {
                                        FabType.START_ACTIVITY -> item.targetActivity ?: "启动 Activity"
                                        FabType.MARK_ALL_READ -> "将全部未读消息标记为已读"
                                        FabType.MODULE_SETTINGS -> "打开模块设置"
                                        FabType.RESTART_HOST -> "重新启动微信进程"
                                        FabType.FORCE_STOP -> "终止微信进程"
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .zIndex(if (isDragging) 1f else 0f)
                                            .graphicsLayer {
                                                translationY = if (isDragging) dragOffset else 0f
                                                scaleX = if (isDragging) 1.02f else 1f
                                                scaleY = if (isDragging) 1.02f else 1f
                                                shadowElevation = if (isDragging) 8.dp.toPx() else 0f
                                            }
                                            .then(if (isDragging) Modifier else Modifier.animateItem())
                                            .padding(vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .pointerInput(item.id) {
                                                    detectDragGesturesAfterLongPress(
                                                        onDragStart = {
                                                            draggingIndex = listState.layoutInfo.visibleItemsInfo
                                                                .firstOrNull { it.key == item.id }
                                                                ?.index
                                                                ?: index
                                                            dragOffset = 0f
                                                        },
                                                        onDragCancel = {
                                                            draggingIndex = null
                                                            dragOffset = 0f
                                                        },
                                                        onDragEnd = {
                                                            draggingIndex = null
                                                            dragOffset = 0f
                                                        },
                                                        onDrag = { change, amount ->
                                                            change.consume()
                                                            val currentIndex = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                                            dragOffset += amount.y
                                                            val currentInfo = listState.layoutInfo.visibleItemsInfo
                                                                .firstOrNull { it.index == currentIndex }
                                                                ?: return@detectDragGesturesAfterLongPress
                                                            val start = currentInfo.offset + dragOffset
                                                            val end = start + currentInfo.size
                                                            val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { targetInfo ->
                                                                if (targetInfo.index == currentIndex) {
                                                                    false
                                                                } else if (dragOffset > 0f) {
                                                                    targetInfo.index > currentIndex &&
                                                                            end > targetInfo.offset + targetInfo.size / 2
                                                                } else {
                                                                    targetInfo.index < currentIndex &&
                                                                            start < targetInfo.offset + targetInfo.size / 2
                                                                }
                                                            }
                                                            if (target != null) {
                                                                moveItem(currentIndex, target.index)
                                                                dragOffset -= target.offset - currentInfo.offset
                                                                draggingIndex = target.index
                                                            }

                                                            val viewport = listState.layoutInfo
                                                            val center = currentInfo.offset + dragOffset + currentInfo.size / 2
                                                            when {
                                                                center < viewport.viewportStartOffset + 56 && listState.canScrollBackward ->
                                                                    coroutineScope.launch { listState.scrollBy(-12f) }

                                                                center > viewport.viewportEndOffset - 56 && listState.canScrollForward ->
                                                                    coroutineScope.launch { listState.scrollBy(12f) }
                                                            }
                                                        },
                                                    )
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Drag_handle,
                                                contentDescription = "拖动以调整顺序",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Icon(
                                            imageVector = iconPool[item.iconName] ?: MaterialSymbols.OutlinedFilled.Add,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .size(28.dp)
                                                .padding(end = 4.dp),
                                        )
                                        Column(modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 8.dp)) {
                                            Text(item.name, fontWeight = FontWeight.Medium)
                                            Text(
                                                description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                val updated = currentItems.filterNot { it.id == item.id }
                                                currentItems = updated
                                                saveConfig(updated)
                                            },
                                        ) {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Delete,
                                                contentDescription = "删除 ${item.name}",
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onDismiss) { Text("完成") } },
            )
        }
    }
}
