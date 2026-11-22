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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.ui.style.WxPalette
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局 AI 配置对话框。主页「AI」卡片和各个 AI 功能的设置页都调它，
 * 保证模块里只有**一个** AI 配置入口。
 *
 * 保存规则（用户明确要求）：先做真实连通性校验，**校验通过才落库**并标记为已激活；
 * 校验失败只把原因显示在对话框里，不覆盖已有配置、不改动已经生效的状态。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiConfigDialogContent(
    onDismiss: () -> Unit,
    onSaved: (AiConfig.Status) -> Unit = {},
) {
    var url by remember { mutableStateOf(AiConfig.rawUrl.ifBlank { AiConfig.DEFAULT_URL }) }
    var key by remember { mutableStateOf(AiConfig.rawApiKey) }
    var model by remember { mutableStateOf(AiConfig.rawModel.ifBlank { AiConfig.DEFAULT_MODEL }) }
    var thinking by remember { mutableStateOf(AiConfig.status.thinking) }
    var thinkingLevel by remember { mutableStateOf(AiConfig.thinkingLevel) }
    // 输出上限与温度是即时生效的请求参数，不参与「配置齐全」判定，也不需要重新校验接口。
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

    // 打开时如果配置齐全，先拉一次模型列表（不消耗 token），方便直接挑模型。
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
                    // 只有真实请求成功才写入偏好 —— 失败时旧配置原样保留。
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

    AlertDialogContent(
        title = { Text("AI 配置") },
        text = {
            DefaultColumn(scrollable = true) {
                // 当前生效状态：与主页卡片同一口径（配置齐全 + 最近一次真实请求成功）。
                Text(
                    text = when {
                        !status.readable -> "当前：读不到配置"
                        status.verified -> "当前：已激活 · ${status.model}"
                        status.configured -> "当前：未激活"
                        else -> "当前：未配置"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.verified) WxPalette.Ok else WxPalette.textSecondary(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("API 地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; error = null },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; error = null },
                    label = { Text("模型名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 深度思考是本地开关：切换即时生效，不需要重新校验接口。常用，放在高级外面。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "深度思考",
                            style = MaterialTheme.typography.bodyMedium,
                            color = WxPalette.textPrimary(),
                        )
                        Text(
                            text = if (thinking) "已开启 · ${thinkingLevel.label}，更慢、消耗更多" else "关闭",
                            style = MaterialTheme.typography.bodySmall,
                            color = WxPalette.textSecondary(),
                        )
                    }
                    Switch(
                        checked = thinking,
                        onCheckedChange = {
                            thinking = it
                            AiConfig.setThinking(it)
                        },
                    )
                }

                // ── 高级：思考强度 / 输出上限 / 温度 ──
                // 都有合理默认值（高 / 393216 / 0.9），摆在正文里只会让人觉得必须调。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { advanced = !advanced }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "高级",
                            style = MaterialTheme.typography.bodyMedium,
                            color = WxPalette.textPrimary(),
                        )
                        Text(
                            text = if (advanced) {
                                "思考强度 · 输出上限 · 说话温度"
                            } else {
                                // 思考关着时不显示强度：展开后也没有那一项，免得它像是生效的。
                                (if (thinking) "强度 ${thinkingLevel.label} · " else "") +
                                    "温度 ${temperature.ifBlank { "0.9" }}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = WxPalette.textSecondary(),
                        )
                    }
                    Text(
                        text = if (advanced) "收起" else "展开",
                        style = MaterialTheme.typography.bodySmall,
                        color = WxPalette.Accent,
                    )
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Chevron_right,
                        contentDescription = null,
                        tint = WxPalette.Accent,
                        modifier = Modifier.size(16.dp),
                    )
                }

                if (advanced) {
                    // 思考强度：四档。思考关着时没有强度可调，不显示。
                    if (thinking) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "思考强度",
                                style = MaterialTheme.typography.bodySmall,
                                color = WxPalette.textSecondary(),
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                AiConfig.ThinkingLevel.values().forEach { level ->
                                    FilterChip(
                                        selected = level == thinkingLevel,
                                        onClick = {
                                            thinkingLevel = level
                                            AiConfig.setThinkingLevel(level)
                                        },
                                        label = { Text(level.label) },
                                    )
                                }
                            }
                        }
                    }

                    // 输出上限：思考与工具调用抢同一份输出预算，服务商默认值偏小时
                    // arguments 会被截成半截 JSON。0 = 不传。
                    OutlinedTextField(
                        value = maxTokens,
                        onValueChange = { text ->
                            maxTokens = text.filter { it.isDigit() }.take(7)
                            maxTokens.toIntOrNull()?.let { AiConfig.setMaxTokens(it) }
                        },
                        label = { Text("输出上限（max_tokens，0 = 不传）") },
                        supportingText = {
                            Text(
                                "默认 393216。服务商不认这个参数或嫌它太大时，会自动去掉重试。",
                                style = MaterialTheme.typography.bodySmall,
                                color = WxPalette.textSecondary(),
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // 温度只影响「说话」类请求（建议 / 自动回复）；
                    // 整理、审查、侧写汇总各自传 0.2~0.3，让分类判断稳定。
                    OutlinedTextField(
                        value = temperature,
                        onValueChange = { text ->
                            temperature = text
                            text.toDoubleOrNull()?.takeIf { it in 0.0..2.0 }
                                ?.let { AiConfig.setTemperature(it) }
                        },
                        label = { Text("温度（0~2，默认 0.9）") },
                        supportingText = {
                            Text(
                                "只影响建议与自动回复的语气：低更板正稳妥，高更活络。",
                                style = MaterialTheme.typography.bodySmall,
                                color = WxPalette.textSecondary(),
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // 识别图片：把对方这一轮最近一张图挂到 user 消息里。关着时图片只有 `[图片]` 占位。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "识别图片",
                                style = MaterialTheme.typography.bodyMedium,
                                color = WxPalette.textPrimary(),
                            )
                            Text(
                                text = "对方发来图片时把这张图一起发给模型，让它看懂图再回。需要模型支持图片输入。",
                                style = MaterialTheme.typography.bodySmall,
                                color = WxPalette.textSecondary(),
                            )
                        }
                        Switch(
                            checked = recognizeImages,
                            onCheckedChange = {
                                recognizeImages = it
                                AiConfig.setRecognizeImages(it)
                            },
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { runFetchModels() },
                        enabled = !loadingModels && url.isNotBlank() && key.isNotBlank(),
                    ) {
                        Text(if (loadingModels) "读取中…" else "读取可用模型")
                    }
                    if (models.isNotEmpty()) {
                        Text(
                            "共 ${models.size} 个",
                            style = MaterialTheme.typography.bodySmall,
                            color = WxPalette.textSecondary(),
                        )
                    }
                }

                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = WxPalette.Danger,
                    )
                }

                if (models.isNotEmpty()) {
                    Text(
                        "可用模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = WxPalette.textSecondary(),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        models.forEach { candidate ->
                            FilterChip(
                                selected = candidate == model,
                                onClick = { model = candidate; error = null },
                                label = { Text(candidate) },
                            )
                        }
                    }
                }

                if (status.configured) {
                    TextButton(
                        onClick = {
                            AiConfig.clear()
                            onSaved(AiConfig.status)
                            showToast("已清除")
                            onDismiss()
                        },
                    ) { Text("清除配置", color = WxPalette.Danger) }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        confirmButton = {
            Button(
                onClick = { runVerifyAndSave() },
                enabled = !verifying,
            ) {
                Text(
                    text = if (verifying) "校验中…" else "测试并保存",
                    fontWeight = FontWeight.Medium,
                )
            }
        },
    )
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

/**
 * 「当前 AI 状态 + 配置入口」行，供各 AI 功能的设置页复用。
 *
 * 功能自己的设置页不再放 API 地址 / Key / 模型输入框，只放这一行：
 * 点它打开全局配置，状态文案与主页卡片同一口径（配置齐全 + 最近一次真实请求成功）。
 */
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
        Icon(
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
    // 未激活时先给模型名，再给一句失败原因。
    else -> status.detail?.takeIf { it.isNotBlank() }
        ?.let { "${status.model} · ${it.take(28)}" }
        ?: "${status.model} · 未激活"
}

