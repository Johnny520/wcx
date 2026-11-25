package com.Johnny.wcx.ai

import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.utils.WeLogger
import kotlinx.serialization.json.JsonArray
import java.util.concurrent.ConcurrentHashMap

/**
 * 档案审查：标签是否站得住、哪些事件可以合并、哪些是关键事件。
 *
 * **只产出建议，不直接改档案。**
 *
 * 审查用的独立模型调用判断「这条是幻觉」「这十条都是闲聊」并不可靠，直接删掉就无法恢复。
 * 分工是：
 *
 * 1. 模型用 `propose_audit` 提交这一片的建议，逐片**累加**进档案的 `pendingAudit`；
 * 2. 全部跑完后，管理弹窗列出每一条与理由，用户逐项勾选；
 * 3. 用户点执行后才走 [apply] —— 纯代码落盘，不再请求模型。
 *
 * 工具集里**没有** `update_profile`：模型在能力上就删不掉东西，不依赖提示词约束。
 *
 * ## 为什么是切片，不是多轮
 *
 * 档案上限 2000 条。一次把全部标题喂进去（约 56k 字符）再加抽检详情，128k 窗口下也只能看
 * 300 条正文，剩下 1700 条只能靠标题判断。改成「标签一片 + 记忆每片 150 条、标题和正文全给」：
 *
 * - 详情覆盖 100%，判断质量不再取决于标题清不清楚；
 * - 每片约 36k 输入，64k 窗口就够，不要求服务商支持长上下文；
 * - 每片一次请求，失败只影响一片，不用重跑整次；
 * - 和 [AiProfileInitializer] 的切片骨架同构，没有第二套多轮循环。
 *
 * 线程模型：阻塞（同步 HTTP），[start] 起后台线程；`auditing` 与 `progress` 供弹窗轮询。
 */
object AiProfileAudit {

    private const val TAG = "AiProfileAudit"

    /**
     * 一片的结局。三种要分开：
     * - [OK] 模型没提建议（这一片干净），或者提了并且成功落盘；
     * - [BAD_OUTPUT] 提了但参数坏了（截断、序号全不对）—— 换一片就能恢复；
     * - [REQUEST_FAILED] 网络 / 鉴权失败 —— 连续几次就该收口，继续跑只是白等超时。
     */
    private enum class SliceResult { OK, BAD_OUTPUT, REQUEST_FAILED }

    private val running = ConcurrentHashMap.newKeySet<String>()

    fun isRunning(wxId: String): Boolean = running.contains(wxId)

    /** 是否有任何好友正在跑长任务。群总结用它避让，不能按 wxId 一个个查。 */
    fun isAnyRunning(): Boolean = running.isNotEmpty()

    /**
     * 后台启动。返回 false 表示已在跑，或该好友正在初始化。
     *
     * @param resume true 表示接着上次中断的片继续：**保留 `pendingAudit` 与 `progressDone`**，
     *   从断点往后跑。false 是全新一次审查，旧提案作废。
     */
    fun start(wxId: String, resume: Boolean = false): Boolean {
        if (!running.add(wxId)) return false
        if (AiProfileInitializer.isRunning(wxId)) {
            running.remove(wxId)
            return false
        }
        val profile = AiProfileStore.readProfile(wxId)
        if (profile == null || profile.busy) {
            running.remove(wxId)
            return false
        }
        AiProfileStore.patchProfile(wxId) {
            if (resume) {
                it.copy(auditing = true)
            } else {
                // 新一次审查从零开始：旧提案作废，否则用户会把两次的结论混在一起执行。
                it.copy(auditing = true, pendingAudit = null, progressDone = 0, progressTotal = 0, progress = "")
            }
        }
        Thread({
            try {
                run(wxId, resume)
            } catch (e: Exception) {
                WeLogger.e(TAG, "审查异常：$wxId", e)
                // 异常 = 中断，保留进度供续跑。
                finish(wxId, "审查失败：" + (e.message ?: e.javaClass.simpleName), interrupted = true)
            } finally {
                running.remove(wxId)
            }
        }, "wcx-ai-audit").start()
        return true
    }

    /**
     * 同步执行（后台线程调用）。返回 true 表示走完流程。
     *
     * @param resume 见 [start]。续跑时前 `progressDone` 片直接跳过，不重复请求。
     */
    fun run(wxId: String, resume: Boolean = false): Boolean {
        val profile = AiProfileStore.readProfile(wxId) ?: run {
            finish(wxId, "档案不存在")
            return false
        }
        // 少于两条没有可审查的东西，不浪费一次请求。
        if (profile.tags.size + profile.memories.size < 2) {
            finish(wxId, "内容太少，无需检查")
            return true
        }

        // 档位用审查开始时算的那一份：它由那一刻的条数决定，中途档案变大变小不改判。
        val guide = AiProfileLimits.reviewGuide(profile)
        val slices = auditSlices(AiMemoryIndex.numbered(profile.memories))
        val tagSlices = if (profile.tags.isNotEmpty()) 1 else 0
        val total = slices.size + tagSlices
        // 续跑只在片数对得上时生效：中断后如果先跑了一次增量整理，记忆变多、切片数也变了，
        // 按旧游标跳过会把没审过的片一起跳掉。审查本身不改档案，只有 apply 才执行。
        val resumeFrom = if (resume && profile.progressTotal == total) profile.progressDone else 0
        // `done` 既是「已处理片数」，也是下一片的游标：标签片占 0，记忆片依次往后。
        var done = resumeFrom.coerceIn(0, total)
        var skipped = 0
        var failures = 0
        markProgress(wxId, done, total)

        if (tagSlices == 1 && done == 0) {
            // 标签片失败也往后走：它的建议可以下次审查再提，卡在这里会挡掉后面所有记忆片。
            if (runTagSlice(wxId, profile, guide) != SliceResult.OK) skipped++
            done = 1
            markProgress(wxId, done, total)
        }

        slices.forEachIndexed { index, slice ->
            val position = index + tagSlices
            if (position < done) return@forEachIndexed
            markProgress(wxId, position, total)
            when (runMemorySlice(wxId, slice, index + 1, guide)) {
                SliceResult.OK -> failures = 0
                SliceResult.BAD_OUTPUT -> skipped++
                SliceResult.REQUEST_FAILED -> {
                    skipped++
                    if (++failures >= 3) {
                        finish(
                            wxId,
                            "审查中断：连续 3 片请求失败，可从第 ${position + 1} 片继续",
                            interrupted = true,
                        )
                        return false
                    }
                }
            }
            done = position + 1
            markProgress(wxId, done, total)
        }

        val plan = AiProfileStore.readProfile(wxId)?.pendingAudit
        finish(
            wxId,
            when {
                plan != null && !plan.isEmpty -> summaryOf(plan) + "（完成 $done/$total 片）"
                skipped > 0 -> "完成 $done/$total 片，$skipped 片没有结果，可再点一次"
                else -> "没有需要清理的"
            },
        )
        return true
    }

    // ── 切片 ────────────────────────────────────────────────────────────────

    /**
     * 按时间正序切，每片 ≤ [AiTools.AUDIT_SLICE_MAX] 条；同一天的条目**不跨片**。
     *
     * 切点落在一天中间时整体退到这一天第一条。退到 `start` 说明整片都是同一天（一天几百条的
     * 极端记录），照切 —— 不为边界无限回退，否则会死循环。
     *
     * 序号是全档序号（来自 [AiMemoryIndex.numbered]），`propose_audit` 按同一份列表解析。
     * 记忆列表是只读的、初始化被 [AiRunState] 拦住，审查期间这个序号集合不会变。
     */
    fun auditSlices(
        numbered: List<Pair<Int, MemoryEntry>>,
    ): List<List<Pair<Int, MemoryEntry>>> {
        if (numbered.isEmpty()) return emptyList()
        val out = mutableListOf<List<Pair<Int, MemoryEntry>>>()
        var start = 0
        while (start < numbered.size) {
            var end = minOf(start + AiTools.AUDIT_SLICE_MAX, numbered.size)
            if (end < numbered.size) {
                val cutDay = dayKey(numbered[end], end)
                var back = end
                while (back > start && dayKey(numbered[back - 1], back - 1) == cutDay) back--
                if (back > start) end = back
            }
            out += numbered.subList(start, end)
            start = end
        }
        return out
    }

    /** `yyyy-MM-dd`（只有月 / 年的取前 7 / 4 位）；未知日期返回带下标的唯一键。 */
    private fun dayKey(pair: Pair<Int, MemoryEntry>, index: Int): String {
        val normalized = AiDates.normalize(pair.second.occurredAt) ?: return "?$index"
        return when {
            normalized.length >= 10 -> normalized.take(10)
            normalized.length >= 7 -> normalized.take(7)
            else -> normalized.take(4)
        }
    }

    // ── 单片 ────────────────────────────────────────────────────────────────

    private fun runTagSlice(
        wxId: String,
        profile: AiProfile,
        guide: AiProfileLimits.Guide,
    ): SliceResult = callSlice(
        wxId = wxId,
        system = AiPrompts.auditTagSystem(guide),
        user = AiPrompts.auditTagUser(profile, guide),
        tools = AiTools.forAuditTags(),
        label = "audit-tag",
    )

    private fun runMemorySlice(
        wxId: String,
        slice: List<Pair<Int, MemoryEntry>>,
        index: Int,
        guide: AiProfileLimits.Guide,
    ): SliceResult = callSlice(
        wxId = wxId,
        system = AiPrompts.auditMemorySystem(guide),
        user = AiPrompts.auditMemoryUser(slice),
        tools = AiTools.forAuditMemories(),
        // 每片一个 dump 文件：统一叫 audit 会让 14 片互相覆盖，只剩最后一片。
        label = "audit-memory-$index",
    )

    /**
     * 一次请求，一次工具调用。**不跑 AiAgentLoop**：这一片的正文已经全给了，模型不需要
     * 再读第二轮，也不需要看工具返回值。
     */
    private fun callSlice(
        wxId: String,
        system: String,
        user: String,
        tools: JsonArray,
        label: String,
    ): SliceResult {
        val messages = listOf(
            AiConfig.ChatMessage(role = "system", content = system),
            AiConfig.ChatMessage(role = "user", content = user),
        )
        AiDebug.dumpPrompt(wxId, label, system, user, tools, AiConfig.isThinkingEnabled)
        // 判断「这条标签站不站得住」要稳定，不要发挥。
        // 思考跟随全局开关；输出够不够由配置里的「输出上限」保证（思考与工具调用抢同一份预算）。
        val result = AiConfig.chatWithTools(messages, tools, temperature = 0.2)
        if (result.error != null) {
            WeLogger.w(TAG, "$wxId $label 请求失败：${result.error}")
            return SliceResult.REQUEST_FAILED
        }
        // 一次响应里可能拆成多次 propose_audit（模型会把删除、合并分批提交，建议里就出现过
        // 单轮两次工具调用）。只执行第一个会让后半段建议静默消失，而这一片仍记为 OK。
        val calls = result.toolCalls.filter { it.name == AiTools.PROPOSE_AUDIT }
        if (calls.isEmpty()) return SliceResult.OK
        var failed = false
        calls.forEach { call ->
            val response = AiTools.execute(wxId, call)
            if (!AiTools.resultOk(response)) {
                WeLogger.w(TAG, "$wxId $label 建议没有落盘：${response.take(120)}")
                failed = true
            }
        }
        return if (failed) SliceResult.BAD_OUTPUT else SliceResult.OK
    }

    // ── 进度 ────────────────────────────────────────────────────────────────

    /**
     * 进度复用档案现成的三个字段，不新增：列表页副标题在 `auditing` 时直接显示它。
     *
     * 每片写一次（加上 [AiTools] 落盘提案的一次，共两次）。2000 条档案约 1MB，
     * 单次写入约 60ms，28 次约 1.7 秒，摊在 7 分钟里可忽略。
     */
    private fun markProgress(wxId: String, done: Int, total: Int) {
        AiProfileStore.patchProfile(wxId) {
            it.copy(
                progress = if (total <= 0 || done >= total) "审查完成" else "审查 ${done + 1}/$total",
                progressDone = done,
                progressTotal = total,
            )
        }
    }

    // ── 执行 ────────────────────────────────────────────────────────────────

    /**
     * 执行用户勾选后的提案（纯代码，不请求模型）。
     *
     * 顺序：**合并标签 → 删标签 → 合并记忆 → 删记忆 → 标记 → 裁尾**。
     *
     * 所有引用都按「日期 + 标题」或「分类 + 键」重新解析：提案生成后用户可能手动改过档案，
     * 找不到的条目直接跳过，不会误删同名之外的东西。
     *
     * **合并先于删除**：同一批建议里「删 A」与「合并 A、B」并存时，合并保留了内容、删除会丢内容，
     * 所以让合并先吃掉 A；随后的删除找不到 A 就跳过。反过来会让合并组不足 2 条被静默跳过，
     * 用户勾了却没执行。
     *
     * 读改写走 [AiProfileStore.patchProfile]，在同一个锁里完成。
     */
    fun apply(wxId: String, plan: AiAuditPlan): ProfileChange? {
        var note = ""
        var applied: ProfileChange? = null
        var droppedTagGroups = 0
        val saved = AiProfileStore.patchProfile(wxId) { profile ->
            val now = System.currentTimeMillis()

            // ── 1. 合并标签 ──
            // 目标键已存在时也算进被合并的一方，否则它的值会被静默覆盖。
            var tags = profile.tags
            var mergedTagGroups = 0
            plan.mergeTags.forEach { group ->
                val category = group.category.trim()
                val key = group.key.trim()
                if (category.isEmpty() || key.isEmpty()) {
                    droppedTagGroups++
                    return@forEach
                }
                val refs = TagRef.ids(group.keys)
                val mergedId = TagRef.id(category, key)
                val targets = tags.filter {
                    TagRef.id(it.category, it.key) == mergedId || TagRef.id(it.category, it.key) in refs
                }
                if (targets.size < 2) {
                    droppedTagGroups++
                    return@forEach
                }
                tags = tags.filterNot {
                    TagRef.id(it.category, it.key) == mergedId || TagRef.id(it.category, it.key) in refs
                }
                val latest = targets.maxByOrNull { it.updatedAt } ?: targets.first()
                tags = tags + ProfileTag(
                    category = category,
                    key = key,
                    // 旧档案的 value 可能超过 40 字，回退时也要截，否则同一条上限管不住两条路径。
                    value = group.value.trim().take(AiTools.VALUE_MAX)
                        .ifBlank { latest.value.take(AiTools.VALUE_MAX) },
                    confidence = targets.maxOf { it.confidence },
                    // 合并多条没法保留全部原话，但至少留一条真实的 —— 档案页的「原话」
                    // 显示「合并自 5 条」等于没有依据。
                    evidence = targets.firstNotNullOfOrNull {
                        it.evidence.takeIf { value -> value.isNotBlank() }
                    } ?: "合并自 ${targets.size} 条",
                    updatedAt = targets.maxOf { it.updatedAt },
                )
                mergedTagGroups++
            }

            // ── 2. 删标签 ──
            // 提案里的分类 / 键与档案里的同一条理论上逐字相同（都是从这份档案里取的），
            // 但实际出现过三次「请求 6 条、命中 0 条」的静默失败（11-18 22:54 / 23:54、
            // 11-19 00:03），档案里那些标签明明都还在。两边都按 trim 归一后再比，
            // 并加一层「键名全档案唯一时按 key 兜底」；仍未全部命中就打一条诊断，
            // 把请求的键与档案里实际的键一起写下来 —— 不猜原因，等日志。
            //
            // `requested` 剔掉刚合并出来的目标键：否则合并结果会刚写进去就被删掉。
            // `requestedKeys` 也要剔：下面是「键名全档案唯一时按 key 兜底」，合并后目标键
            // 恰好是唯一的，不剔就会走那条兜底把刚合并的结果删掉。
            val mergedKeys = plan.mergeTags.mapTo(HashSet()) { it.key.trim() }
            val requested = plan.deleteTags
                .mapTo(HashSet()) { TagRef.id(it.category, it.key) } -
                plan.mergeTags.mapTo(HashSet()) { TagRef.id(it.category, it.key) }
            val requestedKeys = plan.deleteTags.mapTo(HashSet()) { it.key.trim() } - mergedKeys
            // keyCount 用合并后的 tags 重算：按旧键数判断，「键名全档案唯一时按 key 兜底」
            // 在合并后已经不唯一，会用一条错判把别的键删掉。
            val keyCount = tags.groupingBy { it.key.trim() }.eachCount()
            var removedTags = 0
            val keptTags = tags.filterNot { tag ->
                val hit = requested.contains(TagRef.id(tag.category, tag.key)) ||
                    (
                        keyCount[tag.key.trim()] == 1 &&
                            requestedKeys.contains(tag.key.trim())
                        )
                if (hit) removedTags++
                hit
            }
            if (removedTags < plan.deleteTags.size) {
                // 十六进制：肉眼看着一样的字符串，可能是全角/半角、零宽字符或 BOM。
                fun hex(text: String) = text.map { it.code.toString(16) }.joinToString(" ")
                val probe = plan.deleteTags.firstOrNull()?.let { ref ->
                    val same = profile.tags.firstOrNull { it.key.trim() == ref.key.trim() }
                    if (same == null) "首个请求 ${ref.category}/${ref.key}，档案里没有同名键"
                    else "首个请求 ${ref.category}/${ref.key}；档案 ${same.category}/${same.key}；" +
                        "分类十六进制 [${hex(ref.category)}] vs [${hex(same.category)}]"
                }.orEmpty()
                WeLogger.w(
                    TAG,
                    "标签未命中 ${removedTags}/${plan.deleteTags.size}：" +
                        "请求[" +
                        plan.deleteTags.take(6).joinToString(" / ") { it.category + "/" + it.key } + "] " +
                        "档案共 ${profile.tags.size} 个，样例[" +
                        profile.tags.take(4).joinToString(" / ") { it.category + "/" + it.key } + "] $probe",
                )
            }

            // ── 3. 记忆 ──
            val memories = LinkedHashMap<String, MemoryEntry>()
            profile.memories.forEach { memories[it.id] = it }

            fun find(ref: AuditMemoryRef): MemoryEntry? {
                val occurred = AiDates.normalize(ref.occurredAt) ?: ref.occurredAt
                memories[AiTools.memoryId(occurred, ref.title)]?.let { return it }
                return memories.values.firstOrNull { it.title == ref.title }
            }

            var mergedGroups = 0
            var mergedItems = 0
            plan.mergeGroups.forEach { group ->
                val targets = group.items.mapNotNull { find(it) }.distinctBy { it.id }
                if (targets.size < 2) return@forEach
                targets.forEach { if (memories.remove(it.id) != null) mergedItems++ }

                val occurredAt = AiDates.normalize(group.occurredAt)
                    ?: targets.minByOrNull { AiDates.sortKey(it.occurredAt) }?.occurredAt
                    ?: AiDates.UNKNOWN
                val title = group.title.trim().ifBlank { "日常闲聊" }.take(AiTools.TITLE_MAX)
                // 模型写了 detail 就用它（「重要也合并」档唯一能留下具体经过的途径）；
                // 不写时回退成各条标题拼接，保证结果一定忠实于原条目。
                val detail = group.detail.trim().take(AiTools.DETAIL_MAX)
                    .ifBlank { targets.joinToString("；") { it.title }.take(AiTools.DETAIL_MAX) }
                val entry = MemoryEntry(
                    id = AiTools.memoryId(occurredAt, title),
                    occurredAt = occurredAt,
                    title = title,
                    detail = detail,
                    createdAt = now,
                    // 组内有一条是关键事件，合并结果就还是关键事件：模型可能把「第一次见面」
                    // 混进「日常闲聊」组里，写死 false 会让这个标记在合并时静默消失。
                    important = targets.any { it.important },
                )
                memories[entry.id] = entry
                mergedGroups++
            }

            var removedMemories = 0
            plan.deleteMemories.forEach { ref ->
                val target = find(ref) ?: return@forEach
                if (memories.remove(target.id) != null) removedMemories++
            }

            var marked = 0
            plan.markImportant.forEach { ref ->
                val target = find(ref) ?: return@forEach
                if (!target.important) {
                    memories[target.id] = target.copy(important = true)
                    marked++
                }
            }

            // ── 4. 裁尾 ──
            // 与 update_profile 同一套上限（同一个 `AiProfileLimits`，不各写一份）。
            val tagList = AiProfileLimits.tags(keptTags)
            val memoryList = AiProfileLimits.memories(memories.values)

            note = buildString {
                if (mergedTagGroups > 0) append("合并 $mergedTagGroups 组标签")
                if (removedTags > 0) {
                    if (isNotEmpty()) append("、")
                    append("清理 $removedTags 个标签")
                }
                // 只数「真正丢弃的」；合并吃掉的条目算在合并里，否则数字会翻倍。
                if (removedMemories > 0) {
                    if (isNotEmpty()) append("、")
                    append("$removedMemories 条事件")
                }
                if (mergedGroups > 0) {
                    if (isNotEmpty()) append("、")
                    append("合并 $mergedGroups 组（$mergedItems 条）")
                }
                if (marked > 0) {
                    if (isNotEmpty()) append("、")
                    append("标记 $marked 条关键")
                }
            }.ifBlank { "没有执行任何修改" }

            val change = ProfileChange(
                at = now,
                source = "审查",
                tagsMerged = mergedTagGroups,
                tagsRemoved = removedTags,
                memoriesRemoved = removedMemories,
                memoriesMerged = mergedGroups,
                memoriesMergedItems = mergedItems,
                memoriesMarked = marked,
            )
            applied = change

            profile.copy(
                tags = tagList,
                memories = memoryList,
                pendingAudit = null,
                auditing = false,
                progress = "",
                progressDone = 0,
                progressTotal = 0,
                // 审查的变动是「代码执行了多少条」，直接取实际命中数，不重新做前后比对：
                // 合并会先把 N 条并成 1 条，比对照样能算出来，但会把「合并」显示成「删 N 条 + 加 1 条」。
                changes = profile.changes.recorded(change),
            )
        }
        // 带上「请求了多少」：只写结果时，「提案是空的」与「条目全都解析不到」在日志里长得一样，
        // 排查时只能猜（22:56 那次就出现过「提案有 6 个标签、执行结果却是没有修改」）。
        WeLogger.i(
            TAG,
            "执行审查建议：$wxId：请求 合并标签 ${plan.mergeTags.size} / 删标签 ${plan.deleteTags.size} / " +
                "事件 ${plan.deleteMemories.size} / 组 ${plan.mergeGroups.size} / " +
                "标记 ${plan.markImportant.size} → $note saved=${saved != null}" +
                if (droppedTagGroups > 0) "（$droppedTagGroups 组标签不足 2 条跳过）" else "",
        )
        // 返回「刚刚执行了什么」，供弹窗当场显示一句结果；档案里同时已记进「变动记录」。
        return if (saved != null) applied else null
    }

    /** 丢弃提案（用户点「全部忽略」）。 */
    fun discard(wxId: String) {
        AiProfileStore.patchProfile(wxId) {
            it.copy(
                pendingAudit = null,
                auditing = false,
                progress = "",
                progressDone = 0,
                progressTotal = 0,
            )
        }
    }

    private fun summaryOf(plan: AiAuditPlan): String = buildString {
        if (plan.mergeTags.isNotEmpty()) append("合并 ${plan.mergeTags.size} 组标签 / ")
        if (plan.deleteTags.isNotEmpty()) append("删 ${plan.deleteTags.size} 标签 / ")
        if (plan.deleteMemories.isNotEmpty()) append("删 ${plan.deleteMemories.size} 事件 / ")
        if (plan.mergeGroups.isNotEmpty()) append("合并 ${plan.mergeGroups.size} 组 / ")
        if (plan.markImportant.isNotEmpty()) append("标 ${plan.markImportant.size} 关键 / ")
        append("待确认")
    }.take(40)

    /**
     * @param interrupted 中断（连续失败 / 异常 / 进程被杀）：**保留 `progressDone` 与
     *   `progressTotal`**，界面据此显示「从第 N 片继续」；正常完成才清零。
     */
    private fun finish(wxId: String, note: String, interrupted: Boolean = false) {
        AiProfileStore.patchProfile(wxId) {
            if (interrupted) {
                it.copy(auditing = false, progress = "")
            } else {
                it.copy(auditing = false, progress = "", progressDone = 0, progressTotal = 0)
            }
        }
        WeLogger.i(TAG, "审查结束：$wxId：$note")
    }
}
