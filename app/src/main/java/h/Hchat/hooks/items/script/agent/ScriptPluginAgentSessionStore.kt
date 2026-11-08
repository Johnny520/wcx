package h.Hchat.hooks.items.script.agent

import android.content.Context
import android.net.Uri
import android.system.Os
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import h.Hchat.utils.HLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object ScriptPluginAgentSessionStore {
    // ID 和路径都来自用户长期创建的会话/附件，不能把它们直接用作永久锁对象的 key。
    // 固定条带锁仍保持同一 key 的互斥，同时让锁数量与历史记录数量无关。
    private const val LOCK_STRIPE_COUNT = 64
    private val attachmentLocks = Array(LOCK_STRIPE_COUNT) { Any() }
    private val attachmentLeaseCounts = HashMap<String, Int>()
    private val attachmentLeaseMonitor = Any()
    private val sessionSaveLocks = Array(LOCK_STRIPE_COUNT) { Any() }
    private val pendingAsyncSaves = ConcurrentHashMap<String, Pair<Context, ScriptPluginAgentSession>>()
    private val queuedAsyncSaves = ConcurrentHashMap.newKeySet<String>()
    private val deletedSessions = ConcurrentHashMap.newKeySet<String>()
    private val asyncSaveExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Hchat-Agent-Session-Save").apply { isDaemon = true }
    }

    fun list(context: Context): List<ScriptPluginAgentSession> {
        val dir = sessionDir(context)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == "json" }
            ?.mapNotNull { file -> runCatching { decode(file.readText(Charsets.UTF_8)) }.getOrNull() }
            ?.filter { hasConversation(it.messages) }
            ?.toList()
            ?.let(::sorted)
            ?: emptyList()
    }

    fun sorted(sessions: List<ScriptPluginAgentSession>): List<ScriptPluginAgentSession> {
        return sessions.sortedWith(
            compareByDescending<ScriptPluginAgentSession> { it.pinned }
                .thenByDescending { it.sortOrder }
                .thenByDescending { it.updatedAt }
        )
    }

    fun load(context: Context, id: String): ScriptPluginAgentSession? {
        val file = File(sessionDir(context), safeId(id) + ".json")
        return runCatching { if (file.isFile) decode(file.readText(Charsets.UTF_8)) else null }
            .getOrNull()
            ?.takeIf { hasConversation(it.messages) }
    }

    fun newSession(): ScriptPluginAgentSession {
        val now = System.currentTimeMillis()
        return ScriptPluginAgentSession(
            id = UUID.randomUUID().toString().replace("-", ""),
            title = "新对话",
            createdAt = now,
            updatedAt = now,
            messages = emptyList(),
            draft = null
        )
    }

    fun save(context: Context, session: ScriptPluginAgentSession) {
        if (!hasConversation(session.messages)) {
            discardEmptySession(context, session.id)
            return
        }
        val safeSessionId = safeId(session.id)
        synchronized(lockFor(sessionSaveLocks, safeSessionId)) {
            if (safeSessionId in deletedSessions) return
            val dir = sessionDir(context)
            if (!dir.isDirectory) dir.mkdirs()
            val target = File(dir, "$safeSessionId.json")
            val savedRoot = runCatching {
                if (target.isFile) JSONObject(target.readText(Charsets.UTF_8)) else null
            }.getOrNull()
            val savedSeq = savedRoot?.optLong("checkpointSeq", 0L) ?: 0L
            val savedUpdatedAt = savedRoot?.optLong("updatedAt", 0L) ?: 0L
            if (savedSeq > session.checkpointSeq ||
                (savedSeq == 0L && session.checkpointSeq == 0L && savedUpdatedAt > session.updatedAt)
            ) return
            val temp = File(dir, ".${target.name}.tmp")
            FileOutputStream(temp).use { output ->
                output.write(encode(session).toString().toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            try {
                Os.rename(temp.absolutePath, target.absolutePath)
            } catch (error: Throwable) {
                if (!temp.renameTo(target)) {
                    temp.delete()
                    throw IllegalStateException("保存 Agent 会话失败", error)
                }
            }
        }
    }

    fun saveAsync(context: Context, session: ScriptPluginAgentSession) {
        if (!hasConversation(session.messages)) return
        val id = safeId(session.id)
        pendingAsyncSaves[id] = (context.applicationContext ?: context) to session
        if (!queuedAsyncSaves.add(id)) return
        asyncSaveExecutor.execute {
            try {
                while (true) {
                    val pending = pendingAsyncSaves.remove(id) ?: break
                    runCatching { save(pending.first, pending.second) }
                        .onFailure { HLog.e("[Hchat:ScriptAgent] 异步保存会话失败: $id", it) }
                }
            } finally {
                queuedAsyncSaves.remove(id)
                if (pendingAsyncSaves.containsKey(id)) {
                    pendingAsyncSaves[id]?.let { saveAsync(it.first, it.second) }
                }
            }
        }
    }

    fun delete(context: Context, id: String) {
        val safeSessionId = safeId(id)
        deletedSessions += safeSessionId
        pendingAsyncSaves.remove(safeSessionId)
        synchronized(lockFor(sessionSaveLocks, safeSessionId)) {
            File(sessionDir(context), "$safeSessionId.json").delete()
        }
        ScriptPluginAgentToolResultStore.deleteSession(context, id)
    }

    fun revive(id: String) {
        deletedSessions.remove(safeId(id))
    }

    fun discardEmptySession(context: Context, id: String) {
        delete(context, id)
        File(attachmentRoot(context), safeId(id)).deleteRecursively()
    }

    fun attachmentDir(context: Context, sessionId: String): File {
        return File(attachmentRoot(context), safeId(sessionId)).apply { mkdirs() }
    }

    fun materializeAttachments(
        context: Context,
        attachments: List<ScriptPluginAgentAttachment>
    ): Result<Unit> = runCatching {
        attachments.distinctBy { it.path }.forEach { attachment ->
            val target = managedAttachmentFile(context, attachment.path)
                ?: throw IllegalStateException("附件路径无效: ${attachment.name}")
            if (target.isFile && target.length() > 0L) return@forEach
            val lock = lockFor(attachmentLocks, target.path)
            synchronized(lock) {
                if (target.isFile && target.length() > 0L) return@synchronized
                val sourceUri = attachment.sourceUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
                    ?: throw IllegalStateException("附件副本不存在且无法重新读取: ${attachment.name}")
                val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
                try {
                    target.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        FileOutputStream(temp).use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("无法重新读取附件")
                    if (!temp.isFile || temp.length() <= 0L) throw IllegalStateException("附件内容为空")
                    if (target.exists() && !target.delete()) throw IllegalStateException("无法替换附件副本")
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }
                    if (attachment.size > 0L && target.length() != attachment.size) {
                        throw IllegalStateException("附件大小已变化")
                    }
                } catch (error: Throwable) {
                    temp.delete()
                    target.delete()
                    HLog.e("[Hchat:ScriptAgent] 恢复附件失败: ${attachment.name}", error)
                    throw IllegalStateException("无法恢复附件: ${attachment.name}", error)
                }
            }
        }
    }

    fun cleanupAttachments(context: Context, attachments: List<ScriptPluginAgentAttachment>) {
        val root = runCatching { attachmentRoot(context).canonicalFile }.getOrNull() ?: return
        attachments.asSequence()
            .mapNotNull { managedAttachmentFile(context, it.path) }
            .distinctBy { it.path }
            .forEach { file ->
                runCatching { file.delete() }
                var parent = file.parentFile
                while (parent != null && parent != root && parent.list()?.isEmpty() == true) {
                    if (!parent.delete()) break
                    parent = parent.parentFile
                }
            }
    }

    fun <T> withMaterializedAttachments(
        context: Context,
        attachments: List<ScriptPluginAgentAttachment>,
        block: () -> Result<T>
    ): Result<T> {
        val leased = acquireAttachmentLeases(context, attachments)
        return try {
            val preparation = materializeAttachments(context, attachments)
            if (preparation.isFailure) {
                Result.failure(
                    preparation.exceptionOrNull() ?: IllegalStateException("附件准备失败")
                )
            } else {
                runCatching(block).getOrElse { Result.failure(it) }
            }
        } finally {
            releaseAttachmentLeases(context, leased)
        }
    }

    fun hasConversation(messages: List<ScriptPluginAgentChatMessage>): Boolean {
        return messages.any { message ->
            message.role == "user" && (message.content.isNotBlank() || message.attachments.isNotEmpty())
        }
    }

    fun titleFrom(messages: List<ScriptPluginAgentChatMessage>, fallback: String = "新对话"): String {
        val first = messages.firstOrNull { it.role == "user" }?.content.orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
        return first.take(32).ifBlank { fallback }
    }

    private fun sessionDir(context: Context): File {
        return File(ScriptPluginRuntime.scriptDir(context).parentFile, "Agent/sessions")
    }

    private fun lockFor(stripes: Array<Any>, key: String): Any {
        return stripes[(key.hashCode() and Int.MAX_VALUE) % stripes.size]
    }

    private fun attachmentRoot(context: Context): File {
        return File(sessionDir(context).parentFile, "attachments")
    }

    private fun managedAttachmentFile(context: Context, path: String): File? {
        if (path.isBlank()) return null
        val root = runCatching { attachmentRoot(context).canonicalFile }.getOrNull() ?: return null
        val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
        return target.takeIf { it.path.startsWith(rootPrefix) }
    }

    private fun acquireAttachmentLeases(
        context: Context,
        attachments: List<ScriptPluginAgentAttachment>
    ): List<ScriptPluginAgentAttachment> {
        val leased = attachments.filter { it.sourceUri.isNotBlank() }
            .distinctBy { managedAttachmentFile(context, it.path)?.path ?: it.path }
        synchronized(attachmentLeaseMonitor) {
            leased.forEach { attachment ->
                val path = managedAttachmentFile(context, attachment.path)?.path ?: return@forEach
                attachmentLeaseCounts[path] = attachmentLeaseCounts.getOrDefault(path, 0) + 1
            }
        }
        return leased
    }

    private fun releaseAttachmentLeases(
        context: Context,
        attachments: List<ScriptPluginAgentAttachment>
    ) {
        synchronized(attachmentLeaseMonitor) {
            val released = attachments.filter { attachment ->
                val path = managedAttachmentFile(context, attachment.path)?.path ?: return@filter false
                val remaining = attachmentLeaseCounts.getOrDefault(path, 1) - 1
                if (remaining > 0) {
                    attachmentLeaseCounts[path] = remaining
                    false
                } else {
                    attachmentLeaseCounts.remove(path)
                    true
                }
            }
            cleanupAttachments(context, released)
        }
    }

    private fun safeId(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80).ifBlank { "session" }
    }

    private fun encode(session: ScriptPluginAgentSession): JSONObject {
        return JSONObject().apply {
            put("id", session.id)
            put("title", session.title)
            put("createdAt", session.createdAt)
            put("updatedAt", session.updatedAt)
            put("targetPluginId", session.targetPluginId)
            put("conversationSummary", session.conversationSummary)
            put("nativeToolHistory", session.nativeToolHistory)
            put("protocolTranscript", session.protocolTranscript)
            put("compactedMessageCount", session.compactedMessageCount)
            put("pinned", session.pinned)
            put("locked", session.locked)
            put("sortOrder", session.sortOrder)
            put("checkpointSeq", session.checkpointSeq)
            put("resumeState", session.resumeState?.let { state ->
                JSONObject().apply {
                    put("turnId", state.turnId)
                    put("sourceUserMessageId", state.sourceUserMessageId)
                    put("taskGoal", state.taskGoal)
                    put("workContext", state.workContext)
                    put("workspaceCheckpoint", state.workspaceCheckpoint?.let(::encodeWorkspaceCheckpoint)
                        ?: JSONObject.NULL)
                    put("autoOpen", state.autoOpen)
                    put("startedAt", state.startedAt)
                    put("updatedAt", state.updatedAt)
                }
            } ?: JSONObject.NULL)
            put("messages", JSONArray().apply {
                session.messages.forEach { message ->
                    put(JSONObject().apply {
                        put("role", message.role)
                        put("content", message.content)
                        put("id", message.id)
                        put("turnId", message.turnId)
                        put("parentMessageId", message.parentMessageId)
                        put("phase", message.phase)
                        put("progress", message.progress)
                        put("reasoning", message.reasoning)
                        put("diff", message.diff)
                        put("toolEvents", JSONArray().apply {
                            message.toolEvents.forEach { event ->
                                put(JSONObject().apply {
                                    put("id", event.id)
                                    put("kind", event.kind)
                                    put("name", event.name)
                                    put("arguments", event.arguments)
                                    put("result", event.result)
                                    put("diff", event.diff)
                                    put("status", event.status)
                                    put("startedAt", event.startedAt)
                                    put("finishedAt", event.finishedAt)
                                    put("progress", event.progress)
                                    put("turnId", event.turnId)
                                    put("toolCallId", event.toolCallId)
                                    put("protocolName", event.protocolName)
                                    put("providerMetadata", event.providerMetadata)
                                    put("parentAssistantMessageId", event.parentAssistantMessageId)
                                    put("resultHandle", event.resultHandle)
                                    put("resultLength", event.resultLength)
                                    put("truncated", event.truncated)
                                    put("nextOffset", event.nextOffset)
                                })
                            }
                        })
                        put("status", message.status)
                        put("draftSnapshot", message.draftSnapshot?.let { encodeDraft(it) } ?: JSONObject.NULL)
                        put("clearsDraft", message.clearsDraft)
                        put("attachments", JSONArray().apply {
                            message.attachments.forEach { attachment ->
                                put(JSONObject().apply {
                                    put("name", attachment.name)
                                    put("path", attachment.path)
                                    put("mimeType", attachment.mimeType)
                                    put("size", attachment.size)
                                    put("sourceUri", attachment.sourceUri)
                                })
                            }
                        })
                        put("quotedMessage", message.quotedMessage?.let { quoted ->
                            JSONObject().apply {
                                put("role", quoted.role)
                                put("content", quoted.content)
                                put("createdAt", quoted.createdAt)
                            }
                        } ?: JSONObject.NULL)
                        put("createdAt", message.createdAt)
                        put("streamId", message.streamId)
                        put("completedAt", message.completedAt)
                    })
                }
            })
            put("draft", session.draft?.let { encodeDraft(it) } ?: JSONObject.NULL)
        }
    }

    private fun decode(text: String): ScriptPluginAgentSession {
        val root = JSONObject(text)
        val messages = ArrayList<ScriptPluginAgentChatMessage>()
        val array = root.optJSONArray("messages") ?: JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val attachments = ArrayList<ScriptPluginAgentAttachment>()
            val toolEvents = ArrayList<ScriptPluginAgentToolEvent>()
            val toolEventArray = item.optJSONArray("toolEvents") ?: JSONArray()
            for (eventIndex in 0 until toolEventArray.length()) {
                val event = toolEventArray.optJSONObject(eventIndex) ?: continue
                val storedStatus = event.optString("status", "success")
                val restoredStatus = storedStatus.takeUnless {
                    it.equals("running", ignoreCase = true) || it.equals("queued", ignoreCase = true)
                } ?: "interrupted"
                toolEvents += ScriptPluginAgentToolEvent(
                    id = event.optString("id", "legacy-$index-$eventIndex"),
                    kind = event.optString("kind", "tool"),
                    name = event.optString("name", "工具调用"),
                    arguments = event.optString("arguments", ""),
                    result = event.optString("result", ""),
                    diff = event.optString("diff", ""),
                    status = restoredStatus,
                    startedAt = event.optLong("startedAt", 0L),
                    finishedAt = event.optLong("finishedAt", 0L),
                    progress = if (restoredStatus == "interrupted") {
                        "任务意外中断"
                    } else {
                        event.optString("progress", "")
                    },
                    turnId = event.optString("turnId", ""),
                    toolCallId = event.optString("toolCallId", ""),
                    protocolName = event.optString("protocolName", ""),
                    providerMetadata = event.optString("providerMetadata", ""),
                    parentAssistantMessageId = event.optString("parentAssistantMessageId", ""),
                    resultHandle = event.optString("resultHandle", ""),
                    resultLength = event.optInt("resultLength", event.optString("result", "").length),
                    truncated = event.optBoolean("truncated", false),
                    nextOffset = event.optInt("nextOffset", 0)
                )
            }
            val attachmentArray = item.optJSONArray("attachments") ?: JSONArray()
            for (attachmentIndex in 0 until attachmentArray.length()) {
                val attachment = attachmentArray.optJSONObject(attachmentIndex) ?: continue
                attachments += ScriptPluginAgentAttachment(
                    name = attachment.optString("name", ""),
                    path = attachment.optString("path", ""),
                    mimeType = attachment.optString("mimeType", "application/octet-stream"),
                    size = attachment.optLong("size", 0L),
                    sourceUri = attachment.optString("sourceUri", "")
                )
            }
            val storedMessageStatus = item.optString("status", "complete")
            val interruptedOperation = item.optString("progress", "").lineSequence().lastOrNull().orEmpty().let {
                it.contains("等待确认") || it.startsWith("正在提交插件") ||
                    it.startsWith("正在创建插件") || it.startsWith("正在写入插件") ||
                    it.startsWith("正在删除插件")
            }
            val restoredMessageStatus = when {
                storedMessageStatus.equals("streaming", ignoreCase = true) -> "interrupted"
                interruptedOperation -> "interrupted"
                else -> storedMessageStatus
            }
            messages += ScriptPluginAgentChatMessage(
                role = item.optString("role", "user"),
                content = item.optString("content", ""),
                id = item.optString("id", "").ifBlank { UUID.randomUUID().toString() },
                turnId = item.optString("turnId", ""),
                parentMessageId = item.optString("parentMessageId", ""),
                phase = item.optString("phase", item.optString("role", "user")),
                progress = item.optString("progress", ""),
                reasoning = item.optString("reasoning", ""),
                diff = item.optString("diff", ""),
                toolEvents = toolEvents,
                attachments = attachments,
                quotedMessage = item.optJSONObject("quotedMessage")?.let { quoted ->
                    ScriptPluginAgentQuotedMessage(
                        role = quoted.optString("role", "user"),
                        content = quoted.optString("content", ""),
                        createdAt = quoted.optLong("createdAt", 0L)
                    )
                },
                createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                status = restoredMessageStatus,
                draftSnapshot = item.optJSONObject("draftSnapshot")?.let { decodeDraft(it) },
                clearsDraft = item.optBoolean("clearsDraft", false),
                streamId = item.optString("streamId", ""),
                completedAt = item.optLong("completedAt", 0L)
            )
        }
        val updatedAt = root.optLong("updatedAt", System.currentTimeMillis())
        val resumeState = root.optJSONObject("resumeState")?.let { state ->
            val turnId = state.optString("turnId", "")
            val sourceUserMessageId = state.optString("sourceUserMessageId", "")
            if (turnId.isBlank() || sourceUserMessageId.isBlank()) null else ScriptPluginAgentResumeState(
                turnId = turnId,
                sourceUserMessageId = sourceUserMessageId,
                taskGoal = state.optString("taskGoal", ""),
                workContext = state.optString("workContext", ""),
                workspaceCheckpoint = state.optJSONObject("workspaceCheckpoint")
                    ?.let(::decodeWorkspaceCheckpoint),
                autoOpen = state.optBoolean("autoOpen", true),
                startedAt = state.optLong("startedAt", updatedAt),
                updatedAt = state.optLong("updatedAt", updatedAt)
            )
        }
        return ScriptPluginAgentSession(
            id = root.optString("id").ifBlank { UUID.randomUUID().toString().replace("-", "") },
            title = root.optString("title", titleFrom(messages)),
            createdAt = root.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = updatedAt,
            messages = messages,
            draft = root.optJSONObject("draft")?.let { decodeDraft(it) },
            targetPluginId = root.optString("targetPluginId", ""),
            conversationSummary = root.optString("conversationSummary", ""),
            nativeToolHistory = root.optString("nativeToolHistory", ""),
            protocolTranscript = root.optString("protocolTranscript", ""),
            compactedMessageCount = root.optInt("compactedMessageCount", 0).coerceIn(0, messages.size),
            pinned = root.optBoolean("pinned", false),
            locked = root.optBoolean("locked", false),
            sortOrder = root.optLong("sortOrder", updatedAt),
            resumeState = resumeState,
            checkpointSeq = root.optLong("checkpointSeq", 0L).coerceAtLeast(0L)
        )
    }

    private fun encodeDraft(draft: ScriptPluginAgentDraft): JSONObject = JSONObject().apply {
        put("pluginName", draft.pluginName)
        put("pluginId", draft.pluginId)
        put("infoProp", draft.infoProp)
        put("mainJava", draft.mainJava)
        put("summary", draft.summary)
    }

    private fun decodeDraft(obj: JSONObject): ScriptPluginAgentDraft = ScriptPluginAgentDraft(
        pluginName = obj.optString("pluginName", ""),
        pluginId = obj.optString("pluginId", ""),
        infoProp = obj.optString("infoProp", ""),
        mainJava = obj.optString("mainJava", ""),
        summary = obj.optString("summary", "")
    )

    private fun encodeWorkspaceCheckpoint(
        checkpoint: ScriptPluginAgentWorkspaceCheckpoint
    ): JSONObject = JSONObject().apply {
        put("stagingPath", checkpoint.stagingPath)
        put("pluginId", checkpoint.pluginId)
        put("existed", checkpoint.existed)
        put("baseFingerprint", checkpoint.baseFingerprint)
        put("stageFingerprint", checkpoint.stageFingerprint)
        put("basePathStates", JSONObject().apply {
            checkpoint.basePathStates.forEach { (path, state) -> put(path, state) }
        })
        put("initialPluginName", checkpoint.initialPluginName)
        put("revision", checkpoint.revision)
        put("checkedRevision", checkpoint.checkedRevision)
        put("shownRevision", checkpoint.shownRevision)
        put("deletePlugin", checkpoint.deletePlugin)
        put("updatedAt", checkpoint.updatedAt)
    }

    private fun decodeWorkspaceCheckpoint(obj: JSONObject): ScriptPluginAgentWorkspaceCheckpoint {
        val pathStates = LinkedHashMap<String, String>()
        obj.optJSONObject("basePathStates")?.let { states ->
            states.keys().asSequence().forEach { path ->
                pathStates[path] = states.optString(path, "missing")
            }
        }
        return ScriptPluginAgentWorkspaceCheckpoint(
            stagingPath = obj.optString("stagingPath", ""),
            pluginId = obj.optString("pluginId", ""),
            existed = obj.optBoolean("existed", false),
            baseFingerprint = obj.optString("baseFingerprint", ""),
            stageFingerprint = obj.optString("stageFingerprint", ""),
            basePathStates = pathStates,
            initialPluginName = obj.optString("initialPluginName", ""),
            revision = obj.optInt("revision", 0),
            checkedRevision = obj.optInt("checkedRevision", -1),
            shownRevision = obj.optInt("shownRevision", -1),
            deletePlugin = obj.optBoolean("deletePlugin", false),
            updatedAt = obj.optLong("updatedAt", 0L)
        )
    }
}
