package com.Johnny.wcx.features.items.system

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource

import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText

/** 模块级「安全模式」开关 */
object SafeMode {

    private const val TAG = "SafeMode"
    private val flagFile = KnownPaths.moduleData / "safe_mode.flag"

    val isEnabled: Boolean
        get() = flagFile.exists()

    fun showEnableConfirmDialog(context: Context, onConfirmed: () -> Unit) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("开启安全模式？") },
                text = { Text("开启后，下次启动微信时普通功能不会被加载，只保留核心功能；普通功能仍会显示在设置页中，以便关闭安全模式后恢复使用。\n\n确认开启安全模式？") },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        onConfirmed()
                    }) { Text("开启") }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            runCatching {
                flagFile.writeText("")
            }.onFailure {
                WeLogger.e(TAG, "failed to create safe mode flag", it)
            }
        } else {
            runCatching { flagFile.deleteIfExists() }.onFailure {
                WeLogger.e(TAG, "failed to delete safe mode flag", it)
            }
        }
        WeLogger.i(TAG, "safe mode flag ${if (enabled) "created" else "deleted"}: $flagFile")
    }
}
