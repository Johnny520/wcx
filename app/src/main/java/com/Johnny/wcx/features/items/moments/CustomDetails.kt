package com.Johnny.wcx.features.items.moments

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import com.tencent.mm.view.recyclerview.WxRecyclerView
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.ui.WeMomentsContextMenuApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.EditIcon
import com.Johnny.wcx.ui.utils.findViewWhich
import com.Johnny.wcx.ui.utils.rootView
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.formatEpoch
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.serialization.DefaultJson
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Feature(
    name = "自定义底部详细信息",
    categories = ["朋友圈"],
    description = "长按朋友圈自定义该条底部详细信息\n需同时打开「朋友圈/底部详细信息」"
)
object CustomDetails : ClickableFeature(), WeMomentsContextMenuApi.IMenuItemsProvider, IResolveDex {

    private const val TAG = "CustomDetails"

    private val PLACEHOLDERS = listOf(
        $$"$originalText",
        $$"$time",
        $$"$type",
        $$"$snsId",
        $$"$userName"
    )

    private val customTextsFile by lazy { KnownPaths.moduleData / "moments_custom_bottom_details.json" }

    // ── 精确时间展示 ────────────────────────────────────────────────────────────
    private var preciseTimeEnabled by prefOption("moments_precise_notify_time", false)
    private const val PRECISE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss"

    private val TIMESTAMP_REGEX = Regex(
        """^\d+分钟前$|^\d+小时前$|^\d+天前$|^刚刚$|^昨天$|^\d{1,2}:\d{2}$|^\d+\s*mins?\s*ago$|^\d+\s*hrs?\s*ago$|^\d+\s*days?\s*ago$|^yesterday$""",
        RegexOption.IGNORE_CASE
    )

    // ── DEX: 朋友圈消息通知页 (铃铛列表) ──────────────────────────────────────────
    private val classSnsCommentNotifyUI by dexClass {
        searchPackages("com.tencent.mm.plugin.sns.ui")
        matcher {
            usingEqStrings("MicroMsg.SnsCommentUI")
        }
    }

    private val methodSnsCommentNotifyOnCreate by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.ui")
        matcher {
            declaredClass(classSnsCommentNotifyUI.clazz)
            name = "onCreate"
            paramCount = 1
        }
    }

    override fun onEnable() {
        WeMomentsContextMenuApi.addProvider(this)

        if (!classSnsCommentNotifyUI.isPlaceholder) {
            methodSnsCommentNotifyOnCreate.hookAfter {
                val activity = thisObject as Activity
                scheduleAttachNotifyPage(activity)
            }
        } else {
            WeLogger.w(TAG, "SnsCommentUI not found, precise time feature unavailable")
        }
    }

    override fun onDisable() {
        WeMomentsContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeMomentsContextMenuApi.MenuItem> {
        return listOf(
            WeMomentsContextMenuApi.MenuItem(
                777017,
                "自定义底部详细信息",
                EditIcon,
                { _, _ -> true }
            ) click@{ moment ->
                val snsId = resolveSnsId(moment.snsInfo)
                if (snsId == null) {
                    showToast("未找到朋友圈 ID!")
                    return@click
                }
                showEditor(moment.activity, snsId)
            }
        )
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var preciseTimeInput by remember { mutableStateOf(preciseTimeEnabled) }

            AlertDialogContent(
                title = { Text("自定义底部详细信息 - 设置") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable { preciseTimeInput = !preciseTimeInput },
                            trailingContent = {
                                Switch(checked = preciseTimeInput, onCheckedChange = null)
                            },
                            headlineContent = { Text("朋友圈消息通知精确时间") },
                            supportingContent = { Text("在铃铛列表页显示完整时间格式 [年-月-日 时:分:秒]\n仅本地渲染，不影响微信底层数据") },
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        preciseTimeEnabled = preciseTimeInput
                        showToast(if (preciseTimeInput) "已开启精确时间展示" else "已关闭精确时间展示")
                        onDismiss()
                    }) { Text("保存") }
                }
            )
        }
    }

    fun getCustomText(snsId: Long): String? {
        return getCustomTexts()[snsId.toString()]?.takeIf { it.isNotBlank() }
    }

    @OptIn(ExperimentalLayoutApi::class)
    private fun showEditor(context: Context, snsId: Long) {
        showComposeDialog(context) {
            var textInput by remember { mutableStateOf(TextFieldValue(getCustomText(snsId).orEmpty())) }
            var isFocused by remember { mutableStateOf(false) }

            val insertPlaceholder = { placeholder: String ->
                val selection = textInput.selection
                val text = textInput.text
                if (isFocused) {
                    val newText = text.substring(0, selection.start) + placeholder + text.substring(selection.end)
                    val newSelection = TextRange(selection.start + placeholder.length)
                    textInput = TextFieldValue(newText, newSelection)
                } else {
                    val newText = text + placeholder
                    textInput = TextFieldValue(newText, TextRange(newText.length))
                }
            }

            AlertDialogContent(
                title = { Text("自定义底部详细信息") },
                text = {
                    DefaultColumn {
                        Text("留空保存可清除该条自定义内容")
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            label = { Text("底部信息内容") },
                            minLines = 3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isFocused = it.isFocused }
                        )

                        Text("点击插入占位符:")

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            PLACEHOLDERS.forEach { ph ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                        .clickable { insertPlaceholder(ph) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = ph,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        setCustomText(snsId, textInput.text)
                        showToast(if (textInput.text.isBlank()) "已清除自定义底部信息" else "已保存自定义底部信息")
                        onDismiss()
                    }) {
                        Text("保存")
                    }
                }
            )
        }
    }

    private fun resolveSnsId(snsInfo: Any?): Long? {
        return (snsInfo?.reflekt()?.getField("field_snsId", true) as? Number)?.toLong()
    }

    // ── 朋友圈消息通知页 精确时间展示 ────────────────────────────────────────────────

    private fun scheduleAttachNotifyPage(activity: Activity) {
        val root = activity.rootView
        intArrayOf(0, 200, 800, 2_000).forEach { delayMs ->
            root.postDelayed({
                runCatching {
                    val recycler = root.findViewWhich<WxRecyclerView> { it is WxRecyclerView } ?: return@runCatching
                    val adapter = recycler.reflekt().getField("adapter") ?: return@runCatching

                    // Hook adapter.onBindViewHolder to intercept time display
                    adapter.reflekt()
                        .firstMethod { name = "onBindViewHolder" }
                        .hookAfter {
                            if (!preciseTimeEnabled) return@hookAfter
                            val holder = args[0]
                            runCatching {
                                val itemView = holder.reflekt()
                                    .firstField { name = "itemView" }
                                    .get(holder) as? View ?: return@runCatching
                                processNotifyItemView(itemView)
                            }
                        }
                }.onFailure {
                    WeLogger.w(TAG, "notify page attach failed, delay=${delayMs}ms", it)
                }
            }, delayMs.toLong())
        }
    }

    private fun processNotifyItemView(itemView: View) {
        if (!preciseTimeEnabled) return

        val timeTextViews = findTimeTextViews(itemView)
        for (tv in timeTextViews) {
            val text = tv.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) continue

            // 相对时间文本匹配成功时，尝试替换为完整格式
            if (TIMESTAMP_REGEX.matches(text)) {
                // 从 view 层级中查找可能携带时间戳的 tag 或 sibling view
                val timestamp = extractTimestampFromView(itemView)
                if (timestamp != null) {
                    val formatted = formatEpoch(timestamp, PRECISE_TIME_FORMAT)
                    tv.text = formatted
                }
            }
        }
    }

    /**
     * 尝试从通知列表项的 view 层级中提取原始时间戳（毫秒）。
     * 朋友圈通知列表的 itemView 或其子 view 可能通过 tag 携带时间戳信息。
     */
    private fun extractTimestampFromView(itemView: View): Long? {
        // 检查 itemView 自身 tag
        if (itemView is ViewGroup) {
            for (i in 0 until itemView.childCount) {
                val child = itemView.getChildAt(i)
                val tag = child.tag
                if (tag is Long) return tag
            }
        }
        return null
    }

    private fun findTimeTextViews(root: View): List<TextView> {
        val result = mutableListOf<TextView>()
        if (root is TextView && root.isVisible && root.text.isNotBlank()) {
            result.add(root)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                result.addAll(findTimeTextViews(root.getChildAt(i)))
            }
        }
        return result
    }

    private fun setCustomText(snsId: Long, text: String) {
        val customTexts = loadCustomTexts().toMutableMap()
        val key = snsId.toString()
        val normalized = text.trim()
        if (normalized.isBlank()) {
            customTexts.remove(key)
        } else {
            customTexts[key] = normalized
        }
        saveCustomTexts(customTexts)
    }

    /**
     * Load custom texts from JSON file (snsId -> text).
     */
    private fun loadCustomTexts(): Map<String, String> {
        val file = customTextsFile
        if (!file.exists()) return emptyMap()
        return runCatching {
            DefaultJson.decodeFromString<Map<String, String>>(file.readText())
                .filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
        }.getOrElse { e ->
            WeLogger.e(TAG, "failed to load $customTextsFile", e)
            emptyMap()
        }
    }

    private fun saveCustomTexts(customTexts: Map<String, String>) {
        runCatching {
            customTextsFile.writeText(DefaultJson.encodeToString(customTexts))
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to save $customTextsFile", e)
        }
        markCacheDirty()
    }

    @Volatile
    private var customTextsCache: Map<String, String>? = null
    private val cacheDirty = AtomicBoolean(true)

    private fun getCustomTexts(): Map<String, String> {
        if (cacheDirty.compareAndSet(true, false)) {
            customTextsCache = loadCustomTexts()
        }
        return customTextsCache!!
    }

    private fun markCacheDirty() {
        cacheDirty.set(true)
    }
}
