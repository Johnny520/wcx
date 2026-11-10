package com.Johnny.wcx.activity.extensions

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.extensions.ExtensionPack
import com.Johnny.wcx.extensions.ExtensionPackState
import com.Johnny.wcx.extensions.ExtensionPacks
import com.Johnny.wcx.extensions.PythonRuntimePack
import com.Johnny.wcx.ui.content.m3.BaseWidget
import com.Johnny.wcx.ui.content.m3.SegmentedColumn
import com.Johnny.wcx.ui.content.m3.SettingsConfirmDialog
import com.Johnny.wcx.ui.content.m3.SettingsScaffold
import com.Johnny.wcx.ui.utils.theme.ColorosMetrics
import com.Johnny.wcx.ui.utils.theme.ColorosThemeProvider
import com.Johnny.wcx.ui.utils.theme.ModuleTheme

/**
 * 扩展包管理页。入口：Python 设置页「运行时」行的点击。
 * 命名以 SettingsActivity 结尾，使 ActivityProxy 走透明启动代理。
 */
@Keep
class ExtensionsSettingsActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PACK_ID = "pack_id"
        const val EXTRA_AUTO_DOWNLOAD = "auto_download"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ModuleTheme {
              ColorosThemeProvider {
                ExtensionsRoot(
                    autoPackId = intent.getStringExtra(EXTRA_PACK_ID),
                    autoDownload = intent.getBooleanExtra(EXTRA_AUTO_DOWNLOAD, false),
                    onFinish = ::finish,
                )
              }
            }
        }
    }
}

fun openExtensionsActivity(
    activity: ComponentActivity,
    pack: ExtensionPack?,
    autoDownload: Boolean,
) {
    val intent = Intent(activity, ExtensionsSettingsActivity::class.java)
    if (pack != null) intent.putExtra(ExtensionsSettingsActivity.EXTRA_PACK_ID, pack.id)
    intent.putExtra(ExtensionsSettingsActivity.EXTRA_AUTO_DOWNLOAD, autoDownload)
    activity.startActivity(intent)
}

@Composable
private fun ExtensionsRoot(autoPackId: String?, autoDownload: Boolean, onFinish: () -> Unit) {
    LaunchedEffect(Unit) {
        ExtensionPacks.packs.forEach(ExtensionPacks::refresh)
        ExtensionPacks.checkUpdates()
    }
    LaunchedEffect(autoPackId, autoDownload) {
        if (!autoDownload || autoPackId == null) return@LaunchedEffect
        val pack = ExtensionPacks.byId(autoPackId) ?: return@LaunchedEffect
        ExtensionPacks.refresh(pack)
        when (val state = ExtensionPacks.stateFlow(pack).value) {
            is ExtensionPackState.NotInstalled, is ExtensionPackState.Failed -> ExtensionPacks.download(pack)
            else -> Unit
        }
    }

    SettingsScaffold(title = "扩展包", onBack = onFinish) {
        for (pack in ExtensionPacks.packs.filter(ExtensionPack::isSupported)) {
            item(key = pack.id) { PackGroup(pack) }
        }
    }
}

@Composable
private fun PackGroup(pack: ExtensionPack) {
    val state by ExtensionPacks.stateFlow(pack).collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current

    SegmentedColumn {
        item(key = "info") {
            BaseWidget(
                title = pack.displayName,
                description = descriptionLine(pack, state),
                icon = pack.icon,
            )
        }
        when (val s = state) {
            is ExtensionPackState.Downloading -> item(key = "downloading") {
                Column(Modifier.padding(horizontal = ColorosMetrics.spacingRowH, vertical = ColorosMetrics.spacingGroup)) {
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${formatBytes(s.bytesDownloaded)} / ${formatBytes(s.bytesTotal)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { ExtensionPacks.cancelDownload(pack) }) {
                            Text("取消")
                        }
                    }
                }
            }
            is ExtensionPackState.Verifying -> item(key = "verifying") {
                BaseWidget(title = "校验中", description = "下载中")
            }
            else -> item(key = "actions") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ColorosMetrics.spacingRowH, vertical = ColorosMetrics.spacingGroup),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        onClick = { ExtensionPacks.download(pack) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            when (s) {
                                is ExtensionPackState.UpdateAvailable -> "更新"
                                is ExtensionPackState.Failed -> "重试"
                                else -> "下载"
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        enabled = (s is ExtensionPackState.Installed || s is ExtensionPackState.UpdateAvailable) && !pack.isInUse(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f),
                    ) { Text("删除") }
                }
            }
        }
    }

    if (confirmDelete) {
        SettingsConfirmDialog(
            show = true,
            title = "删除扩展包？",
            message = "将删除已安装的扩展包文件，可稍后重新下载。",
            confirmLabel = "删除",
            dismissLabel = "取消",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                if (!ExtensionPacks.delete(pack)) {
                    Toast.makeText(context, "扩展包正在使用中，无法删除", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun descriptionLine(pack: ExtensionPack, state: ExtensionPackState): String {
    val status = when (state) {
        is ExtensionPackState.NotInstalled -> "未安装"
        is ExtensionPackState.Downloading -> "下载中"
        is ExtensionPackState.Verifying -> "校验中"
        is ExtensionPackState.Installed -> "已安装 ${state.version}"
        is ExtensionPackState.UpdateAvailable -> "可更新：${state.installedVersion} → ${state.latestVersion}"
        is ExtensionPackState.Failed -> "失败：${state.reason}"
    }
    val mountedVersion = if (pack === PythonRuntimePack) PythonRuntimePack.mounted()?.manifest?.version else null
    val restart = if (
        mountedVersion != null && mountedVersion != pack.installedManifest()?.version
    ) "\n需重启微信以加载新运行时" else ""
    return "${pack.description}\n$status$restart"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1fKB", kb)
    val mb = kb / 1024.0
    return String.format(java.util.Locale.US, "%.1fMB", mb)
}
