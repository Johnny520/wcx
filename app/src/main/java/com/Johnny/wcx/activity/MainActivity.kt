package com.Johnny.wcx.activity

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.edit
import androidx.core.net.toUri
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Favorite
import com.composables.icons.materialsymbols.outlined.Open_in_new
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlinedfilled.Check_circle
import com.composables.icons.materialsymbols.outlinedfilled.Info
import com.composables.icons.materialsymbols.outlinedfilled.More_vert
import com.composables.icons.materialsymbols.outlinedfilled.Warning
import com.topjohnwu.superuser.Shell
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.constants.WeChatVersions
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.IconButton
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.GitHubIcon
import com.Johnny.wcx.ui.utils.theme.darkScheme
import com.Johnny.wcx.ui.utils.theme.lightScheme
import com.Johnny.wcx.utils.android.androidUserId
import com.Johnny.wcx.utils.android.getEnabled
import com.Johnny.wcx.utils.android.setEnabled
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.formatEpoch
import com.Johnny.wcx.utils.hook_status.HookStatus
import com.Johnny.wcx.utils.openInSystem
import com.Johnny.wcx.ui.style.SoftCard
import com.Johnny.wcx.ui.style.WxPalette
import androidx.compose.ui.text.TextStyle
import com.Johnny.wcx.features.core.FeaturesProvider
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.Johnny.wcx.ui.style.SoftDivider

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getPreferences(MODE_PRIVATE) }

    private var isLaunchingWeChat = false

    override fun onStop() {
        super.onStop()
        if (isLaunchingWeChat) {
            isLaunchingWeChat = false
            finishAndRemoveTask()
        }
    }

    @Composable
    private fun ModuleTheme(
        darkTheme: Boolean = isSystemInDarkTheme(),
        content: @Composable () -> Unit
    ) {
        val colorScheme = if (darkTheme) darkScheme else lightScheme
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
        ) {
            content()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (BuildConfig.HAS_LIBXPOSED_ENTRY) {
            runCatching { HookStatus.init(this) }
        }

        Shell.getShell()

        setContent {
            ModuleTheme {
                AppContent(
                    onUrlClick = { url ->
                        url.toUri().openInSystem(this, true)
                    }
                )
            }
        }
    }

    private data class ActivationState(
        val isActivated: Boolean,
        val title: String,
        val desc: String,
        val color: Color
    )

    @Composable
    private fun AppContent(onUrlClick: (String) -> Unit) {
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

        var showMenu by remember { mutableStateOf(false) }
        var showAboutDialog by remember { mutableStateOf(false) }
        var showConfirmDeleteTinkerDialog by remember { mutableStateOf(false) }
        var showConfirmDeleteModuleDataDialog by remember { mutableStateOf(false) }
        var showNoRootDialog by remember { mutableStateOf(false) }
        var showModifyHostPkgNameDialog by remember { mutableStateOf(false) }
        var shortcutError by remember { mutableStateOf<String?>(null) }

        var isLauncherIconEnabled by remember {
            mutableStateOf(
                ComponentName(
                    this,
                    "${PackageNames.MODULE}.activity.MainActivityAlias"
                ).getEnabled(this)
            )
        }

        @Composable
        fun rememberActivationState(): ActivationState {
            val xposedService by HookStatus.xposedService.collectAsState()
            val isHookEnabled = remember(xposedService) {
                xposedService?.scope?.contains(PackageNames.WECHAT) == true
            }
            // 框架服务（libxposed service）绑定只发生在模块进程，绑定失败不代表模块没注入。
            // 微信进程内检测到 LSPosed 后会把 API 版本写进跨进程缓存，读它来区分
            // 「本页连不上服务」与「模块根本没被加载」两种情况——原来一律显示「未检测到
            // Xposed 框架」，模块其实在工作时会误导用户。
            val cachedApiVersion = remember {
                runCatching { WePrefs.getIntOrDef(Preferences.CACHED_LSP_API_VERSION, 0) }
                    .getOrDefault(0)
            }

            return remember(isHookEnabled, xposedService, cachedApiVersion) {
                when {
                    isHookEnabled -> ActivationState(
                        isActivated = true,
                        title = "已激活",
                        desc = "${xposedService?.frameworkName} ${xposedService?.frameworkVersion} " +
                                "(${xposedService?.frameworkVersionCode}), API ${xposedService?.apiVersion}",
                        color = Color(0xFF4CAF50)
                    )

                    cachedApiVersion > 0 -> ActivationState(
                        isActivated = true,
                        title = "已注入",
                        desc = "微信进程内已检测到 LSPosed API $cachedApiVersion；本页未能连接框架服务，" +
                                "不影响微信内功能。若微信内功能异常，请确认作用域已勾选微信并重启微信。",
                        color = Color(0xFFFB8C00)
                    )

                    else -> ActivationState(
                        isActivated = false,
                        title = "未激活",
                        desc = "未检测到 Xposed 框架。请确认：① 模块已在管理器中启用 " +
                                "② 作用域已勾选微信 ③ 安装或更新后重启微信",
                        color = Color(0xFFF44336)
                    )
                }
            }
        }

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(WxPalette.pageBackgroundBrush()),
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = BuildConfig.TAG,
                                fontSize = 28.sp,
                                color = WxPalette.textPrimary(),
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "微信增强模块 · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                fontSize = 12.sp,
                                color = WxPalette.TextSecondary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                            .copy(alpha = 0.9f),
                    ),
                    actions = {
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(
                                MaterialSymbols.OutlinedFilled.More_vert,
                                contentDescription = "Menu"
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("设置宿主包名") },
                                onClick = {
                                    showMenu = false
                                    showModifyHostPkgNameDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("修复模块加载") },
                                onClick = {
                                    showMenu = false
                                    showConfirmDeleteTinkerDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("清除模块数据") },
                                onClick = {
                                    showMenu = false
                                    showConfirmDeleteModuleDataDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isLauncherIconEnabled) "隐藏桌面图标" else "显示桌面图标") },
                                onClick = {
                                    showMenu = false
                                    val componentName = ComponentName(
                                        this@MainActivity,
                                        "${PackageNames.MODULE}.activity.MainActivityAlias"
                                    )
                                    val newState = !isLauncherIconEnabled
                                    componentName.setEnabled(this@MainActivity, newState)
                                    isLauncherIconEnabled = newState
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("关于") },
                                onClick = {
                                    showMenu = false
                                    showAboutDialog = true
                                }
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 6.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ① 主状态卡（仿仪表盘：状态胶囊 + 图标 + 主标题 + 底部分栏）
                if (BuildConfig.HAS_LIBXPOSED_ENTRY) {
                    val activationState = rememberActivationState()
                    StatusCard(
                        state = activationState,
                        buildTime = formatEpoch(BuildConfig.BUILD_TIMESTAMP, true),
                    )
                }

                // ② 快捷操作
                SectionTitle("快捷操作")
                WhiteCard {
                    ListRow(
                        icon = MaterialSymbols.Outlined.Chat,
                        title = "打开微信",
                        subtitle = "一键强行停止并启动微信",
                        onClick = {
                            // 强停 + 拉起：先 am force-stop 再 am start，成功后关掉本页
                            if (!(Shell.isAppGrantedRoot() ?: false)) {
                                showNoRootDialog = true
                            } else {
                                val userId = androidUserId
                                val hostPkg = prefs.getString("host_pkg_name", PackageNames.WECHAT)!!
                                Shell.cmd(
                                    "am force-stop --user $userId $hostPkg",
                                    "am start --user $userId -n $hostPkg/${PackageNames.WECHAT}.ui.LauncherUI",
                                ).submit { result ->
                                    if (result.isSuccess) {
                                        finishAndRemoveTask()
                                    } else {
                                        shortcutError = (result.out + result.err)
                                            .joinToString("\n")
                                            .ifBlank { "无法启动微信 (包名: $hostPkg)" }
                                    }
                                }
                            }
                        }
                    )
                    SoftDivider()
                    ListRow(
                        icon = MaterialSymbols.Outlined.Settings,
                        title = "打开模块设置",
                        subtitle = "打开微信内的模块设置",
                        onClick = {
                            val hostPkg = prefs.getString("host_pkg_name", PackageNames.WECHAT)!!
                            try {
                                startActivity(com.Johnny.wcx.utils.android.Intent {
                                    setClassName(hostPkg, "${PackageNames.WECHAT}.ui.LauncherUI")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    putExtra(BuildConfig.TAG, "1")
                                })
                                isLaunchingWeChat = true
                            } catch (e: Exception) {
                                shortcutError = e.message ?: "无法打开微信 (包名: $hostPkg)"
                            }
                        }
                    )
                }

                // ③ 设备信息
                SectionTitle("设备信息")
                WhiteCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        InfoItem("构建提交哈希", BuildConfig.COMMIT_HASH.ifEmpty { "未知" })
                        InfoItem("构建提交时间", formatEpoch(BuildConfig.BUILD_TIMESTAMP, true))
                        InfoItem("已适配微信版本", getAdaptedWeChatVersions())
                        InfoItem("当前微信版本", getWeChatVersion())
                    }
                }

                if (showModifyHostPkgNameDialog) {
                    var pkgName by remember {
                        mutableStateOf(
                            prefs.getString(
                                "host_pkg_name",
                                PackageNames.WECHAT
                            )!!
                        )
                    }

                    AlertDialog(
                        onDismissRequest = { showModifyHostPkgNameDialog = false },
                        title = { Text("设置宿主包名") },
                        text = {
                            DefaultColumn {
                                Text("本选项控制模块应用首页三个快捷方式打开的目标微信包名, 适用于通过修改包名而非多用户实现多微信共存的用户")

                                TextField(
                                    label = { Text("宿主包名 (默认为 com.tencent.mm)") },
                                    value = pkgName,
                                    onValueChange = { pkgName = it }
                                )
                            }

                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showModifyHostPkgNameDialog = false
                            }) { Text("取消") }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showModifyHostPkgNameDialog = false
                                prefs.edit {
                                    putString("host_pkg_name", pkgName)
                                }
                            }) { Text("确定") }
                        })
                }

                if (showConfirmDeleteTinkerDialog) {
                    val paths = remember {
                        @Suppress("SdCardPath")
                        listOf(
                            "/data/data/${PackageNames.WECHAT}/tinker",
                            "/data/data/${PackageNames.WECHAT}/tinker_server",
                            "/data/data/${PackageNames.WECHAT}/tinker_temp",
                            "/data/user/0/${PackageNames.WECHAT}/tinker",
                            "/data/user/0/${PackageNames.WECHAT}/tinker_server",
                            "/data/user/0/${PackageNames.WECHAT}/tinker_temp",
                            "/data/user/999/${PackageNames.WECHAT}/tinker",
                            "/data/user/999/${PackageNames.WECHAT}/tinker_server",
                            "/data/user/999/${PackageNames.WECHAT}/tinker_temp"
                        )
                    }

                    AlertDialog(
                        onDismissRequest = { showConfirmDeleteTinkerDialog = false },
                        title = { Text("修复模块加载") },
                        text = {
                            Text(
                                "本操作将尝试修复微信热更新导致的模块不加载, 执行后请重启微信\n" +
                                        "将清空以下路径的内容, 并将其权限递归设置为 000 以阻止微信重读写, 请确认无误后再继续!\n${
                                            paths.joinToString("\n") { "- $it" }
                                        }"
                            )
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showConfirmDeleteTinkerDialog = false
                            }) { Text("取消") }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showConfirmDeleteTinkerDialog = false
                                if (!(Shell.isAppGrantedRoot() ?: false)) {
                                    showNoRootDialog = true
                                } else {
                                    paths.forEach { path ->
                                        ProcessBuilder(
                                            "su", "-mm", "-c",
                                            "if [ -d '$path' ]; then " +
                                                    "find '$path' -mindepth 1 -exec rm -rf {} + ; " +
                                                    "chmod -R 000 '$path' ; " +
                                                    "fi"
                                        )
                                            .redirectErrorStream(true)
                                            .start()
                                    }
                                    showToast(this@MainActivity, "操作成功!")
                                }
                            }) { Text("确定") }
                        })
                }

                if (showConfirmDeleteModuleDataDialog) {
                    val paths = remember {
                        @Suppress("SdCardPath")
                        listOf(
                            "/data/data/${PackageNames.WECHAT}/files/mmkv/wekit_prefs",
                            "/data/data/${PackageNames.WECHAT}/files/mmkv/wekit_prefs.crc",
                        )
                    }

                    AlertDialog(
                        onDismissRequest = { showConfirmDeleteModuleDataDialog = false },
                        title = { Text("清除模块数据") },
                        text = {
                            Text(
                                "本操作将删除模块的功能配置, 用于解决部分功能导致微信无限闪退, 删除后请重启微信\n将删除以下路径的文件, 请确认无误后再删除!\n${
                                    paths.joinToString("\n") { "- $it" }
                                }"
                            )
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showConfirmDeleteModuleDataDialog = false
                            }) { Text("取消") }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showConfirmDeleteModuleDataDialog = false
                                if (!(Shell.isAppGrantedRoot() ?: false)) {
                                    showNoRootDialog = true
                                } else {
                                    // if using Shell.cmd or su -c without -mm, the view of /data/user/0 is restricted
                                    paths.forEach { path ->
                                        ProcessBuilder("su", "-mm", "-c", "rm -rf $path")
                                            .redirectErrorStream(true)
                                            .start()
                                    }
                                    showToast(this@MainActivity, "删除成功!")
                                }
                            }) { Text("确定") }
                        })
                }

                if (showNoRootDialog) {
                    AlertDialog(
                        onDismissRequest = { showNoRootDialog = false },
                        title = { Text("未授予 Root 权限") },
                        text = { Text("请授予 Root 权限以执行此操作") },
                        confirmButton = {
                            Button(onClick = {
                                showNoRootDialog = false
                            }) { Text("确定") }
                        })
                }

                shortcutError?.let { error ->
                    AlertDialog(
                        onDismissRequest = { shortcutError = null },
                        title = { Text("操作失败") },
                        text = { Text(error) },
                        confirmButton = {
                            Button(onClick = {
                                shortcutError = null
                            }) { Text("确定") }
                        })
                }

                SectionTitle("相关链接")
                WhiteCard {
                    ListRow(
                        icon = MaterialSymbols.Outlined.Chat,
                        title = "交流群组",
                        subtitle = "https://t.me/wcx12138",
                        onClick = { onUrlClick("https://t.me/wcx12138") }
                    )
                    SoftDivider()
                    ListRow(
                        icon = GitHubIcon,
                        title = "GitHub",
                        subtitle = "Johnny520/wcx",
                        onClick = { onUrlClick("https://github.com/Johnny520/wcx") }
                    )
                    SoftDivider()
                    ListRow(
                        icon = MaterialSymbols.Outlined.Favorite,
                        title = "支持作者 · 爱发电",
                        subtitle = "afdian.com/a/Johnny520（跳浏览器打开）",
                        onClick = { onUrlClick("https://afdian.com/a/Johnny520") }
                    )
                }
            }

            if (showAboutDialog) {
                AlertDialog(
                    onDismissRequest = { showAboutDialog = false },
                    title = { Text(text = "关于") },
                    text = {
                        Column {
                            Text("${BuildConfig.TAG} 是一款基于 Xposed 框架的开源免费微信模块")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("版本: ${BuildConfig.VERSION_NAME}")
                            Text("版本号: ${BuildConfig.VERSION_CODE}")
                            Text("作者：Johnny520@github")
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAboutDialog = false }) {
                            Text("确定")
                        }
                    },
                )
            }
        }
    }

    /** 分区标题：灰色小字裸排。 */
    @Composable
    private fun SectionTitle(text: String) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = WxPalette.TextSecondary,
            modifier = Modifier.padding(start = 6.dp, top = 8.dp),
        )
    }

    /** 分组卡片容器：冷调玻璃底 + 渐变高光描边，与设置页/自检页同一套视觉。 */
    @Composable
    private fun WhiteCard(content: @Composable ColumnScope.() -> Unit) {
        val shape = RoundedCornerShape(WxPalette.CardRadius)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(WxPalette.cardBrush())
                .border(1.dp, WxPalette.cardBorderBrush(), shape),
            content = content,
        )
    }

    /** 卡片内一行：圆角图标 + 标题/副标题 + 右箭头。 */
    @Composable
    private fun ListRow(
        icon: ImageVector,
        title: String,
        subtitle: String? = null,
        onClick: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(WxPalette.PrimarySoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = WxPalette.Primary,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = WxPalette.textPrimary(),
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = WxPalette.TextSecondary,
                    )
                }
            }
            Text(
                text = "\u203A",
                fontSize = 20.sp,
                color = WxPalette.TextTertiary,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }

    /** 主状态卡：仿「仪表盘」顶部大卡 —— 状态胶囊 + 图标 + 主标题 + 底部分栏。 */
    @Composable
    private fun StatusCard(state: ActivationState, buildTime: String) {
        val shape = RoundedCornerShape(WxPalette.CardRadius)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(WxPalette.cardBrush())
                .border(1.dp, WxPalette.cardBorderBrush(), shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "状态",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = WxPalette.TextSecondary,
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .background(state.color.copy(alpha = 0.12f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = state.title,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = state.color,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(state.color.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (state.isActivated) {
                                MaterialSymbols.OutlinedFilled.Check_circle
                            } else {
                                MaterialSymbols.OutlinedFilled.Warning
                            },
                            contentDescription = null,
                            tint = state.color,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = state.title,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = WxPalette.textPrimary(),
                        )
                        Text(
                            text = state.desc,
                            fontSize = 11.sp,
                            color = WxPalette.TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(WxPalette.cardBorder())
                )
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("版本", fontSize = 11.sp, color = WxPalette.TextSecondary)
                        Text(
                            text = BuildConfig.VERSION_NAME,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WxPalette.textPrimary(),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("构建时间", fontSize = 11.sp, color = WxPalette.TextSecondary)
                        Text(
                            text = buildTime,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WxPalette.textPrimary(),
                        )
                    }
                }
        }
    }

    @Composable
    private fun InfoItem(label: String, value: String) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    private fun getWeChatVersion(): String {
        return runCatching {
            val pm = packageManager ?: return@runCatching "未安装"
            val pkgInfo = pm.getPackageInfo(PackageNames.WECHAT, 0)
            val versionName = pkgInfo.versionName?.ifEmpty { null } ?: return@runCatching "版本未知"
            "v$versionName (${pkgInfo.longVersionCode})"
        }.getOrDefault("未安装")
    }

    private fun getAdaptedWeChatVersions(): String {
        return try {
            val versions = WeChatVersions::class.java.declaredFields
                .mapNotNull { field ->
                    val name = field.name
                    if (name.startsWith("MM_")) {
                        val parts = name.removePrefix("MM_").split("_")
                        if (parts.size >= 3) {
                            "${parts[0]}.${parts[1]}.${parts[2]}"
                        } else null
                    } else null
                }
                .sortedWith(compareBy(
                    { it.split(".").getOrNull(0)?.toIntOrNull() ?: 0 },
                    { it.split(".").getOrNull(1)?.toIntOrNull() ?: 0 },
                    { it.split(".").getOrNull(2)?.toIntOrNull() ?: 0 }
                ))
            if (versions.isNotEmpty()) {
                "版本识别已加入 8.0.79（versionCode 3200）；功能兼容性尚未逐项验证"
            } else {
                "版本适配信息不可用；请以实际功能测试结果为准"
            }
        } catch (e: Exception) {
            "版本适配信息不可用；请以实际功能测试结果为准"
        }
    }

    @Composable
    private fun LinkCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
        ElevatedCard(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = WxPalette.cardBackground()),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
