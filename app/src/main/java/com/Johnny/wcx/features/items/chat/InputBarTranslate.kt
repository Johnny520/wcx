package com.Johnny.wcx.features.items.chat

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_drop_down
import com.composables.icons.materialsymbols.outlined.Language
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.api.ui.WeChatInputBarMenuApi
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.utils.findViewWhich
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.runOnUiThread
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.WeakHashMap

/**
 * 边写边译（输入框实时翻译）。
 *
 * 触发方式（设置项）：
 * - 0 输入框右侧小按钮：在每个 ChatFooter 上注入一个「译」小按钮，点一下就把输入框文本翻译成目标语言并替换；
 * - 1 长按发送：长按原生发送按钮触发翻译（不改发送本身的行为）。
 *
 * 撤销：翻译完成后 Toast 提示「已翻译，长按发送可撤销」，长按发送即还原原文。
 *
 * 兜底设计（重要）：
 * 1. 注入按钮需要遍历微信内部布局，任何一步失败（找不到 EditText / 找不到发送按钮 / addView 抛异常）
 *    都只是「没有那个小按钮」，功能入口降级为输入栏长按菜单里的「翻译输入框文本」，不影响微信。
 * 2. 长按发送会链式调用原生 OnLongClickListener，所以输入框为空或没有待撤销内容时，
 *    原生的「聊天功能」菜单照旧弹出。
 * 3. 所有反射都 runCatching，句柄一律可空，禁止 !!。
 */
@Feature(
    name = "边写边译",
    categories = ["聊天"],
    description = "输入框右侧「译」按钮（或长按发送）一键把输入内容翻译后替换\n翻译结果可撤销，引擎与目标语言沿用「消息翻译」设置"
)
object InputBarTranslate : SwitchFeature(), WeChatInputBarMenuApi.IActionItemsProvider {

    private const val TAG = "InputBarTranslate"

    /** 触发方式：0 = 输入框右侧小按钮，1 = 长按发送。 */
    private const val TRIGGER_BUTTON = 0
    private const val TRIGGER_LONG_PRESS = 1

    private var triggerMode by prefOption("input_translate_trigger", TRIGGER_BUTTON)
    private var targetLangLabel by prefOption("input_translate_target_lang", "中文")

    /** 引擎与「消息翻译」共用同一个配置键，用户只需配一次。 */
    private var sharedEngineValue by prefOption("msg_translate_engine", 0)

    private val injectedFooters: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    @Volatile
    private var undoOriginal: String? = null

    @Volatile
    private var undoTranslated: String? = null

    @Volatile
    private var injectionFailed = false

    private var scope: CoroutineScope? = null

    private fun ioScope(): CoroutineScope =
        scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }

    // ─── 生命周期 ─────────────────────────────────────────────────────────────

    override fun onEnable() {
        runCatching { WeChatInputBarMenuApi.addProvider(this) }
            .onFailure { WeLogger.e(TAG, "注册输入栏菜单项失败", it) }

        runCatching {
            ChatFooter::class.java.reflekt()
                .firstMethod { name = "onAttachedToWindow"; parameterCount = 0 }
                .hookAfter {
                    val footer = thisObject as? ChatFooter ?: return@hookAfter
                    // 构造/挂载阶段控件还没布局完，post 到下一帧再找 EditText / 发送按钮
                    footer.post {
                        runCatching { installOn(footer) }
                            .onFailure { WeLogger.w(TAG, "输入栏注入失败（已降级到长按菜单）", it) }
                    }
                }
            WeLogger.i(TAG, "已安装 ChatFooter.onAttachedToWindow 钩子")
        }.onFailure { WeLogger.e(TAG, "安装输入栏钩子失败，功能降级为长按菜单入口", it) }
    }

    override fun onDisable() {
        runCatching { WeChatInputBarMenuApi.removeProvider(this) }
            .onFailure { WeLogger.e(TAG, "注销输入栏菜单项失败", it) }
        runCatching { scope?.cancel() }
        scope = null
        injectedFooters.clear()
        undoOriginal = null
        undoTranslated = null
        injectionFailed = false
    }

    override fun runtimeIssue(): String? =
        if (isEnabled && injectionFailed) {
            "没有找到输入框容器，右侧「译」按钮未注入（可用输入栏长按菜单里的「翻译输入框文本」）"
        } else {
            null
        }

    // ─── 注入 ─────────────────────────────────────────────────────────────────

    private fun installOn(footer: ChatFooter) {
        if (!injectedFooters.add(footer)) return

        if (triggerMode == TRIGGER_BUTTON) {
            val injected = runCatching { injectTranslateButton(footer) }.getOrDefault(false)
            if (!injected) {
                injectionFailed = true
                WeLogger.w(TAG, "未找到可插入的输入框容器，右侧按钮不注入（功能仍可从长按菜单进入）")
            }
        }

        val sendButton = runCatching { findSendButton(footer) }.getOrNull()
        if (sendButton == null) {
            WeLogger.w(TAG, "未找到原生发送按钮，长按发送触发/撤销不可用")
            return
        }
        runCatching { installSendLongClick(footer, sendButton) }
            .onFailure { WeLogger.w(TAG, "挂载发送按钮长按监听失败", it) }
    }

    private fun injectTranslateButton(footer: ChatFooter): Boolean {
        val button = buildTranslateButton(footer)

        // 首选：紧贴输入框右侧（EditText 的父容器里，EditText 之后）
        val editText = footer.findViewWhich<View> { view ->
            view is EditText && view.isShown && view.width > 0
        }
        val editParent = editText?.parent as? ViewGroup
        if (editText != null && editParent != null) {
            if (insertView(editParent, button, editParent.indexOfChild(editText) + 1)) return true
        }

        // 退路：插在原生发送按钮左边
        val sendButton = findSendButton(footer)
        val sendParent = sendButton?.parent as? ViewGroup
        if (sendButton != null && sendParent != null) {
            if (insertView(sendParent, button, sendParent.indexOfChild(sendButton))) return true
        }
        return false
    }

    private fun insertView(parent: ViewGroup, child: View, index: Int): Boolean = runCatching {
        if (parent.indexOfChild(child) >= 0) return@runCatching true
        val params: ViewGroup.LayoutParams = if (parent is LinearLayout) {
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(parent.context, 4)
            }
        } else {
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        parent.addView(child, index.coerceIn(0, parent.childCount), params)
        true
    }.getOrElse { error ->
        WeLogger.w(TAG, "插入「译」按钮失败", error)
        false
    }

    private fun buildTranslateButton(footer: ChatFooter): View {
        val context = footer.context
        val button = TextView(context)
        button.text = "译"
        button.textSize = 13f
        button.setTextColor(0xFF576B95.toInt())
        button.setPadding(dp(context, 6), dp(context, 2), dp(context, 6), dp(context, 2))
        button.contentDescription = "翻译输入框内容"
        button.isClickable = true
        runCatching {
            val background = GradientDrawable()
            background.cornerRadius = dp(context, 4).toFloat()
            background.setColor(0x14576B95)
            button.background = background
        }
        button.setOnClickListener { view ->
            runCatching { translateInput(footer, view.context) }
                .onFailure { error ->
                    WeLogger.e(TAG, "点击「译」按钮失败", error)
                    showToast(view.context, "翻译失败：${error.message ?: "未知错误"}")
                }
        }
        return button
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** 与 WeChatInputBarMenuApi.findSendButton 同样的判定：父容器里文本为「发送」/send 的 Button。 */
    private fun findSendButton(footer: View): View? = footer.findViewWhich<View> { view ->
        view.javaClass.name == "android.widget.Button" && run {
            val label = (view as? android.widget.Button)?.text?.toString()?.trim().orEmpty()
            label == "发送" || label.equals("send", ignoreCase = true)
        }
    }

    /**
     * 链式长按：先尝试「撤销 / 长按触发翻译」，都不适用时调用原生监听器（唤起「聊天功能」菜单）。
     */
    private fun installSendLongClick(footer: ChatFooter, sendButton: View) {
        val previous = readLongClickListener(sendButton)
        sendButton.setOnLongClickListener { view ->
            val handled = runCatching { handleSendLongPress(footer) }
                .getOrElse { error ->
                    WeLogger.w(TAG, "长按发送处理失败", error)
                    false
                }
            if (handled) {
                true
            } else {
                runCatching { previous?.onLongClick(view) }.getOrDefault(false)
            }
        }
    }

    /** 通过 View.mListenerInfo.mOnLongClickListener 读取原生长按监听器（AOSP 字段名，稳定）。 */
    private fun readLongClickListener(target: View): View.OnLongClickListener? = runCatching {
        val listenerInfo = View::class.java
            .getDeclaredField("mListenerInfo")
            .apply { isAccessible = true }
            .get(target)
            ?: return@runCatching null
        val field = listenerInfo.javaClass
            .getDeclaredField("mOnLongClickListener")
            .apply { isAccessible = true }
        field.get(listenerInfo) as? View.OnLongClickListener
    }.getOrNull()

    private fun handleSendLongPress(footer: ChatFooter): Boolean {
        val current = currentText(footer)
        val original = undoOriginal
        val translated = undoTranslated

        // 1) 有待撤销的翻译结果 → 还原原文
        if (original != null && translated != null && current == translated) {
            val restored = runCatching { footer.lastText = original }.isSuccess
            undoOriginal = null
            undoTranslated = null
            if (restored) {
                showToast("已还原为原文")
                return true
            }
            WeLogger.w(TAG, "还原原文失败，输入框内容已被改动")
            return false
        }

        // 2) 长按发送模式 + 输入框有内容 → 触发翻译
        if (triggerMode == TRIGGER_LONG_PRESS && current.isNotBlank()) {
            translateInput(footer, footer.context)
            return true
        }

        // 3) 其它情况交回原生「聊天功能」菜单
        return false
    }

    // ─── 翻译 ─────────────────────────────────────────────────────────────────

    private fun currentText(footer: ChatFooter): String =
        runCatching { footer.lastText }.getOrNull().orEmpty()

    private fun translateInput(footer: ChatFooter, context: Context) {
        val text = currentText(footer)
        if (text.isBlank()) {
            showToast(context, "输入框是空的")
            return
        }
        val engine = TranslateSupport.Engine.fromValue(sharedEngineValue)
        val lang = TranslateSupport.Lang.fromLabel(targetLangLabel)
        showToast(context, "翻译中…")

        ioScope().launch {
            val result = runCatching { TranslateSupport.translate(text, engine, lang) }
                .getOrElse { Result.failure(it) }

            result.onSuccess { translated ->
                runOnUiThread {
                    runCatching {
                        footer.lastText = translated
                        undoOriginal = text
                        undoTranslated = translated
                    }.onFailure { error ->
                        WeLogger.w(TAG, "写回输入框失败", error)
                        showToast("译文写入输入框失败：${error.message ?: "未知错误"}")
                    }
                    showToast("已翻译，长按发送可撤销")
                }
            }.onFailure { error ->
                WeLogger.e(TAG, "输入框翻译失败", error)
                runOnUiThread { showToast("翻译失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    // ─── 输入栏长按菜单入口（注入失败时的兜底） ────────────────────────────────

    override fun getActionItems(): List<WeChatInputBarMenuApi.ActionItem> = listOf(
        WeChatInputBarMenuApi.ActionItem(
            id = "input_translate",
            icon = MaterialSymbols.Outlined.Language,
            label = "翻译输入框文本",
            isSupported = { _, _ -> true },
            onClick = { context, chatFooter ->
                runCatching { translateInput(chatFooter, context) }
                    .onFailure { error ->
                        WeLogger.e(TAG, "菜单翻译输入框失败", error)
                        showToast(context, "翻译失败：${error.message ?: "未知错误"}")
                    }
            }
        )
    )

    // ─── 设置面板 ─────────────────────────────────────────────────────────────

    @Composable
    override fun Ui() {
        var trigger by remember { mutableStateOf(triggerMode) }
        var langLabel by remember { mutableStateOf(targetLangLabel) }
        var triggerMenuExpanded by remember { mutableStateOf(false) }
        var langMenuExpanded by remember { mutableStateOf(false) }

        ListItem(
            headlineContent = { Text("触发方式") },
            supportingContent = {
                Text(if (trigger == TRIGGER_BUTTON) "点击输入框右侧「译」按钮" else "长按发送按钮")
            },
            trailingContent = {
                Box {
                    IconButton(onClick = { triggerMenuExpanded = true }) {
                        Icon(
                            MaterialSymbols.Outlined.Arrow_drop_down,
                            contentDescription = "选择触发方式"
                        )
                    }
                    DropdownMenu(
                        expanded = triggerMenuExpanded,
                        onDismissRequest = { triggerMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("输入框右侧小按钮") },
                            onClick = {
                                trigger = TRIGGER_BUTTON
                                triggerMode = TRIGGER_BUTTON
                                triggerMenuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("长按发送") },
                            onClick = {
                                trigger = TRIGGER_LONG_PRESS
                                triggerMode = TRIGGER_LONG_PRESS
                                triggerMenuExpanded = false
                            }
                        )
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
            headlineContent = { Text("翻译引擎") },
            supportingContent = {
                Text("沿用「消息翻译」里的引擎设置：" + TranslateSupport.Engine.fromValue(sharedEngineValue).label)
            }
        )
    }
}
