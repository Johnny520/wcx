package com.Johnny.wcx.ai.group

import com.Johnny.wcx.ai.AiRunState
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.utils.WeLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * 一次群总结的完整流程：阈值 → 取数 → 切片 → 生成 → 落盘。
 *
 * 由 [GroupDigestScheduler] 在 IO 线程调用；手动「立即总结」也走这里，只是 `manual = true`。
 *
 * 几条边界：
 * - **每群单飞**：拿不到锁直接返回「正在总结」，不排队 —— 排队会让一次延迟变成连续请求；
 * - **失败不推进窗口**：下个网格重试时窗口含失败段；
 * - **空窗口不推进**：那 1~2 条会落进下一窗，攒够阈值再总结；
 * - **模型判 `significant=false` 要推进**：这段已经花一次请求确认过没内容，再拉一遍是浪费。
 */
object GroupDigestRunner {

    private const val TAG = "GroupDigestRunner"
    private const val TEMPERATURE = 0.3
    private const val REQUEST_TIMEOUT_MS = 180_000

    /**
     * 单群的请求预算。
     *
     * `chatWithTools` 是阻塞调用，`withTimeout` 中断不了正在等的 HTTP；这里只能是
     * 「超预算就不再发起下一片」，用已经拿到的片去合并。单片仍由 [REQUEST_TIMEOUT_MS] 兜底。
     */
    private const val TOTAL_BUDGET_MS = 10 * 60_000L
    private const val MAX_FAILURES = 3

    data class Result(val ok: Boolean, val message: String, val busy: Boolean = false)

    /** 每群一把锁；`tryLock` 失败说明已有任务在跑。 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * 任务不绑调用方的生命周期：管理弹窗关掉、页面重建都不该中断一次正在跑的总结。
     * UI 只调 [start]，状态靠轮询文件。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 从 UI 启动一次总结。返回 false 表示该群已有任务在跑。
     *
     * 先把状态写成 `busy` 再启动：弹窗点完按钮会立刻重读，看不到 busy 的话按钮会
     * 短暂变回可点，用户会以为没触发。
     */
    fun start(groupId: String, manual: Boolean = false): Boolean {
        val mutex = locks.getOrPut(groupId) { Mutex() }
        if (mutex.isLocked) return false
        markStatus(groupId, GroupDigestStatus.BUSY)
        scope.launch { run(groupId, manual) }
        return true
    }

    suspend fun run(groupId: String, manual: Boolean = false): Result {
        val mutex = locks.getOrPut(groupId) { Mutex() }
        if (!mutex.tryLock()) {
            // 自动任务撞上手动任务：不推进窗口，下个网格重试；调用方据此不要重排闹钟。
            return Result(false, "正在总结", busy = true)
        }
        return try {
            runLocked(groupId, manual)
        } catch (e: CancellationException) {
            // 进程退出 / 作用域取消：不是业务失败，不要把群标成 failed。
            throw e
        } catch (e: Throwable) {
            WeLogger.e(TAG, "总结异常：$groupId", e)
            fail(groupId, e.message ?: e.javaClass.simpleName)
            Result(false, "总结失败")
        } finally {
            mutex.unlock()
        }
    }

    private fun runLocked(groupId: String, manual: Boolean): Result {
        val config = GroupDigestStore.readConfig(groupId)
            ?: return Result(false, "群不存在")
        if (!config.enabled && !manual) return Result(false, "未启用")

        // 与好友档案抢同一条网络会 429；手动是用户主动要的，不避让。
        if (!manual && AiRunState.hasLongTask()) {
            markStatus(groupId, GroupDigestStatus.BUSY)
            return Result(false, "好友档案正在整理")
        }
        if (!AiConfig.isConfigured) {
            markStatus(groupId, GroupDigestStatus.UNCONFIGURED)
            return Result(false, "未配置 AI 接口")
        }

        // 幂等：start 已经写过一次时这里不会重复落盘。
        markStatus(groupId, GroupDigestStatus.BUSY)
        val nowMs = System.currentTimeMillis()
        val (fromSec, toSec) = config.windowSec(nowMs)
        if (toSec <= fromSec) {
            markStatus(groupId, GroupDigestStatus.EMPTY, "时间窗口为空")
            return Result(true, "时间窗口为空")
        }

        // 阈值先数文字：媒体占多数时不能拿样本里的文字数判断。
        val textCount = GroupTranscript.textCount(groupId, fromSec, toSec)
        if (textCount < MIN_TEXT_LINES) {
            val reason = "这段只有 $textCount 条文字，少于 $MIN_TEXT_LINES 条"
            markStatus(groupId, GroupDigestStatus.EMPTY, reason)
            return Result(true, reason)
        }

        val transcript = GroupTranscript.load(groupId, fromSec, toSec)
        if (transcript.lines.isEmpty()) {
            markStatus(groupId, GroupDigestStatus.EMPTY, "没有可读的消息")
            return Result(true, "没有可读的消息")
        }

        val chunks = GroupTranscript.chunk(transcript.lines)
        val parsed = generate(config, chunks, fromSec, toSec) ?: run {
            fail(groupId, "生成失败")
            return Result(false, "总结失败")
        }

        if (!parsed.significant) {
            // 文字够但全是寒暄：这段确认过没内容，推进窗口，避免下轮重复请求同一段。
            GroupDigestStore.update(groupId) {
                it.withStatus(GroupDigestStatus.EMPTY, nowMs, "模型判断没有值得总结的内容")
                    .copy(lastSlotEnd = nowMs)
            }
            return Result(true, "没有值得看的内容")
        }

        val digest = GroupDigest(
            id = nowMs.toString(),
            fromSec = fromSec,
            toSec = toSec,
            createdAt = nowMs,
            headline = parsed.headline.ifBlank { "（无摘要）" },
            points = parsed.points,
            todos = parsed.todos,
            messageCount = transcript.lines.size,
            manual = manual,
        )
        val saved = GroupDigestStore.update(groupId) { it.withDigest(digest) }
        return if (saved != null) {
            WeLogger.i(
                TAG,
                "$groupId 完成：${transcript.lines.size} 条 · ${chunks.size} 片 · ${parsed.points.size} 点" +
                    if (transcript.sampled) " · 已截断" else "",
            )
            Result(true, parsed.headline)
        } else {
            Result(false, "写盘失败")
        }
    }

    // ── 生成 ────────────────────────────────────────────────────────────────

    private fun generate(
        config: GroupDigestConfig,
        chunks: List<List<GroupTranscript.Line>>,
        fromSec: Long,
        toSec: Long,
    ): GroupDigestPrompts.Parsed? {
        if (chunks.isEmpty()) return null

        // 单片：一次请求直接出摘要。
        if (chunks.size == 1) {
            val raw = call(
                GroupDigestPrompts.system(),
                GroupDigestPrompts.user(
                    config.name, fromSec, toSec, GroupTranscript.transcript(chunks.first()),
                ),
            ) ?: return null
            return parseOrFallback(raw)
        }

        // 多片：每片只提炼要点，最后合并。提示词里不出现片号 / 片数。
        val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS
        val sliceOutputs = mutableListOf<String>()
        for (chunk in chunks) {
            if (System.currentTimeMillis() >= deadline) {
                WeLogger.w(TAG, "${config.groupId} 超出预算，停止发起后续分片")
                break
            }
            val raw = call(
                GroupDigestPrompts.sliceSystem(),
                GroupDigestPrompts.sliceUser(
                    config.name,
                    chunk.first().atSec,
                    chunk.last().atSec + 1,
                    GroupTranscript.transcript(chunk),
                ),
            ) ?: return null
            sliceOutputs += raw
        }
        if (sliceOutputs.isEmpty()) return null

        val parts = sliceOutputs.mapNotNull { raw ->
            GroupDigestPrompts.parse(raw)?.let { parsed ->
                (parsed.points + parsed.todos).joinToString("\n") { "- $it" }
            }
        }.filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            return GroupDigestPrompts.Parsed(false, "", emptyList(), emptyList())
        }

        val merged = call(
            GroupDigestPrompts.mergeSystem(),
            GroupDigestPrompts.mergeUser(config.name, fromSec, toSec, parts),
        ) ?: return null
        return parseOrFallback(merged)
    }

    /** 模型没按 JSON 输出时不整条丢：headline 取首句，points 留空。 */
    private fun parseOrFallback(raw: String): GroupDigestPrompts.Parsed {
        GroupDigestPrompts.parse(raw)?.let { return it }
        WeLogger.w(TAG, "总结输出不是 JSON，用首句兜底：${raw.take(80)}")
        return GroupDigestPrompts.Parsed(
            significant = true,
            headline = GroupDigestPrompts.fallbackHeadline(raw),
            points = emptyList(),
            todos = emptyList(),
        )
    }

    private fun call(system: String, user: String): String? {
        val result = AiConfig.chatWithTools(
            messages = listOf(
                AiConfig.ChatMessage(role = "system", content = system),
                AiConfig.ChatMessage(role = "user", content = user),
            ),
            temperature = TEMPERATURE,
            readTimeoutMs = REQUEST_TIMEOUT_MS,
            // 后台任务：成功与失败都不改首页的「AI 已激活」状态。
            affectStatus = false,
        )
        if (result.text.isNullOrBlank()) {
            WeLogger.w(TAG, "请求失败：${result.error ?: "空响应"}")
            return null
        }
        if (result.finishReason == "length") {
            WeLogger.w(TAG, "输出被截断（finish_reason=length）")
        }
        return result.text
    }

    // ── 状态 ────────────────────────────────────────────────────────────────

    private fun markStatus(groupId: String, status: String, error: String = "") {
        GroupDigestStore.update(groupId) {
            it.withStatus(status, System.currentTimeMillis(), error)
        }
    }

    private fun fail(groupId: String, message: String) {
        val updated = GroupDigestStore.update(groupId) { current ->
            val next = current.withStatus(
                status = GroupDigestStatus.FAILED,
                nowMs = System.currentTimeMillis(),
                error = message,
                failed = true,
            )
            if (next.failures >= MAX_FAILURES) {
                next.copy(enabled = false, lastStatus = GroupDigestStatus.PAUSED)
            } else {
                next
            }
        }
        if (updated?.lastStatus == GroupDigestStatus.PAUSED) {
            WeLogger.w(TAG, "$groupId 连续失败 $MAX_FAILURES 次，已暂停")
        }
    }

}
