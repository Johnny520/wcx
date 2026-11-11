package h.Hchat.hooks.items.selectedmessages

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.core.FeatureContext
import java.io.File
import java.util.ArrayDeque
import java.util.UUID

class SelectedMessageModuleSender(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val batches = ArrayDeque<SendBatch>()
    private var activeBatch: SendBatch? = null
    private var activeToken = ""
    private var activeRetransmitTargets = emptyList<String>()
    private var timeoutRunnable: Runnable? = null

    fun enqueue(
        snapshots: List<SelectedMessageSnapshot>,
        targetIds: List<String>,
        targetIntervalSeconds: Int = 0,
        itemIntervalSeconds: Int = 0,
        onComplete: ((success: Int, total: Int, canceled: Boolean) -> Unit)? = null
    ): SelectedMessageSendHandle? {
        return enqueueInternal(
            snapshots,
            targetIds,
            targetIntervalSeconds,
            itemIntervalSeconds,
            onComplete
        )
    }

    fun enqueueMassSend(
        snapshots: List<SelectedMessageSnapshot>,
        targetIds: List<String>,
        targetIntervalSeconds: Int = 0,
        itemIntervalSeconds: Int = 0,
        onComplete: ((success: Int, total: Int, canceled: Boolean) -> Unit)? = null
    ): SelectedMessageSendHandle? {
        return enqueueInternal(
            snapshots,
            targetIds,
            targetIntervalSeconds,
            itemIntervalSeconds,
            onComplete
        )
    }

    private fun enqueueInternal(
        snapshots: List<SelectedMessageSnapshot>,
        targetIds: List<String>,
        targetIntervalSeconds: Int,
        itemIntervalSeconds: Int,
        onComplete: ((success: Int, total: Int, canceled: Boolean) -> Unit)?
    ): SelectedMessageSendHandle? {
        if (snapshots.isEmpty()) return null
        val messages = snapshots.filter { it.retransmit != null || it.voicePath.isNotBlank() }
        val targets = targetIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (messages.size != snapshots.size || targets.isEmpty()) return null
        val globalIntervalSeconds = SelectedMessagesSettings.sendIntervalSeconds(context.hostContext())
        val targetDelayMillis = maxOf(
            ForwardSendPolicy.MIN_SEND_INTERVAL_MS,
            maxOf(targetIntervalSeconds, globalIntervalSeconds).coerceIn(0, 3600) * 1000L
        )
        val itemDelayMillis = maxOf(
            NEXT_MESSAGE_DELAY_MS,
            maxOf(itemIntervalSeconds, globalIntervalSeconds).coerceIn(0, 3600) * 1000L
        )
        val batch = SendBatch(
            UUID.randomUUID().toString(),
            messages,
            targets,
            onComplete,
            targetDelayMillis = targetDelayMillis,
            itemDelayMillis = itemDelayMillis
        )
        main.post {
            batches.addLast(batch)
            if (activeBatch == null) startNextBatch()
        }
        return SelectedMessageSendHandle { cancel(batch.id) }
    }

    fun handleRetransmitDone(activity: Activity): Boolean {
        val token = activity.intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (token.isBlank() || token != activeToken) return false
        val batch = activeBatch ?: return false
        timeoutRunnable?.let(main::removeCallbacks)
        timeoutRunnable = null
        val completedTargets = activeRetransmitTargets
        batch.success += completedTargets.size
        advanceTargets(batch, completedTargets.size, ForwardSendPolicy.RETRANSMIT_BATCH_INTERVAL_MS)
        val result = Intent().apply {
            putStringArrayListExtra("SendMsgUsernames", ArrayList(completedTargets))
            putExtra("sendResult", 0)
        }
        activity.setResult(Activity.RESULT_OK, result)
        activity.finish()
        return true
    }

    private fun startNextBatch() {
        if (activeBatch != null) return
        activeBatch = batches.pollFirst()
        val batch = activeBatch ?: return
        batch.total = batch.snapshots.size * batch.targets.size
        sendCurrent(batch)
    }

    private fun sendCurrent(batch: SendBatch) {
        if (activeBatch !== batch) return
        if (batch.index >= batch.snapshots.size) {
            val callback = batch.onComplete
            val success = batch.success
            val total = batch.total
            activeBatch = null
            activeToken = ""
            callback?.invoke(success, total, false)
            startNextBatch()
            return
        }
        val snapshot = batch.snapshots[batch.index]
        val directPlan = directPlan(batch, snapshot)
        if (directPlan != null) {
            sendDirectTarget(batch, snapshot, directPlan)
            return
        }
        val payload = snapshot.retransmit
        if (payload == null) {
            completeCurrentMessage(batch)
            return
        }
        if (batch.targetIndex >= batch.targets.size) {
            completeCurrentMessage(batch)
            return
        }
        // MsgRetransmitUI treats Select_Conv_User as one talker and does not
        // split comma-separated values. Keep the native fallback single-target
        // so group-expanded recipients are sent one by one.
        val retransmitTargets = listOf(batch.targets[batch.targetIndex])
        val token = UUID.randomUUID().toString()
        activeToken = token
        activeRetransmitTargets = retransmitTargets
        val activity = WeChatApis.currentActivity()?.currentActivity() as? Activity
        val launchContext = activity ?: context.hostContext()
        val intent = Intent().apply {
            setClassName(context.hostContext().packageName, MSG_RETRANSMIT_UI)
            if (activity == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("Retr_MsgQuickShare", true)
            putExtra("Select_Conv_User", retransmitTargets.single())
            putExtra("custom_send_text", "")
            putExtra("Retr_Msg_Type", payload.retrType)
            putExtra("Retr_Msg_Id", payload.msgId)
            putExtra("Retr_MsgTalker", payload.sourceTalker)
            putExtra("Retr_Msg_content", payload.content)
            putExtra("Retr_File_Name", payload.fileName)
            putExtra("Edit_Mode_Sigle_Msg", true)
            putExtra("Retr_MsgFromScene", payload.msgFromScene)
            putExtra("Retr_show_success_tips", false)
            putExtra("Retr_go_to_chattingUI", false)
            putExtra("Retr_start_where_you_are", true)
            putExtra("scene_from", 17)
            putExtra(EXTRA_TOKEN, token)
            if (payload.length > 0) putExtra("Retr_length", payload.length)
        }
        runCatching { launchContext.startActivity(intent) }
            .onSuccess {
                timeoutRunnable = Runnable { handleTimeout(token) }.also {
                    main.postDelayed(it, SEND_TIMEOUT_MS)
                }
            }
            .onFailure {
                logger("群发助手启动微信重发失败", it)
                advanceTargets(
                    batch,
                    retransmitTargets.size,
                    ForwardSendPolicy.RETRANSMIT_BATCH_INTERVAL_MS
                )
            }
    }

    private fun handleTimeout(token: String) {
        if (token != activeToken) return
        logger("群发助手等待微信重发完成超时", null)
        (WeChatApis.currentActivity()?.currentActivity() as? Activity)
            ?.takeIf { it.intent?.getStringExtra(EXTRA_TOKEN) == token }
            ?.finish()
        val completedCount = activeRetransmitTargets.size
        activeBatch?.let {
            advanceTargets(it, completedCount, ForwardSendPolicy.RETRANSMIT_BATCH_INTERVAL_MS)
        }
    }

    private fun directPlan(batch: SendBatch, snapshot: SelectedMessageSnapshot): DirectSendPlan? {
        if (batch.preparedSnapshotIndex == batch.index) return batch.directPlan
        val type = snapshot.type and 0xffff
        val payload = snapshot.retransmit
        val messageApi = WeChatApis.message().sender() ?: WeChatApis.messages()
        val mediaApi = WeChatApis.media()
        val videoPath = if (type == 43 || type == 62) resolveVideoPath(snapshot) else ""
        val source = when (type) {
            34 -> snapshot.voicePath
            43, 62 -> videoPath
            47 -> emojiSource(snapshot)
            else -> ""
        }
        val canHandle = when (type) {
            1, 42, 48 -> messageApi != null && !payload?.content.isNullOrBlank()
            49 -> !snapshot.isFile() && messageApi != null && !payload?.content.isNullOrBlank()
            34, 43, 47, 62 -> mediaApi != null && source.isNotBlank() &&
                (type == 47 || File(source).isFile)
            else -> false
        }
        batch.preparedSnapshotIndex = batch.index
        batch.directPlan = if (canHandle) DirectSendPlan(type, source) else null
        return batch.directPlan
    }

    private fun sendDirectTarget(
        batch: SendBatch,
        snapshot: SelectedMessageSnapshot,
        plan: DirectSendPlan
    ) {
        if (batch.targetIndex >= batch.targets.size) {
            completeCurrentMessage(batch)
            return
        }
        val target = batch.targets[batch.targetIndex]
        val sent = runCatching {
            val messageApi = WeChatApis.message().sender() ?: WeChatApis.messages()
            val mediaApi = WeChatApis.media()
            val content = snapshot.retransmit?.content.orEmpty()
            when (plan.type) {
                1 -> messageApi?.sendText(target, content) == true
                34 -> mediaApi?.voices()?.send(target, plan.source, snapshot.voiceDurationMillis) == true
                42 -> messageApi?.sendRaw(target, content, 42) == true
                43, 62 -> mediaApi?.videos()?.send(target, plan.source) == true
                47 -> mediaApi?.sendEmoji(target, plan.source) == true
                48 -> messageApi?.sendRaw(target, content, 48) == true
                49 -> messageApi?.sendXml(target, content) == true
                else -> false
            }
        }.onFailure {
            logger("群发助手模块发送失败: target=$target type=${plan.type}", it)
        }.getOrDefault(false)
        if (sent) batch.success++
        advanceTargets(batch, 1, ForwardSendPolicy.MIN_SEND_INTERVAL_MS)
    }

    private fun resolveVideoPath(snapshot: SelectedMessageSnapshot): String {
        val candidates = listOf(snapshot.retransmit?.fileName.orEmpty(), snapshot.imagePath)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        candidates.firstOrNull { File(it).isFile }?.let { return File(it).absolutePath }
        val videos = WeChatApis.media()?.videos() ?: return ""
        return candidates.firstNotNullOfOrNull { token ->
            videos.resolvePathToken(token).takeIf { it.isNotBlank() }
        }.orEmpty()
    }

    private fun emojiSource(snapshot: SelectedMessageSnapshot): String {
        val payload = snapshot.retransmit
        val fileName = payload?.fileName.orEmpty().trim()
        if (File(fileName).isFile || fileName.matches(Regex("[0-9a-fA-F]{32}"))) return fileName
        val content = payload?.content.orEmpty().ifBlank { snapshot.content }
        return WeChatMessage.xmlAttr(content, "md5")
            .ifBlank { WeChatMessage.xmlTag(content, "md5") }
    }

    private fun completeCurrentMessage(batch: SendBatch) {
        activeToken = ""
        activeRetransmitTargets = emptyList()
        timeoutRunnable?.let(main::removeCallbacks)
        timeoutRunnable = null
        batch.index++
        batch.targetIndex = 0
        batch.preparedSnapshotIndex = -1
        batch.directPlan = null
        if (batch.index >= batch.snapshots.size) {
            main.post { sendCurrent(batch) }
        } else {
            main.postDelayed({ sendCurrent(batch) }, batch.itemDelayMillis)
        }
    }

    private fun advanceTargets(batch: SendBatch, count: Int, delayMillis: Long) {
        activeToken = ""
        activeRetransmitTargets = emptyList()
        timeoutRunnable?.let(main::removeCallbacks)
        timeoutRunnable = null
        batch.targetIndex = (batch.targetIndex + count).coerceAtMost(batch.targets.size)
        if (batch.targetIndex >= batch.targets.size) {
            completeCurrentMessage(batch)
        } else {
            main.postDelayed({ sendCurrent(batch) }, maxOf(delayMillis, batch.targetDelayMillis))
        }
    }

    private fun cancel(batchId: String) {
        main.post {
            val active = activeBatch
            if (active?.id == batchId) {
                val token = activeToken
                timeoutRunnable?.let(main::removeCallbacks)
                timeoutRunnable = null
                activeToken = ""
                activeRetransmitTargets = emptyList()
                (WeChatApis.currentActivity()?.currentActivity() as? Activity)
                    ?.takeIf { it.intent?.getStringExtra(EXTRA_TOKEN) == token }
                    ?.finish()
                activeBatch = null
                active.onComplete?.invoke(active.success, active.total, true)
                startNextBatch()
                return@post
            }
            val iterator = batches.iterator()
            while (iterator.hasNext()) {
                val pending = iterator.next()
                if (pending.id != batchId) continue
                iterator.remove()
                pending.onComplete?.invoke(0, pending.snapshots.size * pending.targets.size, true)
                break
            }
        }
    }

    fun shutdown() {
        main.post {
            val active = activeBatch
            val token = activeToken
            timeoutRunnable?.let(main::removeCallbacks)
            timeoutRunnable = null
            activeToken = ""
            activeRetransmitTargets = emptyList()
            (WeChatApis.currentActivity()?.currentActivity() as? Activity)
                ?.takeIf { it.intent?.getStringExtra(EXTRA_TOKEN) == token }
                ?.finish()
            activeBatch = null
            active?.onComplete?.invoke(active.success, active.total, true)
            while (batches.isNotEmpty()) {
                val pending = batches.removeFirst()
                pending.onComplete?.invoke(0, pending.snapshots.size * pending.targets.size, true)
            }
        }
    }

    private data class SendBatch(
        val id: String,
        val snapshots: List<SelectedMessageSnapshot>,
        val targets: List<String>,
        val onComplete: ((Int, Int, Boolean) -> Unit)?,
        var index: Int = 0,
        var targetIndex: Int = 0,
        var success: Int = 0,
        var total: Int = 0,
        var preparedSnapshotIndex: Int = -1,
        var directPlan: DirectSendPlan? = null,
        val targetDelayMillis: Long,
        val itemDelayMillis: Long
    )

    private data class DirectSendPlan(
        val type: Int,
        val source: String
    )

    companion object {
        const val MSG_RETRANSMIT_UI = "com.tencent.mm.ui.transmit.MsgRetransmitUI"
        const val EXTRA_TOKEN = "hchat_selected_message_send_token"
        private const val SEND_TIMEOUT_MS = 120_000L
        private const val NEXT_MESSAGE_DELAY_MS = 350L
    }
}
