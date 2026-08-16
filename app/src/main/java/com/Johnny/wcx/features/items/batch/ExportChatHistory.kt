package com.Johnny.wcx.features.items.batch

import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton as UiTextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Feature(
    name = "导出聊天记录",
    categories = ["批量操作"],
    description = "导出选中对话的聊天记录为 TXT / CSV / JSON 格式，保存到 Download/WCX 目录"
)
object ExportChatHistory : ClickableFeature() {

    private const val TAG = "ExportChatHistory"

    override val noSwitchWidget = true

    private enum class ExportFormat(val displayName: String, val ext: String) {
        TXT("纯文本 (.txt)", "txt"),
        CSV("表格 (.csv)", "csv"),
        JSON("JSON (.json)", "json")
    }

    override fun onClick(context: ComponentActivity) {
        val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

        showComposeDialog(context) {
            var selectedWxIds by remember { mutableStateOf(emptySet<String>()) }
            var step by remember { mutableIntStateOf(0) }
            var selectedFormat by remember { mutableStateOf(ExportFormat.TXT) }

            if (step == 0) {
                ContactsSelector(
                    title = "选择要导出的对话",
                    contacts = contacts,
                    initialSelectedWxIds = emptySet(),
                    onDismiss = onDismiss,
                    onConfirm = { wxIds ->
                        if (wxIds.isEmpty()) {
                            showToast("请选择至少一个对话")
                            return@ContactsSelector
                        }
                        selectedWxIds = wxIds
                        step = 1
                    }
                )
            } else {
                AlertDialogContent(
                    title = { Text("选择导出格式") },
                    text = {
                        DefaultColumn {
                            Text("已选择 ${selectedWxIds.size} 个对话")
                            Text("导出后文件将保存到: Download/WCX/")
                            Spacer(Modifier.padding(top = 8.dp))
                            ExportFormat.values().forEach { format ->
                                TextButton(
                                    onClick = { selectedFormat = format }
                                ) {
                                    val icon = if (selectedFormat == format) "● " else "○ "
                                    Text(icon + format.displayName)
                                }
                            }
                        }
                    },
                    dismissButton = {
                        UiTextButton(onClick = { step = 0 }) { Text("上一步") }
                    },
                    confirmButton = {
                        Button(onClick = {
                            onDismiss()
                            exportChatHistory(selectedWxIds, selectedFormat)
                        }) { Text("导出") }
                    }
                )
            }
        }
    }

    private fun exportChatHistory(wxIds: Set<String>, format: ExportFormat) {
        CoroutineScope(Dispatchers.IO).launch {
            showToastSuspend("正在导出 ${wxIds.size} 个对话的聊天记录...")

            runCatching {
                val exportDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "WCX"
                )
                exportDir.mkdirs()

                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "wcx_chat_export_${timeStamp}.${format.ext}"
                val outputFile = File(exportDir, fileName)

                FileWriter(outputFile, Charsets.UTF_8).use { writer ->
                    when (format) {
                        ExportFormat.TXT -> exportAsText(writer, wxIds)
                        ExportFormat.CSV -> exportAsCsv(writer, wxIds)
                        ExportFormat.JSON -> exportAsJson(writer, wxIds)
                    }
                }

                outputFile.absolutePath
            }.onSuccess { path ->
                showToastSuspend("导出成功！文件路径: $path")
            }.onFailure { e ->
                showToastSuspend("导出失败: ${e.message}")
            }
        }
    }

    private fun getDisplayName(wxId: String): String {
        return runCatching { WeDatabaseApi.getDisplayName(wxId) }.getOrDefault(wxId)
    }

    private fun exportAsText(writer: FileWriter, wxIds: Set<String>) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        wxIds.forEachIndexed { index, wxId ->
            val displayName = getDisplayName(wxId)

            if (index > 0) {
                writer.write("\n")
                writer.write("=".repeat(60))
                writer.write("\n\n")
            }

            writer.write("对话: $displayName ($wxId)")
            writer.write("\n")
            writer.write("-".repeat(60))
            writer.write("\n\n")

            var pageIndex = 1
            var hasMore = true

            while (hasMore) {
                val messages = WeDatabaseApi.getMessages(wxId, pageIndex, 100)
                if (messages.isEmpty()) {
                    hasMore = false
                    break
                }

                messages.reversed().forEach { msg ->
                    val time = dateFormat.format(Date(msg.createTime))
                    val sender = if (msg.isSend == 1) "我" else displayName
                    val typeName = msg.type?.displayName ?: "未知"
                    val content = when {
                        msg.type?.isText == true -> msg.content
                        else -> "[$typeName] ${msg.content.take(100)}"
                    }

                    writer.write("[$time] $sender: $content")
                    writer.write("\n")
                }

                pageIndex++
                if (messages.size < 100) hasMore = false
            }
        }
    }

    private fun exportAsCsv(writer: FileWriter, wxIds: Set<String>) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        writer.write("对话ID,对话名称,消息ID,发送时间,发送方,消息类型,内容")
        writer.write("\n")

        wxIds.forEach { wxId ->
            val displayName = getDisplayName(wxId)

            var pageIndex = 1
            var hasMore = true

            while (hasMore) {
                val messages = WeDatabaseApi.getMessages(wxId, pageIndex, 100)
                if (messages.isEmpty()) {
                    hasMore = false
                    break
                }

                messages.reversed().forEach { msg ->
                    val time = dateFormat.format(Date(msg.createTime))
                    val sender = if (msg.isSend == 1) "我" else displayName
                    val typeName = msg.type?.displayName ?: "未知"
                    val content = msg.content.replace("\"", "\"\"").replace("\n", "\\n")

                    writer.write("\"$wxId\",\"$displayName\",\"${msg.msgId}\",\"$time\",\"$sender\",\"$typeName\",\"$content\"")
                    writer.write("\n")
                }

                pageIndex++
                if (messages.size < 100) hasMore = false
            }
        }
    }

    private fun exportAsJson(writer: FileWriter, wxIds: Set<String>) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        writer.write("[")
        writer.write("\n")

        wxIds.forEachIndexed { convIdx, wxId ->
            val displayName = getDisplayName(wxId)

            writer.write("  {")
            writer.write("\n")
            writer.write("    \"conversationId\": \"$wxId\",")
            writer.write("\n")
            writer.write("    \"displayName\": \"$displayName\",")
            writer.write("\n")
            writer.write("    \"messages\": [")
            writer.write("\n")

            var pageIndex = 1
            var hasMore = true
            var msgIndex = 0

            while (hasMore) {
                val messages = WeDatabaseApi.getMessages(wxId, pageIndex, 100)
                if (messages.isEmpty()) {
                    hasMore = false
                    break
                }

                messages.reversed().forEach { msg ->
                    val time = dateFormat.format(Date(msg.createTime))
                    val sender = if (msg.isSend == 1) "self" else "other"
                    val typeName = msg.type?.name ?: "UNKNOWN"
                    val content = msg.content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

                    if (msgIndex > 0) writer.write(",")
                    writer.write("\n")
                    writer.write("      {")
                    writer.write("\n")
                    writer.write("        \"msgId\": ${msg.msgId},")
                    writer.write("\n")
                    writer.write("        \"msgSvrId\": ${msg.msgSvrId},")
                    writer.write("\n")
                    writer.write("        \"time\": \"$time\",")
                    writer.write("\n")
                    writer.write("        \"sender\": \"$sender\",")
                    writer.write("\n")
                    writer.write("        \"type\": \"$typeName\",")
                    writer.write("\n")
                    writer.write("        \"content\": \"$content\"")
                    writer.write("\n")
                    writer.write("      }")
                    msgIndex++
                }

                pageIndex++
                if (messages.size < 100) hasMore = false
            }

            writer.write("\n    ]")
            writer.write("\n  }")
            if (convIdx < wxIds.size - 1) writer.write(",")
            writer.write("\n")
        }

        writer.write("]")
        writer.write("\n")
    }
}
