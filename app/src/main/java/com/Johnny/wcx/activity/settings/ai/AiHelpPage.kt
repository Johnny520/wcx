package com.Johnny.wcx.activity.settings.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.ui.style.SoftCard
import com.Johnny.wcx.ui.style.WxPalette

/**
 * AI 的「说明」页 —— 所有工具的用法集中在这一页。
 *
 * 设置页只放开关与接口，说明全部搬到这里，按「开始用 / 在聊天里用 / 在 AI 页用 / 常见问题」分组，
 * 以后新增工具只往对应分组加一条，不再散落在设置里。
 */
@Composable
internal fun AiHelpPage(
    onOpenConfig: () -> Unit = {},
    onMomentsCopy: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HelpSection(
            title = "开始使用",
            subtitle = "三步就能用起来",
            items = listOf(
                HelpItem(
                    name = "1. 填 AI 接口",
                    detail = "填地址、Key、模型，点「测试并保存」。显示「已激活」才算通了。",
                    clickable = true,
                ) to onOpenConfig,
                HelpItem(
                    name = "2. 加好友档案",
                    detail = "回「好友」页点右上角＋，勾选想让 AI 了解的人。",
                ) to null,
                HelpItem(
                    name = "3. 加群总结",
                    detail = "「群组」页点＋选群，再在群里设总结时段。",
                ) to null,
            ),
        )

        HelpSection(
            title = "在聊天里用",
            subtitle = "长按输入框 → 菜单里选",
            items = listOf(
                HelpItem("AI 提取待办", "把这段聊天的待办整理成「事项 — 谁 — 何时」。") to null,
                HelpItem("AI 帮我想怎么回", "按上下文给 3 条语气不同的候选，复制就发。") to null,
                HelpItem("AI 问答", "问一句「他上次说什么时候交」，它翻记录回答。") to null,
                HelpItem("AI 写群公告", "只在群聊出现：给个主题，出公告 + 新成员欢迎语。") to null,
                HelpItem("AI 未读速览", "所有未读会话的要点，需要你回复的排最前面，不会标记已读。") to null,
                HelpItem("AI 今日摘要", "今天各会话的进展与待跟进。") to null,
                HelpItem("AI 周报", "本周各会话的进展汇总，写工作总结可直接用。") to null,
                HelpItem("AI 搜聊天", "给个关键词，在所有会话里找出谁提过、说了什么。") to null,
                HelpItem("AI 群纪要", "群聊里用：把最近消息整理成结论 / 待办 / 待确认。") to null,
                HelpItem("AI 该回谁", "找出「对方最后一句还没被我回复」的会话，并给一句怎么回。") to null,
            ),
        )

        HelpSection(
            title = "在聊天里用",
            subtitle = "长按某一条消息",
            items = listOf(
                HelpItem("AI 改写这条", "自己发的文字：正式 / 简短 / 客气三种版本。") to null,
                HelpItem("AI 写备注", "单聊里用：给这个人写 3 条备注建议。") to null,
                HelpItem("AI 读图翻译", "图片消息：提取图中文字并翻译，没文字就描述图片。") to null,
                HelpItem("AI 速读这条", "超过 80 字的长消息：压成 3 句要点，保留数字与时间。") to null,
            ),
        )

        HelpSection(
            title = "在朋友圈用",
            subtitle = "长按某条朋友圈",
            items = listOf(
                HelpItem(
                    "AI 帮我评论",
                    "给 3 条评论建议，点任意一条复制；作者有档案会带上你们的记忆与标签，不会自动发送。",
                ) to null,
            ),
        )

        HelpSection(
            title = "在 AI 页面用",
            subtitle = "不需要开会话",
            items = listOf(
                HelpItem(
                    name = "朋友圈文案",
                    detail = "给关键词，出 3 条可直接发的文案。",
                    clickable = true,
                ) to onMomentsCopy,
                HelpItem("双击建议", "聊天页双击屏幕，按好友档案给建议（开关在「设置」）。") to null,
                HelpItem("自动回复", "按人开关：在「好友」页点进档案里打开。") to null,
                HelpItem("回复延迟", "自动回复前等多久：3/5/8/12/20/30 秒六档，默认 8 秒，在「设置」里调。") to null,
                HelpItem(
                    "重要信息提醒",
                    "自动回复时识别到对方的截止时间、金额、地点、明确要求，会记在「好友」页顶部，点「知道了」清空。",
                ) to null,
                HelpItem("群聊总结", "按群定时自动总结，在「群组」页逐个设置。") to null,
                HelpItem(
                    "工作群",
                    "「群组」页点进某个群 → 打开「工作群」→ 点「更新进度」，AI 会梳理任务目标与完成度，百分比显示在群名后。",
                ) to null,
            ),
        )

        HelpSection(
            title = "常见问题",
            subtitle = "点了没反应先看这里",
            items = listOf(
                HelpItem("长按没有 AI 菜单", "先回「设置」确认接口显示「已激活」；没配置时菜单会提示去填。") to null,
                HelpItem("读图翻译报错", "当前模型不支持图片输入，换 gpt-4o / qwen-vl / glm-4v 之类。") to null,
                HelpItem("AI 说记录里没提到", "它只依据聊天记录回答，不会猜；换一种问法或看更早的记录。") to null,
                HelpItem("结果不满意", "所有结果都只显示不发送，复制后可自己改；换模型或调「温度」会更活络。") to null,
            ),
        )

        Text(
            text = "本页只说明用法，不会执行任何操作；所有 AI 工具都不会自动发送消息、不会修改聊天记录。",
            fontSize = 11.sp,
            color = WxPalette.textTertiary(),
            lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

private data class HelpItem(
    val name: String,
    val detail: String,
    val clickable: Boolean = false,
)

@Composable
private fun HelpSection(
    title: String,
    subtitle: String,
    items: List<Pair<HelpItem, (() -> Unit)?>>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = WxPalette.textPrimary(),
            )
            Spacer(Modifier.width(6.dp))
            Text(text = subtitle, fontSize = 11.sp, color = WxPalette.textTertiary())
        }
        SoftCard(contentPadding = 0) {
            items.forEachIndexed { index, (item, action) ->
                HelpRow(
                    item = item,
                    showDivider = index != items.lastIndex,
                    onClick = action,
                )
            }
        }
    }
}

@Composable
private fun HelpRow(
    item: HelpItem,
    showDivider: Boolean,
    onClick: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth()) {
        val base = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
        Row(
            modifier = if (onClick != null) base.clickable { onClick() } else base,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontSize = 13.sp,
                    color = if (item.clickable) WxPalette.Accent else WxPalette.textPrimary(),
                    fontWeight = if (item.clickable) FontWeight.Medium else FontWeight.Normal,
                )
                Text(
                    text = item.detail,
                    fontSize = 11.sp,
                    color = WxPalette.textSecondary(),
                    lineHeight = 16.sp,
                )
            }
            if (onClick != null) {
                Text(
                    text = "打开",
                    fontSize = 11.sp,
                    color = WxPalette.Accent,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                )
            }
        }
        if (showDivider) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp)
                    .height(0.5.dp)
                    .background(WxPalette.divider()),
            ) {}
        }
    }
}
