package com.Johnny.wcx.features.items.chat

import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_drop_down
import com.composables.icons.materialsymbols.outlined.Language
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.ChatInfoIcon
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.copyToClipboard
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 消息翻译。
 *
 * 入口：聊天界面「长按文字消息」的菜单里多一项「翻译」（走 [WeChatMessageContextMenuApi] 提供的
 * 稳定扩展接口，不自己 hook 微信菜单方法，因此菜单结构变化时只会「入口消失」，不会影响微信本身）。
 *
 * 能力：
 * - 引擎二选一：AI 模型（复用「AI 自动回复」的接口地址/Key/模型）或免费 Google 接口；
 * - 目标语言可选，源语言自动识别；
 * - 弹窗展示原文 + 译文，可一键复制译文；
 * - 网络在 IO 线程、15s 超时、失败 Toast 提示，译文按「引擎+语言+原文」做内存 LRU 缓存。
 */
@Feature(
    name = "消息翻译",
    categories = ["聊天"],
    description = "长按文字消息菜单增加「翻译」，弹窗展示原文与译文并可复制\n引擎可选 AI 模型（复用 AI 自动回复配置）或免费翻译接口"
)
object MessageTranslate : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "MessageTranslate"

    /** 菜单项 id 必须全局唯一（777000 是「WCX」聚合项，777005 已被「查看详情」占用）。 */
    private const val MENU_ITEM_ID = 777101

    /** 翻译引擎：0 = AI 模型，1 = 免费翻译接口。与「边写边译」共用同一个键。 */
    private var engineValue by prefOption("msg_translate_engine", 0)

    /** 目标语言（存中文标签，见 TranslateSupport.Lang）。 */
    private var targetLangLabel by prefOption("msg_translate_target_lang", "中文")

    override fun onEnable() {
        runCatching { WeChatMessageContextMenuApi.addProvider(this) }
            .onFailure { WeLogger.e(TAG, "注册消息菜单项失败", it) }
    }

    override fun onDisable() {
        runCatching { WeChatMessageContextMenuApi.removeProvider(this) }
            .onFailure { WeLogger.e(TAG, "注销消息菜单项失败", it) }
        TranslateSupport.clearCache()
    }

    // ─── 菜单项 ───────────────────────────────────────────────────────────────

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> = listOf(
        WeChatMessageContextMenuApi.MenuItem(
            MENU_ITEM_ID,
            "翻译",
            ChatInfoIcon,
            MaterialSymbols.Outlined.Language,
            // 只有「有文字的文本/引用消息」才显示菜单项；判断本身也可能因为消息实体字段漂移抛异常，
            // 这里整段 runCatching，异常时当作不支持，效果是菜单项不出现（降级，不影响微信）。
            { msg -> runCatching { isTranslatable(msg) }.getOrDefault(false) },
            // 批量多选时对每条消息逐个弹窗没有意义
            WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported,
        ) { view, _, msgInfo ->
            runCatching { openTranslateDialog(view, msgInfo) }
                .onFailure { error ->
                    WeLogger.e(TAG, "打开翻译弹窗失败", error)
                    showToast("翻译失败：${error.message ?: "未知错误"}")
                }
        }
    )

    private fun isTranslatable(msg: MessageInfo): Boolean {
        val type = msg.type ?: return false
        return type.isText && sourceTextOf(msg).isNotBlank()
    }

    /** 取待翻译文本：引用消息优先取被引用内容，其余取正文（群聊会自动去掉 `wxid:\n` 前缀）。 */
    private fun sourceTextOf(msg: MessageInfo): String {
        val raw = if (msg.type?.code == MessageType.QUOTE.code) {
            msg.quoteMsgActualContent ?: msg.actualContent
        } else {
            msg.actualContent
        }
        // 引用/链接类消息的 content 是 XML，直接翻没意义
        if (raw.trimStart().startsWith("<")) return ""
        return raw.trim()
    }

    // ─── 弹窗 ─────────────────────────────────────────────────────────────────

    private fun openTranslateDialog(view: View, msg: MessageInfo) {
        val original = sourceTextOf(msg)
        if (original.isBlank()) {
            showToast(view.context, "这条消息没有可翻译的文字")
            return
        }

        val engine = TranslateSupport.Engine.fromValue(engineValue)
        val lang = TranslateSupport.Lang.fromLabel(targetLangLabel)

        showComposeDialog(view.context) {
            var translated by remember { mutableStateOf("") }
            var errorText by remember { mutableStateOf("") }
            var loading by remember { mutableStateOf(true) }

            // LaunchedEffect 在 Main 上跑，translate() 是阻塞 socket → 必须 withContext(IO)
            LaunchedEffect(original, engine, lang) {
                val result = withContext(Dispatchers.IO) {
                    TranslateSupport.translate(original, engine, lang)
                }
                loading = false
                result
                    .onSuccess { translated = it }
                    .onFailure { error ->
                        errorText = error.message ?: "未知错误"
                        WeLogger.e(TAG, "翻译失败（${engine.name}/${lang.label}）", error)
                        showToast("翻译失败：$errorText")
                    }
            }

            AlertDialogContent(
                title = { Text("翻译（${lang.label}）") },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("原文", style = MaterialTheme.typography.labelLarge)
                        Text(original, style = MaterialTheme.typography.bodyMedium)
                        Text("译文", style = MaterialTheme.typography.labelLarge)
                        when {
                            loading -> Text("翻译中…", style = MaterialTheme.typography.bodyMedium)
                            errorText.isNotBlank() -> Text(
                                "翻译失败：$errorText",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            else -> Text(translated, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (engine == TranslateSupport.Engine.FREE && errorText.isNotBlank()) {
                            Text(
                                "提示：免费翻译接口不太稳定（需要能访问 Google），可在功能设置里把引擎切换成「AI 模型」。",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("关闭") }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            copyToClipboard(context, translated.ifBlank { original })
                            showToast("已复制")
                        },
                        enabled = translated.isNotBlank()
                    ) { Text("复制译文") }
                }
            )
        }
    }

    // ─── 设置面板 ─────────────────────────────────────────────────────────────

    @Composable
    override fun Ui() {
        var engine by remember { mutableStateOf(engineValue) }
        var langLabel by remember { mutableStateOf(targetLangLabel) }
        var engineMenuExpanded by remember { mutableStateOf(false) }
        var langMenuExpanded by remember { mutableStateOf(false) }

        ListItem(
            headlineContent = { Text("翻译引擎") },
            supportingContent = {
                Text(TranslateSupport.Engine.fromValue(engine).label)
            },
            trailingContent = {
                Box {
                    IconButton(onClick = { engineMenuExpanded = true }) {
                        Icon(
                            MaterialSymbols.Outlined.Arrow_drop_down,
                            contentDescription = "选择翻译引擎"
                        )
                    }
                    DropdownMenu(
                        expanded = engineMenuExpanded,
                        onDismissRequest = { engineMenuExpanded = false }
                    ) {
                        TranslateSupport.Engine.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    engine = option.value
                                    engineValue = option.value
                                    engineMenuExpanded = false
                                    // 换引擎后旧缓存可能来自另一个接口，清掉避免混淆
                                    TranslateSupport.clearCache()
                                }
                            )
                        }
                    }
                }
            }
        )

        ListItem(
            headlineContent = { Text("目标语言") },
            supportingContent = { Text(langLabel) },
            trailingContent = {
                Box {
                    IconButton(onClick = { langMenuExpanded = true }) {
                        Icon(
                            MaterialSymbols.Outlined.Arrow_drop_down,
                            contentDescription = "选择目标语言"
                        )
                    }
                    DropdownMenu(
                        expanded = langMenuExpanded,
                        onDismissRequest = { langMenuExpanded = false }
                    ) {
                        TranslateSupport.Lang.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    langLabel = option.label
                                    targetLangLabel = option.label
                                    langMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        )

        ListItem(
            headlineContent = { Text("源语言") },
            supportingContent = { Text("自动识别（无需设置）") }
        )
    }
}
