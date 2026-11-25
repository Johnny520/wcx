package com.Johnny.wcx.ui.content

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.ui.style.SoftCard
import com.Johnny.wcx.ui.style.WxPalette
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 配置面板：整个模块只有这一份实现。
 *
 * 视觉与 WCX 其它页面同一套（灰底 + 白卡 + 胶囊），不再用 material3 的输入框与按钮，
 * 放进设置页的浮层、功能弹窗、独立对话框都长一样。
 *
 * 保存规则（用户明确要求）：只有真实请求成功才落库并标记为已激活；
 * 失败只把原因显示在面板里，不覆盖现有配置。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiConfigPanel(
    onDismiss: () -> Unit,
    onSaved: (AiConfig.Status) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var url by remember { mutableStateOf(AiConfig.rawUrl.ifBlank { AiConfig.DEFAULT_URL }) }
    var key by remember { mutableStateOf(AiConfig.rawApiKey) }
    var model by remember { mutableStateOf(AiConfig.rawModel.ifBlank { AiConfig.DEFAULT_MODEL }) }
    var thinking by remember { mutableStateOf(AiConfig.status.thinking) }
    var thinkingLevel by remember { mutableStateOf(AiConfig.thinkingLevel) }
    var advanced by remember { mutableStateOf(false) }
    var maxTokens by remember { mutableStateOf(AiConfig.maxTokens.toString()) }
    var temperature by remember { mutableStateOf(AiConfig.temperature.toString()) }
    var recognizeImages by remember { mutableStateOf(AiConfig.recognizeImages) }

    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingModels by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val status = AiConfig.status

    // 打开时如果已有地址与 Key，先拉一次模型列表（只读接口，不消耗 token）。
    LaunchedEffect(Unit) {
        if (url.isNotBlank() && key.isNotBlank()) {
            loadingModels = true
            val result = withContext(Dispatchers.IO) { AiConfig.fetchModels(url, key) }
            loadingModels = false
            if (result.ok) models = result.models
        }
    }

    fun runFetchModels() {
        if (loadingModels || url.isBlank() || key.isBlank()) return
        loadingModels = true
        error = null
        scope.launch(Dispatchers.IO) {
            val result = AiConfig.fetchModels(url, key)
            withContext(Dispatchers.Main) {
                loadingModels = false
                if (result.ok) {
                    models = result.models
                    if (model.isBlank()) model = result.models.firstOrNull().orEmpty()
                    showToast("已获取 ${result.models.size} 个可用模型")
                } else {
                    error = result.message
                }
            }
        }
    }

    fun runVerifyAndSave() {
        if (verifying) return
        if (url.isBlank() || key.isBlank() || model.isBlank()) {
            error = "API 地址、API Key、模型名称都要填写"
            return
        }
        verifying = true
        error = null
        scope.launch(Dispatchers.IO) {
            val result = AiConfig.verify(url, key, model)
            withContext(Dispatchers.Main) {
                verifying = false
                if (result.ok) {
                    val finalModel = model.trim().ifBlank { result.models.firstOrNull().orEmpty() }
                    AiConfig.saveVerified(url, key, finalModel)
                    showToast("已保存")
                    onSaved(AiConfig.status)
                    onDismiss()
                } else {
                    error = result.message
                    if (result.models.isNotEmpty()) models = result.models
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 当前状态：与「AI 助手」页顶部卡片同一口径（配置齐全 + 最近一次真实请求成功）。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (status.verified) WxPalette.Ok else WxPalette.Warning),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    !status.readable -> "当前：读不到配置"
                    status.verified -> "当前：已激活 · ${status.model}"
                    status.configured -> "当前：未激活"
                    else -> "当前：未配置"
                },
                fontSize = 12.sp,
                color = if (status.verified) WxPalette.Ok else WxPalette.textSecondary(),
            )
        }

        SoftCard(contentPadding = 14) {
            AiInputField(
                label = "API 地址",
                value = url,
                placeholder = "https://api.deepseek.com",
                onChange = { url = it; error = null },
            )
            AiInputField(
                label = "API Key",
                value = key,
                placeholder = "sk-...",
                onChange = { key = it; error = null },
            )
            AiInputField(
                label = "模型名称",
                value = model,
                placeholder = AiConfig.DEFAULT_MODEL,
                onChange = { model = it; error = null },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                CfgTextAction(
                    text = if (loadingModels) "读取中…" else "读取可用模型",
                    enabled = !loadingModels && url.isNotBlank() && key.isNotBlank(),
                    onClick = { runFetchModels() },
                )
                if (models.isNotEmpty()) {
                    Text(
                        text = "共 ${models.size} 个",
                        fontSize = 11.sp,
                        color = WxPalette.textTertiary(),
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
            if (models.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    models.forEach { name ->
                        CfgChip(
                            label = name,
                            selected = name == model,
                            onClick = { model = name; error = null },
                        )
                    }
                }
            }
        }

        SoftCard(contentPadding = 14) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { advanced = !advanced },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("高级参数", fontSize = 14.sp, color = WxPalette.textPrimary())
                    Text(
                        text = "思考模式、输出上限、温度、图片识别",
                        fontSize = 11.sp,
                        color = WxPalette.textSecondary(),
                    )
                }
                Text(
                    text = if (advanced) "收起" else "展开",
                    fontSize = 12.sp,
                    color = WxPalette.Accent,
                )
                androidx.compose.material3.Icon(
                    imageVector = MaterialSymbols.Outlined.Chevron_right,
                    contentDescription = null,
                    tint = WxPalette.Accent,
                    modifier = Modifier.size(16.dp),
                )
            }

            if (advanced) {
                Spacer(Modifier.size(4.dp))
                CfgSwitchRow(
                    title = "深度思考",
                    detail = "请求里带 reasoning，慢一些但推理更稳（需模型支持）",
                    checked = thinking,
                    onChange = {
                        thinking = it
                        AiConfig.setThinking(it)
                    },
                )
                if (thinking) {
                    Text("思考强度", fontSize = 12.sp, color = WxPalette.textSecondary())
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AiConfig.ThinkingLevel.values().forEach { level ->
                            CfgChip(
                                label = level.label,
                                selected = level == thinkingLevel,
                                onClick = {
                                    thinkingLevel = level
                                    AiConfig.setThinkingLevel(level)
                                },
                            )
                        }
                    }
                }
                AiInputField(
                    label = "输出上限 max_tokens（0 = 不传）",
                    value = maxTokens,
                    placeholder = "393216",
                    onChange = { text ->
                        maxTokens = text.filter { it.isDigit() }.take(7)
                        maxTokens.toIntOrNull()?.let { AiConfig.setMaxTokens(it) }
                    },
                )
                AiInputField(
                    label = "温度 0~2（默认 0.9）",
                    value = temperature,
                    placeholder = "0.9",
                    onChange = { text ->
                        temperature = text
                        text.toDoubleOrNull()?.takeIf { it in 0.0..2.0 }
                            ?.let { AiConfig.setTemperature(it) }
                    },
                )
                CfgSwitchRow(
                    title = "识别图片",
                    detail = "对方发图时把图一起发给模型，需要模型支持图片输入",
                    checked = recognizeImages,
                    onChange = {
                        recognizeImages = it
                        AiConfig.setRecognizeImages(it)
                    },
                )
            }
        }

        error?.let { message ->
            Text(
                text = message,
                fontSize = 12.sp,
                color = WxPalette.Danger,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        CfgPrimaryButton(
            text = if (verifying) "校验中…" else "测试并保存",
            enabled = !verifying,
            onClick = { runVerifyAndSave() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CfgSecondaryButton(
                text = "取消",
                modifier = Modifier.weight(1f),
                onClick = onDismiss,
            )
            if (status.configured) {
                CfgSecondaryButton(
                    text = "清除配置",
                    modifier = Modifier.weight(1f),
                    color = WxPalette.Danger,
                    onClick = {
                        AiConfig.clear()
                        showToast("已清除 AI 配置")
                        onSaved(AiConfig.status)
                        onDismiss()
                    },
                )
            }
        }
    }
}

/** 兼容旧调用点：内容与 [AiConfigPanel] 完全一致。 */
@Composable
fun AiConfigDialogContent(
    onDismiss: () -> Unit,
    onSaved: (AiConfig.Status) -> Unit = {},
) {
    AiConfigPanel(onDismiss = onDismiss, onSaved = onSaved)
}

/** 在任意 Context（设置页 / 功能弹窗）里打开全局 AI 配置对话框。 */
fun showAiConfigDialog(
    context: Context,
    onSaved: (AiConfig.Status) -> Unit = {},
) {
    showComposeDialog(context) {
        AiConfigDialogContent(
            onDismiss = { onDismiss() },
            onSaved = onSaved,
        )
    }
}

/** 「当前 AI 状态 + 配置入口」行，供各 AI 功能的设置页复用。 */
@Composable
fun AiConfigStatusRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = AiConfig.status
    val active = status.verified
    val accent = if (active) WxPalette.Ok else WxPalette.Warning
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(WxPalette.insetSurface())
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(accent),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (active) "AI 已激活" else "AI 未激活",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = WxPalette.textPrimary(),
            )
            Text(
                text = aiStatusSubtitle(status),
                fontSize = 11.sp,
                color = WxPalette.textSecondary(),
                maxLines = 2,
            )
        }
        Text(
            text = "配置",
            fontSize = 12.sp,
            color = WxPalette.Accent,
        )
        androidx.compose.material3.Icon(
            imageVector = MaterialSymbols.Outlined.Chevron_right,
            contentDescription = null,
            tint = WxPalette.Accent,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 状态副标题：主页卡片与功能设置页共用同一套文案。 */
fun aiStatusSubtitle(status: AiConfig.Status): String = when {
    !status.readable -> "读不到配置"
    !status.configured -> "未配置"
    status.active -> status.model
    else -> status.detail?.takeIf { it.isNotBlank() }
        ?.let { "${status.model} · ${it.take(28)}" }
        ?: "${status.model} · 未激活"
}

// ── 面板内部组件（与 WCX 其它页面同一套视觉） ────────────────────────────────

@Composable
fun AiInputField(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, color = WxPalette.textSecondary())
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(WxPalette.insetSurface())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, fontSize = 14.sp, color = WxPalette.textTertiary())
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = WxPalette.textPrimary()),
                cursorBrush = SolidColor(WxPalette.Primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CfgChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) WxPalette.primarySoft() else WxPalette.insetSurface())
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (selected) WxPalette.Primary else WxPalette.textSecondary(),
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun CfgSwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = WxPalette.textPrimary())
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    fontSize = 11.sp,
                    color = WxPalette.textSecondary(),
                    lineHeight = 16.sp,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        CfgSwitch(checked = checked, onChange = onChange)
    }
}

/** 迷你开关：不依赖 material3 Switch 的尺寸，和卡片排版更贴合。 */
@Composable
private fun CfgSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .width(44.dp)
            .clip(RoundedCornerShape(50))
            .background(if (checked) WxPalette.Primary else WxPalette.textTertiary().copy(alpha = 0.3f))
            .clickable { onChange(!checked) }
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(50))
                .background(WxPalette.cardBackground())
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart),
        )
    }
}

@Composable
private fun CfgTextAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = if (enabled) WxPalette.Accent else WxPalette.textTertiary(),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun CfgPrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) WxPalette.Primary else WxPalette.Primary.copy(alpha = 0.35f))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = androidx.compose.ui.graphics.Color.White)
    }
}

@Composable
private fun CfgSecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(WxPalette.insetSurface())
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 14.sp, color = color ?: WxPalette.textPrimary())
    }
}
