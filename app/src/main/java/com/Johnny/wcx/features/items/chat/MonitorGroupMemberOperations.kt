package com.Johnny.wcx.features.items.chat

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Color as AndroidColor
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.net.models.protobuf.ChatRoomDataProto
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.reflection.BString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import java.lang.reflect.Field

@Feature(
    name = "群成员变动提醒",
    categories = ["联系人与群组"],
    description = "监控群成员变动（入群/退群/改昵称/被踢），支持本地观察与群广播两种模式"
)
object MonitorGroupMemberOperations : ClickableFeature(), IResolveDex,
    WeDatabaseListenerApi.IUpdateListener, WeDatabaseListenerApi.IInsertListener,
    WeChatMessageViewApi.ICreateViewListener {

    private const val TAG = "GroupMemberChangeNotify"

    // =========================================================================
    // 持久化偏好
    // =========================================================================
    private var masterEnabled by prefOption("gmc_master_enabled", false)
    private var modeAEnabled by prefOption("gmc_mode_a_enabled", false)
    private var modeBEnabled by prefOption("gmc_mode_b_enabled", false)
    private var joinEnabled by prefOption("gmc_join_enabled", true)
    private var leaveEnabled by prefOption("gmc_leave_enabled", true)
    private var nickChangeEnabled by prefOption("gmc_nick_change_enabled", true)
    private var kickEnabled by prefOption("gmc_kick_enabled", true)
    private var kickExtraExit by prefOption("gmc_kick_extra_exit", false)
    private var fakeUserBroadcast by prefOption("gmc_fake_user_broadcast", false)
    private var groupFilterEnabled by prefOption("gmc_group_filter_enabled", false)
    private var selectedGroupsJson by prefOption("gmc_selected_groups", "[]")

    // 事件去重缓存：key = "wxid:eventType", value = 触发时间戳
    private val eventDebounceCache = mutableMapOf<String, Long>()
    private const val DEBOUNCE_WINDOW_MS = 4000L // 4秒冷却窗口

    // 指定群过滤：从 JSON 解析选中群列表
    private fun getSelectedGroups(): Set<String> {
        return runCatching {
            json.decodeFromString<Set<String>>(selectedGroupsJson)
        }.getOrDefault(emptySet())
    }

    private fun isGroupAllowed(groupWxId: String): Boolean {
        if (!groupFilterEnabled) return true
        val selected = getSelectedGroups()
        return selected.isEmpty() || groupWxId in selected
    }

    @Serializable
    data class EventConfig(
        val color: String = "#28C445",
        val text: String = ""
    )

    private var joinConfigJson by prefOption("gmc_join_config", "{}")
    private var leaveConfigJson by prefOption("gmc_leave_config", "{}")
    private var nickChangeConfigJson by prefOption("gmc_nick_change_config", "{}")
    private var kickConfigJson by prefOption("gmc_kick_config", "{}")

    private val json = Json { ignoreUnknownKeys = true }

    private fun getEventConfig(eventType: String): EventConfig {
        val jsonStr = when (eventType) {
            "join" -> joinConfigJson
            "leave" -> leaveConfigJson
            "nick_change" -> nickChangeConfigJson
            "kick" -> kickConfigJson
            else -> "{}"
        }
        return runCatching {
            json.decodeFromString<EventConfig>(jsonStr)
        }.getOrElse { EventConfig() }
    }

    // 默认模板维持原有 {链接昵称} 格式，老用户配置无需重置
    private fun getDefaultConfig(eventType: String): EventConfig = when (eventType) {
        "join" -> EventConfig(color = "#28C445", text = "{链接昵称} 加入了群组")
        "leave" -> EventConfig(color = "#28C445", text = "{链接昵称} 退出了群组")
        "nick_change" -> EventConfig(color = "#28C445", text = "{链接昵称} 修改群昵称：{旧昵称} → {新昵称}")
        "kick" -> EventConfig(color = "#F23030", text = "{链接昵称} 被管理员{管理员昵称}移出群组")
        else -> EventConfig()
    }

    private fun getEffectiveConfig(eventType: String): EventConfig {
        val stored = getEventConfig(eventType)
        return if (stored.text.isBlank()) getDefaultConfig(eventType) else stored
    }

    private fun saveEventConfig(eventType: String, config: EventConfig) {
        val jsonStr = json.encodeToString(config)
        when (eventType) {
            "join" -> joinConfigJson = jsonStr
            "leave" -> leaveConfigJson = jsonStr
            "nick_change" -> nickChangeConfigJson = jsonStr
            "kick" -> kickConfigJson = jsonStr
        }
    }

    // =========================================================================
    // 生命周期
    // =========================================================================
    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        WeChatMessageViewApi.addListener(this)

        runCatching {
            methodHandleSpanClick.hookBefore {
                val url = args[1].reflekt().firstField {
                    type = BString
                    modifiers(Modifiers.FINAL)
                }.get()!! as String
                if (!url.startsWith("weixin://weixinhongbao/wekit/chatroom_userinfo/")) return@hookBefore

                val wxId = url.substringAfterLast('/')
                val context = (args[0] as View).context
                WeApi.openContact(context, wxId, WeApi.OpenContactDestination.HOMEPAGE)
            }
        }.onFailure { WeLogger.e(TAG, "failed to hook methodHandleSpanClick", it) }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        WeChatMessageViewApi.removeListener(this)
    }

    // =========================================================================
    // 消息显示 Hook — 彩色文字 + 可点击昵称（仅本地居中提示使用）
    // =========================================================================
    private var contentTextViewField: Field? = null

    override fun onCreateView(param: XC_MethodHook.MethodHookParam, view: View) {
        if (!masterEnabled || !modeBEnabled) return
        try {
            val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
            if (msgInfo.type != MessageType.TEXT) return
            val content = msgInfo.content ?: return
            if (!content.startsWith(GMC_PREFIX)) return

            val parsed = parseGmcContent(content) ?: return
            val tag = view.tag ?: return

            val contentTv = findContentTextView(tag, view)
            if (contentTv != null) {
                applySpans(contentTv, parsed)
            } else {
                // 兜底：若无法定位 TextView，尝试递归查找并移除 GMC 前缀，避免显示原始协议字符
                val fallbackTv = findTextViewRecursive(view)
                fallbackTv?.text = parsed.plainText
            }
        } catch (e: Exception) {
            // 失败时尝试兜底：移除 GMC 前缀，至少显示纯文本而非原始协议字符
            try {
                val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
                val content = msgInfo.content ?: return
                if (content.startsWith(GMC_PREFIX)) {
                    val plainText = content.substringAfter(']')
                    if (plainText.isNotEmpty()) {
                        val fallbackTv = findTextViewRecursive(view)
                        fallbackTv?.text = plainText
                    }
                }
            } catch (_: Exception) {}
            WeLogger.e(TAG, "onCreateView failed", e)
        }
    }

    private fun findContentTextView(tag: Any, rootView: View): TextView? {
        contentTextViewField?.let {
            return runCatching { it.get(tag) as? TextView }.getOrNull()
        }
        for (field in tag.javaClass.declaredFields) {
            if (TextView::class.java.isAssignableFrom(field.type)) {
                try {
                    field.isAccessible = true
                    val tv = field.get(tag) as? TextView ?: continue
                    val text = tv.text?.toString() ?: ""
                    if (text.startsWith(GMC_PREFIX)) {
                        contentTextViewField = field
                        field.isAccessible = true
                        return tv
                    }
                } catch (_: Exception) {}
            }
        }
        return findTextViewRecursive(rootView)
    }

    private fun findTextViewRecursive(view: View): TextView? {
        if (view is TextView) {
            val text = view.text?.toString() ?: ""
            if (text.startsWith(GMC_PREFIX)) return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findTextViewRecursive(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private data class GmcParsedContent(
        val eventType: String,
        val color: Int,
        val plainText: String,
        val clickableWxIds: List<Pair<String, String>> // (displayName, wxId)
    )

    private const val GMC_PREFIX = "[gmc:"

    private fun buildGmcContent(
        eventType: String,
        color: String,
        plainText: String,
        clickableWxIds: List<Pair<String, String>>
    ): String {
        val wxIdsPart = clickableWxIds.joinToString(",") { "${it.first}::${it.second}" }
        return "$GMC_PREFIX$eventType:$color:$wxIdsPart]$plainText"
    }

    private fun parseGmcContent(content: String): GmcParsedContent? {
        if (!content.startsWith(GMC_PREFIX)) return null
        val endIdx = content.indexOf(']')
        if (endIdx < 0) return null
        val header = content.substring(GMC_PREFIX.length, endIdx)
        val parts = header.split(":", limit = 3)
        if (parts.size < 3) return null
        val eventType = parts[0]
        val color = runCatching { AndroidColor.parseColor(parts[1]) }.getOrDefault(AndroidColor.BLACK)
        val wxIdsPart = parts[2]
        val clickableWxIds = if (wxIdsPart.isNotEmpty()) {
            wxIdsPart.split(",").mapNotNull {
                val pair = it.split("::", limit = 2)
                if (pair.size == 2) pair[0] to pair[1] else null
            }
        } else emptyList()
        val plainText = content.substring(endIdx + 1)
        return GmcParsedContent(eventType, color, plainText, clickableWxIds)
    }

    private fun applySpans(textView: TextView, parsed: GmcParsedContent) {
        val spannable = SpannableString(parsed.plainText)
        spannable.setSpan(
            ForegroundColorSpan(parsed.color),
            0, spannable.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        for ((displayName, wxId) in parsed.clickableWxIds) {
            val idx = spannable.indexOf(displayName)
            if (idx >= 0) {
                spannable.setSpan(
                    object : URLSpan("weixin://weixinhongbao/wekit/chatroom_userinfo/$wxId") {
                        override fun updateDrawState(ds: TextPaint) {
                            ds.isUnderlineText = true
                        }
                    },
                    idx, idx + displayName.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        textView.text = spannable
    }

    private val methodHandleSpanClick by dexMethod {
        matcher {
            declaredClass = $$"com.tencent.mm.app.plugin.URISpanHandlerSet\$LuckyMoneyUriSpanHandler"
            usingEqStrings("MicroMsg.URISpanHandlerSet", "LuckyMoneyUriSpanHandler handleSpanClick() clickCallback == null")
        }
    }

    // =========================================================================
    // 事件去重：wxid + 事件类型，3~5秒冷却窗口
    // =========================================================================
    private fun shouldDebounce(wxId: String, eventType: String): Boolean {
        val key = "$wxId:$eventType"
        val now = System.currentTimeMillis()
        val lastTime = eventDebounceCache[key]
        if (lastTime != null && (now - lastTime) < DEBOUNCE_WINDOW_MS) {
            WeLogger.d(TAG, "debounce: $key skipped (${now - lastTime}ms since last)")
            return true
        }
        eventDebounceCache[key] = now
        // 清理过期缓存（超过30秒的条目）
        eventDebounceCache.entries.removeAll { (now - it.value) > 30000 }
        return false
    }

    // =========================================================================
    // 数据库监听 — 检测成员进退群
    // =========================================================================
    @SuppressLint("Range")
    override fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int) {
        if (table != "chatroom") return
        if (!masterEnabled) return

        val group = values.getAsString("chatroomname") ?: return
        if (!isGroupAllowed(group)) return
        val newRawMembers = values.getAsString("memberlist")
        val newRoomData = values.getAsByteArray("roomdata")

        val cursor = WeDatabaseApi.rawQuery(
            "SELECT memberlist,memberCount,roomdata FROM chatroom WHERE chatroomname = ?",
            arrayOf(group)
        )

        runCatching {
            cursor.use { c ->
                if (!c.moveToFirst()) return
                val origRawMembers = c.getString(c.getColumnIndex("memberlist"))
                if (origRawMembers.isNullOrEmpty()) return
                val origMembers = origRawMembers.split(';')

                val origRoomData = c.getBlob(c.getColumnIndex("roomdata"))
                val origDisplayNames = parseRoomData(origRoomData)
                val newDisplayNames = parseRoomData(newRoomData)

                handleMemberChange(group, origMembers, origDisplayNames, newRawMembers, newDisplayNames)
            }
        }.onFailure { WeLogger.e(TAG, "failed to handle group member operations", it) }
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (!masterEnabled || !kickEnabled) return
        if (table != "message") return
        val type = values.getAsInteger("type") ?: return
        if (type != MessageType.SYSTEM.code) return
        val content = values.getAsString("content") ?: return
        if (!content.contains("delchatroommember")) return

        val talker = values.getAsString("talker") ?: return
        if (!talker.endsWith("@chatroom")) return
        if (!isGroupAllowed(talker)) return

        val kickedWxId = extractXmlValue(content, "delchatroommember", "username")
        val adminWxId = extractXmlValue(content, "delchatroommember", "scenceusername")
        if (kickedWxId.isNullOrEmpty()) return

        // 事件去重
        if (shouldDebounce(kickedWxId, "kick")) return

        handleKickEvent(talker, kickedWxId, adminWxId)
    }

    private fun extractXmlValue(xml: String, tag: String, subTag: String): String? {
        val regex = Regex("<$tag>.*?<$subTag>(.*?)</$subTag>.*?</$tag>", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.getOrNull(1)
    }

    private fun handleMemberChange(
        group: String,
        origMembers: List<String>,
        origDisplayNames: Map<String, String>,
        newRawMembers: String?,
        newDisplayNames: Map<String, String>
    ) {
        if (newRawMembers == null) return

        val origSet = origMembers.toSet()
        val newSet = newRawMembers.split(';').toSet()

        val leavers = origSet - newSet
        val joiners = newSet - origSet

        // 入群事件
        if (joinEnabled) {
            joiners.forEach { wxId ->
                if (shouldDebounce(wxId, "join")) return@forEach
                val displayName = getDisplayName(wxId, newDisplayNames)
                val config = getEffectiveConfig("join")
                val text = formatText(config.text, "join", displayName, wxId, "", "", "")
                triggerEvent("join", group, config.color, text, listOf(displayName to wxId))
            }
        }

        // 退群事件
        if (leaveEnabled) {
            leavers.forEach { wxId ->
                if (shouldDebounce(wxId, "leave")) return@forEach
                val displayName = getDisplayName(wxId, origDisplayNames)
                val config = getEffectiveConfig("leave")
                val text = formatText(config.text, "leave", displayName, wxId, "", "", "")
                triggerEvent("leave", group, config.color, text, listOf(displayName to wxId))
            }
        }

        // 修改群昵称事件
        if (nickChangeEnabled) {
            val commonMembers = origSet.intersect(newSet)
            commonMembers.forEach { wxId ->
                val oldName = origDisplayNames[wxId] ?: ""
                val newName = newDisplayNames[wxId] ?: ""
                if (oldName.isNotEmpty() && newName.isNotEmpty() && oldName != newName) {
                    if (shouldDebounce(wxId, "nick_change")) return@forEach
                    val displayName = getDisplayName(wxId, newDisplayNames)
                    val config = getEffectiveConfig("nick_change")
                    val text = formatText(config.text, "nick_change", displayName, wxId, oldName, newName, "")
                    triggerEvent("nick_change", group, config.color, text, listOf(displayName to wxId))
                }
            }
        }
    }

    private fun handleKickEvent(group: String, kickedWxId: String, adminWxId: String?) {
        val displayName = WeDatabaseApi.getDisplayName(kickedWxId).ifEmpty { kickedWxId }
        val adminDisplayName = if (!adminWxId.isNullOrEmpty()) {
            WeDatabaseApi.getDisplayName(adminWxId).ifEmpty { adminWxId }
        } else ""

        val config = getEffectiveConfig("kick")
        val text = formatText(config.text, "kick", displayName, kickedWxId, "", "", adminDisplayName, adminWxId ?: "")

        val clickableList = mutableListOf(displayName to kickedWxId)
        if (adminDisplayName.isNotEmpty()) {
            clickableList.add(adminDisplayName to adminWxId!!)
        }

        triggerEvent("kick", group, config.color, text, clickableList)

        // 附加退出群组提示
        if (kickExtraExit) {
            val exitConfig = EventConfig(color = "#28C445", text = "{链接昵称} 退出了群组")
            val exitText = formatText(exitConfig.text, "leave", displayName, kickedWxId, "", "", "", "")
            triggerEvent("kick_extra", group, exitConfig.color, exitText, listOf(displayName to kickedWxId))
        }
    }

    // =========================================================================
    // 两套通知通道完全隔离，独立代码分支
    // =========================================================================
    private fun triggerEvent(
        eventType: String,
        group: String,
        color: String,
        plainText: String,
        clickableWxIds: List<Pair<String, String>>
    ) {
        // ===== 分支①：本地提醒模式（仅本机聊天界面居中系统提示） =====
        if (modeBEnabled) {
            triggerLocalNotification(eventType, group, color, plainText, clickableWxIds)
        }

        // ===== 分支②：群广播推送模式（以当前微信号发送普通文本消息） =====
        if (modeAEnabled) {
            triggerGroupBroadcast(group, plainText)
        }
    }

    /**
     * 本地提醒模式：仅在本机聊天列表插入居中系统样式条目
     * 纯本地界面渲染，不向微信服务器发送任何内容
     */
    private fun triggerLocalNotification(
        eventType: String,
        group: String,
        color: String,
        plainText: String,
        clickableWxIds: List<Pair<String, String>>
    ) {
        try {
            // 居中系统提示（带颜色、可点击）—— 始终插入，不受假用户播报开关影响
            val gmcContent = buildGmcContent(eventType, color, plainText, clickableWxIds)
            WeMessageApi.createSimpleMsgInfoAndInsert(
                type = MessageType.TEXT.code,
                talker = group,
                content = gmcContent,
                currentTime = System.currentTimeMillis()
            )

            // 假用户播报：仅开关开启时，额外生成虚拟假用户发言（纯文本，无 wxId，仅本地可见）
            if (fakeUserBroadcast && eventType in listOf("join", "leave", "kick", "kick_extra")) {
                // 使用干净文本：剔除 (wxId) 等协议原始标记，仅保留纯昵称
                val cleanText = cleanProtocolMarkers(plainText)
                WeMessageApi.createSimpleMsgInfoAndInsert(
                    type = MessageType.TEXT.code,
                    talker = group,
                    content = cleanText,
                    currentTime = System.currentTimeMillis() + 1
                )
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "triggerLocalNotification failed", e)
        }
    }

    /**
     * 清理文本中的协议格式标记，用于假用户播报
     * 将 "昵称(wxid_xxx)" 还原为 "昵称"，同时移除 GMC 协议前缀等多余字符
     */
    private fun cleanProtocolMarkers(text: String): String {
        return text
            // 移除 GMC 协议头部（若意外泄漏）
            .replace(Regex("""\[gmc:[^\]]*]"""), "")
            // 移除 (wxid_xxx) 格式的协议标识
            .replace(Regex("""\(wxid_[a-zA-Z0-9_]+\)"""), "")
            // 移除 (@chatroom) 后缀
            .replace(Regex("""\(@chatroom\)"""), "")
            // 移除独立的 wxid 字符串（非括号包裹的原始 wxid 格式）
            .replace(Regex("""\bwxid_[a-zA-Z0-9_]+"""), "")
            // 移除 @chatroom 后缀（无括号版本）
            .replace(Regex("""@chatroom"""), "")
            // 清理多余空格和标点残留
            .replace(Regex("""\s{2,}"""), " ")
            .replace(Regex("""\(\s*\)"""), "")
            .replace(Regex("""，\s*，"""), "，")
            .replace(Regex("""。\s*。"""), "。")
            .trim()
    }

    /**
     * 群广播推送模式：以当前登录微信号发送普通文本消息到群内
     * 重要：仅发送纯文本，剔除所有颜色参数，规避仿系统消息风控
     */
    private fun triggerGroupBroadcast(group: String, plainText: String) {
        runCatching {
            // 仅发送纯文本，不携带任何颜色/样式信息
            WeMessageApi.sendText(group, plainText)
        }.onFailure {
            WeLogger.e(TAG, "failed to send broadcast", it)
        }
    }

    // =========================================================================
    // 变量解析：新旧变量并行兼容，无数据变量自动隐藏
    // =========================================================================
    private fun formatText(
        template: String,
        eventType: String,
        displayName: String,
        wxId: String,
        oldNick: String,
        newNick: String,
        adminDisplayName: String,
        adminWxId: String = ""
    ): String {
        val userNameFormatted = "$displayName($wxId)"
        val adminNameFormatted = if (adminDisplayName.isNotEmpty()) "$adminDisplayName($adminWxId)" else ""
        return template
            // 原有兼容旧变量
            .replace("{链接昵称}", userNameFormatted)
            .replace("{管理员昵称}", adminNameFormatted)
            .replace("{旧昵称}", oldNick)
            .replace("{新昵称}", newNick)
            // 旧变量别名（$nickname 与 $userName 完全等效）
            .replace("\$nickname", userNameFormatted)
            // 新标准变量
            .replace("\$userName", userNameFormatted)
            .replace("\$adminName", adminNameFormatted)
            .replace("\$oldNickname", oldNick)
            .replace("\$newNickname", newNick)
    }

    // =========================================================================
    // 通用工具方法
    // =========================================================================
    private fun getDisplayName(wxId: String, roomDisplayNames: Map<String, String>): String {
        val roomName = roomDisplayNames[wxId] ?: ""
        if (roomName.isNotEmpty()) return roomName
        val dbName = WeDatabaseApi.getDisplayName(wxId)
        return dbName.ifEmpty { wxId }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun parseRoomData(blob: ByteArray?): Map<String, String> {
        if (blob == null || blob.isEmpty()) return emptyMap()
        return runCatching {
            ProtoBuf.decodeFromByteArray<ChatRoomDataProto>(blob)
                .members.associate { it.wxId to it.displayName }
        }.getOrElse { emptyMap() }
    }

    // =========================================================================
    // UI — 设置页面
    // =========================================================================
    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var masterEnabledState by remember { mutableStateOf(masterEnabled) }
            var modeAState by remember { mutableStateOf(modeAEnabled) }
            var modeBState by remember { mutableStateOf(modeBEnabled) }
            var joinState by remember { mutableStateOf(joinEnabled) }
            var leaveState by remember { mutableStateOf(leaveEnabled) }
            var nickState by remember { mutableStateOf(nickChangeEnabled) }
            var kickState by remember { mutableStateOf(kickEnabled) }
            var kickExtraState by remember { mutableStateOf(kickExtraExit) }
            var fakeUserState by remember { mutableStateOf(fakeUserBroadcast) }
            var groupFilterEnabledState by remember { mutableStateOf(groupFilterEnabled) }
            var selectedGroupsState by remember { mutableStateOf(getSelectedGroups()) }
            var showGroupSelector by remember { mutableStateOf(false) }

            var joinConfigState by remember { mutableStateOf(getEffectiveConfig("join")) }
            var leaveConfigState by remember { mutableStateOf(getEffectiveConfig("leave")) }
            var nickConfigState by remember { mutableStateOf(getEffectiveConfig("nick_change")) }
            var kickConfigState by remember { mutableStateOf(getEffectiveConfig("kick")) }

            AlertDialogContent(
                title = { Text("群成员变动提醒") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        // 总开关
                        ListItem(
                            modifier = Modifier.clickable { masterEnabledState = !masterEnabledState },
                            trailingContent = {
                                Switch(checked = masterEnabledState, onCheckedChange = null)
                            },
                            headlineContent = { Text("总开关", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("关闭后功能彻底停用，停止监听所有群成员变动事件") }
                        )

                        if (masterEnabledState) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // 模式选择
                            Text(
                                "模式选择",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 8.dp)
                            )

                            // 模式B：本地观察
                            ListItem(
                                modifier = Modifier.clickable {
                                    modeBState = !modeBState
                                    if (!modeBState) modeAState = false
                                },
                                trailingContent = {
                                    Switch(checked = modeBState, onCheckedChange = null)
                                },
                                headlineContent = { Text("本地观察模式") },
                                supportingContent = { Text("变动通知仅自己可见，不向群内发送消息") }
                            )

                            // 模式A：群广播推送
                            ListItem(
                                modifier = Modifier.clickable {
                                    if (modeBState) modeAState = !modeAState
                                },
                                trailingContent = {
                                    Switch(checked = modeAState, onCheckedChange = null)
                                },
                                headlineContent = {
                                    Text(
                                        "群广播推送",
                                        color = if (modeBState) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        "本机查看通知同时以本人账号推送纯文本消息到群聊；开启广播必须启用本地观察",
                                        color = if (modeBState) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    )
                                }
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // 假用户播报开关
                            ListItem(
                                modifier = Modifier.clickable { fakeUserState = !fakeUserState },
                                trailingContent = {
                                    Checkbox(checked = fakeUserState, onCheckedChange = null)
                                },
                                headlineContent = { Text("启用假用户播报") },
                                supportingContent = {
                                    Text(
                                        "关闭：仅显示居中本地系统提示\n" +
                                                "开启：居中提示保留，额外生成虚拟假用户发言（仅本地可见，不会发送到群内）\n" +
                                                "生效范围：进群、退群、被移出群聊"
                                    )
                                }
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // 指定群选择过滤
                            ListItem(
                                modifier = Modifier.clickable {
                                    groupFilterEnabledState = !groupFilterEnabledState
                                },
                                trailingContent = {
                                    Switch(checked = groupFilterEnabledState, onCheckedChange = null)
                                },
                                headlineContent = { Text("仅监控指定群聊") },
                                supportingContent = {
                                    val count = selectedGroupsState.size
                                    Text(
                                        if (count > 0) "已选择 $count 个群聊，仅监控指定群"
                                        else "关闭则监控全部群聊；开启后需选择目标群"
                                    )
                                }
                            )

                            if (groupFilterEnabledState) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { showGroupSelector = true },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("选择群聊")
                                    }
                                    if (selectedGroupsState.isNotEmpty()) {
                                        TextButton(
                                            onClick = {
                                                selectedGroupsState = emptySet()
                                            }
                                        ) {
                                            Text("清空选择")
                                        }
                                    }
                                }
                            }

                            if (showGroupSelector) {
                                val contacts = remember {
                                    WeDatabaseApi.getContacts().filter { it.wxId.endsWith("@chatroom") }
                                }
                                ContactsSelector(
                                    title = "选择监控群聊",
                                    contacts = contacts,
                                    initialSelectedWxIds = selectedGroupsState,
                                    onDismiss = { showGroupSelector = false },
                                    onConfirm = { newSelection ->
                                        selectedGroupsState = newSelection
                                        showGroupSelector = false
                                    }
                                )
                            }

                            Spacer(Modifier.height(4.dp))
                            Text(
                                "事件监控开关",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 8.dp)
                            )

                            EventConfigItem(
                                label = "主动入群提醒",
                                enabled = joinState,
                                onEnabledChange = { joinState = it },
                                config = joinConfigState,
                                onConfigChange = { joinConfigState = it },
                                enabledColor = ComposeColor(0xFF28C445)
                            )

                            EventConfigItem(
                                label = "主动退群提醒",
                                enabled = leaveState,
                                onEnabledChange = { leaveState = it },
                                config = leaveConfigState,
                                onConfigChange = { leaveConfigState = it },
                                enabledColor = ComposeColor(0xFF28C445)
                            )

                            EventConfigItem(
                                label = "修改群昵称提醒",
                                enabled = nickState,
                                onEnabledChange = { nickState = it },
                                config = nickConfigState,
                                onConfigChange = { nickConfigState = it },
                                enabledColor = ComposeColor(0xFF28C445)
                            )

                            EventConfigItem(
                                label = "被管理员踢出群组提醒",
                                enabled = kickState,
                                onEnabledChange = { kickState = it },
                                config = kickConfigState,
                                onConfigChange = { kickConfigState = it },
                                enabledColor = ComposeColor(0xFFF23030)
                            )

                            if (kickState) {
                                ListItem(
                                    modifier = Modifier.clickable { kickExtraState = !kickExtraState },
                                    trailingContent = {
                                        Checkbox(checked = kickExtraState, onCheckedChange = null)
                                    },
                                    headlineContent = { Text("被踢时附带生成【退出群组】样式提示") },
                                    supportingContent = {
                                        Text(
                                            "开启：同时生成红色「被移出群组」+ 绿色「退出了群组」两条提示\n" +
                                                    "关闭：仅展示一条「被移出群组」提示"
                                        )
                                    }
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                            // 变量说明：分区展示
                            Text(
                                "原有兼容旧变量（可继续正常使用）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Text(
                                "{链接昵称}：变动成员昵称，输出格式【昵称(wxid)】，完整兼容旧配置\n" +
                                        "{管理员昵称}：执行踢人操作的管理员，展示格式【昵称(wxid)】\n" +
                                        "{旧昵称}：成员修改之前的旧群昵称\n" +
                                        "{新昵称}：成员修改之后的新群昵称\n" +
                                        "\$nickname：变动成员昵称，与 {链接昵称} / \$userName 完全等效通用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "新标准变量（推荐使用）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "\$userName：发生变动的群成员，展示【昵称(wxid)】，与 {链接昵称} / \$nickname 完全等效通用\n" +
                                        "\$adminName：执行踢人操作的管理员，展示【昵称(wxid)】\n" +
                                        "\$oldNickname：成员修改之前的旧群昵称\n" +
                                        "\$newNickname：成员修改之后的新群昵称",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Text(
                                "无对应数据的占位符自动隐藏，不会原样展示变量文字",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                "彩色文字、昵称点击跳转功能仅本机生效；\n" +
                                        "开启群广播发送消息会自动清除所有样式，仅发送纯文本；\n" +
                                        "群自动发消息存在微信风控风险，请谨慎使用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        masterEnabled = masterEnabledState
                        modeAEnabled = modeAState
                        modeBEnabled = modeBState
                        joinEnabled = joinState
                        leaveEnabled = leaveState
                        nickChangeEnabled = nickState
                        kickEnabled = kickState
                        kickExtraExit = kickExtraState
                        fakeUserBroadcast = fakeUserState
                        groupFilterEnabled = groupFilterEnabledState
                        selectedGroupsJson = json.encodeToString(selectedGroupsState)
                        saveEventConfig("join", joinConfigState)
                        saveEventConfig("leave", leaveConfigState)
                        saveEventConfig("nick_change", nickConfigState)
                        saveEventConfig("kick", kickConfigState)
                        showToast("设置已保存")
                        onDismiss()
                    }) { Text("保存") }
                }
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun EventConfigItem(
        label: String,
        enabled: Boolean,
        onEnabledChange: (Boolean) -> Unit,
        config: EventConfig,
        onConfigChange: (EventConfig) -> Unit,
        enabledColor: ComposeColor
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ListItem(
                modifier = Modifier.clickable { onEnabledChange(!enabled) },
                trailingContent = {
                    Switch(checked = enabled, onCheckedChange = null)
                },
                headlineContent = { Text(label) }
            )

            if (enabled) {
                var colorText by remember(config) {
                    mutableStateOf(TextFieldValue(config.color))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("颜色:", style = MaterialTheme.typography.bodySmall)
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                runCatching {
                                    ComposeColor(AndroidColor.parseColor(config.color))
                                }.getOrDefault(enabledColor)
                            )
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                    )
                    OutlinedTextField(
                        value = colorText,
                        onValueChange = { v ->
                            colorText = v
                            if (v.text.matches(Regex("^#[0-9A-Fa-f]{6}$"))) {
                                onConfigChange(config.copy(color = v.text))
                            }
                        },
                        modifier = Modifier.width(100.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }

                var textValue by remember(config) {
                    mutableStateOf(TextFieldValue(config.text))
                }
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { v ->
                        textValue = v
                        onConfigChange(config.copy(text = v.text))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    label = { Text("文案模板") },
                    supportingText = {
                        Text(
                            buildString {
                                append("{链接昵称} / \$nickname / \$userName（三者等效通用）")
                                if (label.contains("昵称")) append(" | \$oldNickname / {旧昵称} | \$newNickname / {新昵称}")
                                if (label.contains("踢出")) append(" | \$adminName / {管理员昵称}")
                            },
                            fontSize = 11.sp
                        )
                    },
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}