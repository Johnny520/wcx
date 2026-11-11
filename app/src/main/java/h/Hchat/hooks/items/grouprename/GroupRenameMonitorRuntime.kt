package h.Hchat.hooks.items.grouprename

import android.content.SharedPreferences
import h.Hchat.hooks.api.contact.WeChatChatroomChangeApi
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.items.groupleave.GroupLeaveMonitorLinks
import h.Hchat.hooks.items.membertitle.MemberTitleStore
import h.Hchat.hooks.items.realtail.RealNameTailStore
import h.Hchat.preferences.HchatStorage
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class GroupRenameMonitorRuntime(
    context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs: SharedPreferences =
        HchatStorage.preferences(context.hostContext(), GroupRenameMonitorSettings.PREFS_NAME)
    private val realNameTailStore = RealNameTailStore(context.hostContext())
    private val memberSnapshots = ConcurrentHashMap<String, Set<String>>()
    private val memberNickSnapshots = ConcurrentHashMap<String, Map<String, String>>()
    private val memberSnapshotWarmups = ConcurrentHashMap<String, Long>()
    private val recentInserts = ConcurrentHashMap<String, Long>()
    private val atPattern = Regex("\\[AtWx=([^\\]]+)]")

    fun install(track: (Any?) -> Unit) {
        val changes = WeChatApis.contact().chatroomChanges() ?: WeChatApis.chatroomChanges()
        if (changes == null) {
            logger("群聊变更 API 未就绪", null)
            return
        }
        track(changes.subscribe { change -> handleChatroomChange(change) })
        preloadSnapshots()
    }

    fun preloadSnapshots() {
        runCatching {
            val chatrooms = WeChatApis.contact().chatrooms() ?: return
            for (room in chatrooms.getChatrooms()) {
                val groupId = room.chatroomId.trim()
                if (groupId.isEmpty()) continue
                val members = room.memberIds
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toCollection(LinkedHashSet())
                if (members.isEmpty()) continue
                memberSnapshots.putIfAbsent(groupId, members)
                val names = currentGroupNickNames(groupId, members, null)
                if (names.isNotEmpty()) {
                    memberNickSnapshots.putIfAbsent(groupId, names)
                }
            }
        }.onFailure {
            logger("预加载群成员改名快照失败", it)
        }
    }

    private fun handleChatroomChange(change: WeChatChatroomChangeApi.ChatroomChange) {
        val memberListChanged = change.mayMemberListChanged()
        val roomDataChanged = change.mayRoomDataChanged()
        if (!memberListChanged && !roomDataChanged) return
        val groupId = change.chatroomId().trim()
        if (groupId.isEmpty()) return
        val currentMembers = currentMembers(change)
        if (currentMembers.isEmpty()) return
        val currentNames = currentGroupNickNames(groupId, currentMembers, change)
        val previousNames = if (currentNames.isEmpty()) {
            null
        } else {
            memberNickSnapshots.put(groupId, currentNames)
        }
        val previousMembers = if (memberListChanged) {
            memberSnapshots.put(groupId, currentMembers)
        } else {
            memberSnapshots[groupId].also { existing ->
                if (existing == null) memberSnapshots.putIfAbsent(groupId, currentMembers)
            }
        }
        val now = System.currentTimeMillis()
        val joined = if (previousMembers == null) emptySet() else currentMembers - previousMembers
        val left = if (previousMembers == null) emptySet() else previousMembers - currentMembers
        val warmingUp = isSnapshotWarmup(groupId, now)
        val bulkMemberSync = left.isEmpty() && joined.size >= BULK_SYNC_THRESHOLD
        val candidates = if (!warmingUp && !bulkMemberSync && previousNames != null && previousMembers != null) {
            renamedMembers(previousNames, currentNames, previousMembers intersect currentMembers)
        } else {
            emptyList()
        }
        val renames = candidates.takeUnless { it.size >= BULK_SYNC_THRESHOLD }.orEmpty()
        for (rename in renames) {
            if (prefs.getBoolean(
                    GroupRenameMonitorSettings.KEY_NOTICE_ENABLE,
                    GroupRenameMonitorSettings.DEFAULT_NOTICE_ENABLE
                ) && isSystemNoticeGroupEnabled(groupId)
            ) {
                insertRenameMessage(groupId, rename)
            }
            sendRenameNotice(groupId, rename)
        }
        if (previousMembers == null || (memberListChanged && bulkMemberSync)) {
            memberSnapshotWarmups[groupId] = now + SNAPSHOT_WARMUP_MS
        }
    }

    private fun currentMembers(change: WeChatChatroomChangeApi.ChatroomChange): Set<String> {
        val ids = change.chatroom?.memberIds
            ?: WeChatApis.contact().chatrooms()?.getMemberIds(change.chatroomId())
            ?: emptyList()
        return ids.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())
    }

    private fun currentGroupNickNames(
        groupId: String,
        members: Set<String>,
        change: WeChatChatroomChangeApi.ChatroomChange?
    ): Map<String, String> {
        val resolved = WeChatApis.contact().contacts()?.getGroupMemberRoomDisplayNames(groupId).orEmpty()
        val result = LinkedHashMap<String, String>()
        for (memberId in members) {
            if (resolved.containsKey(memberId)) {
                result[memberId] = normalizeGroupNick(resolved[memberId], memberId)
            }
        }
        val chatroom = change?.chatroom
        if (chatroom != null && chatroom.memberIds.isNotEmpty()) {
            val rawNames = splitDisplayNames(chatroom.rawDisplayNames, chatroom.memberIds.size)
            if (rawNames.size == chatroom.memberIds.size) {
                chatroom.memberIds.forEachIndexed { index, memberId ->
                    if (memberId in members && !result.containsKey(memberId)) {
                        result[memberId] = normalizeGroupNick(rawNames[index], memberId)
                    }
                }
            }
        }
        return result.takeIf { it.keys.containsAll(members) }.orEmpty()
    }

    private fun normalizeGroupNick(value: String?, memberId: String): String {
        return value?.trim().orEmpty().takeUnless { it == memberId }.orEmpty()
    }

    private fun renamedMembers(
        previous: Map<String, String>,
        current: Map<String, String>,
        stableMembers: Set<String>
    ): List<MemberRename> {
        return stableMembers.mapNotNull { memberId ->
            val oldName = previous[memberId]?.trim().orEmpty()
            val newName = current[memberId]?.trim().orEmpty()
            if (oldName == newName) null else MemberRename(memberId, oldName, newName)
        }
    }

    private fun isSnapshotWarmup(groupId: String, now: Long): Boolean {
        val until = memberSnapshotWarmups[groupId] ?: return false
        if (now <= until) return true
        memberSnapshotWarmups.remove(groupId, until)
        return false
    }

    private fun insertRenameMessage(groupId: String, rename: MemberRename) {
        val now = System.currentTimeMillis()
        cleanupRecentInserts(now)
        val key = "$groupId|${rename.memberId}|${rename.oldName}|${rename.newName}"
        val previous = recentInserts[key]
        if (previous != null && now - previous < DEDUP_WINDOW_MS) return
        recentInserts[key] = now
        val localMessages = WeChatApis.message().local()
        if (localMessages == null) {
            logger("改名系统消息插入失败: 本地消息 API 未就绪", null)
            return
        }
        runCatching {
            localMessages.ensureReady()
            val result = localMessages.insertSystemMessage(groupId, buildSystemNotice(groupId, rename), now)
            if (result <= 0L) logger("改名系统消息插入失败: $groupId/${rename.memberId}", null)
        }.onFailure {
            logger("改名系统消息插入异常", it)
        }
    }

    private fun isSystemNoticeGroupEnabled(groupId: String): Boolean {
        val scope = prefs.getString(
            GroupRenameMonitorSettings.KEY_NOTICE_SCOPE,
            GroupRenameMonitorSettings.DEFAULT_NOTICE_SCOPE
        ) ?: GroupRenameMonitorSettings.DEFAULT_NOTICE_SCOPE
        return scope != GroupRenameMonitorSettings.NOTICE_SCOPE_SPECIFIC ||
            splitIds(prefs.getString(GroupRenameMonitorSettings.KEY_NOTICE_GROUPS, "").orEmpty()).contains(groupId)
    }

    private fun buildSystemNotice(groupId: String, rename: MemberRename): String {
        val variables = replyVariables(groupId, rename)
        val template = prefs.getString(
            GroupRenameMonitorSettings.KEY_NOTICE_TEXT,
            GroupRenameMonitorSettings.DEFAULT_NOTICE_TEXT
        ) ?: GroupRenameMonitorSettings.DEFAULT_NOTICE_TEXT
        return applySystemNoticeVariables(template, variables)
    }

    private fun wxidLink(memberId: String): String {
        val color = MemberTitleStore.cleanColor(
            prefs.getString(
                GroupRenameMonitorSettings.KEY_WXID_COLOR,
                GroupRenameMonitorSettings.DEFAULT_WXID_COLOR
            )?.substringBefore(',')
        ).ifEmpty { GroupRenameMonitorSettings.DEFAULT_WXID_COLOR }
        return "<_wc_custom_link_ color=\"$color\" href=\"${escapeXml(GroupLeaveMonitorLinks.profileUri(memberId))}\">${escapeXml(memberId)}</_wc_custom_link_>"
    }

    private fun sendRenameNotice(groupId: String, rename: MemberRename) {
        if (!prefs.getBoolean(
                GroupRenameMonitorSettings.KEY_SEND_ENABLE,
                GroupRenameMonitorSettings.DEFAULT_SEND_ENABLE
            )
        ) return
        if (groupId !in listenGroups()) return
        Thread({
            runCatching {
                val template = replyTemplateFor(groupId)
                if (template != null && !template.enabled) return@runCatching
                val delaySeconds = (template?.delaySeconds ?: prefs.getInt(
                    GroupRenameMonitorSettings.KEY_DELAY_SECONDS,
                    GroupRenameMonitorSettings.DEFAULT_DELAY_SECONDS
                )).coerceIn(0, 600)
                sleepQuietly(delaySeconds * 1000L)
                val variables = replyVariables(groupId, rename)
                val promptType = template?.promptType ?: promptTypeFor(groupId)
                val textTask = { sendText(groupId, variables, template) }
                val cardTask = { sendCard(groupId, variables, template) }
                val tasks = when (promptType) {
                    GroupRenameMonitorSettings.PROMPT_CARD -> listOf(cardTask)
                    GroupRenameMonitorSettings.PROMPT_BOTH -> {
                        if ((template?.bothOrder ?: bothOrderFor(groupId)) == GroupRenameMonitorSettings.BOTH_CARD_FIRST) {
                            listOf(cardTask, textTask)
                        } else {
                            listOf(textTask, cardTask)
                        }
                    }
                    else -> listOf(textTask)
                }
                tasks.forEachIndexed { index, task ->
                    task()
                    if (index < tasks.lastIndex) sleepQuietly(300L)
                }
            }.onFailure {
                logger("改名提醒发送异常: $groupId/${rename.memberId}", it)
            }
        }, "Hchat-GroupRenameMonitor").start()
    }

    private fun replyVariables(groupId: String, rename: MemberRename): ReplyVariables {
        val contacts = WeChatApis.contact().contacts()
        val contact = contacts?.getContact(rename.memberId)
        val wechatName = firstNotBlank(contact?.nickname, contact?.remarkName, rename.memberId)
        val oldDisplayName = rename.oldName.ifBlank { wechatName }
        val newDisplayName = rename.newName.ifBlank { wechatName }
        return ReplyVariables(
            userWxid = rename.memberId,
            userName = wechatName,
            groupNickname = newDisplayName,
            oldGroupNickname = oldDisplayName,
            newGroupNickname = newDisplayName,
            realNameTail = realNameTailStore.displayTail(rename.memberId),
            gender = realNameTailStore.genderText(contacts?.getGender(rename.memberId) ?: 0),
            region = contacts?.getRegion(rename.memberId)?.trim()?.replace(Regex("\\s+"), " ").orEmpty(),
            groupName = contacts?.getContact(groupId)?.displayName()?.takeIf { it.isNotBlank() && it != groupId }
                ?: WeChatApis.contact().chatrooms()?.getChatroomName(groupId)?.takeIf { it.isNotBlank() }
                ?: groupId,
            time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        )
    }

    private fun sendText(groupId: String, variables: ReplyVariables, template: GroupRenameReplyTemplate?) {
        val source = template?.text
            ?: groupValue(groupId, GroupRenameMonitorSettings.KEY_TEXT, GroupRenameMonitorSettings.DEFAULT_TEXT)
        val text = applyVariables(pickTemplate(source), variables)
        if (text.isBlank()) return
        val sender = WeChatApis.message().sender() ?: WeChatApis.messages()
        if (sender == null) {
            logger("改名提醒发送失败: 消息发送 API 未就绪", null)
            return
        }
        val parsed = parseAtContent(groupId, text)
        val sent = when {
            parsed == null -> sender.sendText(groupId, text)
            parsed.atList.isEmpty() -> sender.sendText(groupId, parsed.content)
            else -> sender.sendTextWithAtList(groupId, parsed.content, parsed.atList)
        }
        if (!sent && parsed?.content?.isNotBlank() == true) {
            sender.sendText(groupId, parsed.content)
        }
    }

    private fun sendCard(groupId: String, variables: ReplyVariables, template: GroupRenameReplyTemplate?) {
        val titleSource = template?.cardTitle
            ?: groupValue(groupId, GroupRenameMonitorSettings.KEY_CARD_TITLE, GroupRenameMonitorSettings.DEFAULT_CARD_TITLE)
        val descSource = template?.cardDesc
            ?: groupValue(groupId, GroupRenameMonitorSettings.KEY_CARD_DESC, GroupRenameMonitorSettings.DEFAULT_CARD_DESC)
        val title = applyVariables(pickTemplate(titleSource), variables)
        val desc = applyVariables(pickTemplate(descSource), variables)
        if (title.isBlank() && desc.isBlank()) return
        val avatarUrl = WeChatApis.contact().contacts()?.getAvatarUrl(variables.userWxid, true).orEmpty()
        val thumb = downloadBytes(avatarUrl)
        val sent = WeChatApis.media()?.shareWebpage(groupId, title, desc, avatarUrl, thumb, "") == true
        if (!sent) {
            (WeChatApis.message().sender() ?: WeChatApis.messages())?.sendText(
                groupId,
                listOf(title, desc).filter { it.isNotBlank() }.joinToString("\n")
            )
        }
    }

    private fun promptTypeFor(groupId: String): String {
        val groupValue = prefs.getString(
            GroupRenameMonitorSettings.groupKey(GroupRenameMonitorSettings.KEY_PROMPT_TYPE, groupId),
            GroupRenameMonitorSettings.MODE_GLOBAL
        ) ?: GroupRenameMonitorSettings.MODE_GLOBAL
        val value = if (groupValue == GroupRenameMonitorSettings.MODE_GLOBAL) {
            prefs.getString(GroupRenameMonitorSettings.KEY_PROMPT_TYPE, GroupRenameMonitorSettings.DEFAULT_PROMPT_TYPE)
                ?: GroupRenameMonitorSettings.DEFAULT_PROMPT_TYPE
        } else {
            groupValue
        }
        return GroupRenameMonitorSettings.normalizePromptType(value)
    }

    private fun bothOrderFor(groupId: String): String {
        val groupPromptType = prefs.getString(
            GroupRenameMonitorSettings.groupKey(GroupRenameMonitorSettings.KEY_PROMPT_TYPE, groupId),
            GroupRenameMonitorSettings.MODE_GLOBAL
        ) ?: GroupRenameMonitorSettings.MODE_GLOBAL
        val key = if (groupPromptType == GroupRenameMonitorSettings.MODE_GLOBAL) {
            GroupRenameMonitorSettings.KEY_BOTH_ORDER
        } else {
            GroupRenameMonitorSettings.groupKey(GroupRenameMonitorSettings.KEY_BOTH_ORDER, groupId)
        }
        return GroupRenameMonitorSettings.normalizeBothOrder(
            prefs.getString(key, GroupRenameMonitorSettings.DEFAULT_BOTH_ORDER)
                ?: GroupRenameMonitorSettings.DEFAULT_BOTH_ORDER
        )
    }

    private fun groupValue(groupId: String, key: String, fallback: String): String {
        return prefs.getString(GroupRenameMonitorSettings.groupKey(key, groupId), null)
            ?: prefs.getString(key, fallback)
            ?: fallback
    }

    private fun listenGroups(): Set<String> {
        return splitIds(prefs.getString(GroupRenameMonitorSettings.KEY_LISTEN_GROUPS, "").orEmpty())
    }

    private fun replyTemplateFor(groupId: String): GroupRenameReplyTemplate? {
        val binding = GroupRenameMonitorSettings.parseBindings(
            prefs.getString(GroupRenameMonitorSettings.KEY_TEMPLATE_BINDINGS, "").orEmpty()
        ).firstOrNull { it.groupId == groupId } ?: return null
        return GroupRenameMonitorSettings.parseTemplates(
            prefs.getString(GroupRenameMonitorSettings.KEY_TEMPLATES, "").orEmpty()
        ).firstOrNull { it.id == binding.templateId }
    }

    private fun applyVariables(template: String, variables: ReplyVariables): String {
        return template
            .replace("%userName%", variables.userName)
            .replace("%groupNickname%", variables.groupNickname)
            .replace("%oldGroupNickname%", variables.oldGroupNickname)
            .replace("%newGroupNickname%", variables.newGroupNickname)
            .replace("%userWxid%", variables.userWxid)
            .replace("%realNameTail%", variables.realNameTail)
            .replace("%gender%", variables.gender)
            .replace("%region%", variables.region)
            .replace("%groupName%", variables.groupName)
            .replace("%time%", variables.time)
    }

    private fun applySystemNoticeVariables(template: String, variables: ReplyVariables): String {
        val values = mapOf(
            "%userName%" to variables.userName,
            "%groupNickname%" to variables.groupNickname,
            "%oldGroupNickname%" to variables.oldGroupNickname,
            "%newGroupNickname%" to variables.newGroupNickname,
            "%realNameTail%" to variables.realNameTail,
            "%gender%" to variables.gender,
            "%region%" to variables.region,
            "%groupName%" to variables.groupName,
            "%time%" to variables.time
        )
        val tokens = values.keys + "%userWxid%"
        val tokenPattern = Regex(tokens.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) })
        return tokenPattern.replace(template) { match ->
            if (match.value == "%userWxid%") {
                wxidLink(variables.userWxid)
            } else {
                escapeXml(values[match.value].orEmpty())
            }
        }
    }

    private fun parseAtContent(groupId: String, content: String): ParsedAtContent? {
        val atList = ArrayList<String>()
        val parsed = atPattern.replace(content) { match ->
            val wxid = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (wxid.isBlank()) {
                ""
            } else {
                atList.add(wxid)
                "@${mentionDisplayName(groupId, wxid)}\u2005"
            }
        }
        if (atList.isEmpty()) return null
        return ParsedAtContent(parsed, atList.distinct())
    }

    private fun mentionDisplayName(groupId: String, wxid: String): String {
        if (wxid == "notify@all") return "所有人"
        val contacts = WeChatApis.contact().contacts()
        val contact = contacts?.getContact(wxid)
        return firstNotBlank(
            contacts?.getGroupMemberRoomDisplayName(groupId, wxid),
            contact?.nickname,
            contact?.customWxId,
            wxid
        ).replace('\n', ' ').replace('\r', ' ').trim().ifBlank { wxid }
    }

    private fun pickTemplate(value: String): String {
        val choices = value.split("||").map { it.trim() }.filter { it.isNotEmpty() }
        return if (choices.isEmpty()) "" else choices[Random.nextInt(choices.size)]
    }

    private fun splitIds(value: String): Set<String> {
        return value.split('|', ',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun splitDisplayNames(displayNames: String, expectedCount: Int): List<String> {
        if (displayNames.isBlank()) return emptyList()
        for (delimiter in listOf("\u0001", "\u0002", "\n", ";")) {
            val parts = displayNames.split(delimiter)
            if (parts.size == expectedCount) return parts
        }
        return listOf(displayNames)
    }

    private fun cleanupRecentInserts(now: Long) {
        if (recentInserts.size >= 128) {
            recentInserts.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
        }
    }

    private fun downloadBytes(url: String): ByteArray? {
        if (url.isBlank()) return null
        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 1200
            connection.readTimeout = 1200
            connection.requestMethod = "GET"
            connection.inputStream.use { it.readBytes() }.also { connection.disconnect() }
        }.getOrNull()
    }

    private fun sleepQuietly(ms: Long) {
        if (ms <= 0L) return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun firstNotBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private data class MemberRename(val memberId: String, val oldName: String, val newName: String)

    private data class ParsedAtContent(val content: String, val atList: List<String>)

    private data class ReplyVariables(
        val userWxid: String,
        val userName: String,
        val groupNickname: String,
        val oldGroupNickname: String,
        val newGroupNickname: String,
        val realNameTail: String,
        val gender: String,
        val region: String,
        val groupName: String,
        val time: String
    )

    private companion object {
        const val DEDUP_WINDOW_MS = 5000L
        const val SNAPSHOT_WARMUP_MS = 15_000L
        const val BULK_SYNC_THRESHOLD = 10
    }
}
