package com.Johnny.wcx.features.items.contacts

import android.annotation.SuppressLint
import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexConstructor
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.net.WeNetSceneApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.luckypray.dexkit.DexKitBridge
import kotlin.random.Random

@SuppressLint("SetTextI18n")
@Feature(
    name = "自动同意好友申请",
    categories = ["联系人与群组"],
    description = "自动同意好友申请，支持延迟设置、自动发送欢迎语、黑名单过滤"
)
object AutoAcceptFriendRequests : ClickableFeature(), IResolveDex,
    WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "AutoAcceptFriendReq"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // ==================== 持久化偏好 ====================
    private var masterEnabled by prefOption("aafr_master_enabled", false)
    private var delayMode by prefOption("aafr_delay_mode", 0) // 0 = fixed, 1 = random
    private var fixedDelayMs by prefOption("aafr_fixed_delay_ms", 1000)
    private var randomDelayMinMs by prefOption("aafr_random_delay_min_ms", 1000)
    private var randomDelayMaxMs by prefOption("aafr_random_delay_max_ms", 5000)
    private var welcomeText by prefOption("aafr_welcome_text", "你好，我已通过你的好友申请")
    private var sendWelcome by prefOption("aafr_send_welcome", true)
    private var blacklistJson by prefOption("aafr_blacklist", "[]")

    // 已处理的好友请求（防重复处理）
    private val processedRequests = mutableSetOf<String>()

    private fun getBlacklist(): Set<String> {
        return runCatching {
            json.decodeFromString<Set<String>>(blacklistJson)
        }.getOrDefault(emptySet())
    }

    enum class DelayMode(val value: Int, val description: String) {
        FIXED(0, "固定延迟"),
        RANDOM(1, "随机延迟")
    }

    // ==================== DexKit — 好友验证接受方法 ====================

    /**
     * 8.0.76 起 NetSceneVerifyUser 混淆为 pluginsdk.model.m3，旧日志字符串
     * "summerverify opcode[%s], verifyContent[%s], verifyScene[%s]" 被移除，
     * 接受操作的 opcode 改为 MM_VERIFYUSER_VERIFYOK 语义。
     * <init> 内含断言日志 "init MUST use opcode == MM_VERIFYUSER_VERIFYOK"，
     * 以此定位构造器（首参为 opcode）。
     */
    private const val OPCODE_VERIFY_ACCEPT = 1 // 8.0.76: MM_VERIFYUSER_VERIFYOK

    // 好友验证接受方法：NetSceneVerifyUser / NetSceneAddFriend 等
    // 在 WeChat 中，接受好友验证的典型方法是 VerifyUserTask 或 NetSceneVerifyUser
    private val methodVerifyAccept by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(
                "This NetSceneVerifyUser init MUST use opcode == MM_VERIFYUSER_VERIFYOK"
            )
        }
    }

    // 8.0.76 的 NetSceneVerifyUser 构造器（m3.<init>），用于主动构造"接受"请求
    // 使用内联查找版 dexConstructor（resolveInlineDex 时自动解析）
    private val ctorVerifyUserAccept by dexConstructor {
        searchPackages("com.tencent.mm.pluginsdk.model")
        matcher {
            usingEqStrings("This NetSceneVerifyUser init MUST use opcode == MM_VERIFYUSER_VERIFYOK")
        }
    }

    // 好友验证页面的 initView — 用于检测用户手动进入验证页面时提取信息
    // 备用：如果 NetScene 方法无法匹配，通过验证页面输入来触发
    private val methodVerifyOkClick by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(
                "MicroMsg.VerifyUserUtil",
                "verify ok clicked"
            )
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        // 8.0.76+：NetSceneVerifyUser 混淆为 m3，<init> 含 MM_VERIFYUSER_VERIFYOK 断言日志
        methodVerifyAccept.find(dexKit, allowFailure = true) {
            matcher {
                usingEqStrings(
                    "This NetSceneVerifyUser init MUST use opcode == MM_VERIFYUSER_VERIFYOK"
                )
            }
        }

        // 旧版特征（8.0.7x 及更早）
        if (methodVerifyAccept.isPlaceholder) {
            methodVerifyAccept.find(dexKit, allowFailure = true) {
                matcher {
                    usingEqStrings(
                        "MicroMsg.NetSceneVerifyUser",
                        "summerverify opcode[%s], verifyContent[%s], verifyScene[%s]"
                    )
                }
            }
        }

        methodVerifyOkClick.find(dexKit, allowFailure = true) {
            matcher {
                usingEqStrings(
                    "MicroMsg.VerifyUserUtil",
                    "verify ok clicked"
                )
            }
        }
    }

    // ==================== 生命周期 ====================

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)

        // 检查 DexKit 方法是否成功解析
        val verifyUnavailable = methodVerifyAccept.isPlaceholder
        val verifyOkUnavailable = methodVerifyOkClick.isPlaceholder

        if (verifyUnavailable && verifyOkUnavailable) {
            WeLogger.w(TAG, "当前微信版本暂不支持自动同意好友申请 — DexKit 方法均未匹配")
            runCatching {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    showToast("当前微信版本暂不支持自动同意好友申请")
                }
            }
        }

        // 钩住好友验证接受方法，在微信内部接受好友请求时触发后续逻辑
        runCatching {
            if (!methodVerifyAccept.isPlaceholder) {
                methodVerifyAccept.hookAfter {
                    // 8.0.76 的 <init>(opcode, userId, ticket, scene, ...) 与旧版
                    // NetSceneVerifyUser 接受方法参数布局不同，先防护再取参。
                    if (args.size < 3) return@hookAfter
                    if (!masterEnabled) return@hookAfter
                    val opcode = args[0] as? Int ?: return@hookAfter
                    // 8.0.76: MM_VERIFYUSER_VERIFYOK == 1；旧版接受 opcode == 2
                    if (opcode != OPCODE_VERIFY_ACCEPT && opcode != 2) return@hookAfter

                    // 8.0.76: args[1] = userId(encryptUsername), args[2] = ticket
                    // 旧版: args[1] = verifyContent("v2_encrypt@ticket@scene"), args[2] = verifyScene
                    val arg1 = (args[1] as? String)?.takeIf { it.isNotBlank() } ?: return@hookAfter

                    WeLogger.i(TAG, "friend request accepted via verify: arg1=$arg1")

                    // 发送欢迎语
                    if (sendWelcome && welcomeText.isNotBlank()) {
                        val targetWxId = if (arg1.startsWith("v2_")) {
                            extractWxIdFromVerifyContent(arg1)
                        } else {
                            findNewFriendWxId(arg1)
                        }
                        if (targetWxId.isNotEmpty()) {
                            CoroutineScope(Dispatchers.IO).launch {
                                delay(1500) // 等待好友关系建立
                                runCatching {
                                    WeMessageApi.sendText(targetWxId, welcomeText)
                                    WeLogger.i(TAG, "welcome text sent to $targetWxId")
                                }.onFailure { e ->
                                    WeLogger.e(TAG, "failed to send welcome text", e)
                                }
                            }
                        }
                    }
                }
            } else {
                WeLogger.w(TAG, "methodVerifyAccept not resolved, auto-accept will use database listener fallback")
            }
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to hook verify accept", e)
        }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        processedRequests.clear()
    }

    // ==================== 数据库监听 — 检测好友验证消息 ====================

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        if (!masterEnabled) return

        val msgInfo = runCatching { MessageInfo.fromContentValues(values) }.getOrNull() ?: return
        if (msgInfo.isSelfSender) return
        if (msgInfo.typeCode != MessageType.FRIEND_VERIFY.code) return

        val content = msgInfo.content ?: return
        if (content.isEmpty()) return

        // 解析好友验证内容
        val encryptUsername = extractXmlValue(content, "encryptusername")
        val ticket = extractXmlValue(content, "ticket")
        val scene = extractXmlValue(content, "scene") ?: ""

        if (encryptUsername.isNullOrEmpty() || ticket.isNullOrEmpty()) {
            WeLogger.d(TAG, "friend verify message missing required fields")
            return
        }

        // 去重检查
        val requestKey = "$encryptUsername:$ticket"
        if (requestKey in processedRequests) {
            WeLogger.d(TAG, "duplicate friend request, skipped")
            return
        }
        processedRequests.add(requestKey)
        // 清理过期记录
        if (processedRequests.size > 200) {
            processedRequests.clear()
        }

        // 黑名单检查
        val blacklist = getBlacklist()
        if (encryptUsername in blacklist) {
            WeLogger.i(TAG, "user $encryptUsername is in blacklist, skipped")
            return
        }
        // 也检查原始 wxId
        val fromUser = extractXmlValue(content, "fromusername")
        if (fromUser != null && fromUser in blacklist) {
            WeLogger.i(TAG, "user $fromUser is in blacklist, skipped")
            return
        }

        WeLogger.i(TAG, "auto-accepting friend request: encryptUsername=$encryptUsername")

        // 计算延迟
        val delayMs = when (delayMode) {
            DelayMode.RANDOM.value -> {
                val min = randomDelayMinMs.coerceAtLeast(0)
                val max = randomDelayMaxMs.coerceAtLeast(min)
                Random.nextLong(min.toLong(), (max + 1).toLong())
            }
            else -> fixedDelayMs.coerceAtLeast(0).toLong()
        }

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                if (delayMs > 0) {
                    delay(delayMs)
                }
                acceptFriendRequest(encryptUsername, ticket, scene)
                WeLogger.i(TAG, "friend request accepted: encryptUsername=$encryptUsername")

                // 发送欢迎语
                if (sendWelcome && welcomeText.isNotBlank()) {
                    delay(1500) // 等待好友关系建立
                    val targetWxId = findNewFriendWxId(encryptUsername)
                    if (targetWxId.isNotEmpty()) {
                        WeMessageApi.sendText(targetWxId, welcomeText)
                        WeLogger.i(TAG, "welcome text sent to $targetWxId")
                    }
                }
            }.onFailure { e ->
                WeLogger.e(TAG, "failed to accept friend request", e)
            }
        }
    }

    private fun acceptFriendRequest(encryptUsername: String, ticket: String, scene: String) {
        // 8.0.76：构造 NetSceneVerifyUser(m3) opcode=MM_VERIFYUSER_VERIFYOK，走 NetSceneManager 发送
        runCatching {
            if (!ctorVerifyUserAccept.isPlaceholder) {
                val netScene = ctorVerifyUserAccept.newInstance(
                    OPCODE_VERIFY_ACCEPT,
                    encryptUsername,
                    ticket,
                    scene.toIntOrNull() ?: 0,
                    "",
                    0,
                    null,
                    null
                )
                WeNetSceneApi.sendNetScene(netScene)
                WeLogger.i(TAG, "8.0.76: verify accept NetScene sent: encryptUsername=$encryptUsername")
                return
            }
        }.onFailure { e ->
            WeLogger.w(TAG, "8.0.76 accept via ctor failed, trying legacy path", e)
        }

        if (!methodVerifyAccept.isPlaceholder) {
            // 旧版：直接调用接受方法（opcode 2）
            methodVerifyAccept.method.invoke(
                null, // static method
                2, // opcode = accept
                "v2_$encryptUsername@$ticket@$scene", // verifyContent
                "", // verifyScene
                ""  // additional
            )
        } else {
            // 回退：通过数据库操作模拟接受
            // 这是简化的实现，实际接受需要调用微信的 NetScene 接口
            WeLogger.w(TAG, "methodVerifyAccept not available, using fallback accept")
            fallbackAccept(encryptUsername, ticket, scene)
        }
    }

    private fun fallbackAccept(encryptUsername: String, ticket: String, scene: String) {
        // 回退方案：使用 WeChat 的 AddContact 或 VerifyUser 相关的 NetScene
        // 由于无法确定具体的 DexKit 签名，这里记录日志供用户参考
        WeLogger.w(TAG, "fallback accept not fully implemented, " +
                "request: encryptUsername=$encryptUsername, ticket=$ticket, scene=$scene")
        // 尝试通过 WeChat 的数据库操作来标记好友请求为已处理
        // 实际的自动接受功能需要 DexKit 成功解析 WeChat 的验证方法
    }

    private fun findNewFriendWxId(encryptUsername: String): String {
        // 尝试通过 encryptUsername 查找新好友的 wxId
        // 在 WeChat 中，好友请求被接受后，encryptUsername 会对应到实际 wxId
        return runCatching {
            WeDatabaseApi.rawQuery(
                "SELECT username FROM rcontact WHERE encryptUsername=?",
                arrayOf(encryptUsername)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) ?: "" else ""
            }
        }.getOrDefault("")
    }

    private fun extractWxIdFromVerifyContent(verifyContent: String): String {
        // 从 verifyContent 中提取 wxId
        // 格式通常是 "v2_encryptUsername@ticket@scene"
        return runCatching {
            val parts = verifyContent.split("@")
            if (parts.size >= 2) {
                val encryptUsername = parts[0].removePrefix("v2_")
                findNewFriendWxId(encryptUsername)
            } else ""
        }.getOrDefault("")
    }

    private fun extractXmlValue(xml: String, tag: String): String? {
        val regex = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim()
    }

    // ==================== UI ====================

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var localMasterEnabled by remember { mutableStateOf(masterEnabled) }
            var localDelayMode by remember { mutableStateOf(delayMode) }
            var localFixedDelayMs by remember { mutableStateOf(fixedDelayMs) }
            var localRandomMinMs by remember { mutableStateOf(randomDelayMinMs) }
            var localRandomMaxMs by remember { mutableStateOf(randomDelayMaxMs) }
            var localWelcomeText by remember { mutableStateOf(welcomeText) }
            var localSendWelcome by remember { mutableStateOf(sendWelcome) }
            var localBlacklistJson by remember { mutableStateOf(blacklistJson) }
            var localBlacklist by remember { mutableStateOf(getBlacklist().joinToString("\n")) }

            AlertDialogContent(
                title = { Text("自动同意好友申请") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        // 总开关
                        ListItem(
                            modifier = Modifier.clickable { localMasterEnabled = !localMasterEnabled },
                            trailingContent = {
                                Switch(checked = localMasterEnabled, onCheckedChange = null)
                            },
                            headlineContent = { Text("启用自动同意", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("开启后自动同意收到的所有好友申请") }
                        )

                        if (localMasterEnabled) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // 延迟设置
                            Text("延迟设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                            DelayMode.values().forEach { mode ->
                                ListItem(
                                    modifier = Modifier.clickable { localDelayMode = mode.value },
                                    trailingContent = {
                                        Text(if (localDelayMode == mode.value) "✓" else "")
                                    },
                                    headlineContent = { Text(mode.description) }
                                )
                            }

                            when (localDelayMode) {
                                DelayMode.FIXED.value -> {
                                    val presets = listOf(0 to "立即", 500 to "0.5秒", 1000 to "1秒", 2000 to "2秒", 3000 to "3秒", 5000 to "5秒")
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        presets.forEach { (ms, label) ->
                                            FilterChip(
                                                selected = localFixedDelayMs == ms,
                                                onClick = { localFixedDelayMs = ms },
                                                label = { Text(label) }
                                            )
                                        }
                                    }
                                    OutlinedTextField(
                                        value = localFixedDelayMs.toString(),
                                        onValueChange = { v ->
                                            v.toIntOrNull()?.let { localFixedDelayMs = it.coerceIn(0, 60000) }
                                        },
                                        label = { Text("自定义延迟（毫秒）") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                    )
                                }
                                DelayMode.RANDOM.value -> {
                                    OutlinedTextField(
                                        value = localRandomMinMs.toString(),
                                        onValueChange = { v ->
                                            v.toIntOrNull()?.let { localRandomMinMs = it.coerceIn(0, 60000) }
                                        },
                                        label = { Text("最小延迟（毫秒）") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                    )
                                    Spacer(Modifier.padding(top = 4.dp))
                                    OutlinedTextField(
                                        value = localRandomMaxMs.toString(),
                                        onValueChange = { v ->
                                            v.toIntOrNull()?.let { localRandomMaxMs = it.coerceIn(0, 60000) }
                                        },
                                        label = { Text("最大延迟（毫秒）") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                    )
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // 欢迎语
                            ListItem(
                                modifier = Modifier.clickable { localSendWelcome = !localSendWelcome },
                                trailingContent = {
                                    Switch(checked = localSendWelcome, onCheckedChange = null)
                                },
                                headlineContent = { Text("自动发送欢迎语") },
                                supportingContent = { Text("通过好友申请后自动发送一条消息") }
                            )

                            if (localSendWelcome) {
                                OutlinedTextField(
                                    value = localWelcomeText,
                                    onValueChange = { localWelcomeText = it },
                                    label = { Text("欢迎语内容") },
                                    minLines = 2,
                                    maxLines = 4,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // 黑名单
                            Text("黑名单管理", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "黑名单中的用户不会自动同意（每行一个 wxId）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            OutlinedTextField(
                                value = localBlacklist,
                                onValueChange = { localBlacklist = it },
                                label = { Text("黑名单 wxId") },
                                minLines = 3,
                                maxLines = 8,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        val newBlacklist = localBlacklist.lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toSet()

                        masterEnabled = localMasterEnabled
                        delayMode = localDelayMode
                        fixedDelayMs = localFixedDelayMs
                        randomDelayMinMs = localRandomMinMs
                        randomDelayMaxMs = localRandomMaxMs
                        welcomeText = localWelcomeText
                        sendWelcome = localSendWelcome
                        blacklistJson = json.encodeToString(newBlacklist)
                        showToast("设置已保存")
                        onDismiss()
                    }) { Text("保存") }
                }
            )
        }
    }
}