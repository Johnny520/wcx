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

    /** Tab 页背景图片缓存，避免每次重建都重新解码（图片通常几 MB）。 */
    private val tabBgCache = HashMap<String, android.graphics.drawable.BitmapDrawable>()

    /** 记录每个 Tab 页当前要绘制的背景图。 */
    private val pageOverlayDrawables =
        java.util.IdentityHashMap<View, android.graphics.drawable.BitmapDrawable>()

    /** 已经 hook 过 dispatchDraw 的容器类，避免重复挂。 */
    private val dispatchHookedClasses = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<Class<*>, Boolean>(),
    )

    private val themesDir by lazy { (KnownPaths.moduleData / "tab_themes").createDirsSafe() }
    private val currentThemeDir by lazy { (themesDir / "current").createDirsSafe() }

    private var tabThemeEnabled by prefOption("tab_theme_enabled", false)
    private var opacity by prefOption("tab_theme_opacity", 0.15f)
    private var transparentStatusBar by prefOption("tab_theme_transparent_status_bar", true)

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

    /**
     * 解码某个 Tab 的背景图。只在 LauncherUI 首次创建时跑一次（之后走缓存），
     * 用 inSampleSize 降采样避免大图占太多内存。
     */
    /**
     * 给某个 Tab 页容器的类挂 dispatchDraw，在子 View 绘制完成后把背景图补画上去。
     *
     * 为什么不用 ViewOverlay：微信 Tab 页容器是 FrostedContentView，它重写了 dispatchDraw
     * 做磨砂模糊（用 saveLayer 做离屏绘制）。ViewOverlay 是 View.draw() 的最后一步，
     * 会被它的离屏/裁剪吃掉——表现为图确实挂上了（日志有 attached，尺寸也正常）却画不出来。
     * 直接在 dispatchDraw 之后补画，稳定且层级正确（在内容之上、不影响交互）。
     */
    private fun ensureDispatchHook(pageClass: Class<*>) {
        if (!dispatchHookedClasses.add(pageClass)) return
        runCatching {
            pageClass.reflekt()
                .firstMethodOrNull { name = "dispatchDraw"; parameterCount = 1 }
                ?.hookAfter {
                    val v = thisObject as? View ?: return@hookAfter
                    val canvas = args.getOrNull(0) as? android.graphics.Canvas ?: return@hookAfter
                    val d = pageOverlayDrawables[v] ?: return@hookAfter
                    if (v.width <= 0 || v.height <= 0) return@hookAfter
                    var saved = false
                    try {
                        val cnt = canvas.save()
                        saved = true
                        d.setBounds(0, 0, v.width, v.height)
                        d.draw(canvas)
                        canvas.restoreToCount(cnt)
                        saved = false
                    } finally {
                        if (saved) runCatching { canvas.restore() }
                    }
                }
            WeLogger.i(TAG, "dispatchDraw hooked: " + pageClass.name)
        }.onFailure {
            WeLogger.w(TAG, "hook dispatchDraw failed for " + pageClass.name + ": " + (it.message ?: ""))
        }
    }

    private fun loadTabBackgroundDrawable(
        context: android.content.Context,
        tabIndex: Int,
    ): android.graphics.drawable.BitmapDrawable? {
        val file = getTabBackgroundFile(tabIndex)
        if (!file.exists()) {
            WeLogger.i(TAG, "tab bg #" + tabIndex + " not set: " + file)
            return null
        }
        val key = file.toString()
        tabBgCache[key]?.let { if (it.bitmap?.isRecycled != true) return it }
        return runCatching {
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = android.graphics.BitmapFactory.decodeFile(file.toString(), opts) ?: return null
            android.graphics.drawable.BitmapDrawable(context.resources, bmp)
                .also { tabBgCache[key] = it }
        }.getOrElse {
            WeLogger.w(TAG, "decode tab background #$tabIndex failed: " + (it.message ?: ""))
            null
        }
    }

    override fun onEnable() {
        WeLogger.i(TAG, "onEnable: tabThemeEnabled=" + tabThemeEnabled)
        if (!tabThemeEnabled) return
        applyTheme()
    }

    private fun applyTheme() {
        WeLogger.i(TAG, "applyTheme: installing LauncherUI doOnCreate hook")
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            try {
                WeLogger.i(TAG, "LauncherUI doOnCreate fired, preparing tab backgrounds")
            val activity = thisObject.reflekt()
                .firstField { type = "com.tencent.mm.ui.MMFragmentActivity" }
                .get()!! as Activity

            val viewPager = thisObject.reflekt()
                .firstField { name = "mViewPager" }
                .get()!! as ViewGroup
            val tabsAdapter = thisObject.reflekt()
                .firstField { name = "mTabsAdapter" }
                .get()!!
            WeLogger.i(
                TAG,
                "LauncherUI fields: activity=" + activity.javaClass.name +
                    ", pager=" + viewPager.javaClass.name,
            )

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
            val pager = viewPager

            // 沉浸式全屏：让 Tab 页内容延伸到状态栏 / 导航栏下方
            if (transparentStatusBar) {
                applyImmersiveStatusBar(activity)
            }

            // ── 背景挂在「每个 Tab 页自己的容器」上，而不是 DecorView ──
            //
            // 旧实现把背景 ImageView 加到 decorView 上：decorView 属于整个窗口，
            // 而微信聊天页（ChattingUI，独立 Activity）的窗口在沉浸式转场期间是透明的，
            // 于是整个窗口的内容连同背景图一起透出来。只能靠 onPause 之类做判定去藏，
            // 藏不干净还会闪、还会一卡一卡。
            //
            // 改为挂在 Tab 页容器内之后，聊天页是另一个 Activity，两者不在同一条绘制链上，
            // 物理上不可能透——不需要任何聊天页判定，也不需要 Tab 切换时反复开关可见性。
            // （这是 MDWechat 的做法：它把图设到各 Tab 自己的 ListView 上。）
            fun decoratePage(page: View, pos: Int) {
                val file = getTabBackgroundFile(pos)
                val prev = pageOverlayDrawables[page]
                val d = if (file.exists()) loadTabBackgroundDrawable(page.context, pos) else null
                val newAlpha = (opacity.coerceIn(0f, 1f) * 255).toInt()

                if (d == null) {
                    if (prev != null) {
                        pageOverlayDrawables.remove(page)
                        page.invalidate()
                        WeLogger.i(TAG, "tab bg #" + pos + " cleared")
                    }
                    return
                }

                // 已经是同一张图：只同步透明度，绝不 invalidate。
                // onPageScrolled 在静止状态下也会反复回调，这里若每次重绘会造成持续刷新和绘制撕裂。
                if (prev === d) {
                    if (d.alpha != newAlpha) {
                        d.alpha = newAlpha
                        page.invalidate()
                    }
                    return
                }

                d.alpha = newAlpha
                pageOverlayDrawables[page] = d
                // 给这个容器的类挂 dispatchDraw，之后每一次重绘都会把图补画上去
                ensureDispatchHook(page.javaClass)
                page.invalidate()
                WeLogger.i(
                    TAG,
                    "tab bg #" + pos + " attached to " + page.javaClass.simpleName +
                        " (alpha=" + newAlpha + ", size=" + page.width + "x" + page.height + ")",
                )
            }

            var lastLoggedChildCount = -1
            fun decorateAllPages() {
                if (pager.childCount != lastLoggedChildCount) {
                    lastLoggedChildCount = pager.childCount
                    WeLogger.i(TAG, "decorateAllPages: pager.childCount=" + pager.childCount)
                }
                for (i in 0 until pager.childCount) {
                    val child = pager.getChildAt(i)
                    val idx = pager.indexOfChild(child)
                    if (idx in 0..3) decoratePage(child, idx)
                }
            }

            decorateAllPages()
            // doOnCreate 时 ViewPager 往往还没把 4 个 page 加进来（走的是 addViewInLayout），
            // 这里延时补几次，等布局完成后再挂一遍。
            for (delay in longArrayOf(300L, 1200L, 3000L)) {
                pager.postDelayed({ runCatching { decorateAllPages() } }, delay)
            }

            // Tab 页被新建 / 复用时补挂。
            // ViewPager 内部是通过 addViewInLayout 把 page 塞进来的，只 hook addView 抓不到，
            // 所以两个入口都挂上。
            fun onPageAttached(child: View?, container: ViewGroup?) {
                if (child == null || container == null) return
                val idx = container.indexOfChild(child)
                if (idx in 0..3) {
                    decoratePage(child, idx)
                } else {
                    WeLogger.i(TAG, "page attached at idx=" + idx + ", skip decorate")
                }
            }

            var hooked = 0
            for (spec in arrayOf("addView" to 3, "addViewInLayout" to 3, "addViewInLayout" to 4)) {
                runCatching {
                    val m = pager.reflekt()
                        .firstMethodOrNull { name = spec.first; parameterCount = spec.second }
                        ?: return@runCatching
                    m.hookAfter {
                        onPageAttached(args.getOrNull(0) as? View, thisObject as? ViewGroup)
                    }
                    hooked++
                    WeLogger.i(TAG, "hooked " + spec.first + "/" + spec.second + "args")
                }.onFailure {
                    WeLogger.w(TAG, "hook " + spec.first + " failed: " + (it.message ?: ""))
                }
            }
            WeLogger.i(TAG, "page attach hooks installed: " + hooked)

            // 最可靠的兜底时机：初始布局完成后 ViewPager 必会回调一次 onPageScrolled(0, 0f, 0)。
            // addViewInLayout 万一抓不到，这里也能把 4 个 page 扫一遍。
            tabsAdapter.reflekt()
                .firstMethodOrNull { name = "onPageScrolled"; parameterCount = 3 }
                ?.hookAfter {
                    runCatching {
                        if ((args.getOrNull(1) as? Float) == 0f) decorateAllPages()
                    }.onFailure {
                        WeLogger.w(TAG, "onPageScrolled decorate failed: " + (it.message ?: ""))
                    }
                }

            // 切换 Tab 时补齐（应对页面重建后 tag 丢失）
            tabsAdapter.reflekt()
                .firstMethod { name = "onPageSelected" }
                .hookAfter {
                    runCatching {
                        currentTabState.intValue = args[0] as Int
                        decorateAllPages()
                    }.onFailure {
                        WeLogger.e(TAG, "onPageSelected 补挂背景失败", it)
                    }
                }

            // 背景用 ViewOverlay 叠加，不占用 View.background，
            // 所以不必再拦微信的 setBackground*（它设它的，互不干扰）。
            } catch (e: Throwable) {
                WeLogger.e(TAG, "applyTheme hookAfter 异常", e)
            }
        }
    }

    private fun applyImmersiveStatusBar(activity: Activity) {
        runCatching {
            val window = activity.window ?: return
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
            } else {
                @Suppress("DEPRECATION")
                val decor = window.decorView as? ViewGroup ?: return
                decor.systemUiVisibility = decor.systemUiVisibility or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
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
