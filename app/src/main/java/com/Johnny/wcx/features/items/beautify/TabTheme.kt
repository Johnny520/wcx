package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.load
import coil3.request.CachePolicy
import coil3.request.crossfade
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.activity.TransparentActivity
import com.Johnny.wcx.features.api.ui.WeMainActivityBeautifyApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.IconButton
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.LifecycleOwnerProvider
import com.Johnny.wcx.ui.utils.setLifecycleOwner
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.fs.createDirsSafe
import com.Johnny.wcx.utils.nul
import com.Johnny.wcx.utils.serialization.DefaultJson
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Delete_forever
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Wallpaper
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Feature(
    name = "四 Tab 主题背景",
    categories = ["界面美化"],
    description = "为微信主页、通讯录、发现、我四个页面分别设置背景图片，支持主题包导入导出"
)
object TabTheme : ClickableFeature() {

    private const val TAG = "TabTheme"
    private const val OVERLAY_TAG_PREFIX = "wcx_tab_theme_"

    private val themesDir by lazy { (KnownPaths.moduleData / "tab_themes").createDirsSafe() }
    private val currentThemeDir by lazy { (themesDir / "current").createDirsSafe() }

    private var tabThemeEnabled by prefOption("tab_theme_enabled", false)
    private var opacity by prefOption("tab_theme_opacity", 0.15f)
    private var transparentStatusBar by prefOption("tab_theme_transparent_status_bar", true)

    // 持有背景容器引用，用于生命周期显隐控制
    private var bgContainer: android.widget.FrameLayout? = null

    @Serializable
    data class TabThemeConfig(
        val name: String = "默认主题",
        val author: String = "",
        val description: String = "",
        val tabBackgrounds: Map<Int, String> = emptyMap()
    )

    private const val KEY_TAB_CONFIG = "tab_theme_config"

    private val tabNames = mapOf(
        0 to "主页",
        1 to "通讯录",
        2 to "发现",
        3 to "我"
    )

    private fun loadConfig(): TabThemeConfig {
        val json = WePrefs.getString(KEY_TAB_CONFIG) ?: return TabThemeConfig()
        return runCatching { DefaultJson.decodeFromString<TabThemeConfig>(json) }
            .getOrElse { TabThemeConfig() }
    }

    private fun saveConfig(config: TabThemeConfig) {
        WePrefs.putString(KEY_TAB_CONFIG, DefaultJson.encodeToString(config))
    }

    private fun getTabBackgroundFile(tabIndex: Int): Path {
        return currentThemeDir / "tab_$tabIndex.webp"
    }

    private fun hasTabBackground(tabIndex: Int): Boolean {
        return getTabBackgroundFile(tabIndex).exists()
    }

    override fun onEnable() {
        if (!tabThemeEnabled) return
        applyTheme()
    }

    private fun applyTheme() {
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            val activity = thisObject.reflekt()
                .firstField { type = "com.tencent.mm.ui.MMFragmentActivity" }
                .get()!! as Activity

            val viewPager = thisObject.reflekt()
                .firstField { name = "mViewPager" }
                .get()!! as ViewGroup
            val tabsAdapter = thisObject.reflekt()
                .firstField { name = "mTabsAdapter" }
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

            val lifecycleOwner = LifecycleOwnerProvider.lifecycleOwner

            // 沉浸式全屏渲染：将背景加到 decorView，覆盖状态栏和导航栏
            if (transparentStatusBar) {
                applyImmersiveStatusBar(activity)
            }

            val decor = activity.window?.decorView as? ViewGroup ?: return@hookAfter

            // 检查是否已有背景容器，避免重复添加
            val existingTag = "${OVERLAY_TAG_PREFIX}container"
            var localContainer = decor.findViewWithTag<android.widget.FrameLayout>(existingTag)
            if (localContainer == null) {
                localContainer = android.widget.FrameLayout(activity).apply {
                    tag = existingTag
                    setLifecycleOwner(lifecycleOwner)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                decor.addView(localContainer)
            }
            // 保存引用，供生命周期回调控制显隐
            bgContainer = localContainer

            val tabImageViews = mutableMapOf<Int, ImageView>()

            for (tabIndex in 0..3) {
                val tabFile = getTabBackgroundFile(tabIndex)
                if (tabFile.exists()) {
                    val iv = ImageView(activity).apply {
                        tag = "${OVERLAY_TAG_PREFIX}tab_$tabIndex"
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        load(tabFile.toFile()) {
                            crossfade(true)
                            memoryCachePolicy(CachePolicy.DISABLED)
                            diskCachePolicy(CachePolicy.DISABLED)
                        }
                        alpha = opacity
                    }
                    localContainer.addView(iv)
                    tabImageViews[tabIndex] = iv
                }
            }

            tabsAdapter.reflekt()
                .firstMethod { name = "onPageSelected" }
                .hookAfter {
                    val position = args[0] as Int
                    tabImageViews.forEach { (idx, iv) ->
                        iv.visibility = if (idx == position) View.VISIBLE else View.GONE
                    }
                }

            // ── 生命周期挂钩：离开主页时隐藏背景，避免与聊天背景重叠 ──
            // 当聊天 Activity 覆盖在主 Activity 之上时，onPause 会被调用
            activity.javaClass.reflekt()
                .firstMethod { name = "onResume"; parameterCount = 0 }
                .hookAfter {
                    if (tabThemeEnabled && bgContainer != null) {
                        bgContainer!!.visibility = View.VISIBLE
                    }
                }

            activity.javaClass.reflekt()
                .firstMethod { name = "onPause"; parameterCount = 0 }
                .hookAfter {
                    bgContainer?.visibility = View.GONE
                }
        }
    }

    private fun applyImmersiveStatusBar(activity: Activity) {
        runCatching {
            val window = activity.window ?: return
            window.statusBarColor = android.graphics.Color.TRANSPARENT

            // 根据深色模式调整状态栏图标颜色，确保在背景图片上可见
            val isDark = (activity.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES

            val decor = window.decorView as? ViewGroup ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
            } else {
                @Suppress("DEPRECATION")
                var flags = decor.systemUiVisibility
                flags = flags or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                decor.systemUiVisibility = flags
            }

            // Use WindowInsetsControllerCompat for consistent behavior across API levels
            androidx.core.view.WindowInsetsControllerCompat(window, decor).apply {
                isAppearanceLightStatusBars = !isDark
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to apply immersive status bar", it)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var showThemeList by remember { mutableStateOf(false) }
            var showSaveDialog by remember { mutableStateOf(false) }
            var saveName by remember { mutableStateOf("我的主题") }

            if (showSaveDialog) {
                AlertDialogContent(
                    title = { Text("保存为主题") },
                    text = {
                        DefaultColumn {
                            Text("主题名称", style = MaterialTheme.typography.labelLarge)
                            androidx.compose.material3.OutlinedTextField(
                                value = saveName,
                                onValueChange = { saveName = it },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("取消") } },
                    confirmButton = {
                        Button(
                            enabled = saveName.isNotBlank(),
                            onClick = {
                                saveCurrentAsTheme(saveName.trim())
                                showToast("主题已保存: ${saveName.trim()}")
                                showSaveDialog = false
                            }
                        ) { Text("保存") }
                    }
                )
            } else if (showThemeList) {
                ThemeListScreen(
                    onDismiss = { showThemeList = false },
                    onSelect = { themeName ->
                        switchToTheme(themeName)
                        showThemeList = false
                        showToast("已切换主题: $themeName")
                    },
                    onDelete = { themeName ->
                        deleteTheme(themeName)
                        showToast("已删除主题: $themeName")
                    },
                    onExport = { themeName ->
                        exportTheme(context, themeName)
                    },
                    onImport = {
                        importTheme(context)
                    }
                )
            } else {
                TabThemeSettings(
                    activity = context,
                    onDismiss = onDismiss,
                    onOpenThemeList = { showThemeList = true },
                    onSaveAs = { showSaveDialog = true }
                )
            }
        }
    }

    @Composable
    private fun TabThemeSettings(
        activity: ComponentActivity,
        onDismiss: () -> Unit,
        onOpenThemeList: () -> Unit,
        onSaveAs: () -> Unit
    ) {
        var enabledState by remember { mutableStateOf(tabThemeEnabled) }
        var opacityState by remember { mutableFloatStateOf(opacity) }
        var transparentStatusBarState by remember { mutableStateOf(transparentStatusBar) }

        // Reactive state: track which tabs have images, initialized from disk
        val hasImageStates = remember {
            mutableStateMapOf<Int, Boolean>().apply {
                for (i in 0..3) put(i, hasTabBackground(i))
            }
        }
        // Version counter for cache busting on preview refresh
        val imageVersions = remember {
            mutableStateMapOf<Int, Int>().apply {
                for (i in 0..3) put(i, 0)
            }
        }

        AlertDialogContent(
            title = { Text("四 Tab 主题背景") },
            text = {
                DefaultColumn(scrollable = true) {
                    ListItem(
                        modifier = Modifier.clickable { enabledState = !enabledState },
                        trailingContent = {
                            Switch(checked = enabledState, onCheckedChange = null)
                        },
                        headlineContent = { Text("启用 Tab 主题") },
                        supportingContent = { Text("为四个 Tab 分别设置背景图片") }
                    )

                    Text(
                        text = "透明度: ${(opacityState * 100).toInt()}%",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Slider(
                        value = opacityState,
                        onValueChange = { opacityState = it },
                        valueRange = 0.05f..0.6f
                    )

                    ListItem(
                        modifier = Modifier.clickable {
                            transparentStatusBarState = !transparentStatusBarState
                        },
                        trailingContent = {
                            Switch(checked = transparentStatusBarState, onCheckedChange = null)
                        },
                        headlineContent = { Text("沉浸式全屏") },
                        supportingContent = { Text("背景完整铺满屏幕，消除上下白条") }
                    )

                    Text(
                        "Tab 背景设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 16.dp)
                    )

                    tabNames.forEach { (index, name) ->
                        TabBackgroundItem(
                            activity = activity,
                            tabIndex = index,
                            tabName = name,
                            hasImage = hasImageStates[index] ?: false,
                            imageVersion = imageVersions[index] ?: 0,
                            onImagePicked = {
                                hasImageStates[index] = true
                                imageVersions[index] = (imageVersions[index] ?: 0) + 1
                            },
                            onImageDeleted = {
                                hasImageStates[index] = false
                                imageVersions[index] = (imageVersions[index] ?: 0) + 1
                            }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onOpenThemeList,
                            modifier = Modifier.weight(1f)
                        ) { Text("主题管理") }
                        Button(
                            onClick = onSaveAs,
                            modifier = Modifier.weight(1f)
                        ) { Text("保存为主题") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            confirmButton = {
                Button(onClick = {
                    tabThemeEnabled = enabledState
                    opacity = opacityState
                    transparentStatusBar = transparentStatusBarState
                    if (enabledState) {
                        showToast("设置已保存，重启微信生效")
                    } else {
                        showToast("已关闭 Tab 主题")
                    }
                    onDismiss()
                }) { Text("保存") }
            }
        )
    }

    @Composable
    private fun TabBackgroundItem(
        activity: ComponentActivity,
        tabIndex: Int,
        tabName: String,
        hasImage: Boolean,
        imageVersion: Int = 0,
        onImagePicked: () -> Unit,
        onImageDeleted: () -> Unit
    ) {
        ListItem(
            modifier = Modifier.clickable {
                pickImageForTab(activity, tabIndex, onImagePicked)
            },
            leadingContent = {
                if (hasImage) {
                    // Use key with version to force cache refresh when image changes
                    key("tab_bg_${tabIndex}_v$imageVersion") {
                        AsyncImage(
                            model = getTabBackgroundFile(tabIndex).toFile(),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            contentScale = ContentScale.Crop
                        )
                    }
                } else {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Wallpaper,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.Gray
                    )
                }
            },
            headlineContent = { Text(tabName) },
            supportingContent = {
                Text(if (hasImage) "已设置" else "点击设置图片", color = Color.Gray)
            },
            trailingContent = if (hasImage) {
                {
                    IconButton(onClick = {
                        deleteTabBackground(tabIndex)
                        onImageDeleted()
                        showToast("已清除 $tabName 背景")
                    }) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Delete,
                            contentDescription = "删除"
                        )
                    }
                }
            } else null
        )
    }

    @Composable
    private fun ThemeListScreen(
        onDismiss: () -> Unit,
        onSelect: (String) -> Unit,
        onDelete: (String) -> Unit,
        onExport: (String) -> Unit,
        onImport: () -> Unit
    ) {
        val themes = remember { listThemes() }
        var showImportConfirm by remember { mutableStateOf(false) }

        AlertDialogContent(
            title = { Text("主题管理") },
            text = {
                DefaultColumn {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = onImport, modifier = Modifier.weight(1f)) {
                            Text("导入主题")
                        }
                        Text("共 ${themes.size} 个", fontSize = 12.sp, color = Color.Gray)
                    }

                    if (themes.isEmpty()) {
                        Text(
                            "还没有保存的主题，先设置好各 Tab 背景，然后点「保存为主题」。",
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(themes) { theme ->
                                ListItem(
                                    modifier = Modifier.clickable { onSelect(theme) },
                                    headlineContent = { Text(theme) },
                                    supportingContent = { Text("点击切换到此主题") },
                                    trailingContent = {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            IconButton(onClick = { onExport(theme) }) {
                                                Icon(
                                                    imageVector = MaterialSymbols.Outlined.Download,
                                                    contentDescription = "导出"
                                                )
                                            }
                                            IconButton(onClick = { onDelete(theme) }) {
                                                Icon(
                                                    imageVector = MaterialSymbols.Outlined.Delete_forever,
                                                    contentDescription = "删除",
                                                    tint = Color.Red
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } },
            confirmButton = { }
        )
    }

    private fun pickImageForTab(activity: ComponentActivity, tabIndex: Int, onPicked: () -> Unit) {
        TransparentActivity.launch(activity) {
            val context = this
            val launcher = registerForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                finish()
                if (uri == null) return@registerForActivityResult

                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )

                    val destFile = getTabBackgroundFile(tabIndex)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    val config = loadConfig().copy(
                        tabBackgrounds = loadConfig().tabBackgrounds + (tabIndex to destFile.toString())
                    )
                    saveConfig(config)

                    // Notify UI to refresh preview immediately
                    onPicked()

                    showToast("${tabNames[tabIndex]} 背景已设置，重启微信生效")
                }.onFailure {
                    WeLogger.e(TAG, "failed to set tab background", it)
                    showToast("设置失败: ${it.message}")
                }
            }

            launcher.launch(
                PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }
    }

    private fun deleteTabBackground(tabIndex: Int) {
        runCatching {
            val file = getTabBackgroundFile(tabIndex)
            if (file.exists()) Files.delete(file)
            val config = loadConfig().copy(
                tabBackgrounds = loadConfig().tabBackgrounds - tabIndex
            )
            saveConfig(config)
        }
    }

    private fun listThemes(): List<String> {
        return runCatching {
            themesDir.listDirectoryEntries()
                .filter { it.isDirectory() && it.name != "current" }
                .map { it.name }
                .sorted()
        }.getOrElse { emptyList() }
    }

    private fun saveCurrentAsTheme(name: String) {
        runCatching {
            val themeDir = themesDir / name
            if (!themeDir.exists()) themeDir.createDirsSafe()

            for (tabIndex in 0..3) {
                val src = getTabBackgroundFile(tabIndex)
                if (src.exists()) {
                    val dst = themeDir / "tab_$tabIndex.webp"
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                }
            }

            val config = loadConfig().copy(name = name)
            (themeDir / "config.json").writeText(DefaultJson.encodeToString(config))
        }.onFailure {
            WeLogger.e(TAG, "failed to save theme", it)
        }
    }

    private fun switchToTheme(name: String) {
        runCatching {
            val themeDir = themesDir / name
            if (!themeDir.exists()) return

            for (tabIndex in 0..3) {
                val src = themeDir / "tab_$tabIndex.webp"
                val dst = getTabBackgroundFile(tabIndex)
                if (src.exists()) {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                } else if (dst.exists()) {
                    Files.delete(dst)
                }
            }

            val configFile = themeDir / "config.json"
            if (configFile.exists()) {
                val config = DefaultJson.decodeFromString<TabThemeConfig>(configFile.readText())
                saveConfig(config)
            }
        }.onFailure {
            WeLogger.e(TAG, "failed to switch theme", it)
        }
    }

    private fun deleteTheme(name: String) {
        runCatching {
            val themeDir = themesDir / name
            if (themeDir.exists()) {
                themeDir.toFile().deleteRecursively()
            }
        }.onFailure {
            WeLogger.e(TAG, "failed to delete theme", it)
        }
    }

    private fun exportTheme(context: Context, name: String) {
        runCatching {
            val themeDir = themesDir / name
            if (!themeDir.exists()) {
                showToast("主题不存在")
                return
            }

            val exportDir = KnownPaths.downloads / "wcx_themes"
            exportDir.createDirsSafe()
            val zipFile = exportDir / "$name.wcxtheme"

            zipDirectory(themeDir, zipFile)
            showToast("主题已导出到 ${zipFile.toAbsolutePath()}")

            // 提供系统分享快捷按钮
            shareThemeFile(context, zipFile, name)
        }.onFailure {
            WeLogger.e(TAG, "failed to export theme", it)
            showToast("导出失败: ${it.message}")
        }
    }

    private fun shareThemeFile(context: Context, zipFile: Path, themeName: String) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${HostInfo.application.packageName}.fileprovider",
                zipFile.toFile()
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "WCX主题: $themeName")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(shareIntent, "分享主题: $themeName")
            )
        }.onFailure {
            WeLogger.w(TAG, "failed to share theme file", it)
        }
    }

    private fun importTheme(context: ComponentActivity) {
        TransparentActivity.launch(HostInfo.application) {
            val launcher = registerForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                finish()
                if (uri == null) return@registerForActivityResult

                runCatching {
                    val contentResolver = HostInfo.application.contentResolver
                    val fileName = getFileName(contentResolver, uri) ?: "imported_theme"
                    val themeName = fileName.removeSuffix(".wcxtheme").removeSuffix(".zip")

                    val importDir = themesDir / themeName
                    if (!importDir.exists()) importDir.createDirsSafe()

                    contentResolver.openInputStream(uri)?.use { input ->
                        unzip(input, importDir)
                    }

                    showToast("主题已导入: $themeName")
                }.onFailure {
                    WeLogger.e(TAG, "failed to import theme", it)
                    showToast("导入失败: ${it.message}")
                }
            }

            launcher.launch(arrayOf("*/*"))
        }
    }

    private fun zipDirectory(sourceDir: Path, zipFile: Path) {
        val tmpZip = KnownPaths.moduleCache / "${zipFile.name}.tmp"
        val process = ProcessBuilder(
            "sh", "-c",
            "cd ${sourceDir.parent} && zip -r ${tmpZip.toAbsolutePath()} ${sourceDir.name}"
        ).start()
        process.waitFor()
        if (process.exitValue() == 0) {
            Files.move(tmpZip, zipFile, StandardCopyOption.REPLACE_EXISTING)
        } else {
            throw RuntimeException("zip failed")
        }
    }

    private fun unzip(inputStream: java.io.InputStream, destDir: Path) {
        val tmpFile = KnownPaths.moduleCache / "import_theme_${System.currentTimeMillis()}.zip"
        tmpFile.outputStream().use { inputStream.copyTo(it) }

        val process = ProcessBuilder(
            "sh", "-c",
            "unzip -o ${tmpFile.toAbsolutePath()} -d ${destDir.toAbsolutePath()}"
        ).start()
        process.waitFor()

        runCatching { Files.delete(tmpFile) }

        if (process.exitValue() != 0) {
            throw RuntimeException("unzip failed")
        }
    }

    private fun getFileName(
        contentResolver: android.content.ContentResolver,
        uri: Uri
    ): String? {
        val cursor = contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(
                    android.provider.OpenableColumns.DISPLAY_NAME
                )
                if (nameIndex != -1) it.getString(nameIndex) else null
            } else null
        }
    }

}
