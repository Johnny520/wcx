package h.Hchat.hooks.items.selectedmessages

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.media.WeChatMediaApi
import h.Hchat.hooks.api.message.WeChatMessageApi
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskContentItem
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskSettings
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class CustomMassSendModuleSender(
    private val context: Context,
    private val logger: (String, Throwable?) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Hchat-CustomMassSend").apply { isDaemon = true }
    }
    private val operations = ConcurrentHashMap<String, SendOperation>()

    fun enqueue(
        items: List<ScheduledTaskContentItem>,
        targetIds: List<String>,
        targetIntervalSeconds: Int,
        itemIntervalSeconds: Int,
        onComplete: ((success: Int, total: Int, canceled: Boolean) -> Unit)? = null
    ): SelectedMessageSendHandle? {
        val contents = normalizeItems(items)
        val targets = targetIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (contents.isEmpty() || targets.isEmpty()) {
            return null
        }
        val globalIntervalSeconds = SelectedMessagesSettings.sendIntervalSeconds(context)
        val effectiveTargetIntervalSeconds = maxOf(targetIntervalSeconds, globalIntervalSeconds)
        val effectiveItemIntervalSeconds = maxOf(itemIntervalSeconds, globalIntervalSeconds)
        val operation = SendOperation(UUID.randomUUID().toString())
        operations[operation.id] = operation
        executor.execute {
            val messageApi = WeChatApis.message().sender() ?: WeChatApis.messages()
            val mediaApi = WeChatApis.media()
            var success = 0
            targets.forEachIndexed { targetIndex, talker ->
                if (operation.canceled.get()) return@forEachIndexed
                success += sendToTalker(
                    contents,
                    talker,
                    messageApi,
                    mediaApi,
                    effectiveItemIntervalSeconds,
                    operation
                )
                if (targetIndex < targets.lastIndex &&
                    waitForCancel(operation, effectiveTargetIntervalSeconds)
                ) {
                    return@forEachIndexed
                }
            }
            operations.remove(operation.id)
            main.post {
                onComplete?.invoke(success, contents.size * targets.size, operation.canceled.get())
            }
        }
        return SelectedMessageSendHandle { cancel(operation.id) }
    }

    fun shutdown() {
        operations.values.forEach { cancel(it) }
        operations.clear()
        executor.shutdownNow()
    }

    private fun sendToTalker(
        items: List<ScheduledTaskContentItem>,
        talker: String,
        messageApi: WeChatMessageApi?,
        mediaApi: WeChatMediaApi?,
        itemIntervalSeconds: Int,
        operation: SendOperation
    ): Int {
        val displayName = runCatching {
            WeChatApis.contact().contacts()?.getDisplayName(talker)
        }.getOrNull().orEmpty().ifBlank { talker }
        items.forEachIndexed { index, item ->
            if (operation.canceled.get()) return index
            val sent = runCatching {
                sendItem(item, talker, displayName, messageApi, mediaApi)
            }.onFailure {
                logger("自定义群发失败: target=$talker type=${item.type}", it)
            }.getOrDefault(false)
            if (!sent) return index
            if (index < items.lastIndex && waitForCancel(operation, itemIntervalSeconds)) return index + 1
        }
        return items.size
    }

    private fun sendItem(
        item: ScheduledTaskContentItem,
        talker: String,
        displayName: String,
        messageApi: WeChatMessageApi?,
        mediaApi: WeChatMediaApi?
    ): Boolean {
        return when (item.type) {
            ScheduledTaskSettings.TYPE_TEXT -> {
                val content = item.value.replace("%friendName%", displayName)
                content.isNotBlank() && messageApi?.sendText(talker, content) == true
            }
            ScheduledTaskSettings.TYPE_XML -> {
                val content = item.value.replace("%friendName%", displayName)
                content.isNotBlank() && messageApi?.sendXml(talker, content) == true
            }
            ScheduledTaskSettings.TYPE_FAVORITE -> {
                mediaApi?.favorites()?.send(talker, item.value) == true
            }
            ScheduledTaskSettings.TYPE_IMAGE,
            ScheduledTaskSettings.TYPE_VIDEO,
            ScheduledTaskSettings.TYPE_FILE,
            ScheduledTaskSettings.TYPE_EMOJI,
            ScheduledTaskSettings.TYPE_VOICE -> {
                val file = File(item.value)
                if (!file.isFile || mediaApi == null) return false
                when (item.type) {
                    ScheduledTaskSettings.TYPE_IMAGE -> mediaApi.images().sendOriginal(talker, item.value)
                    ScheduledTaskSettings.TYPE_VIDEO -> mediaApi.videos().send(talker, item.value)
                    ScheduledTaskSettings.TYPE_FILE -> mediaApi.sendFile(talker, item.value, file.name)
                    ScheduledTaskSettings.TYPE_EMOJI -> mediaApi.sendEmoji(talker, item.value)
                    ScheduledTaskSettings.TYPE_VOICE -> mediaApi.sendVoice(talker, item.value)
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun normalizeItems(items: List<ScheduledTaskContentItem>): List<ScheduledTaskContentItem> {
        return items.mapNotNull { item ->
            val value = item.value.trim()
            item.takeIf {
                value.isNotBlank() &&
                    item.type in ScheduledTaskSettings.TYPE_TEXT..ScheduledTaskSettings.TYPE_FAVORITE
            }?.copy(value = value)
        }
    }

    private fun waitForCancel(operation: SendOperation, seconds: Int): Boolean {
        if (operation.canceled.get()) return true
        val waitMillis = maxOf(
            ForwardSendPolicy.MIN_SEND_INTERVAL_MS,
            seconds.coerceIn(0, 3600).toLong() * 1000L
        )
        return try {
            operation.cancelSignal.await(waitMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            true
        }
    }

    private fun cancel(operationId: String) {
        operations[operationId]?.let(::cancel)
    }

    private fun cancel(operation: SendOperation) {
        operation.canceled.set(true)
        operation.cancelSignal.countDown()
    }

    private data class SendOperation(
        val id: String,
        val canceled: AtomicBoolean = AtomicBoolean(false),
        val cancelSignal: CountDownLatch = CountDownLatch(1)
    )

}

object SelectedMessagesRuntimeCoordinator {
    const val CHANNEL_MODULE = ScheduledTaskSettings.SEND_CHANNEL_MODULE
    const val CHANNEL_OFFICIAL = ScheduledTaskSettings.SEND_CHANNEL_OFFICIAL

    @Volatile
    private var state: State? = null

    internal fun attach(
        moduleSender: CustomMassSendModuleSender,
        selectedModuleSender: SelectedMessageModuleSender,
        officialSender: OfficialMassSendSender
    ) {
        state = State(moduleSender, selectedModuleSender, officialSender)
    }

    internal fun detach(moduleSender: CustomMassSendModuleSender) {
        if (state?.moduleSender === moduleSender) state = null
    }

    fun validationError(channel: Int, items: List<ScheduledTaskContentItem>): String? {
        val contents = normalizeItems(items)
        if (contents.isEmpty()) return "请配置发送内容"
        contents.firstOrNull { requiresFile(it.type) && !File(it.value).isFile }?.let {
            return "发送文件不存在: ${File(it.value).name.ifBlank { scheduledTypeLabel(it.type) }}"
        }
        val current = state ?: return "群发助手尚未就绪"
        if (channel == CHANNEL_OFFICIAL) {
            if (contents.any {
                    it.type == ScheduledTaskSettings.TYPE_XML &&
                        !MassSendContentPolicy.supportsOfficial(it)
                }
            ) {
                return "视频号内容无效，请填写视频号分享消息 XML"
            }
            val unsupported = contents.filterNot { MassSendContentPolicy.supportsOfficial(it) }
            if (unsupported.isNotEmpty()) {
                return "微信原生群发助手仅支持${MassSendContentPolicy.OFFICIAL_SUPPORTED_TYPES_TEXT}: " +
                    unsupported.map { MassSendContentPolicy.customTypeLabel(it.type) }
                        .distinct()
                        .joinToString("、")
            }
            if (!current.officialSender.isAvailable()) return "微信原生群发助手通道不可用"
            if (contents.any {
                    (it.type == ScheduledTaskSettings.TYPE_TEXT || it.type == ScheduledTaskSettings.TYPE_XML) &&
                        it.value.contains("%friendName%")
                }
            ) {
                return "微信原生群发助手不支持好友昵称变量"
            }
            val runtimeUnsupported = current.officialSender.unsupportedCustomLabels(contents)
            if (runtimeUnsupported.isNotEmpty()) {
                return "微信原生群发助手不支持: ${runtimeUnsupported.joinToString("、")}"
            }
            current.officialSender.customPreparationError(contents)?.let { return it }
        }
        return null
    }

    fun snapshotValidationError(channel: Int, snapshots: List<SelectedMessageSnapshot>): String? {
        if (snapshots.isEmpty()) return "请选择发送内容"
        val current = state ?: return "群发助手尚未就绪"
        if (channel != CHANNEL_OFFICIAL) return null
        if (!current.officialSender.isAvailable()) return "微信原生群发助手通道不可用"
        val unsupported = current.officialSender.unsupportedLabels(snapshots)
        if (unsupported.isNotEmpty()) {
            return "微信原生群发助手不支持: ${unsupported.joinToString("、")}"
        }
        return current.officialSender.preparationError(snapshots)
    }

    fun enqueueScheduledItems(
        channel: Int,
        items: List<ScheduledTaskContentItem>,
        targetIds: List<String>,
        targetIntervalSeconds: Int,
        itemIntervalSeconds: Int,
        onComplete: (success: Int, total: Int, canceled: Boolean) -> Unit
    ): SelectedMessageSendHandle? {
        val current = state ?: return null
        val contents = normalizeItems(items)
        val targets = targetIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (contents.isEmpty() || targets.isEmpty()) return null
        return if (channel == CHANNEL_OFFICIAL) {
            current.officialSender.enqueueCustom(
                contents,
                targets,
                targetIntervalSeconds,
                itemIntervalSeconds,
                onComplete
            )
        } else {
            current.moduleSender.enqueue(
                contents,
                targets,
                targetIntervalSeconds,
                itemIntervalSeconds,
                onComplete
            )
        }
    }

    fun enqueueScheduledSnapshots(
        channel: Int,
        snapshots: List<SelectedMessageSnapshot>,
        targetIds: List<String>,
        targetIntervalSeconds: Int = 0,
        itemIntervalSeconds: Int = 0,
        onComplete: (success: Int, total: Int, canceled: Boolean) -> Unit
    ): SelectedMessageSendHandle? {
        val current = state ?: return null
        val targets = targetIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (snapshots.isEmpty() || targets.isEmpty()) return null
        return if (channel == CHANNEL_OFFICIAL) {
            current.officialSender.enqueue(
                snapshots,
                targets,
                targetIntervalSeconds,
                itemIntervalSeconds,
                onComplete
            )
        } else {
            current.selectedModuleSender.enqueue(
                snapshots,
                targets,
                targetIntervalSeconds,
                itemIntervalSeconds,
                onComplete
            )
        }
    }

    fun sendCustom(
        activity: Activity,
        channel: Int,
        items: List<ScheduledTaskContentItem>,
        targetIds: List<String>,
        targetIntervalSeconds: Int,
        itemIntervalSeconds: Int
    ): Boolean {
        val contents = normalizeItems(items)
        val targets = targetIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        validationError(channel, contents)?.let {
            toast(activity, it)
            return false
        }
        if (targets.isEmpty()) {
            toast(activity, "请选择群发对象")
            return false
        }
        val current = state ?: return false
        var handle: SelectedMessageSendHandle? = null
        val official = channel == CHANNEL_OFFICIAL
        val finishing = AtomicBoolean(false)
        val progress = if (SelectedMessagesSettings.isBackgroundSilentSendEnabled(activity)) {
            null
        } else {
            VoiceForwardMiuixDialog.showLoading(
                activity = activity,
                onDismiss = { if (!finishing.get()) handle?.cancel() },
                title = if (official) "微信原生群发助手" else "模块群发",
                message = "正在发送..."
            )
        }
        val callback: (Int, Int, Boolean) -> Unit = { success, total, canceled ->
            finishing.set(true)
            progress?.close()
            val title = if (official) "原生群发" else "模块群发"
            val message = when {
                canceled -> "$title 已取消: $success/$total"
                success == total -> "$title 完成: $success/$total"
                else -> "$title 部分失败: $success/$total"
            }
            toast(activity, message)
        }
        handle = if (official) {
            current.officialSender.enqueueCustom(
                contents,
                targets,
                targetIntervalSeconds,
                itemIntervalSeconds,
                callback
            )
        } else {
            current.moduleSender.enqueue(
                contents,
                targets,
                targetIntervalSeconds,
                itemIntervalSeconds,
                callback
            )
        }
        if (handle == null) {
            finishing.set(true)
            progress?.close()
            toast(activity, if (official) "微信原生群发助手启动失败" else "模块群发启动失败")
            return false
        }
        toast(activity, if (official) "已开始微信原生群发" else "已开始模块群发")
        return true
    }

    private fun normalizeItems(items: List<ScheduledTaskContentItem>): List<ScheduledTaskContentItem> {
        return items.mapNotNull { item ->
            val value = item.value.trim()
            item.takeIf {
                value.isNotBlank() &&
                    item.type in ScheduledTaskSettings.TYPE_TEXT..ScheduledTaskSettings.TYPE_FAVORITE
            }?.copy(value = value)
        }
    }

    private fun requiresFile(type: Int): Boolean {
        return type == ScheduledTaskSettings.TYPE_IMAGE ||
            type == ScheduledTaskSettings.TYPE_VIDEO ||
            type == ScheduledTaskSettings.TYPE_FILE ||
            type == ScheduledTaskSettings.TYPE_EMOJI ||
            type == ScheduledTaskSettings.TYPE_VOICE
    }

    private fun scheduledTypeLabel(type: Int): String = MassSendContentPolicy.customTypeLabel(type)

    private fun toast(activity: Activity, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(activity.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private data class State(
        val moduleSender: CustomMassSendModuleSender,
        val selectedModuleSender: SelectedMessageModuleSender,
        val officialSender: OfficialMassSendSender
    )
}
