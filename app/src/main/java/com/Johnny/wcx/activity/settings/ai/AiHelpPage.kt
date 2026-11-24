package com.Johnny.wcx.activity.settings.ai

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.ui.style.SoftCard
import com.Johnny.wcx.ui.style.WxPalette

/**
 * AI 的「说明」页 —— 只放用法文字，不放任何功能入口。
 *
 * 这一页的职责是「教怎么用」：接口在哪填、怎么填，每个工具在哪个位置、长按哪里。
 * 需要动手的操作（填接口、写朋友圈文案）都在「设置」页里，说明里只写明去哪儿做。
 */
@Composable
internal fun AiHelpPage() {
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
                    detail = "在「设置」页最上面那行「AI 接口」点开，三个框按顺序填：" +
                        "① 服务地址，填完整路径，例如 https://api.deepseek.com/v1/chat/completions；" +
                        "② Key，粘贴服务商给的密钥（sk- 开头那一串）；" +
                        "③ 模型名，例如 deepseek-chat。" +
                        "填完点「测试并保存」，显示「已激活」就说明通了；报错就把提示原文贴给服务商对照。",
                ),
                HelpItem(
                    name = "2. 加好友档案",
                    detail = "回「好友」页点右上角＋，勾选想让 AI 了解的人。" +
                        "档案建好后，AI 才会带上这个人的称呼、喜好和你俩聊过的事。",
                ),
                HelpItem(
                    name = "3. 加群总结",
                    detail = "「群组」页点＋选群，再在群里设总结时段。",
                ),
            ),
        )

        HelpSection(
            title = "在聊天里用",
            subtitle = "长按输入框 → 菜单里选",
            items = listOf(
                HelpItem("AI 提取待办", "把这段聊天的待办整理成「事项 — 谁 — 何时」。"),
                HelpItem("AI 帮我想怎么回", "按上下文给 3 条语气不同的候选，复制就发。"),
                HelpItem("AI 问答", "问一句「他上次说什么时候交」，它翻记录回答。"),
                HelpItem("AI 写群公告", "只在群聊出现：给个主题，出公告 + 新成员欢迎语。"),
                HelpItem("AI 未读速览", "所有未读会话的要点，需要你回复的排最前面，不会标记已读。"),
                HelpItem("AI 今日摘要", "今天各会话的进展与待跟进。"),
                HelpItem("AI 周报", "本周各会话的进展汇总，写工作总结可直接用。"),
                HelpItem("AI 搜聊天", "给个关键词，在所有会话里找出谁提过、说了什么。"),
                HelpItem("AI 群纪要", "群聊里用：把最近消息整理成结论 / 待办 / 待确认。"),
                HelpItem("AI 该回谁", "找出「对方最后一句还没被我回复」的会话，并给一句怎么回。"),
            ),
        )

        HelpSection(
            title = "在聊天里用",
            subtitle = "长按某一条消息",
            items = listOf(
                HelpItem("AI 改写这条", "自己发的文字：正式 / 简短 / 客气三种版本。"),
                HelpItem("AI 写备注", "单聊里用：给这个人写 3 条备注建议。"),
                HelpItem("AI 读图翻译", "图片消息：提取图中文字并翻译，没文字就描述图片。"),
                HelpItem("AI 速读这条", "超过 80 字的长消息：压成 3 句要点，保留数字与时间。"),
            ),
        )

        HelpSection(
            title = "在朋友圈用",
            subtitle = "长按某条朋友圈",
            items = listOf(
                HelpItem(
                    "AI 帮我评论",
                    "给 3 条评论建议，点任意一条复制；作者有档案会带上你们的记忆与标签，不会自动发送。",
                ),
            ),
        )

        HelpSection(
            title = "在 AI 页面用",
            subtitle = "不用开会话",
            items = listOf(
                HelpItem(
                    "朋友圈文案",
                    "在「设置」页点「朋友圈文案」那一行：给个关键词，出 3 条可直接发的文案，复制后自己发。",
                ),
                HelpItem("双击建议", "聊天页双击屏幕，按好友档案给建议（开关在「设置」）。"),
                HelpItem("自动回复", "按人开关：在「好友」页点进档案里打开。"),
                HelpItem("回复延迟", "自动回复前等多久：3/5/8/12/20/30 秒六档，默认 8 秒，在「设置」里调。"),
                HelpItem(
                    "档案自动整理",
                    "开启后每天到点给已就绪的好友档案补一次新增聊天（默认关，会消耗额度），在「设置」里调时间。",
                ),
                HelpItem(
                    "重要信息提醒",
                    "自动回复时识别到对方的截止时间、金额、地点、明确要求，会记在「好友」页顶部，点「知道了」清空。",
                ),
                HelpItem("群聊总结", "按群定时自动总结，在「群组」页逐个设置。"),
                HelpItem(
                    "每日总结",
                    "「总结」页可手动生成或开自动（默认 22 点）：今天做了什么、和谁聊了什么、还有什么没做完，工作群进度一并列出。",
                ),
                HelpItem(
                    "工作群",
                    "「群组」页点进某个群 → 打开「工作群」→ 点「更新进度」，AI 会梳理任务目标与完成度，百分比显示在群名后。",
                ),
            ),
        )

        HelpSection(
            title = "常见问题",
            subtitle = "点了没反应先看这里",
            items = listOf(
                HelpItem("长按没有 AI 菜单", "先回「设置」确认接口显示「已激活」；没配置时菜单会提示去填。"),
                HelpItem("读图翻译报错", "当前模型不支持图片输入，换 gpt-4o / qwen-vl / glm-4v 之类。"),
                HelpItem("AI 说记录里没提到", "它只依据聊天记录回答，不会猜；换一种问法或看更早的记录。"),
                HelpItem("结果不满意", "所有结果都只显示不发送，复制后可自己改；换模型或调「温度」会更活络。"),
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
)

@Composable
private fun HelpSection(
    title: String,
    subtitle: String,
    items: List<HelpItem>,
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
            items.forEachIndexed { index, item ->
                HelpRow(item = item, showDivider = index != items.lastIndex)
            }
        }
    }
}

@Composable
private fun HelpRow(
    item: HelpItem,
    showDivider: Boolean,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontSize = 13.sp,
                    color = WxPalette.textPrimary(),
                )
                Text(
                    text = item.detail,
                    fontSize = 11.sp,
                    color = WxPalette.textSecondary(),
                    lineHeight = 16.sp,
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
