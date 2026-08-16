package com.Johnny.wcx.features.items.chat.panel

import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.Johnny.wcx.activity.TransparentActivity
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.AudioUtils
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.android.showToastSuspend
import com.Johnny.wcx.utils.coerceToInt
import com.Johnny.wcx.utils.fileExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.outputStream

/**
 * Opens a system file picker to select an audio file and send it as a WeChat voice message.
 *
 * Extracted from [com.Johnny.wcx.features.items.chat.ChatInputBarEnhancements] so that
 * [com.Johnny.wcx.features.items.chat.VoicePanel] can offer the same escape-hatch without
 * duplicating the logic.
 */
internal fun selectAndSendVoice(context: Context, currentConv: String) {
    TransparentActivity.launch(context) {
        val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) {
                finish()
                return@registerForActivityResult
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val extension = uri.fileExtension.trimStart('.').ifEmpty { "mp3" }
                val tempPath = PanelPaths.panelCacheDir / "picked-${UUID.randomUUID()}.$extension"
                val prepareResult = runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        tempPath.outputStream().use(input::copyTo)
                    } ?: error("无法读取所选语音文件")
                    val mimeType = contentResolver.getType(uri).orEmpty()
                    val isSilk = mimeType in setOf("audio/amr", "audio/silk") ||
                            extension.equals("silk", true) || extension.equals("amr", true)
                    Triple(isSilk, AudioUtils.getDurationMs(tempPath.absolutePathString()), tempPath)
                }
                if (prepareResult.isFailure) {
                    tempPath.deleteIfExists()
                    withContext(Dispatchers.Main) {
                        finish()
                        showToast(prepareResult.exceptionOrNull()?.message ?: "语音文件读取失败")
                    }
                    return@launch
                }
                val (isSilk, durationMs) = prepareResult.getOrThrow()
                showToastSuspend("语音文件准备完成")

                withContext(Dispatchers.Main) {
                    finish()
                    showComposeDialog(context) {
                        DisposableEffect(tempPath) {
                            onDispose { tempPath.deleteIfExists() }
                        }
                        var durationInput by remember { mutableStateOf(durationMs.toString()) }
                        AlertDialogContent(
                            title = { Text("发送语音文件") },
                            text = {
                                TextField(
                                    value = durationInput,
                                    onValueChange = { durationInput = it.filter { c -> c.isDigit() } },
                                    label = { Text("语音时长 (毫秒)") })
                            },
                            dismissButton = {
                                TextButton({
                                    tempPath.deleteIfExists()
                                    onDismiss()
                                }) { Text("取消") }
                            },
                            confirmButton = {
                                Button(onClick = {
                                    val durMs = durationInput.toLongOrNull()
                                    if (durMs == null) {
                                        showToast("时长格式不正确!")
                                        return@Button
                                    }

                                    val tempSilkPath = PanelPaths.panelCacheDir / "picked-${UUID.randomUUID()}.silk"
                                    val success = try {
                                        if (isSilk) {
                                            showToast("正在发送 SILK...")
                                            WeMessageApi.sendVoice(
                                                currentConv,
                                                tempPath.absolutePathString(),
                                                durMs.coerceToInt()
                                            )
                                        } else {
                                            showToast("正在将音频转换为 SILK...")
                                            if (AudioUtils.anyToSilk(
                                                    tempPath.absolutePathString(),
                                                    tempSilkPath.absolutePathString(),
                                                )
                                            ) {
                                                WeMessageApi.sendVoice(
                                                    currentConv,
                                                    tempSilkPath.absolutePathString(),
                                                    durMs.coerceToInt(),
                                                )
                                            } else {
                                                showToast("转换失败! 查看日志以了解错误详情")
                                                false
                                            }
                                        }
                                    } finally {
                                        tempSilkPath.deleteIfExists()
                                        tempPath.deleteIfExists()
                                    }
                                    showToast("语音发送${if (success) "成功" else "失败!"}")
                                    onDismiss()
                                }) { Text("确定") }
                            })
                    }
                }
            }
        }
        // android couldn't distinguish AMR-extension SILK files, so we just use amr here
        importLauncher.launch(
            arrayOf(
                "audio/mpeg",
                "audio/amr",
                "audio/x-wav",
                "audio/wav",
                "audio/mp4",
                "audio/x-m4a",
                "application/octet-stream"
            )
        )
    }
}
