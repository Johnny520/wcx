package com.Johnny.wcx.features.items.chat

import android.content.ContentValues
import com.Johnny.wcx.ai.AiImportantAlerts
import com.Johnny.wcx.ai.AiProfileStore
import com.Johnny.wcx.ai.AiRunState
import com.Johnny.wcx.ai.AiSuggestEngine
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * AI 自动回复：按好友档案里的「自动回复」开关，由模型代回一条。
 *
 * 与旧「AI 自动回复」的差别：
 * - 开关**按好友**（在 AI 页里逐个打开），不再有全局黑白名单、群聊规则、回复前缀等一堆参数；
 * - 回复内容复用智能建议的提示词（同一份关系上下文），所以自动回复与手动建议口径一致；
 * - 只回**单聊**文字消息。群聊要处理 @、关键词、多发言人，风险高，一期不做。
 *
 * 三道闸门缺一不可：档案已就绪 + 该好友开着自动回复 + 全局 AI 配置可用。
 *
 * ## 为什么要等一段静默期
 *
 * 对方经常把一段话拆成几条发（「在吗」「我跟你说个事」「昨天那个」），每条落库都会触发
 * 一次 `onInsert`。如果每条都回，就会出现「第一条刚回完，对方还在打字」的场面，而且回的是
 * 半句话。所以这里用**防抖**：每条新消息都把待回复任务取消重建，等最后一条之后
 * 「回复延迟」秒内再没有新动静，才真正生成并发送。
 *
 * 回复生成期间同样算「有新消息就作废」：生成要几秒到十几秒，这期间对方再说话，
 * 已经生成的那条已经过时，丢弃后由新消息重新计时。
 *
 * 这和主流客服机器人的做法一致：单会话单飞（同时只有一个回复任务）、静默窗口合并连发、
 * 发送前再校验一次上下文有没有变。微信没有「对方正在输入」的回调，静默窗口是唯一可靠的信号。
 */
@Feature(
    name = "AI 自动回复",
    categories = ["API"],
    description = "对开启了自动回复的好友，收到消息后由 AI 代回一条（按好友开关，仅单聊文字）",
)
object AiAutoReplyFeature : ApiFeature(), WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "AiAutoReplyFeature"

    /**
     * 静默窗口：对方最后一条消息之后，等这么久没有新消息才回。
     *
     * 固定 8 秒：对方把一段话拆成几条发时，每条都会重置计时，只有最后一条之后
     * 8 秒内没有新动静才真正生成 —— 短于这个数会抢话，长于这个数回得太慢。
     *
     * 它同时就是「两次回复之间的最小间隔」：回复发出去之后对方再说话，仍然要等
     * 他自己的 8 秒静默，所以不需要再叠一层冷却。
     */
    /** 静默窗口的默认值；实际值由「AI 助手 → 设置 → 回复延迟」决定（3~30 秒六档）。 */

    private val quietMs: Long get() = AiConfig.replyDelayMs

    /** 用户自己最后一次发消息的时间：比我方收到的那条更晚，说明用户已经自己回了。 */
    private val lastUserMessageAt = ConcurrentHashMap<String, Long>()

    /** 会话里最新一条消息的时间（自己发的也算）。比它更旧的只有补同步进来的历史消息。 */
    private val lastActivityAt = ConcurrentHashMap<String, Long>()

    /** 每个会话的待回复任务代际：新消息到来时 +1，旧任务发现代际变了就自己退出。 */
    private val generation = ConcurrentHashMap<String, Long>()

    /** 待回复任务：新消息进来时取消旧的，保证同一会话只有一个任务在跑。 */
    private val pending = ConcurrentHashMap<String, Job>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        // 开关集合要在后台建：旧索引第一次会触发一次全量重建（读所有档案），
        // 放在消息线程上会把第一条消息卡住好几秒。
        Thread({ runCatching { AiProfileStore.warmAutoReplySet() } }, "wcx-ai-autoreply-warm").start()
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        // 总开关关着时连消息都不用解析，直接跳过（定时器之后也不会再排队）。
        if (!AiConfig.isMasterEnabled) return
        if (!AiConfig.isConfigured) {
            WeLogger.d(TAG, "自动回复跳过：AI 未配置")
            return
        }

        // 这是一个**全局** hook：所有会话的消息都会进来。先用最便宜的字段判断
        // 「这个会话有没有开着自动回复」，不是就立刻退出 —— 不构造 MessageInfo、
        // 不读档案。绝大多数消息在这里就结束了，也保证没选中的会话内容不被解析。
        val rawTalker = values.getAsString("talker").orEmpty()
        if (rawTalker.isBlank() || rawTalker.isGroupChatWxId) return
        if (!AiProfileStore.isAutoReplyOn(rawTalker)) return

        val message = runCatching { MessageInfo.fromContentValues(values) }.getOrNull()
        if (message == null) {
            WeLogger.d(TAG, "自动回复跳过：消息解析失败")
            return
        }
        val talker = message.talker
        if (talker.isBlank() || talker.isGroupChatWxId) return

        val msgTime = message.createTime.coerceAtMost(System.currentTimeMillis())
        val typeCode = message.typeCode
        val isText = typeCode == 1
        // 开了「识别图片」时，图片消息也触发：模型能看见图，回一句是合理的。
        // 只放图片（3）—— 表情包/语音/视频仍然不触发，它们没有模型能读的内容。
        val isImage = typeCode == MessageType.IMAGE.code && AiConfig.recognizeImages

        // **自己**发的任何消息（文字 / 图片 / 表情 / 语音）都算用户已经介入：收起建议浮层、
        // 记下时间、作废待回复任务。只认文字的话，用户发一张图把话答了，8 秒后 AI 还会补一条。
        // 对方发来的消息不收起浮层 —— 手动点的建议是用户主动要的，浮层一移除，生成中的协程
        // 就被取消，对方下一句话就能让这次点击白费。「新消息让回复作废」只属于自动回复
        // 那条链（它有自己的代际号与静默窗口），不该牵连手动建议。
        // 为什么在这里做：输入栏发送按钮的 OnClickListener 无法安全包装（替换掉微信自己的监听
        // 会导致发送失效），而消息落库是发送绕不过去的唯一出口。
        if (message.isSelfSender) {
            AiSuggestOverlay.dismissFor(talker)
            lastUserMessageAt[talker] = msgTime
            lastActivityAt[talker] = msgTime
            invalidate(talker)
            return
        }

        if (!isText && !isImage) {
            skip(talker, "非文字消息 type=$typeCode")
            return
        }
        if (isText) {
            // 可读性判断：type=1 里也混着 XML（小程序、系统卡片、引用残留），
            // 这些对模型没有语义，回出去只会答非所问。与 AiChatHistory.sanitize 同一口径。
            val content = message.content.trim()
            if (content.isEmpty() || content.startsWith("<")) {
                skip(talker, "内容为空或 XML")
                return
            }
        }

        // 补同步进来的历史消息：时间落后于已经处理过的位置。它既不该触发回复（微信重连、
        // 打开会话、恢复备份都会重放历史），也不该把正在等待的任务作废。
        // 两个时间都写进日志：单位不一致（秒 / 毫秒）时这里会静默吞掉所有消息，量级一眼可辨。
        val last = lastActivityAt[talker] ?: 0L
        if (msgTime < last) {
            skip(talker, "当成历史重放 msg=$msgTime last=$last")
            return
        }

        // 档案读取放在触发前：没建档的好友连一次文件读都不该触发协程。
        val profile = AiProfileStore.readProfile(talker)
        if (profile == null) {
            skip(talker, "没有档案")
            return
        }
        if (!profile.isReady || !profile.autoReply) {
            skip(talker, "档案未就绪或自动回复已关闭 ready=${profile.isReady} autoReply=${profile.autoReply}")
            return
        }

        // 重要信息提醒：对方给的截止时间 / 金额 / 地址 / 明确要求，单独记一条，
        // 免得被「AI 已经替我回了」盖过去。规则判定，不发额外请求。
        if (isText) {
            runCatching {
                AiImportantAlerts.maybeRecord(
                    talker = talker,
                    name = profile.remark.ifBlank { profile.nickname },
                    text = message.content,
                    at = msgTime,
                )
            }.onFailure { WeLogger.w(TAG, "记录重要信息失败", it) }
        }
        // 整理/审查期间整档只读，自动回复一律不触发（和智能建议同一条拦截）。
        if (AiRunState.isBusy(talker, profile)) {
            skip(talker, "档案忙（初始化或审查中）")
            return
        }
        // 用户正在看这位好友的建议：不要抢在他前面自动回复，
        // 否则「AI 建议」还没读完，AI 已经替他把话发出去了。
        if (AiSuggestOverlay.isShowingFor(talker)) {
            skip(talker, "建议浮层开着")
            return
        }

        // 每条消息都重建任务：等价于「静默期从这条重新开始数」。
        lastActivityAt[talker] = msgTime
        val seq = invalidate(talker)
        pending[talker] = scope.launch {
            delay(quietMs)

            // 静默期内又来了消息（对方的或自己的）：这一轮作废，由那条新消息重新计时。
            if (generation[talker] != seq) {
                skip(talker, "静默期内有新消息，本轮作废")
                return@launch
            }
            val latest = AiProfileStore.readProfile(talker)
            if (latest == null) {
                skip(talker, "静默期后档案不见了")
                return@launch
            }
            if (!latest.isReady || !latest.autoReply) {
                skip(talker, "静默期后开关被关或档案未就绪")
                return@launch
            }
            if (AiRunState.isBusy(talker, latest)) {
                skip(talker, "静默期后档案变忙")
                return@launch
            }
            // 静默期里用户可能刚点开建议：同样让路。
            if (AiSuggestOverlay.isShowingFor(talker)) {
                skip(talker, "静默期后建议浮层打开")
                return@launch
            }

            // 引擎按当天全量记录取上下文：对方连发时，最后一段就是这次要回的内容。
            val suggestion = AiSuggestEngine.suggest(talker)
            if (suggestion.error != null) {
                WeLogger.w(TAG, "自动回复跳过 ${maskTalker(talker)}：${suggestion.error}")
                return@launch
            }
            // 三条里取第一条（提示词要求「一条顺着对方说」排在最前），其余当作备选丢弃。
            // 再压一次换行：解析层已经做过，这里是发送前最后一道，保证不会发出空行。
            val reply = AiSuggestEngine.oneLine(
                suggestion.replies.firstOrNull().orEmpty()
            )
            if (reply.isEmpty()) {
                WeLogger.w(TAG, "自动回复跳过 ${maskTalker(talker)}：没有生成可用回复")
                return@launch
            }
            // 生成期间（十几秒）对方又发了、或用户自己回了：这条回复已经过时，丢掉。
            if (generation[talker] != seq) {
                WeLogger.d(TAG, "自动回复放弃 ${maskTalker(talker)}：生成期间有新消息")
                return@launch
            }
            val userAt = lastUserMessageAt[talker] ?: 0L
            if (userAt > msgTime) {
                skip(talker, "用户已经自己回复了 user=$userAt msg=$msgTime")
                return@launch
            }

            val sent = runCatching { WeMessageApi.sendText(talker, reply) }.getOrDefault(false)
            if (sent) {
                WeLogger.i(TAG, "已自动回复 ${maskTalker(talker)}：${reply.take(12)}…")
            } else {
                WeLogger.w(TAG, "自动回复发送失败：${maskTalker(talker)}")
            }
        }
    }

    /**
     * 作废该会话的待回复任务，并返回新一轮的代际号。
     *
     * 取消只是让还在 [delay] 里的任务提前退出；已经在生成中的任务无法中断（HTTP 是阻塞调用），
     * 它会在发送前比一次代际号，发现变了就自己放弃。
     */
    private fun invalidate(talker: String): Long {
        pending.remove(talker)?.cancel()
        return generation.merge(talker, 1L, Long::plus) ?: 1L
    }

    /** 每个「该回但没回」的出口都留一行：排查时不用再猜卡在哪一步。 */
    private fun skip(talker: String, reason: String) {
        WeLogger.d(TAG, "自动回复跳过 ${maskTalker(talker)}：$reason")
    }

    /** 日志里不留完整 wxid（隐私 + 无意义长度）。 */
    private fun maskTalker(talker: String): String =
        if (talker.length <= 8) talker else talker.take(4) + "…" + talker.takeLast(2)
}
