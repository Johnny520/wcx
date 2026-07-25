package com.Johnny.wcx.features.items.chat

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

@Feature(name = "已读追踪", categories = ["聊天"], description = "追踪群聊消息已读人数，通过本地Hook微信已读回执，不依赖外部服务器")
object ReadReceipts : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener {

    private const val TAG = "ReadReceipts"

    private var prefix by prefOption("read_receipts_prefix", "#")

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private const val VIEW_TAG_ID = 0x7E000002
    private const val COUNT_MARKER = "​ | 已读 "

    private val counts = ConcurrentHashMap<Long, Int>()
    private val activeViews = Collections.synchronizedMap(WeakHashMap<TextView, TrackedRef>())

    private data class TrackedRef(val talker: String, val msgId: Long)

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
        hookReadReceipt()
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        activeViews.clear()
        counts.clear()
    }

    private fun hookReadReceipt() {
        runCatching {
            val clazz = Class.forName("com.tencent.mm.storage.MsgInfo")
            clazz.reflekt().methods {
                name = "setReadStatus"
                parameters(Int::class.java)
            }.forEach { method ->
                method.hookAfter {
                    val msgInfo = thisObject
                    val msgId = msgInfo.reflekt().firstField { name = "msgId" }.get() as Long
                    val talker = msgInfo.reflekt().firstField { name = "talker" }.get() as String
                    val isSend = msgInfo.reflekt().firstField { name = "isSend" }.get() as Int

                    if (isSend != 1) return@hookAfter
                    if (!talker.isGroupChatWxId) return@hookAfter

                    updateReadCount(talker, msgId)
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook read receipt", it)
        }

        runCatching {
            val clazz = Class.forName("com.tencent.mm.storage.MsgInfoStorage")
            clazz.reflekt().methods {
                name = "updateReadStatus"
            }.forEach { method ->
                method.hookAfter {
                    val talker = args.getOrNull(0) as? String ?: return@hookAfter
                    if (!talker.isGroupChatWxId) return@hookAfter

                    updateAllReadCounts(talker)
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook updateReadStatus", it)
        }
    }

    private fun updateReadCount(talker: String, msgId: Long) {
        runCatching {
            val cursor = WeDatabaseApi.rawQuery(
                "SELECT COUNT(DISTINCT sender) FROM message WHERE talker = ? AND msgId <= ? AND isSend = 0 AND status = 3",
                arrayOf(talker, msgId.toString())
            )

            cursor.use {
                if (it.moveToFirst()) {
                    val count = it.getInt(0)
                    counts[msgId] = count

                    val targets = synchronized(activeViews) {
                        activeViews.entries.filter { entry -> entry.value.msgId == msgId }.map { entry -> entry.key }
                    }
                    for (tv in targets) {
                        mainHandler.post { applyCount(tv, msgId, count) }
                    }
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to update read count", it)
        }
    }

    private fun updateAllReadCounts(talker: String) {
        runCatching {
            val cursor = WeDatabaseApi.rawQuery(
                "SELECT msgId, COUNT(DISTINCT sender) as readCount FROM message " +
                        "WHERE talker = ? AND isSend = 1 AND type = 1 " +
                        "GROUP BY msgId ORDER BY msgId DESC LIMIT 50",
                arrayOf(talker)
            )

            cursor.use {
                while (it.moveToNext()) {
                    val msgId = it.getLong(0)
                    val count = it.getInt(1)
                    counts[msgId] = count

                    val targets = synchronized(activeViews) {
                        activeViews.entries.filter { entry -> entry.value.msgId == msgId }.map { entry -> entry.key }
                    }
                    for (tv in targets) {
                        mainHandler.post { applyCount(tv, msgId, count) }
                    }
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to update all read counts", it)
        }
    }

    override fun onCreateView(param: XC_MethodHook.MethodHookParam, view: View) {
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (msgInfo.isSend == 0) return

        val talker = msgInfo.talker
        if (!talker.isGroupChatWxId) return

        val msgId = msgInfo.msgId

        val tag = view.tag ?: return
        val timeTV = tag.reflekt()
            .firstField { name = "timeTV"; superclass() }
            .get() as? TextView? ?: return

        timeTV.setTag(VIEW_TAG_ID, msgId)
        activeViews[timeTV] = TrackedRef(talker, msgId)

        counts[msgId]?.let { applyCount(timeTV, msgId, it) }
    }

    @SuppressLint("SetTextI18n")
    private fun applyCount(timeTV: TextView, msgId: Long, count: Int) {
        if (timeTV.getTag(VIEW_TAG_ID) != msgId) return
        val base = (timeTV.text ?: "").toString().substringBefore(COUNT_MARKER)
        timeTV.text = "$base$COUNT_MARKER$count 人"
        timeTV.visibility = View.VISIBLE
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var prefixInput by remember { mutableStateOf(prefix) }

            AlertDialogContent(
                title = { Text("已读追踪") },
                text = {
                    DefaultColumn {
                        TextField(
                            value = prefixInput,
                            onValueChange = { prefixInput = it },
                            label = { Text("触发前缀") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "说明: 本功能通过本地Hook微信已读回执实现，无需外部服务器。由于微信客户端限制，已读人数可能存在延迟或不完全准确。",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        if (prefixInput.isEmpty()) {
                            showToast(context, "警告: 「触发前缀」为空, 所有文本消息将启用已读追踪!")
                        }
                        prefix = prefixInput
                        onDismiss()
                    }) { Text("确定") }
                })
        }
    }
}