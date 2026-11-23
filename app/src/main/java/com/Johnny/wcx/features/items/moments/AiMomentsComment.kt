package com.Johnny.wcx.features.items.moments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.Johnny.wcx.ai.AiProfile
import com.Johnny.wcx.ai.AiProfileStore
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.features.api.ui.WeMomentsApi
import com.Johnny.wcx.features.api.ui.WeMomentsContextMenuApi
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.items.chat.TranslateSupport
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.ChatInfoIcon
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.copyToClipboard
import com.Johnny.wcx.utils.android.showToast
import dev.ujhhgtg.reflekt.reflekt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 朋友圈帮我评论：长按朋友圈 → 给 3 条评论建议，点一条复制，不会自动发送。
 *
 * 如果这条朋友圈的作者有 AI 档案，会把标签、最近的记忆与你的说话风格一起带上，
 * 语气更像「你们平时怎么聊」，而不是套话。
 */
@Feature(
    name = "朋友圈帮我评论",
    categories = ["朋友圈"],
    description = "长按朋友圈给出 3 条评论建议，点一条复制；有档案的好友会带上记忆与标签，不会自动发送",
)
object AiMomentsComment : ApiFeature(), WeMomentsContextMenuApi.IMenuItemsProvider {

    private const val TAG = "AiMomentsComment"

    private const val MENU_ID = 777030

    private const val SYSTEM_PROMPT =
        "你是替用户写微信朋友圈评论的助手：口语、自然、简短，不肉麻、不堆表情、不写营销腔。"

    override fun onEnable() {
        runCatching { WeMomentsContextMenuApi.addProvider(this) }
            .onFailure { WeLogger.e(TAG, "注册朋友圈菜单项失败", it) }
    }

    override fun onDisable() {
        runCatching { WeMomentsContextMenuApi.removeProvider(this) }
            .onFailure { WeLogger.e(TAG, "注销朋友圈菜单项失败", it) }
    }

    override fun getMenuItems(): List<WeMomentsContextMenuApi.MenuItem> = listOf(
        WeMomentsContextMenuApi.MenuItem(
            MENU_ID,
            "AI 帮我评论",
            ChatInfoIcon,
            { _, _ -> true },
        ) { moment ->
            runCatching { showDialog(moment) }
                .onFailure { error ->
                    WeLogger.e(TAG, "打开评论弹窗失败", error)
                    showToast(moment.activity, "AI 评论失败：${error.message ?: "未知错误"}")
                }
        },
    )

    private fun showDialog(moment: WeMomentsContextMenuApi.MomentsContext) {
        val activity = moment.activity
        if (!AiConfig.isConfigured) {
            showToast(activity, "请先到「AI 助手 → 设置 → AI 接口」填写配置")
            return
        }
        val content = runCatching {
            WeMomentsApi.getMomentContent(moment.snsInfo, moment.timelineObject)?.contentText?.trim().orEmpty()
        }.getOrDefault("")
        if (content.isBlank()) {
            showToast(activity, "这条朋友圈没有文字正文，暂时没法写评论")
            return
        }
        val author = readAuthor(moment.snsInfo)
        val profile = author?.let { runCatching { AiProfileStore.readProfile(it) }.getOrNull() }

        showComposeDialog(activity) {
            CommentDialog(
                content = content,
                profile = profile,
                onDismiss = { onDismiss() },
            )
        }
    }

    /** SnsInfo 的作者 wxid：优先 getUserName()，拿不到再读 field_userName。 */
    private fun readAuthor(snsInfo: Any?): String? {
        val normalized = snsInfo ?: return null
        return runCatching {
            normalized.reflekt().firstMethodOrNull { name = "getUserName" }?.invoke() as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching {
                normalized.reflekt().firstFieldOrNull { name = "field_userName" }?.get() as? String
            }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 把档案压成几行「他是谁 / 我们之间的事 / 我的说话风格」，别把整份档案塞给模型。 */
    private fun profileBrief(profile: AiProfile?): String {
        if (profile == null || !profile.isReady) return ""
        val builder = StringBuilder()
        val name = profile.remark.ifBlank { profile.nickname }
        if (name.isNotBlank()) builder.append("对方：").append(name).append('\n')
        if (profile.tags.isNotEmpty()) {
            builder.append("标签：")
                .append(profile.tags.take(6).joinToString("、") { "${it.key}:${it.value}" })
                .append('\n')
        }
        if (profile.memories.isNotEmpty()) {
            builder.append("我了解的关于对方的事：\n")
            profile.memories.takeLast(5).forEach { memory ->
                builder.append("- ").append(memory.title.take(40)).append('\n')
            }
        }
        val style = profile.style.summary
        if (style.isNotBlank()) builder.append("我平时的说话风格：").append(style.take(120)).append('\n')
        return builder.toString()
    }

    private fun generate(content: String, profile: AiProfile?): Result<String> {
        val brief = profileBrief(profile)
        val user = StringBuilder()
        if (brief.isNotBlank()) {
            user.append("关于这条朋友圈的作者：\n").append(brief).append('\n')
        }
        user.append("朋友圈正文：\n").append(content.take(500)).append("\n\n")
        user.append(
            "请写 3 条我可以直接发的评论，用简体中文，编号 1./2./3.：\n" +
                    "1. 每条不超过 25 字，口语，不肉麻、不堆表情；\n" +
                    "2. 三条语气不同（附和 / 关心 / 玩笑），都别夸张；\n" +
                    "3. 不要提「AI」、不要解释。",
        )
        return TranslateSupport.chatCompletion(SYSTEM_PROMPT, user.toString())
    }

    @Composable
    private fun CommentDialog(
        content: String,
        profile: AiProfile?,
        onDismiss: () -> Unit,
    ) {
        var result by remember { mutableStateOf("") }
        var errorText by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(true) }
        val context = androidx.compose.ui.platform.LocalContext.current

        LaunchedEffect(Unit) {
            val outcome = withContext(Dispatchers.IO) { generate(content, profile) }
            loading = false
            outcome.onSuccess { result = it }.onFailure { errorText = it.message ?: "未知错误" }
        }

        val lines = remember(result) {
            result.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        }

        AlertDialogContent(
            title = {
                Text(
                    if (profile != null && profile.isReady) "AI 评论（参考了你们的关系）" else "AI 评论建议",
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when {
                        loading -> Text("正在读这条朋友圈并想评论…", style = MaterialTheme.typography.bodyMedium)
                        errorText.isNotBlank() -> Text("失败：$errorText", style = MaterialTheme.typography.bodyMedium)
                        else -> lines.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .clickable {
                                        copyToClipboard(context, line)
                                        showToast(context, "已复制")
                                    }
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                    if (!loading && lines.isNotEmpty()) {
                        Text("点任意一条即可复制（不会自动发送）", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            dismissButton = { TextButton(onDismiss) { Text("关闭") } },
            confirmButton = {
                if (result.isNotBlank()) {
                    TextButton(
                        onClick = {
                            copyToClipboard(context, result)
                            showToast(context, "已复制全部")
                        },
                    ) { Text("复制全部") }
                }
            },
        )
    }
}
