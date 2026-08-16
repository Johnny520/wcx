package com.Johnny.wcx.features.items.chat

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Person_search
import com.composables.icons.materialsymbols.outlined.Schedule
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.IWeContact
import com.Johnny.wcx.features.api.core.models.WeContact
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.IconButton
import com.Johnny.wcx.ui.content.SingleContactSelector
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.copyToClipboard
import com.Johnny.wcx.utils.android.readTextFromClipboard
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.serialization.XmlJsonParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.random.Random

@Feature(name = "伪造聊天记录消息", categories = ["聊天"], description = "创建伪造的聊天记录并生成卡片消息 XML")
object FabricateChatHistoryMessage : ClickableFeature() {

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        val contacts = WeDatabaseApi.getContacts()

        showComposeDialog(context) {
            ChatRecordXmlGeneratorDialog(
                contacts = contacts,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun ChatRecordXmlGeneratorDialog(
    contacts: List<WeContact>,
    onDismiss: () -> Unit
) {
    var outerTitle by remember { mutableStateOf("聊天记录") }
    var outerDesc by remember { mutableStateOf("描述") }

    val rows = remember {
        mutableStateListOf(
            MessageRowState(initialTimeMillis = System.currentTimeMillis())
        )
    }

    val context = LocalContext.current
    val contactsByWxId = remember(contacts) { contacts.associateBy { it.wxId } }

    val canGenerate by remember(rows, contacts) {
        derivedStateOf {
            rows.isNotEmpty() &&
                    rows.all { it.senderWxId != null && it.senderWxId!!.isNotBlank() && it.text.isNotBlank() && !it.isTimeError } &&
                    contactsByWxId.isNotEmpty()
        }
    }

    AlertDialogContent(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        title = { Text("伪造聊天记录消息") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = outerTitle,
                    onValueChange = { outerTitle = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("标题") },
                    singleLine = true
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = outerDesc,
                        onValueChange = { outerDesc = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("描述") }
                    )

                    Spacer(Modifier.width(8.dp))

                    TextButton(
                        onClick = {
                            outerDesc = buildString {
                                val takeCount = minOf(rows.size, 5)
                                for (i in 0 until takeCount) {
                                    val row = rows[i]
                                    val nickname = contactsByWxId[row.senderWxId]?.nickname ?: "未知"
                                    append("$nickname: ${row.text}")
                                    if (i < takeCount - 1) {
                                        append("\n")
                                    }
                                }
                                if (rows.size > 5) {
                                    append("...")
                                }
                            }
                        }
                    ) {
                        Text("生成")
                    }
                }

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        val clipboardText = readTextFromClipboard(context)
                        if (clipboardText.isNullOrBlank()) {
                            showToast(context, "剪贴板为空!")
                            return@Button
                        }
                        runCatching {
                            val outerJson = XmlJsonParser.toJsonObject(clipboardText)
                            val appmsg = outerJson["msg"]?.jsonObject?.get("appmsg")?.jsonObject ?: error("未找到 appmsg")
                            val parsedTitle = appmsg["title"]?.jsonPrimitive?.contentOrNull
                            val parsedDesc = appmsg["des"]?.jsonPrimitive?.contentOrNull
                            val recordItemCdata = appmsg["recorditem"]!!.jsonPrimitive.content
                            if (recordItemCdata.isBlank()) {
                                error("recorditem 内容为空")
                            }
                            val innerJson = XmlJsonParser.toJsonObject(recordItemCdata)
                            val recordInfo = innerJson["recordinfo"]?.jsonObject ?: error("未找到 recordinfo")
                            val datalist = recordInfo["datalist"]?.jsonObject ?: error("未找到 datalist")
                            val dataItems: List<JsonObject> = when (val elems = datalist["dataitem"]) {
                                is JsonArray -> elems.map { it.jsonObject }
                                is JsonObject -> listOf(elems)
                                else -> emptyList()
                            }
                            if (dataItems.isEmpty()) {
                                error("未找到消息条目")
                            }

                            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            val newRows = dataItems.mapNotNull { item ->
                                val text = item["datadesc"]?.jsonPrimitive?.contentOrNull?.trim()
                                    ?: return@mapNotNull null
                                val sourceName = item["sourcename"]?.jsonPrimitive?.contentOrNull
                                    ?: return@mapNotNull null
                                val contact = contacts.firstOrNull { it.nickname == sourceName }

                                // Attempt to extract and parse the existing source time
                                val sourceTimeStr = item["sourcetime"]?.jsonPrimitive?.contentOrNull?.replace("&#x20;", " ")
                                val parsedTimeMillis = sourceTimeStr?.let {
                                    runCatching { timeFormat.parse(it)?.time }.getOrNull()
                                } ?: System.currentTimeMillis()

                                MessageRowState(
                                    senderWxId = contact?.wxId,
                                    text = text,
                                    initialTimeMillis = parsedTimeMillis
                                )
                            }
                            if (newRows.isEmpty()) {
                                error("未能解析出有效消息")
                            }
                            if (parsedTitle != null) outerTitle = parsedTitle
                            if (parsedDesc != null) outerDesc = parsedDesc
                            rows.clear()
                            rows.addAll(newRows)
                            showToast(context, "已从剪贴板加载 ${newRows.size} 条消息")
                        }.onFailure {
                            showToast(context, "解析失败: ${it.message}")
                            WeLogger.e("FabricateChatHistoryMessage", "failed to parse messages from clipboard", it)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("从剪贴板加载")
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "消息列表",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = {
                            // Automatically offset new items progressively by 2 seconds
                            val nextTime = if (rows.isNotEmpty()) {
                                (rows.last().parsedTimeMillis ?: System.currentTimeMillis()) + 2000L
                            } else {
                                System.currentTimeMillis()
                            }
                            rows.add(MessageRowState(initialTimeMillis = nextTime))
                        }
                    ) {
                        Icon(MaterialSymbols.Outlined.Add, contentDescription = "Add message")
                    }
                }

                Spacer(Modifier.height(8.dp))

                rows.forEachIndexed { index, row ->
                    MessageRowEditor(
                        row = row,
                        contactsByWxId = contactsByWxId,
                        onPickSender = {
                            showComposeDialog(context) {
                                SingleContactSelector(
                                    title = "选择发送者",
                                    contacts = contacts,
                                    initialSelectedWxId = row.senderWxId,
                                    onDismiss = this.onDismiss,
                                    onConfirm = { wxId ->
                                        row.senderWxId = wxId
                                        this.onDismiss()
                                    }
                                )
                            }
                        },
                        onRemove = {
                            if (rows.size > 1) {
                                rows.removeAt(index)
                            } else {
                                showToast(context, "无法删除最后一条消息!")
                            }
                        }
                    )

                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        confirmButton = {
            Button(
                enabled = canGenerate,
                onClick = {
                    val xml = buildWeChatRecordXml(
                        outerTitle = outerTitle,
                        outerDesc = outerDesc,
                        rows = rows.map { it.toSnapshot() },
                        contacts = contacts
                    )

                    copyToClipboard(context, xml)
                    showToast(context, "已复制")
                }
            ) { Text("复制") }
            Button(
                enabled = canGenerate,
                onClick = {
                    val xml = buildWeChatRecordXml(
                        outerTitle = outerTitle,
                        outerDesc = outerDesc,
                        rows = rows.map { it.toSnapshot() },
                        contacts = contacts
                    )
                    showComposeDialog(context) {
                        SingleContactSelector(
                            title = "选择发送目标",
                            contacts = contacts,
                            initialSelectedWxId = null,
                            onDismiss = this.onDismiss,
                            onConfirm = { wxId ->
                                WeMessageApi.sendXmlAppMsg(wxId, xml)
                                showToast(context, "已发送")
                                this.onDismiss()
                            }
                        )
                    }
                }
            ) { Text("发送") }
        }
    )
}

@Composable
private fun MessageRowEditor(
    row: MessageRowState,
    contactsByWxId: Map<String, IWeContact>,
    onPickSender: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val selectedContact = remember(row.senderWxId, contactsByWxId) {
        row.senderWxId?.let { contactsByWxId[it] }
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedContact?.displayName ?: "选择发送者 →",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (selectedContact != null) {
                        Text(
                            text = selectedContact.wxId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onPickSender) {
                    Icon(MaterialSymbols.Outlined.Person_search, contentDescription = "Select sender")
                }

                IconButton(
                    onClick = onRemove,
                    enabled = true
                ) {
                    Icon(MaterialSymbols.Outlined.Delete, contentDescription = "Remove message")
                }
            }

            OutlinedTextField(
                value = row.text,
                onValueChange = { row.text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("消息内容") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = row.timeText,
                onValueChange = { row.timeText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("发送时间 (yyyy-MM-dd HH:mm:ss)") },
                isError = row.isTimeError,
                supportingText = {
                    if (row.isTimeError) {
                        Text("时间格式不正确")
                    }
                },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            showNativeDateTimePicker(context, row.parsedTimeMillis ?: System.currentTimeMillis()) { selectedMillis ->
                                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                row.timeText = formatter.format(Date(selectedMillis))
                            }
                        }
                    ) {
                        Icon(MaterialSymbols.Outlined.Schedule, contentDescription = "Pick Date Time")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
            )
        }
    }
}

private fun showNativeDateTimePicker(
    context: Context,
    initialMillis: Long,
    onDateTimeSelected: (Long) -> Unit
) {
    val calendar = Calendar.getInstance().apply { timeInMillis = initialMillis }

    val datePickerDialog = DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            calendar.set(Calendar.YEAR, year)
            calendar.set(Calendar.MONTH, month)
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)

            TimePickerDialog(
                context,
                { _, hourOfDay, minute ->
                    calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    calendar.set(Calendar.MINUTE, minute)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    onDateTimeSelected(calendar.timeInMillis)
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                true
            ).show()
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    )
    datePickerDialog.show()
}

@Stable
private class MessageRowState(
    senderWxId: String? = null,
    text: String = "",
    initialTimeMillis: Long = System.currentTimeMillis()
) {
    var senderWxId by mutableStateOf(senderWxId)
    var text by mutableStateOf(text)

    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    var timeText: String by mutableStateOf(formatter.format(Date(initialTimeMillis)))

    val parsedTimeMillis: Long? by derivedStateOf {
        runCatching { formatter.parse(timeText)?.time }.getOrNull()
    }

    val isTimeError: Boolean by derivedStateOf {
        parsedTimeMillis == null
    }

    fun toSnapshot() = MessageRowSnapshot(
        senderWxId = senderWxId,
        text = text,
        timestampMillis = parsedTimeMillis ?: System.currentTimeMillis()
    )
}

private data class MessageRowSnapshot(
    val senderWxId: String?,
    val text: String,
    val timestampMillis: Long
)

// ------------------------------------------------------------
// XML generation
// ------------------------------------------------------------

private fun buildWeChatRecordXml(
    outerTitle: String,
    outerDesc: String,
    rows: List<MessageRowSnapshot>,
    contacts: List<WeContact>
): String {
    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }

    val innerTitle = xmlEscape(outerTitle)
    val innerDesc = xmlEscape(outerDesc)

    return buildString {
        appendLine("<msg>")
        appendLine("    <appmsg>")
        appendLine("        <title>${xmlEscape(outerTitle)}</title>")
        appendLine("        <des>${xmlEscapeNewLines(outerDesc)}</des>")
        appendLine("        <action>view</action>")
        appendLine("        <type>19</type>")
        appendLine("        <url>")
        appendLine("            https://support.weixin.qq.com/cgi-bin/mmsupport-bin/readtemplate?t=page/favorite_record__w_unsupport&amp;from=singlemessage&amp;isappinstalled=0</url>")
        appendLine("        <recorditem><![CDATA[<recordinfo><title>$innerTitle</title><desc>$innerDesc</desc><datalist count=\"${rows.size}\">")

        rows.forEachIndexed { index, row ->
            val contact = row.senderWxId?.let { wxId -> contacts.firstOrNull { it.wxId == wxId } }
            if (contact != null) {
                val sourceName = contact.nickname
                val sourceHeadUrl = contact.avatarUrl

                val sourcetime = timeFormat.format(Date(row.timestampMillis)).replace(" ", "&#x20;")
                val srcMsgCreateTime = row.timestampMillis / 1000L
                val fromNewMsgId = generateFakeMsgId(index)

                append("<dataitem datatype=\"1\" dataid=\"\">")
                append("<datadesc>${xmlEscape(row.text)}</datadesc>")
                append("<sourcename>${xmlEscape(sourceName)}</sourcename>")
                append("<sourceheadurl>${xmlEscape(sourceHeadUrl)}</sourceheadurl>")
                append("<sourcetime>$sourcetime</sourcetime>")
                append("<srcMsgCreateTime>$srcMsgCreateTime</srcMsgCreateTime>")
                append("<fromnewmsgid>$fromNewMsgId</fromnewmsgid>")
                append("<dataitemsource><hashusername></hashusername></dataitemsource>")
                append("</dataitem>")
            }
        }

        appendLine("</datalist></recordinfo>]]></recorditem>")
        appendLine("    </appmsg>")
        appendLine("    <extcommoninfo>")
        appendLine("        <media_expire_at>2000000000</media_expire_at>")
        appendLine("    </extcommoninfo>")
        appendLine("</msg>")
    }
}

private fun generateFakeMsgId(index: Int): Long {
    val a = System.currentTimeMillis()
    val b = Random.nextLong(1_000_000_000L, 9_999_999_999L)
    return abs(a + b + index)
}

private fun xmlEscape(text: String): String {
    return buildString(text.length) {
        text.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }
}

private fun xmlEscapeNewLines(text: String): String {
    return xmlEscape(text).replace("\n", "&#x0A;").replace(" ", "&#x20;")
}
