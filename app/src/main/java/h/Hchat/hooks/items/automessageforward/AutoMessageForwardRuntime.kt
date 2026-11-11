package h.Hchat.hooks.items.automessageforward

import android.content.Context
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.media.WeChatImageApi
import h.Hchat.hooks.api.media.WeChatVideoApi
import h.Hchat.hooks.api.media.VoiceMessageDurationResolver
import h.Hchat.hooks.api.message.WeChatMessageObserveApi
import h.Hchat.hooks.api.message.WeChatRetransmitPayloadFactory
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.api.model.WeChatMessageTypes
import h.Hchat.utils.KeywordReplacementRules
import java.io.File
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class AutoMessageForwardRuntime(
    context: Context,
    private val logger: (String, Throwable?) -> Unit
) {
    private val appContext = context.applicationContext ?: context
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "Hchat-AutoMessageForward").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
    }
    private val recentMessages = LinkedHashMap<String, Long>()
    private val pendingKeys = ConcurrentHashMap.newKeySet<String>()
    private val pendingForwards = ConcurrentHashMap<String, PendingForward>()
    private val protectedMediaPaths = ConcurrentHashMap.newKeySet<String>()
    private val moduleOutgoing = ConcurrentLinkedDeque<ModuleOutgoing>()
    private val recallRecords = ArrayList<AutoMessageForwardRecallRecord>()
    private val recentRecallKeys = LinkedHashMap<String, Long>()
    private val recallRevocationsInFlight = linkedSetOf<String>()
    private var recallAccount = ""
    private var nextSendAllowedAt = 0L

    init {
        ensureRecallAccount()
        scheduleMediaCacheMaintenance(0L)
    }

    fun handleMessage(observed: WeChatMessageObserveApi.ObservedMessage) {
        if (observed.talker.isBlank()) return
        if (executor.isShutdown) return
        if (observed.isRecalled()) {
            schedule(0L) { acceptObservedRecall(observed) }
            return
        }
        val delayMillis = if (observed.outgoing) MODULE_OUTGOING_SETTLE_DELAY_MS else 0L
        runCatching {
            executor.schedule(Runnable { acceptMessage(observed) }, delayMillis, TimeUnit.MILLISECONDS)
        }
    }

    fun handleRecall(event: Events.MessageRecalled) {
        if (event.talker.isBlank() || executor.isShutdown) return
        schedule(0L) { acceptRecall(event) }
    }

    fun shutdown() {
        executor.shutdownNow()
        synchronized(recentMessages) { recentMessages.clear() }
        pendingKeys.clear()
        pendingForwards.clear()
        moduleOutgoing.clear()
        recallRecords.clear()
        recentRecallKeys.clear()
        recallRevocationsInFlight.clear()
    }

    private fun acceptMessage(observed: WeChatMessageObserveApi.ObservedMessage) {
        if (observed.outgoing && consumeModuleOutgoing(observed)) return
        if (!AutoMessageForwardSettings.isEnabled(appContext)) return
        if (!isSupportedKind(observed)) return
        val searchable = buildSearchableContent(observed)
        val sourceSender = observed.sender.ifBlank { observed.getSendTalker() }.trim()
        val acceptedAt = System.currentTimeMillis()
        val targetRules = linkedMapOf<String, MutableMap<String, Long>>()
        AutoMessageForwardSettings.rules(appContext).forEach { rule ->
            if (!ruleMatches(
                    rule,
                    observed.talker,
                    observed.kind,
                    observed.message.type,
                    searchable,
                    observed.outgoing,
                    sourceSender
                )
            ) {
                return@forEach
            }
            rule.targetIds.forEach { target ->
                if (target.isNotBlank() && target != observed.talker) {
                    val delayMillis = if (rule.delayEnabled) {
                        TimeUnit.SECONDS.toMillis(rule.delaySeconds.coerceAtLeast(0L))
                    } else {
                        0L
                    }
                    targetRules.getOrPut(target) { linkedMapOf() }[rule.id] =
                        saturatingAdd(acceptedAt, delayMillis)
                }
            }
        }
        val targets = targetRules.keys.sortedBy { target ->
            targetRules[target]?.values?.minOrNull() ?: Long.MAX_VALUE
        }
        if (targets.isEmpty()) return

        val key = messageKey(observed)
        if (!rememberMessage(messageIdentityKeys(observed))) return
        if (!pendingKeys.add(key)) return
        if (pendingKeys.size > MAX_PENDING_MESSAGES) {
            pendingKeys.remove(key)
            return
        }
        val source = observed.message
        val pending = PendingForward(
            key = key,
            talker = observed.talker,
            msgSvrId = source.msgSvrId,
            fallback = source,
            targets = targets,
            targetRules = targetRules.mapValues { it.value.toMap() },
            kind = observed.kind,
            messageType = source.type,
            searchableContent = searchable,
            outgoing = observed.outgoing,
            sourceSender = sourceSender,
            deadline = acceptedAt + MEDIA_WAIT_TIMEOUT_MS,
            cacheToken = UUID.randomUUID().toString().replace("-", "")
        )
        pendingForwards[key] = pending
        if (cancelRecentlyRecalledTargets(pending)) return
        if (!schedule(INITIAL_DELAY_MS) { prepareAndSend(pending) }) {
            finishPending(pending)
        }
    }

    private fun prepareAndSend(pending: PendingForward) {
        if (pending.key !in pendingKeys || !AutoMessageForwardSettings.isEnabled(appContext)) {
            finishPending(pending)
            return
        }
        if (cancelRecentlyRecalledTargets(pending)) return
        try {
            val message = resolveStoredMessage(pending) ?: pending.fallback
            when (val preparation = prepare(message, pending)) {
                is Preparation.Ready -> sendNext(pending, preparation.plan, 0)
                Preparation.Waiting -> {
                    if (System.currentTimeMillis() < pending.deadline) {
                        if (!schedule(MEDIA_RETRY_INTERVAL_MS) { prepareAndSend(pending) }) {
                            finishPending(pending)
                        }
                    } else {
                        finishPending(pending)
                        logger("等待消息媒体文件超时: talker=${pending.talker} msgSvrId=${pending.msgSvrId}", null)
                    }
                }
                Preparation.Downloading -> Unit
                is Preparation.Failed -> {
                    finishPending(pending)
                    logger(
                        "消息媒体准备失败: talker=${pending.talker} msgSvrId=${pending.msgSvrId} " +
                            "reason=${preparation.reason}",
                        null
                    )
                }
                Preparation.Unsupported -> {
                    finishPending(pending)
                    logger(
                        "消息类型暂不支持静默转发: talker=${pending.talker} type=${message.type}",
                        null
                    )
                }
            }
        } catch (error: Throwable) {
            finishPending(pending)
            logger("准备转发消息异常: talker=${pending.talker} msgSvrId=${pending.msgSvrId}", error)
        }
    }

    private fun sendNext(pending: PendingForward, plan: ForwardPlan, index: Int) {
        runCatching { sendNextInternal(pending, plan, index) }
            .onFailure { error ->
                finishPending(pending)
                logger(
                    "执行转发任务异常: talker=${pending.talker} msgSvrId=${pending.msgSvrId}",
                    error
                )
            }
    }

    private fun sendNextInternal(pending: PendingForward, plan: ForwardPlan, index: Int) {
        if (pending.key !in pendingKeys || !AutoMessageForwardSettings.isEnabled(appContext)) {
            finishPending(pending)
            return
        }
        if (cancelRecentlyRecalledTargets(pending)) return
        var targetIndex = index
        var dueAt: Long? = null
        while (targetIndex < pending.targets.size) {
            dueAt = targetDueAt(pending, pending.targets[targetIndex])
            if (dueAt != null) break
            targetIndex++
        }
        if (targetIndex >= pending.targets.size) {
            finishPending(pending)
            return
        }
        val target = pending.targets[targetIndex]
        val now = System.currentTimeMillis()
        val waitMillis = (maxOf(nextSendAllowedAt, dueAt ?: now) - now).coerceAtLeast(0L)
        if (waitMillis > 0L) {
            if (!schedule(waitMillis) { sendNext(pending, plan, targetIndex) }) {
                finishPending(pending)
            }
            return
        }
        val followSource = followSourceForTarget(pending, target)
        val targetPlan = planForTarget(pending, target, plan)
        if (targetPlan == null) {
            logger("关键词替换后文字为空，已跳过: target=$target", null)
        } else {
            runCatching { sendWithSuppression(targetPlan, target, followSource) }
                .onFailure { logger("转发消息异常: target=$target type=${targetPlan.type}", it) }
                .onSuccess { success ->
                    if (!success) logger("转发消息失败: target=$target type=${targetPlan.type}", null)
                }
        }
        nextSendAllowedAt = System.currentTimeMillis() + TARGET_SEND_INTERVAL_MS
        if (targetIndex + 1 < pending.targets.size) {
            if (!schedule(TARGET_SEND_INTERVAL_MS) { sendNext(pending, plan, targetIndex + 1) }) {
                finishPending(pending)
            }
        } else {
            finishPending(pending)
        }
    }

    private fun schedule(delayMillis: Long, action: () -> Unit): Boolean {
        if (executor.isShutdown) return false
        return runCatching {
            executor.schedule(Runnable { action() }, delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            true
        }.getOrDefault(false)
    }

    private fun isSupportedKind(observed: WeChatMessageObserveApi.ObservedMessage): Boolean {
        return observed.kind in AutoMessageForwardSettings.supportedKinds ||
            (WeChatMessageTypes.normalize(observed.message.type) == VIDEO_NUMBER_TYPE &&
                WeChatMessageObserveApi.Kind.VIDEO_NUMBER_VIDEO in AutoMessageForwardSettings.supportedKinds)
    }

    private fun ruleMatches(
        rule: AutoMessageForwardRule,
        talker: String,
        kind: String,
        messageType: Int,
        searchable: String,
        outgoing: Boolean,
        sourceSender: String
    ): Boolean {
        if (!rule.enabled || talker !in rule.sourceIds) return false
        if (outgoing && !rule.forwardOwnMessages) return false
        if (rule.sourceMemberIds.isNotEmpty()) {
            if (!WeChatMessage.isGroupTalker(talker) || sourceSender.isBlank()) return false
            if ("$talker/$sourceSender" !in rule.sourceMemberIds) return false
        }
        if (!kindMatches(rule.messageKinds, kind, messageType)) return false
        return matchesKeywords(rule, searchable)
    }

    private fun kindMatches(
        selectedKinds: Set<String>,
        kind: String,
        messageType: Int
    ): Boolean {
        if (kind in selectedKinds) return true
        return WeChatMessageTypes.normalize(messageType) == VIDEO_NUMBER_TYPE &&
            WeChatMessageObserveApi.Kind.VIDEO_NUMBER_VIDEO in selectedKinds
    }

    private fun targetDueAt(pending: PendingForward, target: String): Long? {
        if (target in pending.canceledTargets) return null
        val dueByRule = pending.targetRules[target].orEmpty()
        if (dueByRule.isEmpty() || !AutoMessageForwardSettings.isEnabled(appContext)) return null
        return activeRulesForTarget(pending, target)
            .asSequence()
            .mapNotNull { dueByRule[it.id] }
            .minOrNull()
    }

    private fun activeRulesForTarget(
        pending: PendingForward,
        target: String
    ): List<AutoMessageForwardRule> {
        val dueByRule = pending.targetRules[target].orEmpty()
        if (dueByRule.isEmpty()) return emptyList()
        return AutoMessageForwardSettings.rules(appContext).filter { rule ->
            rule.id in dueByRule && target in rule.targetIds && ruleMatches(
                rule,
                pending.talker,
                pending.kind,
                pending.messageType,
                pending.searchableContent,
                pending.outgoing,
                pending.sourceSender
            )
        }
    }

    private fun planForTarget(
        pending: PendingForward,
        target: String,
        plan: ForwardPlan
    ): ForwardPlan? {
        if (WeChatMessageTypes.normalize(plan.type) != WeChatMessageTypes.TEXT) return plan
        val dueByRule = pending.targetRules[target].orEmpty()
        val rule = activeRulesForTarget(pending, target)
            .minByOrNull { dueByRule[it.id] ?: Long.MAX_VALUE }
            ?.takeIf { it.replaceKeywordsEnabled && it.keywordReplacements.isNotEmpty() }
            ?: return plan
        val content = KeywordReplacementRules.apply(plan.content, rule.keywordReplacements)
        return plan.copy(content = content).takeIf { content.isNotBlank() }
    }

    private fun followSourceForTarget(pending: PendingForward, target: String): FollowSource? {
        val ruleIds = activeRulesForTarget(pending, target)
            .filter { it.followSourceRecall }
            .mapTo(linkedSetOf()) { it.id }
        if (ruleIds.isEmpty()) return null
        val source = resolveStoredMessage(pending) ?: pending.fallback
        val sourceMsgId = source.msgId.takeIf { it > 0L } ?: pending.fallback.msgId
        val sourceMsgSvrId = source.msgSvrId.takeIf { it > 0L } ?: pending.msgSvrId
        if (sourceMsgId <= 0L && sourceMsgSvrId <= 0L) return null
        return FollowSource(
            talker = pending.talker,
            msgId = sourceMsgId,
            msgSvrId = sourceMsgSvrId,
            ruleIds = ruleIds
        )
    }

    private fun resolveStoredMessage(pending: PendingForward): WeChatMessage? {
        val store = WeChatApis.messageStore() ?: return null
        return when {
            pending.msgSvrId > 0L -> store.getMessageBySvrId(pending.talker, pending.msgSvrId)
                ?: store.getMessageBySvrId(pending.msgSvrId)
            pending.fallback.msgId > 0L -> store.getMessageById(pending.fallback.msgId)
            else -> store.getRecentMessages(pending.talker, SOURCE_MESSAGE_QUERY_LIMIT)
                .firstOrNull { message ->
                    WeChatMessageTypes.normalize(message.type) ==
                        WeChatMessageTypes.normalize(pending.messageType) &&
                        message.isOutgoing() == pending.outgoing &&
                        sequenceOf(message.content, message.bodyContent())
                            .any { it.isNotBlank() && it == pending.fallback.bodyContent() } &&
                        kotlin.math.abs(
                            messageTimeMillis(message.createTime) -
                                messageTimeMillis(pending.fallback.createTime)
                        ) <= SOURCE_MESSAGE_CLOCK_SLOP_MS
                }
        }
    }

    private fun prepare(message: WeChatMessage, pending: PendingForward): Preparation {
        val type = WeChatMessageTypes.normalize(message.type)
        val nativeMessage = message.msgId.takeIf { it > 0L }
            ?.let { runCatching { WeChatApis.database()?.nativeMessageById(it) }.getOrNull() }

        if (message.isVoice()) {
            val fileName = voiceFileName(message)
            val path = resolveVoicePath(fileName)
            if (path.isBlank()) return Preparation.Waiting
            val duration = VoiceMessageDurationResolver.resolve(
                nativeMessage,
                fileName,
                message.msgId,
                listOf(message.content, message.bodyContent()),
                DEFAULT_VOICE_DURATION_MS
            )
            return Preparation.Ready(ForwardPlan(type, path = path, durationMillis = duration))
        }

        val payload = WeChatRetransmitPayloadFactory.build(message, nativeMessage)
        return when {
            message.isText() -> payload?.content.orEmpty().takeIf { it.isNotBlank() }
                ?.let { Preparation.Ready(ForwardPlan(type, content = it)) }
                ?: Preparation.Unsupported
            message.isImage() -> prepareImage(message, nativeMessage, payload?.fileName.orEmpty(), pending)
            message.isVideo() -> prepareVideo(message, payload?.fileName.orEmpty(), pending, type)
            type == 62 -> existingPath(payload?.fileName, message.imagePath)
                .takeIf { it.isNotBlank() }
                ?.let { Preparation.Ready(ForwardPlan(type, path = it)) }
                ?: Preparation.Waiting
            message.isEmoji() -> emojiSource(message, payload?.fileName.orEmpty())
                .takeIf { it.isNotBlank() }
                ?.let { Preparation.Ready(ForwardPlan(type, path = it)) }
                ?: Preparation.Waiting
            message.isFile() -> existingPath(payload?.fileName, message.imagePath)
                .takeIf { it.isNotBlank() }
                ?.let {
                    Preparation.Ready(
                        ForwardPlan(type, path = it, title = message.getFileMsg()?.title.orEmpty())
                    )
                }
                ?: Preparation.Waiting
            message.isShareCard() || message.isLocation() -> payload?.content.orEmpty()
                .takeIf { it.isNotBlank() }
                ?.let { Preparation.Ready(ForwardPlan(type, content = it)) }
                ?: Preparation.Unsupported
            WeChatRetransmitPayloadFactory.isRetransmittableAppMessage(message) -> payload?.content.orEmpty()
                .takeIf { it.isNotBlank() }
                ?.let { Preparation.Ready(ForwardPlan(type, content = it)) }
                ?: Preparation.Unsupported
            else -> Preparation.Unsupported
        }
    }

    private fun send(plan: ForwardPlan, target: String): Boolean {
        val sender = WeChatApis.message().sender() ?: return false
        val media = WeChatApis.media()
        return when (plan.type) {
            WeChatMessageTypes.TEXT -> sender.sendText(target, plan.content)
            WeChatMessageTypes.IMAGE -> media?.images()?.sendOriginal(target, plan.path) == true
            WeChatMessageTypes.VOICE -> media?.voices()?.send(target, plan.path, plan.durationMillis) == true
            WeChatMessageTypes.VIDEO, 62 -> media?.videos()?.send(target, plan.path) == true
            WeChatMessageTypes.EMOJI -> media?.emojis()?.send(target, plan.path) == true
            42, 66 -> sender.sendRaw(target, plan.content, plan.type)
            WeChatMessageTypes.LOCATION -> sender.sendRaw(target, plan.content, WeChatMessageTypes.LOCATION)
            WeChatMessageTypes.APP -> if (plan.path.isNotBlank()) {
                media?.files()?.send(target, plan.path, plan.title) == true
            } else {
                sender.sendXml(target, plan.content)
            }
            else -> false
        }
    }

    private fun sendWithSuppression(
        plan: ForwardPlan,
        target: String,
        followSource: FollowSource?
    ): Boolean {
        val token = registerModuleOutgoing(target, plan, followSource)
        return try {
            send(plan, target).also { success ->
                if (success) {
                    refreshModuleOutgoing(token)
                    if (followSource != null) scheduleFollowRecallCapture(token, 0)
                } else {
                    moduleOutgoing.remove(token)
                }
            }
        } catch (error: Throwable) {
            moduleOutgoing.remove(token)
            throw error
        }
    }

    private fun registerModuleOutgoing(
        talker: String,
        plan: ForwardPlan,
        followSource: FollowSource?
    ): ModuleOutgoing {
        cleanupModuleOutgoing()
        while (moduleOutgoing.size >= MAX_MODULE_OUTGOING) moduleOutgoing.pollFirst()
        val messageType = if (plan.type == VIDEO_NUMBER_TYPE) {
            WeChatMessageTypes.VIDEO
        } else {
            WeChatMessageTypes.normalize(plan.type)
        }
        return ModuleOutgoing(
            talker = talker,
            messageType = messageType,
            content = plan.content,
            followSource = followSource,
            baselineMessageIds = recentOutgoingMessages(talker, messageType)
                .mapNotNullTo(linkedSetOf()) { it.msgId.takeIf { id -> id > 0L } },
            createdAt = System.currentTimeMillis()
        ).also(moduleOutgoing::addLast)
    }

    private fun consumeModuleOutgoing(observed: WeChatMessageObserveApi.ObservedMessage): Boolean {
        val now = System.currentTimeMillis()
        val messageType = WeChatMessageTypes.normalize(observed.message.type)
        for (candidate in moduleOutgoing) {
            if (now - candidate.createdAt > MODULE_OUTGOING_TTL_MS) {
                moduleOutgoing.remove(candidate)
                continue
            }
            if (candidate.talker == observed.talker && candidate.messageType == messageType) {
                refreshModuleOutgoing(candidate)
                val observedMessage = observed.storedMessage ?: observed.message
                val storedBySvrId = observedMessage.msgSvrId.takeIf { it > 0L }
                    ?.let { WeChatApis.messageStore()?.getMessageBySvrId(observed.talker, it) }
                if (candidate.messageIds.isEmpty() && candidate.messageSvrIds.isEmpty()) {
                    sequenceOf(observedMessage, storedBySvrId)
                        .filterNotNull()
                        .firstOrNull { matchesModuleOutgoingCandidate(candidate, it, now) }
                        ?.let { captureModuleOutgoingIdentity(candidate, it) }
                }
                val matchesMessageId = sequenceOf(observedMessage, storedBySvrId)
                    .filterNotNull()
                    .any { message ->
                        (message.msgId > 0L && message.msgId in candidate.messageIds) ||
                            (message.msgSvrId > 0L && message.msgSvrId in candidate.messageSvrIds)
                    }
                val matchesContentFallback = observed.source == "local_send" &&
                    !candidate.localFallbackConsumed &&
                    candidate.content.isNotBlank() &&
                    sequenceOf(observed.content, observedMessage.bodyContent())
                        .any { it == candidate.content }
                if (matchesMessageId || matchesContentFallback) {
                    sequenceOf(observedMessage, storedBySvrId)
                        .filterNotNull()
                        .forEach { captureModuleOutgoingIdentity(candidate, it) }
                    if (matchesContentFallback) candidate.localFallbackConsumed = true
                    rememberMessage(messageIdentityKeys(observed))
                    return true
                }
            }
        }
        return false
    }

    private fun refreshModuleOutgoing(candidate: ModuleOutgoing) {
        val store = WeChatApis.messageStore() ?: return
        if (candidate.messageIds.isNotEmpty() || candidate.messageSvrIds.isNotEmpty()) {
            sequenceOf(
                candidate.messageIds.toList().asSequence().mapNotNull { store.getMessageById(it) },
                candidate.messageSvrIds.toList().asSequence().mapNotNull {
                    store.getMessageBySvrId(candidate.talker, it)
                }
            ).flatten()
                .forEach { captureModuleOutgoingIdentity(candidate, it) }
            return
        }
        val candidates = recentOutgoingMessages(candidate.talker, candidate.messageType)
            .asSequence()
            .filter { message ->
                matchesModuleOutgoingCandidate(candidate, message, System.currentTimeMillis())
            }
            .take(2)
            .toList()
        if (candidates.size == 1) {
            captureModuleOutgoingIdentity(candidate, candidates.single())
        } else if (candidates.size > 1) {
            candidate.identityAmbiguous = true
        }
    }

    private fun matchesModuleOutgoingCandidate(
        candidate: ModuleOutgoing,
        message: WeChatMessage,
        now: Long
    ): Boolean {
        if (!message.isOutgoing()) return false
        if (WeChatMessageTypes.normalize(message.type) != candidate.messageType) return false
        if (message.msgId > 0L && message.msgId in candidate.baselineMessageIds) return false
        val messageTime = messageTimeMillis(message.createTime)
        if (messageTime < candidate.createdAt - MODULE_OUTGOING_CLOCK_SLOP_MS ||
            messageTime > now + MODULE_OUTGOING_CLOCK_SLOP_MS
        ) {
            return false
        }
        return candidate.content.isBlank() || sequenceOf(message.content, message.bodyContent())
            .any { it == candidate.content }
    }

    private fun captureModuleOutgoingIdentity(candidate: ModuleOutgoing, message: WeChatMessage) {
        if (message.msgId > 0L) candidate.messageIds.add(message.msgId)
        if (message.msgSvrId > 0L) candidate.messageSvrIds.add(message.msgSvrId)
    }

    private fun recentOutgoingMessages(talker: String, messageType: Int): List<WeChatMessage> {
        return WeChatApis.messageStore()?.getRecentMessages(talker, MODULE_OUTGOING_QUERY_LIMIT)
            .orEmpty()
            .filter { message ->
                message.isOutgoing() && WeChatMessageTypes.normalize(message.type) == messageType
            }
    }

    private fun messageTimeMillis(value: Long): Long {
        return if (value in 1L until 100_000_000_000L) value * 1000L else value
    }

    private fun cleanupModuleOutgoing() {
        val cutoff = System.currentTimeMillis() - MODULE_OUTGOING_TTL_MS
        moduleOutgoing.removeIf { it.createdAt < cutoff }
    }

    private fun acceptRecall(event: Events.MessageRecalled, accountAttempt: Int = 0) {
        if (!AutoMessageForwardSettings.isEnabled(appContext)) return
        val sourceMsgId = event.sourceMsgId.takeIf { it > 0L } ?: 0L
        val sourceSvrIds = (event.lookupSvrIds + event.sourceMsgSvrId)
            .filterTo(linkedSetOf()) { it > 0L }
        if (sourceMsgId <= 0L && sourceSvrIds.isEmpty()) return
        rememberRecall(event.talker, sourceMsgId, sourceSvrIds)

        pendingForwards.values
            .filter { pending ->
                pending.talker == event.talker && pendingMatchesRecall(pending, sourceMsgId, sourceSvrIds)
            }
            .forEach { pending ->
                pending.targets.asSequence()
                    .filter { it !in pending.canceledTargets }
                    .filter { followSourceForTarget(pending, it) != null }
                    .forEach(pending.canceledTargets::add)
                if (pending.targets.all(pending.canceledTargets::contains)) finishPending(pending)
            }

        ensureRecallAccount()
        if (recallAccount.isBlank()) {
            if (accountAttempt + 1 < MAX_RECALL_ACCOUNT_ATTEMPTS) {
                schedule(RECALL_ACCOUNT_RETRY_MS) { acceptRecall(event, accountAttempt + 1) }
            }
            return
        }
        val cleaned = cleanupRecallRecords()
        val matching = recallRecords.filter { record ->
            record.sourceTalker == event.talker &&
                ((sourceMsgId > 0L && record.sourceMsgId == sourceMsgId) ||
                    (record.sourceMsgSvrId > 0L && record.sourceMsgSvrId in sourceSvrIds))
        }
        if (matching.isEmpty()) {
            if (cleaned) persistRecallRecords()
            return
        }

        val activeRuleIds = AutoMessageForwardSettings.rules(appContext)
            .asSequence()
            .filter { it.enabled && it.followSourceRecall }
            .mapTo(linkedSetOf()) { it.id }
        val eligible = matching
            .filter { record -> record.ruleIds.any(activeRuleIds::contains) }
            .distinctBy { "${it.targetTalker}:${it.forwardedMsgId}" }
        val ignored = matching.filterNot { it in eligible }
        if (ignored.isNotEmpty()) recallRecords.removeAll(ignored.toSet())
        if (cleaned || ignored.isNotEmpty()) persistRecallRecords()
        revokeNext(eligible, 0, 0)
    }

    private fun acceptObservedRecall(observed: WeChatMessageObserveApi.ObservedMessage) {
        val lookupSvrIds = sequenceOf(observed.xml, observed.content, observed.message.bodyContent())
            .filter { it.isNotBlank() }
            .flatMap { raw ->
                sequenceOf("msgid", "newmsgid").mapNotNull { tag ->
                    WeChatMessage.xmlTag(raw, tag).trim().toLongOrNull()?.takeIf { it > 0L }
                }
            }
            .toCollection(linkedSetOf())
        if (lookupSvrIds.isEmpty()) return
        val source = lookupSvrIds.asSequence()
            .mapNotNull { id ->
                WeChatApis.messageStore()?.getMessageBySvrId(observed.talker, id)
            }
            .firstOrNull { it.talker == observed.talker && !it.isRecalled() }
        if (source != null) {
            if (source.msgSvrId > 0L) lookupSvrIds.add(source.msgSvrId)
        }
        acceptRecall(
            Events.MessageRecalled(
                talker = observed.talker,
                sourceMsgId = source?.msgId ?: 0L,
                sourceMsgSvrId = source?.msgSvrId ?: 0L,
                lookupSvrIds = lookupSvrIds
            )
        )
    }

    private fun revokeNext(
        records: List<AutoMessageForwardRecallRecord>,
        index: Int,
        attempt: Int
    ) {
        if (index >= records.size) return
        val record = records[index]
        val revokeKey = "${record.targetTalker}:${record.forwardedMsgId}"
        if (!AutoMessageForwardSettings.isEnabled(appContext)) {
            recallRevocationsInFlight.remove(revokeKey)
            return
        }
        if (attempt == 0 && !recallRevocationsInFlight.add(revokeKey)) {
            schedule(FOLLOW_REVOKE_INTERVAL_MS) { revokeNext(records, index + 1, 0) }
            return
        }
        val ruleStillActive = AutoMessageForwardSettings.rules(appContext).any { rule ->
            rule.id in record.ruleIds &&
                rule.enabled &&
                rule.followSourceRecall &&
                record.sourceTalker in rule.sourceIds &&
                record.targetTalker in rule.targetIds
        }
        if (!ruleStillActive) {
            recallRevocationsInFlight.remove(revokeKey)
            recallRecords.removeAll {
                it.targetTalker == record.targetTalker && it.forwardedMsgId == record.forwardedMsgId
            }
            persistRecallRecords()
            schedule(FOLLOW_REVOKE_INTERVAL_MS) { revokeNext(records, index + 1, 0) }
            return
        }
        val stillTracked = recallRecords.any {
            it.targetTalker == record.targetTalker && it.forwardedMsgId == record.forwardedMsgId
        }
        if (!stillTracked) {
            recallRevocationsInFlight.remove(revokeKey)
            schedule(FOLLOW_REVOKE_INTERVAL_MS) { revokeNext(records, index + 1, 0) }
            return
        }
        val success = runCatching {
            WeChatApis.message().sender()?.revoke(record.forwardedMsgId) == true
        }.onFailure {
            logger(
                "跟随撤回转发消息异常: target=${record.targetTalker} msgId=${record.forwardedMsgId}",
                it
            )
        }.getOrDefault(false)
        if (success) {
            recallRevocationsInFlight.remove(revokeKey)
            recallRecords.removeAll {
                it.targetTalker == record.targetTalker && it.forwardedMsgId == record.forwardedMsgId
            }
            persistRecallRecords()
            schedule(FOLLOW_REVOKE_INTERVAL_MS) { revokeNext(records, index + 1, 0) }
        } else if (attempt + 1 < MAX_FOLLOW_REVOKE_ATTEMPTS) {
            schedule(FOLLOW_REVOKE_RETRY_MS) { revokeNext(records, index, attempt + 1) }
        } else {
            recallRevocationsInFlight.remove(revokeKey)
            recallRecords.removeAll {
                it.targetTalker == record.targetTalker && it.forwardedMsgId == record.forwardedMsgId
            }
            persistRecallRecords()
            logger(
                "跟随撤回转发消息失败: target=${record.targetTalker} msgId=${record.forwardedMsgId}",
                null
            )
            schedule(FOLLOW_REVOKE_INTERVAL_MS) { revokeNext(records, index + 1, 0) }
        }
    }

    private fun pendingMatchesRecall(
        pending: PendingForward,
        sourceMsgId: Long,
        sourceSvrIds: Set<Long>
    ): Boolean {
        val stored = resolveStoredMessage(pending)
        val localIds = linkedSetOf(
            stored?.msgId ?: 0L,
            pending.fallback.msgId
        ).filterTo(linkedSetOf()) { it > 0L }
        val svrIds = linkedSetOf(
            stored?.msgSvrId ?: 0L,
            pending.fallback.msgSvrId,
            pending.msgSvrId
        ).filterTo(linkedSetOf()) { it > 0L }
        return (sourceMsgId > 0L && sourceMsgId in localIds) || svrIds.any(sourceSvrIds::contains)
    }

    private fun cancelRecentlyRecalledTargets(pending: PendingForward): Boolean {
        val sourceMessage = resolveStoredMessage(pending) ?: pending.fallback
        val source = FollowSource(
            talker = pending.talker,
            msgId = sourceMessage.msgId.takeIf { it > 0L } ?: pending.fallback.msgId,
            msgSvrId = sourceMessage.msgSvrId.takeIf { it > 0L } ?: pending.msgSvrId,
            ruleIds = emptySet()
        )
        if (!sourceWasRecentlyRecalled(source)) return false
        pending.targets.asSequence()
            .filter { it !in pending.canceledTargets }
            .filter { followSourceForTarget(pending, it) != null }
            .forEach(pending.canceledTargets::add)
        return pending.targets.all(pending.canceledTargets::contains).also { allCanceled ->
            if (allCanceled) finishPending(pending)
        }
    }

    private fun rememberRecall(talker: String, sourceMsgId: Long, sourceSvrIds: Set<Long>) {
        val now = System.currentTimeMillis()
        recentRecallKeys.entries.removeAll { now - it.value > RECENT_RECALL_TTL_MS }
        if (sourceMsgId > 0L) recentRecallKeys["$talker:local:$sourceMsgId"] = now
        sourceSvrIds.forEach { id -> recentRecallKeys["$talker:svr:$id"] = now }
        while (recentRecallKeys.size > MAX_RECENT_RECALL_KEYS) {
            recentRecallKeys.remove(recentRecallKeys.keys.first())
        }
    }

    private fun sourceWasRecentlyRecalled(source: FollowSource): Boolean {
        val now = System.currentTimeMillis()
        recentRecallKeys.entries.removeAll { now - it.value > RECENT_RECALL_TTL_MS }
        return (source.msgId > 0L && "${source.talker}:local:${source.msgId}" in recentRecallKeys) ||
            (source.msgSvrId > 0L && "${source.talker}:svr:${source.msgSvrId}" in recentRecallKeys)
    }

    private fun ensureRecallAccount() {
        val account = runCatching { WeChatApis.account()?.selfWxId().orEmpty().trim() }
            .getOrDefault("")
        if (account.isBlank() || account == recallAccount) return
        if (recallAccount.isNotBlank()) persistRecallRecords()
        recallAccount = account
        recallRecords.clear()
        recallRecords.addAll(AutoMessageForwardRecallStore.load(appContext, account))
    }

    private fun cleanupRecallRecords(): Boolean {
        val cutoff = System.currentTimeMillis() - AutoMessageForwardRecallStore.RECORD_TTL_MS
        return recallRecords.removeAll { it.createdAt < cutoff }
    }

    private fun persistRecallRecords() {
        if (recallAccount.isBlank()) return
        AutoMessageForwardRecallStore.replace(appContext, recallAccount, recallRecords)
    }

    private fun scheduleFollowRecallCapture(candidate: ModuleOutgoing, attempt: Int) {
        if (candidate.followSource == null || attempt >= FOLLOW_CAPTURE_DELAYS_MS.size) return
        schedule(FOLLOW_CAPTURE_DELAYS_MS[attempt]) {
            refreshModuleOutgoing(candidate)
            if (!captureFollowRecall(candidate)) {
                scheduleFollowRecallCapture(candidate, attempt + 1)
            }
        }
    }

    private fun captureFollowRecall(candidate: ModuleOutgoing): Boolean {
        val source = candidate.followSource ?: return false
        if (candidate.followCaptured) return true
        if (candidate.identityAmbiguous) return false
        val messageIds = candidate.messageIds.filter { it > 0L }.distinct()
        if (messageIds.size != 1) {
            candidate.stableCandidateMsgId = 0L
            candidate.stableCandidateChecks = 0
            return false
        }
        val candidateMsgId = messageIds.single()
        if (candidate.stableCandidateMsgId == candidateMsgId) {
            candidate.stableCandidateChecks++
        } else {
            candidate.stableCandidateMsgId = candidateMsgId
            candidate.stableCandidateChecks = 1
        }
        if (candidate.stableCandidateChecks < REQUIRED_FOLLOW_CANDIDATE_CHECKS) return false
        ensureRecallAccount()
        if (recallAccount.isBlank()) return false
        cleanupRecallRecords()
        var changed = false
        messageIds.forEach { forwardedMsgId ->
            val record = AutoMessageForwardRecallRecord(
                sourceTalker = source.talker,
                sourceMsgId = source.msgId,
                sourceMsgSvrId = source.msgSvrId,
                targetTalker = candidate.talker,
                forwardedMsgId = forwardedMsgId,
                ruleIds = source.ruleIds,
                createdAt = candidate.createdAt
            )
            val existingIndex = recallRecords.indexOfFirst {
                it.targetTalker == record.targetTalker && it.forwardedMsgId == forwardedMsgId
            }
            if (existingIndex >= 0) {
                val existing = recallRecords[existingIndex]
                val merged = existing.copy(ruleIds = existing.ruleIds + record.ruleIds)
                if (merged != existing) {
                    recallRecords[existingIndex] = merged
                    changed = true
                }
            } else {
                recallRecords.add(record)
                changed = true
            }
        }
        if (recallRecords.size > AutoMessageForwardRecallStore.MAX_RECORDS_PER_ACCOUNT) {
            recallRecords.sortBy { it.createdAt }
            repeat(recallRecords.size - AutoMessageForwardRecallStore.MAX_RECORDS_PER_ACCOUNT) {
                recallRecords.removeAt(0)
            }
            changed = true
        }
        if (changed) persistRecallRecords()
        candidate.followCaptured = true
        if (sourceWasRecentlyRecalled(source)) {
            acceptRecall(
                Events.MessageRecalled(
                    talker = source.talker,
                    sourceMsgId = source.msgId,
                    sourceMsgSvrId = source.msgSvrId,
                    lookupSvrIds = setOf(source.msgSvrId).filterTo(linkedSetOf()) { it > 0L }
                )
            )
        }
        return true
    }

    private fun matchesKeywords(rule: AutoMessageForwardRule, content: String): Boolean {
        if (rule.includeKeywordsEnabled) {
            val includes = AutoMessageForwardSettings.splitKeywords(rule.includeKeywords)
            if (includes.isNotEmpty() && includes.none { content.contains(it, ignoreCase = true) }) return false
        }
        if (rule.excludeKeywordsEnabled) {
            val excludes = AutoMessageForwardSettings.splitKeywords(rule.excludeKeywords)
            if (excludes.any { content.contains(it, ignoreCase = true) }) return false
        }
        return true
    }

    private fun prepareImage(
        message: WeChatMessage,
        nativeMessage: Any?,
        fallbackPath: String,
        pending: PendingForward
    ): Preparation {
        val imageApi = WeChatApis.media()?.images() ?: return Preparation.Waiting
        val image = message.getImageMsg()
        val originalPath = existingPath(imageApi.resolveBestAvailablePath(nativeMessage))
        if (originalPath.isNotBlank() && isStableLocalFile(
                pending,
                originalPath,
                image?.bigLength?.toLong()?.takeIf { it > 0L }
                    ?: mediaLongValue(message, "hdlength")
            )
        ) {
            return Preparation.Ready(ForwardPlan(WeChatMessageTypes.IMAGE, path = originalPath))
        }
        existingPath(pending.downloadPath).takeIf { it.isNotBlank() }?.let {
            return Preparation.Ready(ForwardPlan(WeChatMessageTypes.IMAGE, path = it))
        }

        val download = imageDownloadSpec(message)
        if (download != null && !pending.downloadRequested) {
            val target = imageDownloadTarget(pending).absolutePath
            pending.downloadTargetPath = target
            protectManagedMediaFile(File(target))
            pending.downloadRequested = true
            pending.downloadInFlight = true
            val submitted = imageApi.downloadCdn(
                    download.md5,
                    download.url,
                    download.aesKey,
                    target,
                    download.fileType,
                    download.expectedLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    object : WeChatImageApi.DownloadCallback {
                        override fun onSuccess(file: File) = resumeDownloadedMedia(pending, file)

                        override fun onError(message: String) = failDownloadedMedia(pending, message)
                    }
                )
            if (submitted) {
                scheduleDownloadTimeout(pending)
                return Preparation.Downloading
            }
            pending.downloadInFlight = false
            pending.downloadFailed = true
            pending.downloadError = "CDN任务提交失败"
        }
        if (pending.downloadInFlight) return Preparation.Downloading

        if (pending.downloadFailed && download != null) {
            return Preparation.Failed(pending.downloadError.ifBlank { "图片下载失败" })
        }

        val bestFallback = existingPath(fallbackPath, originalPath, message.imagePath)
        return when {
            bestFallback.isNotBlank() && isStableLocalFile(
                pending,
                bestFallback,
                image?.midLength?.toLong()?.takeIf { it > 0L }
                    ?: mediaLongValue(message, "length")
            ) -> Preparation.Ready(
                ForwardPlan(WeChatMessageTypes.IMAGE, path = bestFallback)
            )
            pending.downloadFailed -> Preparation.Failed(pending.downloadError.ifBlank { "图片下载失败" })
            else -> Preparation.Waiting
        }
    }

    private fun prepareVideo(
        message: WeChatMessage,
        fallbackPath: String,
        pending: PendingForward,
        type: Int
    ): Preparation {
        val videoApi = WeChatApis.media()?.videos() ?: return Preparation.Waiting
        val nativeDownload = resolveVideoDownloadInfo(videoApi, message.imagePath, fallbackPath)
        val download = videoDownloadSpec(message, nativeDownload)
        val localPath = existingPath(
            fallbackPath,
            message.imagePath,
            videoApi.resolvePathToken(message.imagePath)
        )
        if (localPath.isNotBlank() && isStableLocalFile(
                pending,
                localPath,
                nativeDownload?.totalLength?.takeIf { it > 0L }
                    ?: download?.expectedLength
                    ?: message.getVideoMsg()?.length
                    ?: 0L
            )
        ) {
            return Preparation.Ready(ForwardPlan(type, path = localPath))
        }
        existingPath(pending.downloadPath).takeIf { it.isNotBlank() }?.let {
            return Preparation.Ready(ForwardPlan(type, path = it))
        }

        if (download != null && !pending.downloadRequested) {
            val target = videoDownloadTarget(pending).absolutePath
            pending.downloadTargetPath = target
            protectManagedMediaFile(File(target))
            pending.downloadRequested = true
            pending.downloadInFlight = true
            val submitted = videoApi.downloadCdn(
                download.md5,
                download.url,
                download.aesKey,
                target,
                object : WeChatVideoApi.DownloadCallback {
                    override fun onSuccess(file: File) {
                        if (download.expectedLength > 0L && file.length() < download.expectedLength) {
                            failDownloadedMedia(
                                pending,
                                "视频下载文件不完整: ${file.length()}/${download.expectedLength}"
                            )
                        } else {
                            resumeDownloadedMedia(pending, file)
                        }
                    }

                    override fun onError(message: String) = failDownloadedMedia(pending, message)
                }
            )
            if (submitted) {
                scheduleDownloadTimeout(pending)
                return Preparation.Downloading
            }
            pending.downloadInFlight = false
            pending.downloadFailed = true
            pending.downloadError = "CDN任务提交失败"
        }
        return when {
            pending.downloadInFlight -> Preparation.Downloading
            pending.downloadFailed -> Preparation.Failed(pending.downloadError.ifBlank { "视频下载失败" })
            else -> Preparation.Waiting
        }
    }

    private fun imageDownloadSpec(message: WeChatMessage): CdnDownloadSpec? {
        val image = message.getImageMsg()
        val aesKey = firstNotBlank(image?.key, mediaValue(message, "aeskey"))
        if (aesKey.isBlank()) return null
        val bigUrl = firstNotBlank(image?.bigImgUrl, mediaValue(message, "cdnbigimgurl"))
        if (bigUrl.isNotBlank()) {
            return CdnDownloadSpec(
                md5 = firstNotBlank(image?.md5, mediaValue(message, "md5")),
                url = bigUrl,
                aesKey = aesKey,
                fileType = IMAGE_BIG_CDN_FILE_TYPE,
                expectedLength = (image?.bigLength?.toLong() ?: 0L).takeIf { it > 0L }
                    ?: mediaLongValue(message, "hdlength")
            )
        }
        val midUrl = firstNotBlank(image?.midImgUrl, mediaValue(message, "cdnmidimgurl"))
        if (midUrl.isBlank()) return null
        return CdnDownloadSpec(
            md5 = firstNotBlank(image?.md5, mediaValue(message, "md5")),
            url = midUrl,
            aesKey = aesKey,
            fileType = IMAGE_MID_CDN_FILE_TYPE,
            expectedLength = (image?.midLength?.toLong() ?: 0L).takeIf { it > 0L }
                ?: mediaLongValue(message, "length")
        )
    }

    private fun resolveVideoDownloadInfo(
        videoApi: WeChatVideoApi,
        vararg tokens: String?
    ): WeChatVideoApi.DownloadInfo? {
        var fallback: WeChatVideoApi.DownloadInfo? = null
        val candidates = tokens.asSequence()
            .map { it.orEmpty().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        for (token in candidates) {
            val info = runCatching { videoApi.resolveDownloadInfo(token) }.getOrNull() ?: continue
            if (fallback == null) fallback = info
            if (info.cdnUrl.isNotBlank() && info.aesKey.isNotBlank()) return info
        }
        return fallback
    }

    private fun videoDownloadSpec(
        message: WeChatMessage,
        native: WeChatVideoApi.DownloadInfo?
    ): CdnDownloadSpec? {
        val video = message.getVideoMsg()
        val url = firstNotBlank(native?.cdnUrl, video?.cdnVideoUrl, mediaValue(message, "cdnvideourl"))
        val aesKey = firstNotBlank(native?.aesKey, video?.aesKey, mediaValue(message, "aeskey"))
        if (url.isBlank() || aesKey.isBlank()) return null
        return CdnDownloadSpec(
            md5 = firstNotBlank(
                native?.md5,
                video?.md5,
                video?.newMd5,
                mediaValue(message, "md5"),
                mediaValue(message, "newmd5")
            ),
            url = url,
            aesKey = aesKey,
            fileType = VIDEO_CDN_FILE_TYPE,
            expectedLength = (native?.totalLength ?: 0L).takeIf { it > 0L }
                ?: (video?.length ?: 0L).takeIf { it > 0L }
                ?: mediaLongValue(message, "length")
        )
    }

    private fun mediaLongValue(message: WeChatMessage, name: String): Long {
        return mediaValue(message, name).toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    }

    private fun mediaValue(message: WeChatMessage, name: String): String {
        return sequenceOf(
            message.bodyContent(),
            message.reserved,
            message.translatedContent,
            message.msgSource
        ).map { source ->
            WeChatMessage.xmlAttr(source, name).ifBlank { WeChatMessage.xmlTag(source, name) }
        }.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun firstNotBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }

    private fun isStableLocalFile(pending: PendingForward, path: String, expectedLength: Long): Boolean {
        val file = File(path)
        val length = if (file.isFile) file.length() else -1L
        if (length <= 0L || expectedLength > 0L && length < expectedLength) return false
        if (pending.stableLocalPath == file.absolutePath && pending.stableLocalLength == length) {
            pending.stableLocalChecks++
        } else {
            pending.stableLocalPath = file.absolutePath
            pending.stableLocalLength = length
            pending.stableLocalChecks = 0
        }
        return pending.stableLocalChecks >= REQUIRED_LOCAL_STABLE_CHECKS
    }

    private fun imageDownloadTarget(pending: PendingForward): File {
        val directory = mediaCacheDirectory()
        return File(directory, "image_${pending.cacheToken}.jpg")
    }

    private fun videoDownloadTarget(pending: PendingForward): File {
        val directory = mediaCacheDirectory()
        return File(directory, "video_${pending.cacheToken}.mp4")
    }

    private fun mediaCacheDirectory(): File {
        return File(appContext.cacheDir, MEDIA_CACHE_DIRECTORY).apply { mkdirs() }
    }

    private fun resumeDownloadedMedia(pending: PendingForward, file: File) {
        dispatchDownloadResult(pending, { deleteDiscardedDownload(pending, file) }) {
            pending.downloadInFlight = false
            pending.downloadPath = file.absolutePath
            prepareAndSend(pending)
        }
    }

    private fun failDownloadedMedia(pending: PendingForward, message: String) {
        dispatchDownloadResult(pending) {
            pending.downloadInFlight = false
            pending.downloadFailed = true
            pending.downloadError = message
            prepareAndSend(pending)
        }
    }

    private fun dispatchDownloadResult(
        pending: PendingForward,
        discarded: (() -> Unit)? = null,
        action: () -> Unit
    ) {
        if (executor.isShutdown) {
            discarded?.invoke()
            return
        }
        runCatching {
            executor.execute {
                if (!pending.downloadInFlight || pending.key !in pendingKeys) {
                    discarded?.invoke()
                    return@execute
                }
                action()
            }
        }.onFailure { discarded?.invoke() }
    }

    private fun scheduleDownloadTimeout(pending: PendingForward) {
        schedule(MEDIA_WAIT_TIMEOUT_MS) {
            if (!pending.downloadInFlight || pending.key !in pendingKeys) return@schedule
            pending.downloadInFlight = false
            pending.downloadFailed = true
            pending.downloadError = "等待下载完成回调超时"
            prepareAndSend(pending)
        }
    }

    private fun finishPending(pending: PendingForward) {
        pendingForwards.remove(pending.key, pending)
        if (!pendingKeys.remove(pending.key)) return
        pending.downloadInFlight = false
        if (!pending.downloadRequested && pending.downloadPath.isBlank()) return
        schedule(MEDIA_CACHE_CLEANUP_DELAY_MS) { cleanupPendingMedia(pending) }
    }

    private fun cleanupPendingMedia(pending: PendingForward) {
        sequenceOf(pending.downloadTargetPath, pending.downloadPath)
            .filter { it.isNotBlank() }
            .distinct()
            .map(::File)
            .forEach(::deleteManagedMediaFile)
    }

    private fun cleanupStaleMediaCache() {
        val directory = File(appContext.cacheDir, MEDIA_CACHE_DIRECTORY)
        val cutoff = System.currentTimeMillis() - MEDIA_CACHE_MAX_AGE_MS
        directory.listFiles()?.asSequence()
            ?.filter { file ->
                file.isFile &&
                    (file.name.startsWith("image_") || file.name.startsWith("video_")) &&
                    file.lastModified() <= cutoff &&
                    managedMediaPath(file)?.let { it !in protectedMediaPaths } == true
            }
            ?.forEach(::deleteManagedMediaFile)
    }

    private fun scheduleMediaCacheMaintenance(delayMillis: Long) {
        schedule(delayMillis) {
            cleanupStaleMediaCache()
            scheduleMediaCacheMaintenance(MEDIA_CACHE_SWEEP_INTERVAL_MS)
        }
    }

    private fun protectManagedMediaFile(file: File) {
        managedMediaPath(file)?.let(protectedMediaPaths::add)
    }

    private fun deleteDiscardedDownload(pending: PendingForward, file: File) {
        val targetPath = managedMediaPath(File(pending.downloadTargetPath)) ?: return
        if (managedMediaPath(file) == targetPath) deleteManagedMediaFile(file)
    }

    private fun deleteManagedMediaFile(file: File) {
        val path = managedMediaPath(file) ?: return
        if (file.isFile) file.delete()
        protectedMediaPaths.remove(path)
    }

    private fun managedMediaPath(file: File): String? {
        val directory = File(appContext.cacheDir, MEDIA_CACHE_DIRECTORY)
        return runCatching {
            val canonical = file.canonicalFile
            canonical.path.takeIf { canonical.parentFile == directory.canonicalFile }
        }.getOrNull()
    }

    private fun saturatingAdd(left: Long, right: Long): Long {
        if (right <= 0L) return left
        return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }

    private fun buildSearchableContent(observed: WeChatMessageObserveApi.ObservedMessage): String {
        return listOf(observed.content, observed.xml, observed.message.bodyContent())
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
    }

    private fun messageKey(observed: WeChatMessageObserveApi.ObservedMessage): String {
        val message = observed.message
        return when {
            message.msgSvrId > 0L -> "svr:${message.msgSvrId}"
            message.msgId > 0L -> "local:${message.msgId}"
            else -> "event:${observed.talker}:${message.createTime}:${message.type}:${observed.content.hashCode()}"
        }
    }

    private fun messageIdentityKeys(observed: WeChatMessageObserveApi.ObservedMessage): Set<String> {
        val localIds = linkedSetOf<Long>()
        val serverIds = linkedSetOf<Long>()
        fun capture(message: WeChatMessage?) {
            if (message == null) return
            if (message.msgId > 0L) localIds.add(message.msgId)
            if (message.msgSvrId > 0L) serverIds.add(message.msgSvrId)
        }

        capture(observed.message)
        capture(observed.storedMessage)
        val store = WeChatApis.messageStore()
        if (store != null) {
            serverIds.toList().forEach { serverId ->
                capture(runCatching { store.getMessageBySvrId(observed.talker, serverId) }.getOrNull())
            }
            localIds.toList().forEach { localId ->
                capture(runCatching { store.getMessageById(localId) }.getOrNull())
            }
            if (observed.outgoing && localIds.isEmpty()) {
                val observedTime = messageTimeMillis(observed.message.createTime)
                val observedContent = sequenceOf(observed.content, observed.message.bodyContent())
                    .filter { it.isNotBlank() }
                    .toSet()
                val candidates = runCatching {
                    store.getRecentMessages(observed.talker, SOURCE_MESSAGE_QUERY_LIMIT)
                }.getOrDefault(emptyList())
                    .filter { message ->
                        message.isOutgoing() &&
                            WeChatMessageTypes.normalize(message.type) ==
                            WeChatMessageTypes.normalize(observed.message.type) &&
                            kotlin.math.abs(messageTimeMillis(message.createTime) - observedTime) <=
                            SOURCE_MESSAGE_CLOCK_SLOP_MS &&
                            (observedContent.isEmpty() ||
                                sequenceOf(message.content, message.bodyContent()).any(observedContent::contains))
                    }
                    .take(2)
                if (candidates.size == 1) capture(candidates.single())
            }
        }
        return linkedSetOf<String>().apply {
            localIds.forEach { add("local:$it") }
            serverIds.forEach { add("svr:$it") }
            if (isEmpty()) add(messageKey(observed))
        }
    }

    private fun rememberMessage(keys: Set<String>): Boolean = synchronized(recentMessages) {
        val now = System.currentTimeMillis()
        recentMessages.entries.removeAll { now - it.value > DEDUPE_TTL_MS }
        val duplicate = keys.any(recentMessages::containsKey)
        keys.forEach { recentMessages[it] = now }
        !duplicate
    }

    private fun existingPath(vararg values: String?): String {
        return values.asSequence()
            .map { it.orEmpty().trim() }
            .filter { it.isNotBlank() }
            .map(::File)
            .firstOrNull(File::isFile)
            ?.absolutePath
            .orEmpty()
    }

    private fun resolveVoicePath(fileName: String): String {
        if (fileName.isBlank()) return ""
        existingPath(fileName).takeIf { it.isNotBlank() }?.let { return it }
        return WeChatApis.media()?.voices()?.resolvePath(fileName)
            ?.takeIf { File(it).isFile }
            .orEmpty()
    }

    private fun voiceFileName(message: WeChatMessage): String {
        message.imagePath.trim().takeIf { it.isNotBlank() }?.let { return it }
        val body = message.bodyContent().trimEnd('\n', '\r')
        val parts = body.split(':')
        if (parts.size >= 3 && '<' !in body) {
            return (if (parts.size == 4) parts[1] else parts[0]).trim()
        }
        return WeChatMessage.xmlAttr(body, "filename")
            .ifBlank { WeChatMessage.xmlAttr(body, "voiceurl") }
            .ifBlank { WeChatMessage.xmlTag(body, "filename") }
    }

    private fun emojiSource(message: WeChatMessage, fileName: String): String {
        existingPath(fileName, message.imagePath).takeIf { it.isNotBlank() }?.let { return it }
        val values = listOf(fileName, message.imagePath)
        values.firstOrNull { it.matches(MD5_REGEX) }?.let { return it }
        val body = message.bodyContent()
        return WeChatMessage.xmlAttr(body, "md5")
            .ifBlank { WeChatMessage.xmlTag(body, "md5") }
    }

    private data class PendingForward(
        val key: String,
        val talker: String,
        val msgSvrId: Long,
        val fallback: WeChatMessage,
        val targets: List<String>,
        val targetRules: Map<String, Map<String, Long>>,
        val kind: String,
        val messageType: Int,
        val searchableContent: String,
        val outgoing: Boolean,
        val sourceSender: String,
        val deadline: Long,
        val cacheToken: String,
        var downloadTargetPath: String = "",
        var downloadPath: String = "",
        var downloadRequested: Boolean = false,
        var downloadInFlight: Boolean = false,
        var downloadFailed: Boolean = false,
        var downloadError: String = "",
        var stableLocalPath: String = "",
        var stableLocalLength: Long = -1L,
        var stableLocalChecks: Int = 0,
        val canceledTargets: MutableSet<String> = linkedSetOf()
    )

    private data class ForwardPlan(
        val type: Int,
        val content: String = "",
        val path: String = "",
        val durationMillis: Int = 0,
        val title: String = ""
    )

    private class ModuleOutgoing(
        val talker: String,
        val messageType: Int,
        val content: String,
        val followSource: FollowSource?,
        val baselineMessageIds: Set<Long>,
        val createdAt: Long
    ) {
        val messageIds: MutableSet<Long> = ConcurrentHashMap.newKeySet<Long>()
        val messageSvrIds: MutableSet<Long> = ConcurrentHashMap.newKeySet<Long>()
        var localFallbackConsumed: Boolean = false
        var identityAmbiguous: Boolean = false
        var followCaptured: Boolean = false
        var stableCandidateMsgId: Long = 0L
        var stableCandidateChecks: Int = 0
    }

    private data class FollowSource(
        val talker: String,
        val msgId: Long,
        val msgSvrId: Long,
        val ruleIds: Set<String>
    )

    private data class CdnDownloadSpec(
        val md5: String,
        val url: String,
        val aesKey: String,
        val fileType: Int,
        val expectedLength: Long
    )

    private sealed interface Preparation {
        data class Ready(val plan: ForwardPlan) : Preparation
        data object Waiting : Preparation
        data object Downloading : Preparation
        data class Failed(val reason: String) : Preparation
        data object Unsupported : Preparation
    }

    companion object {
        private val MD5_REGEX = Regex("[0-9a-fA-F]{32}")
        private const val DEFAULT_VOICE_DURATION_MS = 1000
        private const val INITIAL_DELAY_MS = 350L
        private const val MEDIA_RETRY_INTERVAL_MS = 500L
        private const val MEDIA_WAIT_TIMEOUT_MS = 60_000L
        private const val MEDIA_CACHE_CLEANUP_DELAY_MS = 60 * 60_000L
        private const val MEDIA_CACHE_MAX_AGE_MS = 24 * 60 * 60_000L
        private const val MEDIA_CACHE_SWEEP_INTERVAL_MS = 6 * 60 * 60_000L
        private const val TARGET_SEND_INTERVAL_MS = 500L
        private const val DEDUPE_TTL_MS = 10 * 60_000L
        private const val MAX_PENDING_MESSAGES = 128
        private const val MODULE_OUTGOING_TTL_MS = 10_000L
        private const val MODULE_OUTGOING_SETTLE_DELAY_MS = 250L
        private const val MODULE_OUTGOING_CLOCK_SLOP_MS = 5_000L
        private const val MODULE_OUTGOING_QUERY_LIMIT = 24
        private const val SOURCE_MESSAGE_QUERY_LIMIT = 16
        private const val SOURCE_MESSAGE_CLOCK_SLOP_MS = 10_000L
        private const val MAX_MODULE_OUTGOING = 128
        private val FOLLOW_CAPTURE_DELAYS_MS = longArrayOf(250L, 1_000L, 3_000L)
        private const val FOLLOW_REVOKE_INTERVAL_MS = 300L
        private const val FOLLOW_REVOKE_RETRY_MS = 1_000L
        private const val MAX_FOLLOW_REVOKE_ATTEMPTS = 3
        private const val REQUIRED_FOLLOW_CANDIDATE_CHECKS = 2
        private const val RECENT_RECALL_TTL_MS = 60_000L
        private const val MAX_RECENT_RECALL_KEYS = 256
        private const val RECALL_ACCOUNT_RETRY_MS = 1_000L
        private const val MAX_RECALL_ACCOUNT_ATTEMPTS = 10
        private const val VIDEO_NUMBER_TYPE = 62
        private const val IMAGE_BIG_CDN_FILE_TYPE = 1
        private const val IMAGE_MID_CDN_FILE_TYPE = 2
        private const val VIDEO_CDN_FILE_TYPE = 4
        private const val MEDIA_CACHE_DIRECTORY = "Hchat_auto_message_forward"
        private const val REQUIRED_LOCAL_STABLE_CHECKS = 1
    }
}
