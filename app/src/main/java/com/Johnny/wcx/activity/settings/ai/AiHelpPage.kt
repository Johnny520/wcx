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
                        "面板顶部有一排常用服务商（DeepSeek / OpenAI / 通义千问 / 智谱 / Kimi / 硅基流动 / 本地 Ollama），" +
                        "点一下就自动填好地址和默认模型；也可以选「自定义」自己填 —— 地址完全按你写的用，" +
                        "带端口、子路径、中转站都可以，系统不会自动追加任何后缀。" +
                        "填完点「测试并保存」，显示「已激活」就说明通了；报错就把提示原文贴给服务商对照。" +
                        "地址和 Key 都是长文本，输入框会自动折行（最多 4 行，超出可上下滑）。" +
                        "高级参数里还能单独填「识图模型」—— 主模型不支持看图时，填个 gpt-4o / qwen-vl 之类。",
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
            subtitle = "长按任意一条消息 → 菜单里点「AI 助手」",
            items = listOf(
                HelpItem(
                    name = "怎么打开",
                    detail = "在任意聊天（群聊、私聊）里长按任意一条消息 —— 文字、图片、语音、视频、小程序都行 —— " +
                        "微信原生长按菜单里会多一项「AI 助手」，点它就打开工作台。" +
                        "复制、转发等原生功能照旧可用；双击和长按输入框的入口已取消，避免和微信抢手势。",
                ),
                HelpItem("总结这段聊天", "把最近聊天整理成 3-6 条要点 + 一句整体氛围。"),
                HelpItem("提取待办", "只挑真要做的事，每条写成「事项（谁 / 什么时候）」。"),
                HelpItem("帮我回", "以你的身份给最后一条消息写 3 条可直接发的回复。"),
                HelpItem("追问 AI", "填一句问题，比如「他们刚才在争什么」，它翻前面记录回答。"),
                HelpItem("群总结", "群里用：今天聊了什么 / 有结论的事 / 谁要做什么 / 金句。" +
                    "面板里的「群总结提示词」可以改成你自己的模板，留空用内置的。"),
                HelpItem("写群公告", "群里用：给个主题，出可以直接发群的公告。"),
                HelpItem("未读速览", "所有未读会话的要点，按「要不要马上看」排序，不会标记已读。"),
                HelpItem("今日摘要", "今天有动静的会话分成「今天要处理」和「只是闲聊」两类。"),
                HelpItem(
                    name = "结果怎么用",
                    detail = "生成完在面板底部：「复制」进剪贴板；「发送到本会话」直接发到当前聊天；" +
                        "「生成图片」把结果做成分享长图（彩条卡片 / 漫画日报 / 霓虹科技 / 极简白卡 / 分析报告，" +
                        "字多自动变长图），可存相册或直接发进会话；「每天自动发」把这个会话设成每天定时发总结图；" +
                        "不满意点「重跑」。",
                ),
            ),
        )

        HelpSection(
            title = "在朋友圈用",
            subtitle = "长按某条朋友圈（AI 总开关打开时）",
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
                HelpItem(
                    name = "首页有个圆形按钮想关掉",
                    detail = "那是群总结入口（标题栏「＋」「放大镜」左边那个小图标）。" +
                        "到「AI 助手 → 设置 → 首页群总结入口」关掉即可，不用关整个 AI 助手。",
                ),
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
                AiExpandableText(
                    text = item.detail,
                    collapsedLines = 2,
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
