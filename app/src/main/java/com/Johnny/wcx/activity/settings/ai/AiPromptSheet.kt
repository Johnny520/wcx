package com.Johnny.wcx.activity.settings.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.ai.AiMemoryIndex
import com.Johnny.wcx.ai.AiProfile
import com.Johnny.wcx.ai.AiProfileLimits
import com.Johnny.wcx.ai.AiProfileStore
import com.Johnny.wcx.ai.AiPrompts
import com.Johnny.wcx.ai.AiTokens
import com.Johnny.wcx.ai.AiTools
import com.Johnny.wcx.ai.ChatGoal
import com.Johnny.wcx.ai.group.GroupDigestPrompts
import com.Johnny.wcx.ai.ReplyStyle
import com.Johnny.wcx.ai.StyleNote
import com.Johnny.wcx.ui.content.ScrimDialog
import com.Johnny.wcx.ui.style.WxPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 提示词与工具参数预览（debug 专用）。
 *
 * 入口在 AI 页顶部、「添加」左边。内容是**实际发送的同一份**（同一批函数渲染），
 * 包括每次请求附带的工具 schema；每段默认收起，点开才展开 —— 全部展开有上千行，
 * 首屏会完全看不到结构。
 *
 * 变量用一个真实档案代入（取列表里第一个好友）；没有档案时用占位示例。
 */
@Composable
internal fun AiPromptSheet(onDismiss: () -> Unit) {
    var profile by remember { mutableStateOf(SAMPLE_PROFILE) }
    var usingSample by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        // 只读第一个档案，用于把「标签 / 记忆标题」这些变量填成真实值。
        val loaded = withContext(Dispatchers.IO) {
            AiProfileStore.loadIndex().profiles.firstOrNull()?.wxId
                ?.let { AiProfileStore.readProfile(it) }
        }
        if (loaded != null) {
            profile = loaded
            usingSample = false
        }
    }

    ScrimDialog(show = true, onDismissRequest = onDismiss, title = "提示词") {
        Column(
            modifier = Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (usingSample) {
                    "还没有档案，变量用占位示例显示。这里是模型实际收到的那一份。"
                } else {
                    "变量已代入「${profile.nickname.ifBlank { profile.wxId }}」的档案。" +
                        "这里是模型实际收到的那一份。"
                },
                fontSize = 11.sp,
                color = WxPalette.textTertiary(),
                lineHeight = 16.sp,
            )

            // ── 工具参数：与运行时调同一批函数（AiTools.forInit / forAuditTags / forAuditMemories …）──
            // 副标题也从 schema 里读工具名，不手写 —— 否则工具集改了、这里还写着旧的两项。
            // 初始化按全量整理预览：运行时增量不带 style 字段，但这里要看的是完整形态。
            val initTools = remember { AiTools.forInit(withStyle = true) }
            val auditTagTools = remember { AiTools.forAuditTags() }
            val auditMemoryTools = remember { AiTools.forAuditMemories() }
            val suggestTools = remember { AiTools.forSuggest() }
            val styleTools = remember { AiTools.forStyle() }
            PromptSection("工具 · 初始化", toolNames(initTools), toolSchema(initTools))
            PromptSection("工具 · 审查标签", toolNames(auditTagTools), toolSchema(auditTagTools))
            PromptSection("工具 · 审查记忆", toolNames(auditMemoryTools), toolSchema(auditMemoryTools))
            PromptSection("工具 · 智能建议", toolNames(suggestTools), toolSchema(suggestTools))
            PromptSection("工具 · 风格汇总", toolNames(styleTools), toolSchema(styleTools))

            // ── 初始化 ──
            PromptSection("初始化 · system", "分片整理的规则", AiPrompts.initSystem())
            PromptSection(
                "初始化 · 单片 user",
                "时间范围 + 该片记录 + 标签配额与已有标签",
                AiPrompts.initYearUser(
                    label = "2024 年 5 月",
                    transcript = "（运行时注入该时间段的文字记录）",
                    profile = profile,
                ),
            )

            // ── 审查档案 ──
            // 档位从同一份 reviewGuide 取：预览看到的这一句，就是审查时真实拼进去的那一句。
            val guide = remember(profile) { AiProfileLimits.reviewGuide(profile) }
            PromptSection(
                "审查标签 · system",
                "标签清理标准（${guide.tag.name}）",
                AiPrompts.auditTagSystem(guide),
            )
            PromptSection(
                "审查标签 · user",
                "标签（带置信度、时间、原话）",
                AiPrompts.auditTagUser(profile, guide),
            )
            PromptSection("审查记忆 · system", "按档位压缩（${guide.memory.name}）", AiPrompts.auditMemorySystem(guide))
            PromptSection(
                "审查记忆 · user",
                "该片全部记忆（标题 + 正文），示例取前 20 条",
                AiPrompts.auditMemoryUser(AiMemoryIndex.numbered(profile.memories).take(20)),
            )

            // ── 人格侧写汇总（全量整理的最后一步） ──
            PromptSection(
                "人格侧写汇总 · system",
                "八节 + 取舍规则",
                AiPrompts.styleSummarySystem(),
            )
            PromptSection(
                "人格侧写汇总 · user",
                "近期 / 早期两组的片内观察",
                AiPrompts.styleSummaryUser(SAMPLE_NOTES, SAMPLE_EARLY_NOTES),
            )

            // ── 智能建议 ──
            PromptSection("智能建议 · system", "三条回复 + 关系约束", AiPrompts.suggestSystem(profile))
            PromptSection(
                "智能建议 · user",
                "当天聊天记录（含媒体占位）+ 对方档案（记忆只带最近半年）",
                AiPrompts.suggestUser(
                    profile = profile,
                    transcript = "（运行时注入当天聊天记录；图片、语音、表情包显示为占位）",
                    samples = "- （没有侧写时才出现：从记录里挑出的本人原话，3~40 字，最多 10 条）",
                ),
            )

            // ── 群总结（独立模块，与好友档案零共享） ──
            // 三段对应三种请求：单片直接出摘要、多片每片只提炼、最后合并。
            val digestNow = remember { System.currentTimeMillis() / 1000L }
            val digestSample = remember {
                """
                    [11-21 16:02] 张三: 周六的聚会改到几点？
                    [11-21 16:03] 李四: 七点吧，老地方
                    [11-21 16:04] 张三: 好，我订位子
                    [11-21 16:05] 王五: [图片]
                """.trimIndent()
            }
            PromptSection(
                "群总结 · 单片",
                "一次请求直接出摘要",
                buildString {
                    append("── system ──\n").append(GroupDigestPrompts.system())
                    append("\n\n── user ──\n")
                    append(
                        GroupDigestPrompts.user(
                            "示例群", digestNow - 3600, digestNow, digestSample,
                        )
                    )
                },
            )
            PromptSection(
                "群总结 · 分片",
                "多片时每片只提炼要点（不出现片号）",
                buildString {
                    append("── system ──\n").append(GroupDigestPrompts.sliceSystem())
                    append("\n\n── user ──\n")
                    append(
                        GroupDigestPrompts.sliceUser(
                            "示例群", digestNow - 7200, digestNow - 3600, digestSample,
                        )
                    )
                },
            )
            PromptSection(
                "群总结 · 合并",
                "把几份要点合成一份摘要",
                buildString {
                    append("── system ──\n").append(GroupDigestPrompts.mergeSystem())
                    append("\n\n── user ──\n")
                    append(
                        GroupDigestPrompts.mergeUser(
                            "示例群",
                            digestNow - 7200,
                            digestNow,
                            listOf("- 周六聚会改到七点，老地方", "- 王五会晚到半小时"),
                        )
                    )
                },
            )
        }
    }
}

/** 收起态只显示标题、组成与体量；点一下才渲染全文。 */
@Composable
private fun PromptSection(title: String, subtitle: String, text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(WxPalette.cardBackground())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = WxPalette.textPrimary(),
                )
                Text(
                    text = subtitle + " · " + AiTokens.estimate(text) + " token",
                    fontSize = 11.sp,
                    color = WxPalette.textTertiary(),
                )
            }
            Text(
                text = if (expanded) "收起" else "展开",
                fontSize = 12.sp,
                color = WxPalette.Primary,
            )
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(WxPalette.divider())
                )
                Text(
                    text = text,
                    fontSize = 11.sp,
                    color = WxPalette.textSecondary(),
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * 工具名列表，直接读 schema 里的 `function.name`。
 *
 * 不手写副标题：工具集改了（例如初始化只剩 `update_profile`），这里自动跟着变，
 * 不会出现「显示两个、实际一个」。
 */
private fun toolNames(tools: JsonArray): String = tools.joinToString(" + ") { element ->
    element.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
}

/** 工具 schema 展开成可读 JSON：单行字符串在窄屏上没法核对参数。 */
private fun toolSchema(tools: JsonArray): String =
    runCatching { PRETTY_JSON.encodeToString(JsonArray.serializer(), tools) }
        .getOrElse { tools.toString() }

private val PRETTY_JSON = Json { prettyPrint = true }

/** 占位档案：变量代入真实档案，但侧写往往是空的，示例里给一段，预览才看得出注入效果。 */
private val SAMPLE_PROFILE = AiProfile(
    wxId = "",
    nickname = "示例好友",
    remark = "",
    goal = ChatGoal(relation = "朋友", purpose = "（未填写）", stage = "朋友"),
    style = ReplyStyle(
        summary = "【整体】嘴上不饶人，实际很愿意帮忙；对熟人爱抬杠，对不熟的人客气但话少。\n" +
            "【消息形态】单条基本不超过 15 字，很少用句号；不用表情和颜文字。",
        basedUntil = "2026-11",
    ),
)

/** 侧写汇总的示例观察：只为让预览页看得出「近期 / 早期」两组长什么样。 */
private val SAMPLE_NOTES = listOf(
    StyleNote(
        period = "2026 年 5 月",
        summary = "话少、回得快；别人说得多时只接一句。",
        style = listOf("单条基本不超过 12 字", "不用表情，偶尔连发两条"),
        behavior = listOf("对方抱怨工作 → 先跟着骂两句，再给一个具体办法"),
    ),
)

private val SAMPLE_EARLY_NOTES = listOf(
    StyleNote(
        period = "2021 年 3 月",
        summary = "比现在话多一点，会主动问对方在忙什么。",
        style = listOf("偶尔用句号", "会用「哈哈哈」"),
        behavior = listOf("对方冷淡 → 追问一句「怎么了」"),
    ),
)
