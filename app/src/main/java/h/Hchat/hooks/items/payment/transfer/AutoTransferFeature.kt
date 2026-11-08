package h.Hchat.hooks.items.payment.transfer

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.message.WeChatMessageObserveApi
import h.Hchat.hooks.api.payment.TransferOperationParams
import h.Hchat.hooks.api.payment.TransferQueryParams
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import java.util.concurrent.ConcurrentHashMap

class AutoTransferFeature : BaseFeature() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val handledTransfers = ConcurrentHashMap<String, Long>()
    @Volatile private var observerInstalled = false
    @Volatile private var feedback: TransferSuccessFeedback? = null

    private data class TransferAttempt(val success: Boolean, val failure: String = "")

    override fun featureId(): String = ID

    override fun name(): String = "自动收款"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(AutoTransferSettingsProvider())
    }

    @Throws(Throwable::class)
    override fun onFeatureInstall(context: FeatureContext) {
        scheduleInstall(context)
        subscribe(Events.DexReady::class.java) {
            scheduleInstall(context)
        }
    }

    private fun scheduleInstall(context: FeatureContext) {
        DexInstallScheduler.schedule(ID, name(), stage = DexInstallScheduler.Stage.WARMUP) {
            try {
                TransferReceiveAccountStore.install(context, ::logError)
                if (feedback == null) {
                    feedback = TransferSuccessFeedback(context.hostContext(), ::logError)
                }
                if (observerInstalled) return@schedule true
                val observer = WeChatApis.message().observe()
                if (observer == null || !observer.isAvailable) {
                    logError("消息观察不可用，自动收款未安装", null)
                    return@schedule false
                } else {
                    trackSubscription(observer.subscribe { message -> handleMessage(context, message) })
                    observerInstalled = true
                    return@schedule true
                }
            } catch (e: Throwable) {
                logError("自动收款安装失败", e)
                false
            }
        }
    }

    private fun handleMessage(context: FeatureContext, message: WeChatMessageObserveApi.ObservedMessage?) {
        if (message == null) return
        val rawContent = message.content.ifBlank { message.xml }
        if (message.outgoing) {
            return
        }
        val settings = AutoTransferSettings(context.hostContext())
        if (!settings.isEnabled()) {
            return
        }

        val info = TransferMessageParser.parse(rawContent, message.sender)
        if (info == null) {
            return
        }
        if (!info.pending) {
            return
        }

        val selfWxId = WeChatApis.contact().account()?.selfWxId().orEmpty()
        if (info.receiverUsername.isNotBlank() && selfWxId.isNotBlank() && info.receiverUsername != selfWxId) {
            return
        }
        if (selfWxId.isNotBlank() && info.payerUsername == selfWxId) {
            return
        }

        val rule = TransferRuleResolver(settings).resolve(
            message.talker,
            info.payerUsername.ifBlank { message.sender }
        )
        if (!rule.enabled || !markTransferOnce(info)) return
        val rejectReason = rejectReason(rule, info, message)
        val delay = rule.nextDelayMillis()
        val action = Runnable {
            if (rejectReason == null) {
                receiveTransfer(settings, rule, message, info)
            } else if (rule.refundRejected) {
                refundTransfer(message, info, rejectReason)
            }
        }
        if (delay > 0L) {
            mainHandler.postDelayed(action, delay)
        } else {
            action.run()
        }
    }

    private fun rejectReason(
        rule: TransferEffectiveRule,
        info: TransferMessageInfo,
        message: WeChatMessageObserveApi.ObservedMessage
    ): String? {
        val payer = info.payerUsername.ifBlank { message.sender }
        val talker = message.talker
        val group = message.group || talker.endsWith("@chatroom")
        if (rule.isInQuietTime()) return "当前处于禁收时段"
        when (rule.listMode) {
            1 -> {
                val allowed = idInList(payer, rule.whitelist) || (group && idInList(talker, rule.whitelist))
                if (!allowed) return "非白名单"
            }
            2 -> {
                val blocked = idInList(payer, rule.blacklist) || (group && idInList(talker, rule.blacklist))
                if (blocked) return "黑名单"
            }
        }

        if (rule.amountEnabled) {
            val limit = rule.amountValue.toDoubleOrNull() ?: 0.0
            val matched = when (rule.amountCondition) {
                0 -> info.amount > limit
                2 -> kotlin.math.abs(info.amount - limit) < 0.01
                else -> info.amount < limit
            }
            val action = rule.amountAction
            if (action == 0 && matched) return "金额规则"
            if (action == 1 && !matched) return "金额规则"
        }

        val keywordMode = rule.keywordMode
        val hasKeyword = TransferMessageParser.containsKeywords(info.rawXml, rule.keywords)
        if (keywordMode == 1 && !hasKeyword) return "缺少关键词"
        if (keywordMode == 2 && hasKeyword) return "关键词规则"
        return null
    }

    private fun receiveTransfer(
        settings: AutoTransferSettings,
        rule: TransferEffectiveRule,
        message: WeChatMessageObserveApi.ObservedMessage,
        info: TransferMessageInfo
    ) {
        val selectedKey = rule.receiveAccount
        if (selectedKey == TransferReceiveAccountStore.DEFAULT_KEY) {
            finishReceive(rule, message, info, null)
            return
        }
        val cachedAccount = TransferReceiveAccountStore.find(settings.hostContext(), selectedKey)
        if (cachedAccount != null) {
            finishReceive(rule, message, info, cachedAccount)
            return
        }
        val api = WeChatApis.payment().transfers()
        if (api == null || !api.canQuery()) {
            finishReceive(rule, message, info, null)
            return
        }
        val started = api.query(
            TransferQueryParams(
                transactionId = info.transactionId,
                transId = info.transId,
                invalidTime = info.invalidTime,
                groupUsername = if (message.talker.endsWith("@chatroom")) message.talker else "",
                transferAttach = info.transferAttach
            )
        ) { result ->
            val accounts = if (result != null && result.errorCode == 0) {
                TransferReceiveAccountStore.captureResponse(settings.hostContext(), result.response)
            } else {
                emptyList()
            }
            val matched = TransferReceiveAccountStore.find(accounts, selectedKey)
            finishReceive(rule, message, info, matched)
        }
        if (!started) {
            finishReceive(rule, message, info, null)
        }
    }

    private fun finishReceive(
        rule: TransferEffectiveRule,
        message: WeChatMessageObserveApi.ObservedMessage,
        info: TransferMessageInfo,
        account: TransferReceiveAccount?
    ) {
        val attempt = operateTransfer(message, info, "confirm", account)
        if (!attempt.success) {
            logError(
                if (attempt.failure.isBlank()) "自动收款失败" else "自动收款失败: ${attempt.failure}",
                null
            )
        }
        if (!attempt.success) return
        feedback?.onReceived(transferKey(info), message, info, rule)
    }

    private fun refundTransfer(
        message: WeChatMessageObserveApi.ObservedMessage,
        info: TransferMessageInfo,
        reason: String
    ) {
        val attempt = operateTransfer(message, info, "refuse", null)
        if (!attempt.success) {
            logError(
                buildString {
                    append("自动退回失败: ")
                    append(reason)
                    if (attempt.failure.isNotBlank()) append("; ").append(attempt.failure)
                },
                null
            )
        }
    }

    private fun operateTransfer(
        message: WeChatMessageObserveApi.ObservedMessage,
        info: TransferMessageInfo,
        op: String,
        account: TransferReceiveAccount?
    ): TransferAttempt {
        if (TextUtils.isEmpty(info.transactionId) || TextUtils.isEmpty(info.transId)) {
            return TransferAttempt(false, "转账单号缺失")
        }
        val api = WeChatApis.payment().transfers()
        if (api == null || !api.canOperate()) {
            return TransferAttempt(
                false,
                "转账 API 未就绪: api=${api != null} canOperate=${api?.canOperate()}"
            )
        }
        val params = TransferOperationParams(
            transactionId = info.transactionId,
            transId = info.transId,
            totalFee = info.totalFee,
            username = info.payerUsername,
            invalidTime = info.invalidTime,
            groupUsername = message.talker.takeIf {
                message.group || it.endsWith("@chatroom")
            }.orEmpty(),
            recvAccountType = account?.accountType ?: 0,
            bindSerial = account?.bindSerial.orEmpty(),
            subRecvChannelId = account?.subChannelId ?: 0L,
            transferAttach = info.transferAttach
        )
        val success = if (op == "refuse") api.refund(params) else api.receive(params)
        return TransferAttempt(success, if (success) "" else api.lastFailureReason())
    }

    private fun markTransferOnce(info: TransferMessageInfo): Boolean {
        val key = transferKey(info)
        if (key == "::") return true
        cleanupHandledTransfers()
        return handledTransfers.putIfAbsent(key, System.currentTimeMillis()) == null
    }

    private fun cleanupHandledTransfers() {
        if (handledTransfers.size < 64) return
        val deadline = System.currentTimeMillis() - HANDLED_TRANSFER_TTL_MS
        handledTransfers.entries.removeIf { it.value < deadline }
    }

    private fun transferKey(info: TransferMessageInfo): String = buildString {
        append(info.transactionId)
        append(':')
        append(info.transId)
        append(':')
        append(info.payerUsername)
    }

    private fun idInList(id: String?, value: String): Boolean {
        if (id.isNullOrBlank()) return false
        return value.split("|", ",", "，", "\n", "\r")
            .any { it.trim() == id }
    }

    companion object {
        const val ID = "auto_transfer"
        private const val HANDLED_TRANSFER_TTL_MS = 60 * 60 * 1000L
    }
}
