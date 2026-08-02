package com.Johnny.wcx.features.items.payment

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.net.toUri
import dev.ujhhgtg.reflekt.utils.createInstance
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.net.WeNetSceneApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.random.Random

@SuppressLint("DiscouragedApi")
@Feature(name = "自动抢红包", categories = ["红包与支付"], description = "监听消息并自动拆开红包")
object AutoOpenRedPackets : ClickableFeature(), WeDatabaseListenerApi.IInsertListener,
    IResolveDex {

    private const val TAG = "AutoOpenRedPackets"

    private val classReceiveLuckyMoney by dexClass {
        matcher {
            methods {
                add {
                    name = "<init>"
                    usingEqStrings("MicroMsg.NetSceneReceiveLuckyMoney")
                }
            }
        }
    }
    private val classOpenLuckyMoney by dexClass {
        matcher {
            methods {
                add {
                    name = "<init>"
                    usingEqStrings("MicroMsg.NetSceneOpenLuckyMoney")
                }
            }
        }
    }
    private val methodReceiveOnGYNetEnd by dexMethod {
        matcher {
            declaredClass(classReceiveLuckyMoney.clazz)
            name = "onGYNetEnd"
            paramCount = 3
        }
    }
    private val methodOpenOnGYNetEnd by dexMethod {
        matcher {
            declaredClass(classOpenLuckyMoney.clazz)
            name = "onGYNetEnd"
            paramCount = 3
        }
    }

    private val currentRedPacketMap = ConcurrentHashMap<String, RedPacketInfo>()

    private var packetNotif by WePrefs.prefOption("red_packet_notification", false)
    private var packetSelf by WePrefs.prefOption("red_packet_self", false)
    private var packetUseWhitelist by WePrefs.prefOption("red_packet_use_whitelist", false)
    private var packetWhitelist by WePrefs.prefOption("red_packet_whitelist", emptySet())
    private var packetBlacklist by WePrefs.prefOption("red_packet_blacklist", emptySet())
    private var packetDelayCustom by WePrefs.prefOption("red_packet_delay_custom", "0")
    private var packetDelayRandomRange by WePrefs.prefOption("red_packet_delay_random_range", "300")
    private var packetAutoReply by WePrefs.prefOption("red_packet_auto_reply", "")

    // ── 新增：私聊/群聊分离延迟配置 ─────────────────────────────────────────────────
    private var packetDelayMinPrivate by WePrefs.prefOption("red_packet_delay_min_private", "200")
    private var packetDelayMaxPrivate by WePrefs.prefOption("red_packet_delay_max_private", "500")
    private var packetDelayMinGroup by WePrefs.prefOption("red_packet_delay_min_group", "300")
    private var packetDelayMaxGroup by WePrefs.prefOption("red_packet_delay_max_group", "800")

    // ── 极速模式 ──────────────────────────────────────────────────────────────────
    private var packetSpeedMode by WePrefs.prefOption("red_packet_speed_mode", false)

    // ── 去重与重试 ────────────────────────────────────────────────────────────────
    private val processedSendIds = ConcurrentHashMap.newKeySet<String>()
    private val retryCountMap = ConcurrentHashMap<String, Int>()
    private const val MAX_RETRIES = 3
    private const val MIN_DELAY_MS = 100L // 内置最小延迟保护

    private data class RedPacketInfo(
        val sendId: String,
        val nativeUrl: String,
        val talker: String,
        val msgType: Int,
        val channelId: Int,
        val headImg: String = "",
        val nickName: String = ""
    )

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)

        methodReceiveOnGYNetEnd.hookAfter {
            val json = args[2] as? JSONObject ?: return@hookAfter
            val sendId = json.optString("sendId")
            val timingIdentifier = json.optString("timingIdentifier")

            if (timingIdentifier.isNullOrEmpty() || sendId.isNullOrEmpty()) return@hookAfter

            val info = currentRedPacketMap[sendId] ?: run {
                WeLogger.e(TAG, "failed to find red packet in map (sendId=$sendId)")
                return@hookAfter
            }

            val retCode = json.optInt("retcode", -1)
            if (retCode != 0) {
                // 重试逻辑
                val retries = retryCountMap.getOrDefault(sendId, 0)
                if (retries < MAX_RETRIES) {
                    retryCountMap[sendId] = retries + 1
                    val retryDelay = Random.nextLong(200, 1000)
                    WeLogger.w(TAG, "receive failed (retcode=$retCode), retry ${retries + 1}/$MAX_RETRIES after ${retryDelay}ms (sendId=$sendId)")
                    thread(name = "RetryReceiveRedPacket") {
                        Thread.sleep(retryDelay)
                        try {
                            val req = classReceiveLuckyMoney.clazz.createInstance(
                                info.msgType, info.channelId, info.sendId, info.nativeUrl,
                                1, "v1.0", info.talker
                            )
                            WeNetSceneApi.sendNetScene(req)
                        } catch (e: Throwable) {
                            WeLogger.e(TAG, "retry receive failed (sendId=$sendId)", e)
                            currentRedPacketMap.remove(sendId)
                            retryCountMap.remove(sendId)
                            processedSendIds.remove(sendId)
                        }
                    }
                } else {
                    WeLogger.e(TAG, "receive exhausted retries (sendId=$sendId)")
                    currentRedPacketMap.remove(sendId)
                    retryCountMap.remove(sendId)
                    processedSendIds.remove(sendId)
                }
                return@hookAfter
            }

            WeLogger.i(TAG, "unpack request finished, sending open request ($sendId)")

            thread(name = "OpenRedPacketThread") {
                try {
                    val openReq = classOpenLuckyMoney.clazz.createInstance(
                        info.msgType, info.channelId, info.sendId, info.nativeUrl,
                        info.headImg, info.nickName, info.talker,
                        "v1.0", timingIdentifier, ""
                    )
                    WeNetSceneApi.sendNetScene(openReq)
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "failed to send open request", e)
                    currentRedPacketMap.remove(sendId)
                    retryCountMap.remove(sendId)
                    processedSendIds.remove(sendId)
                }
            }
        }

        methodOpenOnGYNetEnd.hookAfter {
            val json = args[2] as? JSONObject ?: return@hookAfter

            val sendId = json.optString("sendId")
            if (sendId.isNullOrEmpty()) return@hookAfter

            val info = currentRedPacketMap.remove(sendId) ?: return@hookAfter
            retryCountMap.remove(sendId)
            processedSendIds.remove(sendId)

            val retCode = json.optInt("retcode", -1)
            if (retCode != 0) {
                WeLogger.w(TAG, "failed to grab packet (retcode=$retCode, sendId=$sendId)")
                return@hookAfter
            }

            val receiveStatus = json.optInt("receiveStatus", -1)
            if (receiveStatus != 2) {
                WeLogger.w(TAG, "missed the packet (recvStatus=$receiveStatus, sendId=$sendId)")
                return@hookAfter
            }

            val amount = json.optInt("amount", 0)
            if (amount <= 0) return@hookAfter

            val displayAmount = amount / 100.0

            val reply = packetAutoReply
            if (reply.isNotBlank()) {
                WeMessageApi.sendText(info.talker, reply.replace($$"$amount", "¥$displayAmount"))
            }

            if (!packetNotif) return@hookAfter

            val displayName = WeDatabaseApi.getDisplayName(info.talker)
            val isGroup = info.talker.isGroupChatWxId
            val sourceLabel = if (isGroup) "群组" else "私聊"
            showToast("抢到${sourceLabel}「${displayName}」中的红包 ¥${displayAmount}")
        }
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type") ?: 0
        val isKnownRedPacket = MessageType.fromCode(type)?.isRedPacket ?: false
        val isLikelyRedPacket = !isKnownRedPacket && isContentRedPacket(values)

        if (isKnownRedPacket || isLikelyRedPacket) {
            if (isLikelyRedPacket) {
                WeLogger.i(TAG, "detected red packet via content fallback; type=$type")
            } else {
                WeLogger.i(TAG, "detected red packet message; type=$type")
            }
            handleRedPacket(values)
        }
    }

    /**
     * Content-based red packet detection fallback for enterprise WeChat interop groups
     * where the message type code may differ from standard red packet codes.
     * Does NOT try to capture red packets sent directly from enterprise WeChat client.
     */
    private fun isContentRedPacket(values: ContentValues): Boolean {
        val content = values.getAsString("content") ?: return false
        if (!content.contains("nativeurl") || !content.contains("hongbao")) return false

        val talker = values.getAsString("talker") ?: return false
        if (!talker.isGroupChatWxId) return false

        // Only capture red packets from WeChat users in interop groups,
        // NOT from enterprise WeChat client users (openim_/wm_ prefix)
        val senderPrefix = content.substringBefore(":")
        if (senderPrefix.startsWith("openim_") || senderPrefix.startsWith("wm_")) {
            WeLogger.i(TAG, "skipping enterprise WeChat sender in interop group: $senderPrefix")
            return false
        }

        WeLogger.i(TAG, "interop group red packet detected via content: talker=$talker, sender=$senderPrefix")
        return true
    }

    private fun handleRedPacket(values: ContentValues) {
        try {
            val msgInfo = MessageInfo.fromContentValues(values)
            if (msgInfo.isSelfSender && !packetSelf) return

            val talker = msgInfo.talker
            val isInteropGroup = talker.endsWith("@im.chatroom")
            if (isInteropGroup) {
                WeLogger.i(TAG, "detected interop group message: talker=$talker")
            }

            if (packetUseWhitelist) {
                if (talker !in packetWhitelist) {
                    WeLogger.i(TAG, "skipping packet from $talker due to not in whitelist")
                    return
                }
            } else {
                if (talker in packetBlacklist) {
                    WeLogger.i(TAG, "skipping packet from $talker due to in blacklist")
                    return
                }
            }

            val content = msgInfo.content
            val isGroupChat = msgInfo.isInGroupChat
            val sender = msgInfo.sender

            // Skip red packets sent by enterprise WeChat (企业微信) users in interop groups
            if (isInteropGroup && (sender.startsWith("openim_") || sender.startsWith("wm_"))) {
                WeLogger.i(TAG, "skipping enterprise WeChat sender in interop group: $sender")
                return
            }

            if (isGroupChat && !RedPacketGroupMemberFilter.shouldGrab(talker, sender)) {
                WeLogger.i(TAG, "skipping packet from $sender in $talker per group member filter")
                return
            }

            var xmlContent = content
            if (!content.startsWith("<") && content.contains(":")) {
                xmlContent = content.substring(content.indexOf(":") + 1).trim()
            }

            val nativeUrl = extractXmlParam(xmlContent, "nativeurl")
            if (nativeUrl.isEmpty()) return

            val uri = nativeUrl.toUri()
            val msgType = uri.getQueryParameter("msgtype")?.toIntOrNull() ?: 1
            val channelId = uri.getQueryParameter("channelid")?.toIntOrNull() ?: 1
            val sendId = uri.getQueryParameter("sendid") ?: ""
            val headImg = extractXmlParam(xmlContent, "headimgurl")
            val nickName = extractXmlParam(xmlContent, "sendertitle")

            if (sendId.isEmpty()) return

            // ── 去重：同一 sendId 只处理一次 ──────────────────────────────────────
            if (!processedSendIds.add(sendId)) {
                WeLogger.i(TAG, "skipping duplicate red packet (sendId=$sendId)")
                return
            }

            WeLogger.i(TAG, "detected red packet (sendId=$sendId)")

            currentRedPacketMap[sendId] = RedPacketInfo(
                sendId = sendId,
                nativeUrl = nativeUrl,
                talker = talker,
                msgType = msgType,
                channelId = channelId,
                headImg = headImg,
                nickName = nickName
            )

            // ── 延迟计算：私聊/群聊分离 + 极速模式 + 内置最小延迟保护 ─────────────
            val delayTime = if (packetSpeedMode) {
                WeLogger.i(TAG, "speed mode enabled, using min delay (${MIN_DELAY_MS}ms)")
                MIN_DELAY_MS
            } else {
                val (minDelay, maxDelay) = if (isGroupChat) {
                    (packetDelayMinGroup.toLongOrNull() ?: 300L) to (packetDelayMaxGroup.toLongOrNull() ?: 800L)
                } else {
                    (packetDelayMinPrivate.toLongOrNull() ?: 200L) to (packetDelayMaxPrivate.toLongOrNull() ?: 500L)
                }
                val safeMin = maxOf(minDelay, MIN_DELAY_MS)
                val safeMax = maxOf(maxDelay, safeMin)
                val delay = if (safeMax > safeMin) {
                    Random.nextLong(safeMin, safeMax)
                } else {
                    safeMin
                }
                WeLogger.i(TAG, "delay: isGroup=$isGroupChat, min=$safeMin, max=$safeMax, chosen=$delay")
                delay
            }

            thread(name = "ReceiveRedPacketThread") {
                try {
                    if (delayTime > 0) {
                        WeLogger.i(TAG, "started delaying for ${delayTime}ms (sendId=$sendId)")
                        Thread.sleep(delayTime)
                    }

                    WeLogger.i(
                        TAG,
                        "delay ended, preparing to send receive request (sendId=$sendId)"
                    )

                    val req = classReceiveLuckyMoney.clazz.createInstance(
                        msgType, channelId, sendId, nativeUrl, 1 /* inWay */, "v1.0" /* ver */, talker
                    )

                    WeNetSceneApi.sendNetScene(req)
                    WeLogger.i(TAG, "sent receive request (sendId=$sendId)")
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "failed to send receive request (sendId=$sendId)", e)
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to parse red packet data", e)
        }
    }

    private fun extractXmlParam(xml: String, tag: String): String {
        val pattern = "<$tag><!\\[CDATA\\[(.*?)]]></$tag>".toRegex()
        val match = pattern.find(xml)
        if (match != null) return match.groupValues[1]
        val patternSimple = "<$tag>(.*?)</$tag>".toRegex()
        val matchSimple = patternSimple.find(xml)
        return matchSimple?.groupValues?.get(1) ?: ""
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        currentRedPacketMap.clear()
        processedSendIds.clear()
        retryCountMap.clear()
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var notification by remember { mutableStateOf(packetNotif) }
            var self by remember { mutableStateOf(packetSelf) }
            var delayMinPrivateInput by remember { mutableStateOf(packetDelayMinPrivate) }
            var delayMaxPrivateInput by remember { mutableStateOf(packetDelayMaxPrivate) }
            var delayMinGroupInput by remember { mutableStateOf(packetDelayMinGroup) }
            var delayMaxGroupInput by remember { mutableStateOf(packetDelayMaxGroup) }
            var speedMode by remember { mutableStateOf(packetSpeedMode) }
            var useWhitelist by remember { mutableStateOf(packetUseWhitelist) }
            var autoReplyInput by remember { mutableStateOf(packetAutoReply) }

            AlertDialogContent(
                title = { Text("自动抢红包") },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        ListItem(
                            modifier = Modifier.clickable { useWhitelist = !useWhitelist },
                            trailingContent = { Switch(checked = useWhitelist, onCheckedChange = { useWhitelist = it }) },
                            supportingContent = { Text(if (useWhitelist) "仅对选中联系人抢红包" else "对选中联系人跳过抢红包") },
                            headlineContent = { Text(if (useWhitelist) "黑名单 [> 白名单 <]" else "[> 黑名单 <] 白名单") },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                val regularContacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
                                val currentList = if (useWhitelist) packetWhitelist else packetBlacklist

                                showComposeDialog(context) {
                                    ContactsSelector(
                                        title = if (useWhitelist) "选择白名单" else "选择黑名单",
                                        contacts = regularContacts,
                                        initialSelectedWxIds = currentList,
                                        onDismiss = onDismiss
                                    ) { selectedIds ->
                                        if (useWhitelist) {
                                            packetWhitelist = selectedIds
                                        } else {
                                            packetBlacklist = selectedIds
                                        }
                                        showToast("已保存 ${selectedIds.size} 个联系人, 重启微信以使更改生效")
                                        onDismiss()
                                    }
                                }
                            },
                            supportingContent = { Text("点击选择联系人") },
                            headlineContent = { Text(if (useWhitelist) "配置白名单" else "配置黑名单") },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                RedPacketGroupMemberFilter.showManagerDialog(context)
                            },
                            supportingContent = { Text("为指定群聊按发送成员设置黑/白名单") },
                            headlineContent = { Text("群聊指定群成员") },
                        )
                        ListItem(
                            modifier = Modifier.clickable { notification = !notification },
                            trailingContent = { Switch(checked = notification, onCheckedChange = { notification = it }) },
                            supportingContent = { Text("使用 Toast 显示抢到的金额") },
                            headlineContent = { Text("抢到后通知") },
                        )
                        ListItem(
                            modifier = Modifier.clickable { self = !self },
                            trailingContent = { Switch(checked = self, onCheckedChange = { self = it }) },
                            supportingContent = { Text("默认情况下不抢自己发出的红包") },
                            headlineContent = { Text("抢自己的红包") },
                        )
                        TextField(
                            value = delayMinPrivateInput,
                            onValueChange = { delayMinPrivateInput = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("私聊最小延迟 (毫秒)") },
                            supportingText = { Text("私聊抢红包随机延迟下限, 内置最小保护 ${MIN_DELAY_MS}ms") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        TextField(
                            value = delayMaxPrivateInput,
                            onValueChange = { delayMaxPrivateInput = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("私聊最大延迟 (毫秒)") },
                            supportingText = { Text("私聊抢红包随机延迟上限, 实际在 [最小, 最大] 区间随机") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        TextField(
                            value = delayMinGroupInput,
                            onValueChange = { delayMinGroupInput = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("群聊最小延迟 (毫秒)") },
                            supportingText = { Text("群聊抢红包随机延迟下限, 内置最小保护 ${MIN_DELAY_MS}ms") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        TextField(
                            value = delayMaxGroupInput,
                            onValueChange = { delayMaxGroupInput = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("群聊最大延迟 (毫秒)") },
                            supportingText = { Text("群聊抢红包随机延迟上限, 实际在 [最小, 最大] 区间随机") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        ListItem(
                            modifier = Modifier.clickable { speedMode = !speedMode },
                            trailingContent = { Switch(checked = speedMode, onCheckedChange = { speedMode = it }) },
                            supportingContent = {
                                Text(
                                    "极速模式会跳过随机延迟, 仅使用内置最小延迟 ${MIN_DELAY_MS}ms\n" +
                                    "⚠ 极速模式会提升账号行为异常特征, 存在微信支付限制、账号封禁风险, 请谨慎使用"
                                )
                            },
                            headlineContent = { Text("极速模式 (危险)") },
                        )
                        TextField(
                            value = autoReplyInput,
                            onValueChange = { autoReplyInput = it.trim() },
                            label = { Text("抢到后自动回复 (留空禁用)") },
                            supportingText = { Text($$"成功抢到红包后向来源对话发送自定义消息\n(使用占位符 $amount 表示金额)") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        packetNotif = notification
                        packetSelf = self
                        packetDelayMinPrivate = delayMinPrivateInput.ifBlank { "200" }
                        packetDelayMaxPrivate = delayMaxPrivateInput.ifBlank { "500" }
                        packetDelayMinGroup = delayMinGroupInput.ifBlank { "300" }
                        packetDelayMaxGroup = delayMaxGroupInput.ifBlank { "800" }
                        packetSpeedMode = speedMode
                        packetUseWhitelist = useWhitelist
                        packetAutoReply = autoReplyInput
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(text = "警告") },
                    text = { Text(text = "此功能可能导致账号异常, 确定要启用吗?") },
                    confirmButton = {
                        Button(onClick = {
                            applyToggle(true)
                            onDismiss()
                        }) { Text("确定") }
                    },
                    dismissButton = { TextButton(onDismiss) { Text("取消") } }
                )
            }
            return false
        }

        return true
    }
}
