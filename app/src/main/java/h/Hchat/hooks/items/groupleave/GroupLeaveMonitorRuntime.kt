package h.Hchat.hooks.items.groupleave

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import h.Hchat.hooks.api.contact.WeChatChatroomChangeApi
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.message.WeChatMessageObserveApi
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.items.membertitle.MemberTitleStore
import h.Hchat.hooks.items.realtail.RealNameTailStore
import h.Hchat.preferences.HchatStorage
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import java.util.concurrent.ConcurrentHashMap

class GroupLeaveMonitorRuntime(
    context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs: SharedPreferences =
        HchatStorage.preferences(context.hostContext(), GroupLeaveMonitorSettings.PREFS_NAME)
    private val realNameTailStore = RealNameTailStore(context.hostContext())
    private val memberSnapshots = ConcurrentHashMap<String, Set<String>>()
    private val memberSnapshotWarmups = ConcurrentHashMap<String, Long>()
    private val memberNameCache = ConcurrentHashMap<String, String>()
    private val observedMemberNames = ConcurrentHashMap<String, ObservedMemberName>()
    private val recentInserts = ConcurrentHashMap<String, Long>()
    private val pendingInviteDetails = ConcurrentHashMap<String, PendingInviteDetail>()
    private val inviteCountLock = Any()
    private val atPattern = Regex("\\[AtWx=([^\\]]+)]")

    fun install(track: (Any?) -> Unit) {
        val changeApi = WeChatApis.contact().chatroomChanges() ?: WeChatApis.chatroomChanges()
        if (changeApi != null) {
            track(changeApi.subscribe { change ->
                handleChatroomChange(change)
            })
        } else {
            logger("群聊变更 API 未就绪", null)
        }
        val observeApi = runCatching { WeChatApis.messageObserve() }.getOrNull()
        if (observeApi != null && observeApi.isAvailable()) {
            runCatching { observeApi.install() }
                .onFailure { logger("邀请详情消息监听安装失败", it) }
            track(observeApi.subscribe { message ->
                handleObservedMessage(message)
            })
        }
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
                rememberCurrentGroupNickNames(groupId, members)
            }
        }.onFailure {
            logger("预加载群成员快照失败", it)
        }
    }

    private fun handleChatroomChange(change: WeChatChatroomChangeApi.ChatroomChange) {
        val now = System.currentTimeMillis()
        val memberListChanged = change.mayMemberListChanged()
        val roomDataChanged = change.mayRoomDataChanged()
        if (!memberListChanged && !roomDataChanged) return
        val groupId = change.chatroomId().trim()
        if (groupId.isEmpty()) return
        if (roomDataChanged) {
            if (isMemberSnapshotWarmup(groupId, now)) {
                clearPendingInviteDetails(groupId)
            } else {
                flushPendingInviteDetails(groupId)
            }
        }
        if (!memberListChanged) return
        rememberDisplayNames(groupId, change)
        val current = currentMembers(change)
        if (current.isEmpty()) return
        rememberCurrentGroupNickNames(groupId, current)
        val previous = memberSnapshots.put(groupId, current)
        if (previous == null) {
            startMemberSnapshotWarmup(groupId, now)
            return
        }
        val joinedMembers = current - previous
        val leftMembers = previous - current
        if (joinedMembers.isEmpty() && leftMembers.isEmpty()) return
        if (isMemberSnapshotWarmup(groupId, now) || isBulkMemberSync(joinedMembers, leftMembers)) {
            startMemberSnapshotWarmup(groupId, now)
            clearPendingInviteDetails(groupId)
            return
        }
        for (memberId in leftMembers) {
            if (isEnabled() && isSystemNoticeGroupEnabled(groupId)) {
                insertLeaveMessage(groupId, memberId)
            }
            handleMemberReply("left", groupId, memberId)
        }
        for (memberId in joinedMembers) {
            if (!handleInviteDetailFromRoomData(groupId, memberId)) {
                rememberPendingInviteDetail(groupId, memberId)
            }
            handleMemberReply("join", groupId, memberId)
        }
    }

    private fun startMemberSnapshotWarmup(groupId: String, now: Long) {
        memberSnapshotWarmups[groupId] = now + NEW_GROUP_SNAPSHOT_WARMUP_MS
        clearPendingInviteDetails(groupId)
    }

    private fun isMemberSnapshotWarmup(groupId: String, now: Long): Boolean {
        val until = memberSnapshotWarmups[groupId] ?: return false
        if (now <= until) return true
        memberSnapshotWarmups.remove(groupId, until)
        return false
    }

    private fun isBulkMemberSync(joinedMembers: Set<String>, leftMembers: Set<String>): Boolean {
        return leftMembers.isEmpty() && joinedMembers.size >= BULK_MEMBER_SYNC_THRESHOLD
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

    private fun rememberDisplayNames(groupId: String, change: WeChatChatroomChangeApi.ChatroomChange) {
        val chatroom = change.chatroom ?: return
        val members = chatroom.memberIds
        val names = splitDisplayNames(chatroom.rawDisplayNames, members.size)
        if (members.isEmpty() || names.size != members.size) return
        members.forEachIndexed { index, wxid ->
            val name = names.getOrNull(index)?.trim().orEmpty()
            if (wxid.isNotBlank() && name.isNotBlank() && name != wxid) {
                memberNameCache[cacheKey(groupId, wxid)] = name
            }
        }
    }

    private fun rememberCurrentGroupNickNames(groupId: String, members: Set<String>) {
        val names = WeChatApis.contact().contacts()?.getGroupMemberRoomDisplayNames(groupId).orEmpty()
        for (memberId in members) {
            val name = names[memberId]?.trim().orEmpty()
            if (name.isNotEmpty() && name != memberId) {
                memberNameCache[cacheKey(groupId, memberId)] = name
            }
        }
    }

    private fun memberDisplay(groupId: String, memberId: String): MemberDisplay {
        val contacts = WeChatApis.contact().contacts()
        val observedName = observedMemberName(groupId, memberId)
        val groupNick = memberNameCache[cacheKey(groupId, memberId)]
            ?.takeIf { it.isNotBlank() && it != memberId }
            ?: contacts?.getGroupMemberRoomDisplayName(groupId, memberId)
                ?.takeIf { it.isNotBlank() && it != memberId }
            ?: observedName
            ?: contacts?.getGroupMemberDisplayName(groupId, memberId)
                ?.takeIf { it.isNotBlank() && it != memberId }
            ?: ""
        val contact = contacts?.getContact(memberId)
        val wechatNick = contact?.nickname
            ?.takeIf { it.isNotBlank() && it != memberId }
            ?: ""
        val remarkName = contact?.remarkName
            ?.takeIf { it.isNotBlank() && it != memberId }
            ?: ""
        return MemberDisplay(groupNick, wechatNick, remarkName, memberId, observedName)
    }

    private fun insertLeaveMessage(groupId: String, memberId: String) {
        val now = System.currentTimeMillis()
        cleanupRecentInserts(now)
        val key = "$groupId|$memberId"
        val previous = recentInserts[key]
        if (previous != null && now - previous < DEDUP_WINDOW_MS) return
        recentInserts[key] = now
        val localMessages = WeChatApis.message().local()
        if (localMessages == null) {
            logger("本地消息 API 未就绪", null)
            return
        }
        runCatching {
            localMessages.ensureReady()
            val content = buildLeaveNoticeText(groupId, memberDisplay(groupId, memberId))
            val result = localMessages.insertSystemMessage(groupId, content, now)
            if (result <= 0L) {
                logger("退群系统消息插入失败: $groupId/$memberId", null)
            }
        }.onFailure {
            logger("退群系统消息插入异常", it)
        }
    }

    private fun handleObservedMessage(message: WeChatMessageObserveApi.ObservedMessage) {
        if (!message.isSystem() || !message.isGroupChat()) return
        val groupId = message.getTalker().trim().ifBlank { message.talker.trim() }
        if (groupId.isEmpty()) return
        val content = message.getContent()
        val xml = message.xml
        val raw = buildString {
            if (content.isNotBlank()) append(content)
            if (xml.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(xml)
            }
        }
        if (looksLikeJoinNotice(raw)) {
            collectInviteMembers(groupId, xml, content).forEach { member ->
                rememberInviteMemberName(groupId, member)
            }
        }
        if (!isInviteDetailEnabled()) return
        val detail = parseInviteDetail(groupId, message) ?: return
        rememberInviteMemberName(groupId, detail.inviter)
        for (invitee in detail.invitees) {
            rememberInviteMemberName(groupId, invitee)
        }
    }

    private fun parseInviteDetail(
        groupId: String,
        message: WeChatMessageObserveApi.ObservedMessage
    ): InviteDetail? {
        val content = message.getContent()
        val xml = message.xml
        val raw = buildString {
            if (content.isNotBlank()) append(content)
            if (xml.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(xml)
            }
        }
        if (!looksLikeInviteNotice(raw)) return null
        val members = collectInviteMembers(groupId, xml, content)
        if (members.isEmpty()) return null

        val sender = message.getSendTalker()
            .takeIf { isValidInviteMemberId(groupId, it) }
            ?.let { InviteMember(it, "") }
        val self = WeChatApis.contact().account()?.selfWxId()
            ?.takeIf { isValidInviteMemberId(groupId, it) }
            ?.let { InviteMember(it, "") }

        val inviter: InviteMember
        val invitees: List<InviteMember>
        if (members.size >= 2) {
            inviter = members.first()
            invitees = members.drop(1)
        } else {
            inviter = sender ?: self?.takeIf { raw.contains("你邀请") } ?: return null
            invitees = members
        }
        val cleanedInvitees = invitees
            .filter { it.wxid != inviter.wxid }
            .distinctBy { it.wxid }
        if (cleanedInvitees.isEmpty()) return null
        return InviteDetail(inviter, cleanedInvitees)
    }

    private fun collectInviteMembers(groupId: String, vararg sources: String): List<InviteMember> {
        val members = LinkedHashMap<String, InviteMember>()
        for (source in sources) {
            collectInviteMembersFromSource(groupId, source, members)
            val decoded = decodeXml(source)
            if (decoded != source) {
                collectInviteMembersFromSource(groupId, decoded, members)
            }
        }
        return members.values.toList()
    }

    private fun collectInviteMembersFromSource(
        groupId: String,
        source: String,
        out: LinkedHashMap<String, InviteMember>
    ) {
        if (source.isBlank()) return
        INVITE_MEMBER_TAG.findAll(source).forEach { match ->
            val attrs = tagAttributes(match.value)
            val wxid = attrs["username"]?.trim().orEmpty()
            if (!isValidInviteMemberId(groupId, wxid) || out.containsKey(wxid)) return@forEach
            val name = firstNotBlank(
                attrs["name"],
                attrs["nickname"],
                linkInnerText(source, match.range.last + 1)
            ).trim()
            out[wxid] = InviteMember(wxid, name)
        }
    }

    private fun tagAttributes(tag: String): Map<String, String> {
        val attrs = LinkedHashMap<String, String>()
        INVITE_TAG_ATTR.findAll(tag).forEach { match ->
            val key = match.groupValues.getOrNull(1)?.lowercase(Locale.US).orEmpty()
            val value = decodeXml(match.groupValues.getOrNull(2).orEmpty())
            if (key.isNotBlank()) attrs[key] = value
        }
        return attrs
    }

    private fun linkInnerText(source: String, start: Int): String {
        if (start <= 0 || start >= source.length) return ""
        val end = source.indexOf("</link>", start, true)
        if (end <= start) return ""
        return stripXmlTags(source.substring(start, end))
    }

    private fun insertInviteDetailMessage(
        groupId: String,
        inviter: InviteMember,
        invitee: InviteMember,
        count: Int
    ) {
        val now = System.currentTimeMillis()
        val localMessages = WeChatApis.message().local()
        if (localMessages == null) {
            logger("邀请详情插入失败: 本地消息 API 未就绪", null)
            return
        }
        runCatching {
            localMessages.ensureReady()
            val content = buildInviteDetailNoticeText(
                groupId,
                memberDisplay(groupId, inviter.wxid),
                memberDisplay(groupId, invitee.wxid),
                count
            )
            val result = localMessages.insertSystemMessage(groupId, content, now)
            if (result <= 0L) {
                logger("邀请详情插入失败: $groupId/${inviter.wxid}/${invitee.wxid}", null)
            }
        }.onFailure {
            logger("邀请详情插入异常", it)
        }
    }

    private fun buildInviteDetailNoticeText(
        groupId: String,
        inviter: MemberDisplay,
        invitee: MemberDisplay,
        count: Int
    ): String {
        val template = prefs.getString(
            GroupLeaveMonitorSettings.KEY_INVITE_NOTICE_TEXT,
            GroupLeaveMonitorSettings.DEFAULT_INVITE_NOTICE_TEXT
        ) ?: GroupLeaveMonitorSettings.DEFAULT_INVITE_NOTICE_TEXT
        return applySystemTemplate(
            template,
            values = mapOf(
                "%inviterName%" to inviter.inviteDetailName(),
                "%inviterGroupNickname%" to inviter.groupNick,
                "%inviteeName%" to invitee.inviteDetailName(),
                "%inviteeGroupNickname%" to invitee.groupNick,
                "%inviteCount%" to count.coerceAtLeast(1).toString(),
                "%groupName%" to groupDisplayName(groupId),
                "%time%" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            ),
            links = mapOf(
                "%inviterWxid%" to inviter.memberId,
                "%inviteeWxid%" to invitee.memberId
            )
        )
    }

    private fun rememberInviteMemberName(groupId: String, member: InviteMember) {
        val name = member.name.trim()
        if (name.isNotEmpty() && name != member.wxid) {
            val key = cacheKey(groupId, member.wxid)
            val now = System.currentTimeMillis()
            cleanupObservedMemberNames(now)
            memberNameCache[key] = name
            observedMemberNames[key] = ObservedMemberName(name, now + OBSERVED_MEMBER_NAME_TTL_MS)
        }
    }

    private fun observedMemberName(groupId: String, memberId: String): String {
        val key = cacheKey(groupId, memberId)
        val cached = observedMemberNames[key] ?: return ""
        if (cached.expiresAt < System.currentTimeMillis()) {
            observedMemberNames.remove(key, cached)
            return ""
        }
        return cached.name.takeIf { it.isNotBlank() && it != memberId }.orEmpty()
    }

    private fun cleanupObservedMemberNames(now: Long) {
        if (observedMemberNames.size < 128) return
        observedMemberNames.entries.removeIf { it.value.expiresAt < now }
    }

    private fun handleInviteDetailFromRoomData(
        groupId: String,
        inviteeId: String,
        inviteCountDelta: Int = 1
    ): Boolean {
        val inviterId = WeChatApis.contact().chatrooms()
            ?.getMemberInviter(groupId, inviteeId)
            ?.trim()
            .orEmpty()
        if (!isValidInviteMemberId(groupId, inviterId) || inviterId == inviteeId) return false
        val inviter = InviteMember(inviterId, "")
        val invitee = InviteMember(inviteeId, "")
        val count = addInviteCount(groupId, inviter.wxid, inviteCountDelta)
        if (isInviteDetailEnabled() && isSystemNoticeGroupEnabled(groupId)) {
            insertInviteDetailMessage(groupId, inviter, invitee, count)
        }
        return true
    }

    private fun rememberPendingInviteDetail(groupId: String, inviteeId: String) {
        if (!isValidInviteMemberId(groupId, inviteeId)) return
        val now = System.currentTimeMillis()
        cleanupPendingInviteDetails(now)
        pendingInviteDetails.compute(pendingInviteKey(groupId, inviteeId)) { _, current ->
            PendingInviteDetail((current?.count ?: 0) + 1, now)
        }
    }

    private fun flushPendingInviteDetails(groupId: String) {
        val now = System.currentTimeMillis()
        cleanupPendingInviteDetails(now)
        val prefix = "$groupId|"
        pendingInviteDetails.entries
            .filter { it.key.startsWith(prefix) }
            .forEach { entry ->
                val inviteeId = entry.key.removePrefix(prefix)
                if (handleInviteDetailFromRoomData(groupId, inviteeId, entry.value.count)) {
                    pendingInviteDetails.remove(entry.key)
                }
            }
    }

    private fun clearPendingInviteDetails(groupId: String) {
        val prefix = "$groupId|"
        pendingInviteDetails.keys.removeIf { it.startsWith(prefix) }
    }

    private fun buildLeaveNoticeText(groupId: String, display: MemberDisplay): String {
        val primaryName = display.primaryName()
        val wechatNick = display.wechatNick.ifBlank { display.memberId }
        val displayName = buildString {
            append(primaryName)
            if (display.shouldShowWechatNick(primaryName)) {
                append('(').append(wechatNick).append(')')
            }
            if (display.shouldShowRemark(primaryName, wechatNick)) {
                append('[').append(display.remarkName).append(']')
            }
        }
        val template = prefs.getString(
            GroupLeaveMonitorSettings.KEY_LEAVE_NOTICE_TEXT,
            GroupLeaveMonitorSettings.DEFAULT_LEAVE_NOTICE_TEXT
        ) ?: GroupLeaveMonitorSettings.DEFAULT_LEAVE_NOTICE_TEXT
        return applySystemTemplate(
            template,
            values = mapOf(
                "%displayName%" to displayName,
                "%groupNickname%" to display.groupNick,
                "%userName%" to display.wechatNick,
                "%remarkName%" to display.remarkName,
                "%groupName%" to groupDisplayName(groupId),
                "%time%" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            ),
            links = mapOf("%userWxid%" to display.memberId)
        )
    }

    private fun applySystemTemplate(
        template: String,
        values: Map<String, String>,
        links: Map<String, String>
    ): String {
        val tokens = values.keys + links.keys
        if (tokens.isEmpty()) return template
        val tokenPattern = Regex(tokens.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) })
        return tokenPattern.replace(template) { match ->
            val token = match.value
            links[token]?.let(::wxidLink) ?: escapeXml(values[token].orEmpty())
        }
    }

    private fun wxidLink(memberId: String): String {
        val linkColor = MemberTitleStore.cleanColor(
            prefs.getString(
                GroupLeaveMonitorSettings.KEY_WXID_COLOR,
                GroupLeaveMonitorSettings.DEFAULT_WXID_COLOR
            )?.substringBefore(',')
        ).ifEmpty { GroupLeaveMonitorSettings.DEFAULT_WXID_COLOR }
        val escapedMemberId = escapeXml(memberId)
        return "<_wc_custom_link_ color=\"$linkColor\" href=\"${escapeXml(GroupLeaveMonitorLinks.profileUri(memberId))}\">$escapedMemberId</_wc_custom_link_>"
    }

    private fun isEnabled(): Boolean {
        return prefs.getBoolean(
            GroupLeaveMonitorSettings.KEY_ENABLE,
            GroupLeaveMonitorSettings.DEFAULT_ENABLE
        )
    }

    private fun isInviteDetailEnabled(): Boolean {
        return prefs.getBoolean(
            GroupLeaveMonitorSettings.KEY_INVITE_DETAIL_ENABLE,
            GroupLeaveMonitorSettings.DEFAULT_INVITE_DETAIL_ENABLE
        )
    }

    private fun isSystemNoticeGroupEnabled(groupId: String): Boolean {
        val scope = prefs.getString(
            GroupLeaveMonitorSettings.KEY_NOTICE_SCOPE,
            GroupLeaveMonitorSettings.DEFAULT_NOTICE_SCOPE
        ) ?: GroupLeaveMonitorSettings.DEFAULT_NOTICE_SCOPE
        return scope != GroupLeaveMonitorSettings.NOTICE_SCOPE_SPECIFIC ||
            splitList(prefs.getString(GroupLeaveMonitorSettings.KEY_NOTICE_GROUPS, "").orEmpty()).contains(groupId)
    }

    private fun handleMemberReply(type: String, groupId: String, memberId: String) {
        if (!prefs.getBoolean(GroupLeaveMonitorSettings.KEY_REPLY_ENABLE, GroupLeaveMonitorSettings.DEFAULT_REPLY_ENABLE)) {
            return
        }
        if (!listenGroups().contains(groupId)) return
        val template = replyTemplateFor(groupId)
        if (type == "join") {
            if (template != null) {
                if (!template.enabled || !template.joinEnabled) return
            } else {
                if (!prefs.getBoolean(
                        GroupLeaveMonitorSettings.KEY_JOIN_REPLY_ENABLE,
                        GroupLeaveMonitorSettings.DEFAULT_JOIN_REPLY_ENABLE
                    )
                ) return
                if (isGroupDisabled(GroupLeaveMonitorSettings.KEY_JOIN_DISABLED_GROUPS, groupId)) return
            }
        }
        if (type == "left") {
            if (template != null) {
                if (!template.enabled || !template.leftEnabled) return
            } else {
                if (!prefs.getBoolean(
                        GroupLeaveMonitorSettings.KEY_LEFT_REPLY_ENABLE,
                        GroupLeaveMonitorSettings.DEFAULT_LEFT_REPLY_ENABLE
                    )
                ) return
                if (isGroupDisabled(GroupLeaveMonitorSettings.KEY_LEFT_DISABLED_GROUPS, groupId)) return
            }
        }

        Thread({
            runCatching {
                val delaySeconds = prefs.getInt(
                    GroupLeaveMonitorSettings.KEY_DELAY_SECONDS,
                    GroupLeaveMonitorSettings.DEFAULT_DELAY_SECONDS
                ).coerceIn(0, 600)
                sleepQuietly(delaySeconds * 1000L)
                val display = resolveReplyMemberDisplay(type, groupId, memberId)
                val variables = ReplyVariables(
                    userWxid = memberId,
                    userName = display.replyUserName(),
                    groupNickname = display.primaryName(),
                    realNameTail = realNameTailStore.displayTail(memberId),
                    gender = memberGenderText(memberId),
                    region = memberRegionText(memberId),
                    groupName = groupDisplayName(groupId),
                    time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                )
                executeReplyChain(type, groupId, variables, template)
            }.onFailure {
                logger("进退群自动回复异常: $groupId/$memberId/$type", it)
            }
        }, "Hchat-GroupMemberReply").start()
    }

    private fun resolveReplyMemberDisplay(
        type: String,
        groupId: String,
        memberId: String
    ): MemberDisplay {
        var display = memberDisplay(groupId, memberId)
        if (type != "join" || display.hasResolvedName()) return display
        val deadline = System.currentTimeMillis() + JOIN_NAME_RESOLVE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted) {
            sleepQuietly(JOIN_NAME_RETRY_INTERVAL_MS)
            display = memberDisplay(groupId, memberId)
            if (display.hasResolvedName()) break
        }
        return display
    }

    private fun executeReplyChain(
        type: String,
        groupId: String,
        variables: ReplyVariables,
        template: GroupLeaveReplyTemplate?
    ) {
        val promptType = promptTypeFor(groupId, template)
        val mediaOrder = mediaOrderFor(groupId, template)
        val mediaTasks = mediaTasks(type, groupId, mediaOrder, template)
        val promptTasks = promptTasks(type, groupId, variables, promptType, template)
        val tasks = when (mediaOrder) {
            GroupLeaveMonitorSettings.MEDIA_BEFORE -> mediaTasks + promptTasks
            GroupLeaveMonitorSettings.MEDIA_AFTER -> promptTasks + mediaTasks
            else -> promptTasks
        }
        for (task in tasks) {
            sleepQuietly(task.delayMs)
            runCatching { task.action() }
                .onFailure { logger("进退群自动回复发送失败: $groupId/${task.type}", it) }
            sleepQuietly(300L)
        }
    }

    private fun promptTasks(
        type: String,
        groupId: String,
        variables: ReplyVariables,
        promptType: String,
        template: GroupLeaveReplyTemplate?
    ): List<ReplyTask> {
        val delay = delayMsFor(
            groupId,
            GroupLeaveMonitorSettings.KEY_PROMPT_DELAY_MS,
            GroupLeaveMonitorSettings.DEFAULT_PROMPT_DELAY_MS,
            template
        )
        return when (promptType) {
            GroupLeaveMonitorSettings.PROMPT_CARD -> listOf(ReplyTask("card", delay) {
                sendCard(type, groupId, variables, template)
            })
            GroupLeaveMonitorSettings.PROMPT_BOTH -> {
                val textTask = ReplyTask("text", delay) { sendText(type, groupId, variables, template) }
                val cardTask = ReplyTask("card", 120L) { sendCard(type, groupId, variables, template) }
                if (bothOrderFor(groupId, template) == GroupLeaveMonitorSettings.BOTH_CARD_FIRST) {
                    listOf(cardTask, textTask)
                } else {
                    listOf(textTask, cardTask)
                }
            }
            else -> listOf(ReplyTask("text", delay) { sendText(type, groupId, variables, template) })
        }
    }

    private fun mediaTasks(
        type: String,
        groupId: String,
        mediaOrder: String,
        template: GroupLeaveReplyTemplate?
    ): List<ReplyTask> {
        if (mediaOrder == GroupLeaveMonitorSettings.MEDIA_NONE) return emptyList()
        val sequence = mediaSequenceFor(groupId, template)
        val tasks = mapOf(
            "image" to pathsReplyTask(
                "image",
                mediaPaths(groupId, type, GroupLeaveMonitorSettings.KEY_JOIN_IMAGE_PATHS, GroupLeaveMonitorSettings.KEY_LEFT_IMAGE_PATHS, template),
                delayMsFor(groupId, GroupLeaveMonitorSettings.KEY_IMAGE_DELAY_MS, GroupLeaveMonitorSettings.DEFAULT_MEDIA_DELAY_MS, template)
            ) { path -> WeChatApis.media()?.sendImage(groupId, path) == true },
            "voice" to pathsReplyTask(
                "voice",
                mediaPaths(groupId, type, GroupLeaveMonitorSettings.KEY_JOIN_VOICE_PATHS, GroupLeaveMonitorSettings.KEY_LEFT_VOICE_PATHS, template),
                delayMsFor(groupId, GroupLeaveMonitorSettings.KEY_VOICE_DELAY_MS, GroupLeaveMonitorSettings.DEFAULT_MEDIA_DELAY_MS, template)
            ) { path ->
                Handler(Looper.getMainLooper()).post {
                    runCatching { WeChatApis.media()?.sendVoice(groupId, path) }
                }
                true
            },
            "emoji" to pathsReplyTask(
                "emoji",
                mediaPaths(groupId, type, GroupLeaveMonitorSettings.KEY_JOIN_EMOJI_PATHS, GroupLeaveMonitorSettings.KEY_LEFT_EMOJI_PATHS, template),
                delayMsFor(groupId, GroupLeaveMonitorSettings.KEY_EMOJI_DELAY_MS, GroupLeaveMonitorSettings.DEFAULT_MEDIA_DELAY_MS, template)
            ) { path -> WeChatApis.media()?.sendEmoji(groupId, path) == true },
            "video" to pathsReplyTask(
                "video",
                mediaPaths(groupId, type, GroupLeaveMonitorSettings.KEY_JOIN_VIDEO_PATHS, GroupLeaveMonitorSettings.KEY_LEFT_VIDEO_PATHS, template),
                delayMsFor(groupId, GroupLeaveMonitorSettings.KEY_VIDEO_DELAY_MS, GroupLeaveMonitorSettings.DEFAULT_MEDIA_DELAY_MS, template)
            ) { path -> WeChatApis.media()?.videos()?.send(groupId, path) == true },
            "file" to pathsReplyTask(
                "file",
                mediaPaths(groupId, type, GroupLeaveMonitorSettings.KEY_JOIN_FILE_PATHS, GroupLeaveMonitorSettings.KEY_LEFT_FILE_PATHS, template),
                delayMsFor(groupId, GroupLeaveMonitorSettings.KEY_FILE_DELAY_MS, GroupLeaveMonitorSettings.DEFAULT_MEDIA_DELAY_MS, template)
            ) { path -> WeChatApis.media()?.shareFile(groupId, java.io.File(path).name, path, "") == true },
            "favorite" to pathsReplyTask(
                "favorite",
                mediaPaths(groupId, type, GroupLeaveMonitorSettings.KEY_JOIN_FAVORITE_PATHS, GroupLeaveMonitorSettings.KEY_LEFT_FAVORITE_PATHS, template),
                delayMsFor(groupId, GroupLeaveMonitorSettings.KEY_FAVORITE_DELAY_MS, GroupLeaveMonitorSettings.DEFAULT_MEDIA_DELAY_MS, template)
            ) { localId -> WeChatApis.media()?.favorites()?.send(groupId, localId) == true }
        )
        return sequence.split(',')
            .map { it.trim().lowercase(Locale.US) }
            .mapNotNull { tasks[it] }
    }

    private fun pathsReplyTask(
        type: String,
        value: String,
        delay: Long,
        sender: (String) -> Boolean
    ): ReplyTask? {
        val paths = splitList(value)
        if (paths.isEmpty()) return null
        return ReplyTask(type, delay) {
            for (path in paths) {
                runCatching { sender(path) }
            }
        }
    }

    private fun sendText(
        type: String,
        groupId: String,
        variables: ReplyVariables,
        template: GroupLeaveReplyTemplate?
    ) {
        val key = if (type == "join") GroupLeaveMonitorSettings.KEY_JOIN_TEXT else GroupLeaveMonitorSettings.KEY_LEFT_TEXT
        val defaultValue = if (type == "join") GroupLeaveMonitorSettings.DEFAULT_JOIN_TEXT else GroupLeaveMonitorSettings.DEFAULT_LEFT_TEXT
        val textTemplate = pickTemplate(promptTemplate(groupId, key, defaultValue, template))
        if (textTemplate.isBlank()) return
        val text = applyVariables(textTemplate, variables)
        if (text.isBlank()) return
        val sender = WeChatApis.message().sender() ?: WeChatApis.messages()
        if (sender == null) {
            logger("进退群自动回复发送失败: 消息发送 API 未就绪", null)
            return
        }
        val parsed = parseAtContent(groupId, text)
        if (parsed == null) {
            sender.sendText(groupId, text)
            return
        }
        val sent = if (parsed.atList.isEmpty()) {
            sender.sendText(groupId, parsed.content)
        } else {
            sender.sendTextWithAtList(groupId, parsed.content, parsed.atList)
        }
        if (!sent && parsed.content.isNotBlank()) {
            sender.sendText(groupId, parsed.content)
        }
    }

    private fun sendCard(
        type: String,
        groupId: String,
        variables: ReplyVariables,
        template: GroupLeaveReplyTemplate?
    ) {
        val titleKey = if (type == "join") GroupLeaveMonitorSettings.KEY_JOIN_CARD_TITLE else GroupLeaveMonitorSettings.KEY_LEFT_CARD_TITLE
        val descKey = if (type == "join") GroupLeaveMonitorSettings.KEY_JOIN_CARD_DESC else GroupLeaveMonitorSettings.KEY_LEFT_CARD_DESC
        val titleDefault = if (type == "join") GroupLeaveMonitorSettings.DEFAULT_JOIN_CARD_TITLE else GroupLeaveMonitorSettings.DEFAULT_LEFT_CARD_TITLE
        val descDefault = if (type == "join") GroupLeaveMonitorSettings.DEFAULT_JOIN_CARD_DESC else GroupLeaveMonitorSettings.DEFAULT_LEFT_CARD_DESC
        val title = applyVariables(pickTemplate(cardTemplate(groupId, titleKey, titleDefault, template)), variables)
        val desc = applyVariables(pickTemplate(cardTemplate(groupId, descKey, descDefault, template)), variables)
        if (title.isBlank() && desc.isBlank()) return
        val avatarUrl = WeChatApis.contact().contacts()?.getAvatarUrl(variables.userWxid, true).orEmpty()
        val thumb = downloadBytes(avatarUrl)
        val ok = WeChatApis.media()?.shareWebpage(groupId, title, desc, avatarUrl, thumb, "") == true
        if (!ok && (title.isNotBlank() || desc.isNotBlank())) {
            WeChatApis.messages()?.sendText(groupId, listOf(title, desc).filter { it.isNotBlank() }.joinToString("\n"))
        }
    }

    private fun listenGroups(): Set<String> = splitList(
        prefs.getString(GroupLeaveMonitorSettings.KEY_LISTEN_GROUPS, "").orEmpty()
    ).toSet()

    private fun groupDisplayName(groupId: String): String {
        val contact = WeChatApis.contact().contacts()?.getContact(groupId)
        return contact?.displayName()?.takeIf { it.isNotBlank() && it != groupId }
            ?: WeChatApis.contact().chatrooms()?.getChatroomName(groupId)?.takeIf { it.isNotBlank() }
            ?: groupId
    }

    private fun mediaPaths(
        groupId: String,
        type: String,
        joinKey: String,
        leftKey: String,
        template: GroupLeaveReplyTemplate?
    ): String {
        val key = if (type == "join") joinKey else leftKey
        if (usesCustomMedia(groupId, template)) {
            val templateValue = template?.mediaValue(key)
            if (templateValue != null) return templateValue
            return prefs.getString(groupKey(key, groupId), "").orEmpty()
        }
        return prefs.getString(key, "").orEmpty()
    }

    private fun promptTypeFor(groupId: String, template: GroupLeaveReplyTemplate?): String {
        val templateValue = template?.promptType.orEmpty()
        if (template != null) {
            return if (templateValue.isNotBlank() && templateValue != GroupLeaveMonitorSettings.MODE_GLOBAL) {
                normalizePromptType(templateValue)
            } else {
                normalizePromptType(
                    prefs.getString(
                        GroupLeaveMonitorSettings.KEY_PROMPT_TYPE,
                        GroupLeaveMonitorSettings.DEFAULT_PROMPT_TYPE
                    ) ?: GroupLeaveMonitorSettings.DEFAULT_PROMPT_TYPE
                )
            }
        }
        val groupValue = prefs.getString(groupKey(GroupLeaveMonitorSettings.KEY_PROMPT_TYPE, groupId), GroupLeaveMonitorSettings.MODE_GLOBAL)
            ?: GroupLeaveMonitorSettings.MODE_GLOBAL
        if (groupValue != GroupLeaveMonitorSettings.MODE_GLOBAL) {
            return normalizePromptType(groupValue)
        }
        return normalizePromptType(
            prefs.getString(
                GroupLeaveMonitorSettings.KEY_PROMPT_TYPE,
                GroupLeaveMonitorSettings.DEFAULT_PROMPT_TYPE
            ) ?: GroupLeaveMonitorSettings.DEFAULT_PROMPT_TYPE
        )
    }

    private fun bothOrderFor(groupId: String, template: GroupLeaveReplyTemplate?): String {
        if (template != null) {
            return GroupLeaveMonitorSettings.normalizeBothOrder(
                if (template.promptType == GroupLeaveMonitorSettings.PROMPT_BOTH) {
                    template.bothOrder
                } else {
                    prefs.getString(
                        GroupLeaveMonitorSettings.KEY_BOTH_ORDER,
                        GroupLeaveMonitorSettings.DEFAULT_BOTH_ORDER
                    ) ?: GroupLeaveMonitorSettings.DEFAULT_BOTH_ORDER
                }
            )
        }
        val groupValue = prefs.getString(groupKey(GroupLeaveMonitorSettings.KEY_BOTH_ORDER, groupId), "").orEmpty()
        val value = groupValue.ifBlank {
            prefs.getString(
                GroupLeaveMonitorSettings.KEY_BOTH_ORDER,
                GroupLeaveMonitorSettings.DEFAULT_BOTH_ORDER
            ) ?: GroupLeaveMonitorSettings.DEFAULT_BOTH_ORDER
        }
        return if (value == GroupLeaveMonitorSettings.BOTH_CARD_FIRST) {
            GroupLeaveMonitorSettings.BOTH_CARD_FIRST
        } else {
            GroupLeaveMonitorSettings.BOTH_TEXT_FIRST
        }
    }

    private fun promptTemplate(
        groupId: String,
        key: String,
        globalDefault: String,
        template: GroupLeaveReplyTemplate?
    ): String {
        if (template != null) {
            val templateValue = template.promptValue(key)
            return if (template.promptType != GroupLeaveMonitorSettings.MODE_GLOBAL && templateValue != null) {
                templateValue
            } else {
                prefs.getString(key, globalDefault) ?: globalDefault
            }
        }
        return if (usesCustomPrompt(groupId, null)) {
            prefs.getString(groupKey(key, groupId), "").orEmpty()
        } else {
            prefs.getString(key, globalDefault) ?: globalDefault
        }
    }

    private fun cardTemplate(
        groupId: String,
        key: String,
        globalDefault: String,
        template: GroupLeaveReplyTemplate?
    ): String {
        val global = prefs.getString(key, globalDefault) ?: globalDefault
        if (template != null) {
            val templateValue = template.promptValue(key)
            return if (template.promptType != GroupLeaveMonitorSettings.MODE_GLOBAL && templateValue != null) {
                templateValue.ifBlank { global }
            } else {
                global
            }
        }
        return if (usesCustomPrompt(groupId, null)) {
            prefs.getString(groupKey(key, groupId), "").orEmpty().ifBlank { global }
        } else {
            global
        }
    }

    private fun mediaOrderFor(groupId: String, template: GroupLeaveReplyTemplate?): String {
        val global = normalizeMediaOrder(
            prefs.getString(
                GroupLeaveMonitorSettings.KEY_MEDIA_ORDER,
                GroupLeaveMonitorSettings.DEFAULT_MEDIA_ORDER
            ) ?: GroupLeaveMonitorSettings.DEFAULT_MEDIA_ORDER
        )
        if (template != null) {
            return when (template.mediaMode) {
                GroupLeaveMonitorSettings.MEDIA_NONE -> GroupLeaveMonitorSettings.MEDIA_NONE
                GroupLeaveMonitorSettings.MODE_CUSTOM -> normalizeMediaOrder(template.mediaOrder)
                else -> global
            }
        }
        val groupMode = mediaModeFor(groupId, null)
        return if (groupMode == GroupLeaveMonitorSettings.MEDIA_NONE) {
            GroupLeaveMonitorSettings.MEDIA_NONE
        } else if (groupMode == GroupLeaveMonitorSettings.MODE_CUSTOM) {
            normalizeMediaOrder(prefs.getString(groupKey(GroupLeaveMonitorSettings.KEY_MEDIA_ORDER, groupId), global) ?: global)
        } else {
            global
        }
    }

    private fun mediaSequenceFor(groupId: String, template: GroupLeaveReplyTemplate?): String {
        val global = prefs.getString(
            GroupLeaveMonitorSettings.KEY_MEDIA_SEQUENCE,
            GroupLeaveMonitorSettings.DEFAULT_MEDIA_SEQUENCE
        ) ?: GroupLeaveMonitorSettings.DEFAULT_MEDIA_SEQUENCE
        return if (template != null) {
            if (template.mediaMode == GroupLeaveMonitorSettings.MODE_CUSTOM) {
                template.mediaSequence.ifBlank { global }
            } else {
                global
            }
        } else if (usesCustomMedia(groupId, null)) {
            prefs.getString(groupKey(GroupLeaveMonitorSettings.KEY_MEDIA_SEQUENCE, groupId), global) ?: global
        } else {
            global
        }
    }

    private fun delayMsFor(
        groupId: String,
        key: String,
        defaultValue: Int,
        template: GroupLeaveReplyTemplate?
    ): Long {
        val global = prefs.getInt(key, defaultValue).coerceAtLeast(0)
        val value = if (template != null) {
            if (template.delayMode == GroupLeaveMonitorSettings.MODE_CUSTOM) {
                template.delayValue(key)
            } else {
                global
            }
        } else if (usesCustomDelay(groupId, null)) {
            prefs.getInt(groupKey(key, groupId), global)
        } else {
            global
        }
        return value.coerceAtLeast(0).toLong()
    }

    private fun usesCustomPrompt(groupId: String, template: GroupLeaveReplyTemplate?): Boolean {
        if (template != null) return template.promptType != GroupLeaveMonitorSettings.MODE_GLOBAL
        val value = prefs.getString(groupKey(GroupLeaveMonitorSettings.KEY_PROMPT_TYPE, groupId), GroupLeaveMonitorSettings.MODE_GLOBAL)
            ?: GroupLeaveMonitorSettings.MODE_GLOBAL
        return value != GroupLeaveMonitorSettings.MODE_GLOBAL
    }

    private fun usesCustomMedia(groupId: String, template: GroupLeaveReplyTemplate?): Boolean {
        return mediaModeFor(groupId, template) == GroupLeaveMonitorSettings.MODE_CUSTOM
    }

    private fun usesCustomDelay(groupId: String, template: GroupLeaveReplyTemplate?): Boolean {
        if (template != null) return template.delayMode == GroupLeaveMonitorSettings.MODE_CUSTOM
        return prefs.getString(delayModeKey(groupId), GroupLeaveMonitorSettings.MODE_GLOBAL) == GroupLeaveMonitorSettings.MODE_CUSTOM
    }

    private fun replyTemplateFor(groupId: String): GroupLeaveReplyTemplate? {
        val binding = GroupLeaveMonitorSettings
            .parseBindings(prefs.getString(GroupLeaveMonitorSettings.KEY_REPLY_TEMPLATE_BINDINGS, "").orEmpty())
            .firstOrNull { it.groupId == groupId }
            ?: return null
        return GroupLeaveMonitorSettings
            .parseTemplates(prefs.getString(GroupLeaveMonitorSettings.KEY_REPLY_TEMPLATES, "").orEmpty())
            .firstOrNull { it.id == binding.templateId }
    }

    private fun GroupLeaveReplyTemplate.promptValue(key: String): String? {
        return when (key) {
            GroupLeaveMonitorSettings.KEY_JOIN_TEXT -> joinText
            GroupLeaveMonitorSettings.KEY_LEFT_TEXT -> leftText
            GroupLeaveMonitorSettings.KEY_JOIN_CARD_TITLE -> joinCardTitle
            GroupLeaveMonitorSettings.KEY_JOIN_CARD_DESC -> joinCardDesc
            GroupLeaveMonitorSettings.KEY_LEFT_CARD_TITLE -> leftCardTitle
            GroupLeaveMonitorSettings.KEY_LEFT_CARD_DESC -> leftCardDesc
            else -> null
        }
    }

    private fun GroupLeaveReplyTemplate.mediaValue(key: String): String? {
        return when (key) {
            GroupLeaveMonitorSettings.KEY_JOIN_IMAGE_PATHS -> joinImages
            GroupLeaveMonitorSettings.KEY_LEFT_IMAGE_PATHS -> leftImages
            GroupLeaveMonitorSettings.KEY_JOIN_VOICE_PATHS -> joinVoices
            GroupLeaveMonitorSettings.KEY_LEFT_VOICE_PATHS -> leftVoices
            GroupLeaveMonitorSettings.KEY_JOIN_EMOJI_PATHS -> joinEmojis
            GroupLeaveMonitorSettings.KEY_LEFT_EMOJI_PATHS -> leftEmojis
            GroupLeaveMonitorSettings.KEY_JOIN_VIDEO_PATHS -> joinVideos
            GroupLeaveMonitorSettings.KEY_LEFT_VIDEO_PATHS -> leftVideos
            GroupLeaveMonitorSettings.KEY_JOIN_FILE_PATHS -> joinFiles
            GroupLeaveMonitorSettings.KEY_LEFT_FILE_PATHS -> leftFiles
            GroupLeaveMonitorSettings.KEY_JOIN_FAVORITE_PATHS -> joinFavorites
            GroupLeaveMonitorSettings.KEY_LEFT_FAVORITE_PATHS -> leftFavorites
            else -> null
        }
    }

    private fun GroupLeaveReplyTemplate.delayValue(key: String): Int {
        return when (key) {
            GroupLeaveMonitorSettings.KEY_PROMPT_DELAY_MS -> promptDelayMs
            GroupLeaveMonitorSettings.KEY_IMAGE_DELAY_MS -> imageDelayMs
            GroupLeaveMonitorSettings.KEY_VOICE_DELAY_MS -> voiceDelayMs
            GroupLeaveMonitorSettings.KEY_EMOJI_DELAY_MS -> emojiDelayMs
            GroupLeaveMonitorSettings.KEY_VIDEO_DELAY_MS -> videoDelayMs
            GroupLeaveMonitorSettings.KEY_FILE_DELAY_MS -> fileDelayMs
            GroupLeaveMonitorSettings.KEY_FAVORITE_DELAY_MS -> favoriteDelayMs
            else -> GroupLeaveMonitorSettings.DEFAULT_MEDIA_DELAY_MS
        }
    }

    private fun isGroupDisabled(key: String, groupId: String): Boolean {
        return splitList(prefs.getString(key, "").orEmpty()).contains(groupId)
    }

    private fun normalizePromptType(value: String): String {
        return when (value) {
            GroupLeaveMonitorSettings.PROMPT_CARD,
            GroupLeaveMonitorSettings.PROMPT_BOTH,
            GroupLeaveMonitorSettings.PROMPT_TEXT -> value
            else -> GroupLeaveMonitorSettings.DEFAULT_PROMPT_TYPE
        }
    }

    private fun normalizeMediaOrder(value: String): String {
        return when (value) {
            GroupLeaveMonitorSettings.MEDIA_BEFORE,
            GroupLeaveMonitorSettings.MEDIA_AFTER,
            GroupLeaveMonitorSettings.MEDIA_NONE -> value
            else -> GroupLeaveMonitorSettings.DEFAULT_MEDIA_ORDER
        }
    }

    private fun mediaModeKey(groupId: String): String = GroupLeaveMonitorSettings.KEY_MEDIA_MODE_PREFIX + groupId

    private fun mediaModeFor(groupId: String, template: GroupLeaveReplyTemplate?): String {
        if (template != null) return GroupLeaveMonitorSettings.normalizeMediaMode(template.mediaMode)
        return mediaModeFor(groupId)
    }

    private fun mediaModeFor(groupId: String): String {
        return when (prefs.getString(mediaModeKey(groupId), GroupLeaveMonitorSettings.MODE_GLOBAL)) {
            GroupLeaveMonitorSettings.MODE_CUSTOM -> GroupLeaveMonitorSettings.MODE_CUSTOM
            GroupLeaveMonitorSettings.MEDIA_NONE -> GroupLeaveMonitorSettings.MEDIA_NONE
            else -> GroupLeaveMonitorSettings.MODE_GLOBAL
        }
    }

    private fun delayModeKey(groupId: String): String = GroupLeaveMonitorSettings.KEY_DELAY_MODE_PREFIX + groupId

    private fun groupKey(key: String, groupId: String): String = "${key}_${groupId}"

    private fun pickTemplate(value: String): String {
        val parts = value.split("||").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        return parts[Random.nextInt(parts.size)]
    }

    private fun applyVariables(template: String, variables: ReplyVariables): String {
        return template
            .replace("%userName%", variables.userName)
            .replace("%groupNickname%", variables.groupNickname)
            .replace("%userWxid%", variables.userWxid)
            .replace("%realNameTail%", variables.realNameTail)
            .replace("%gender%", variables.gender)
            .replace("%region%", variables.region)
            .replace("%groupName%", variables.groupName)
            .replace("%time%", variables.time)
    }

    private fun memberGenderText(memberId: String): String {
        val gender = WeChatApis.contact().contacts()?.getGender(memberId) ?: 0
        return realNameTailStore.genderText(gender)
    }

    private fun memberRegionText(memberId: String): String {
        return WeChatApis.contact().contacts()?.getRegion(memberId)
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()
    }

    private fun parseAtContent(groupId: String, content: String): ParsedAtContent? {
        val atList = ArrayList<String>()
        val parsed = atPattern.replace(content) { match ->
            val wxId = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (wxId.isBlank()) {
                ""
            } else {
                atList.add(wxId)
                "@${mentionDisplayName(groupId, wxId)}\u2005"
            }
        }
        if (atList.isEmpty()) return null
        return ParsedAtContent(parsed, atList.distinct())
    }

    private fun mentionDisplayName(groupId: String, wxId: String): String {
        if (wxId == "notify@all") return "所有人"
        val contacts = WeChatApis.contact().contacts()
        val contact = contacts?.getContact(wxId)
        val display = memberDisplay(groupId, wxId)
        return firstNotBlank(
            display.groupNick,
            display.wechatNick,
            display.remarkName,
            contact?.customWxId,
            wxId
        ).replace('\n', ' ').replace('\r', ' ').trim().ifBlank { wxId }
    }

    private fun firstNotBlank(vararg values: String?): String {
        for (value in values) {
            if (!value.isNullOrBlank()) return value
        }
        return ""
    }

    private fun splitList(value: String): List<String> {
        return value.split('|', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun looksLikeInviteNotice(raw: String): Boolean {
        if (raw.isBlank()) return false
        val text = decodeXml(raw)
        val invited = INVITE_KEYWORDS.any { text.contains(it, ignoreCase = true) }
        val joined = INVITE_JOIN_KEYWORDS.any { text.contains(it, ignoreCase = true) }
        val left = INVITE_LEFT_KEYWORDS.any { text.contains(it, ignoreCase = true) }
        return invited && joined && !left
    }

    private fun looksLikeJoinNotice(raw: String): Boolean {
        if (raw.isBlank()) return false
        val text = decodeXml(raw)
        val joined = JOIN_NOTICE_KEYWORDS.any { text.contains(it, ignoreCase = true) }
        val left = INVITE_LEFT_KEYWORDS.any { text.contains(it, ignoreCase = true) }
        return joined && !left
    }

    private fun stripXmlTags(value: String): String {
        return decodeXml(value).replace(Regex("<[^>]+>"), "")
    }

    private fun isValidInviteMemberId(groupId: String, wxid: String?): Boolean {
        val id = wxid?.trim().orEmpty()
        return id.isNotEmpty() &&
            id != groupId &&
            id != "notify@all" &&
            !id.endsWith("@chatroom") &&
            !id.endsWith("@im.chatroom")
    }

    private fun addInviteCount(groupId: String, inviterId: String, delta: Int): Int {
        return synchronized(inviteCountLock) {
            val key = inviteCountKey(groupId, inviterId)
            val next = currentInviteCount(groupId, inviterId) + delta.coerceAtLeast(1)
            prefs.edit().putInt(key, next).commit()
            next
        }
    }

    private fun currentInviteCount(groupId: String, inviterId: String): Int {
        return prefs.getInt(inviteCountKey(groupId, inviterId), 0).coerceAtLeast(0)
    }

    private fun inviteCountKey(groupId: String, inviterId: String): String {
        return GroupLeaveMonitorSettings.KEY_INVITE_COUNT_PREFIX + groupId + "|" + inviterId
    }

    private fun sleepQuietly(ms: Long) {
        if (ms <= 0L) return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun downloadBytes(url: String): ByteArray? {
        if (url.isBlank()) return null
        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 1200
            connection.readTimeout = 1200
            connection.requestMethod = "GET"
            connection.inputStream.use { it.readBytes() }.also {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private fun cleanupRecentInserts(now: Long) {
        if (recentInserts.size < 128) return
        recentInserts.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
    }

    private fun cleanupPendingInviteDetails(now: Long) {
        if (pendingInviteDetails.isNotEmpty()) {
            pendingInviteDetails.entries.removeIf { now - it.value.updatedAt > PENDING_INVITE_WINDOW_MS }
        }
    }

    private fun cacheKey(groupId: String, memberId: String): String = "$groupId|$memberId"

    private fun pendingInviteKey(groupId: String, inviteeId: String): String = "$groupId|$inviteeId"

    private fun splitDisplayNames(displayNames: String, expectedCount: Int): List<String> {
        if (displayNames.isBlank()) return emptyList()
        val delimiters = listOf("\u0001", "\u0002", "\n", ";")
        for (delimiter in delimiters) {
            val parts = displayNames.split(delimiter)
            if (expectedCount <= 0 || parts.size == expectedCount) return parts
        }
        return listOf(displayNames)
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun decodeXml(value: String): String {
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private data class MemberDisplay(
        val groupNick: String,
        val wechatNick: String,
        val remarkName: String,
        val memberId: String,
        val observedName: String
    ) {
        fun primaryName(): String {
            val cleanGroupNick = groupNick.takeIf {
                it.isNotBlank() && it != memberId && it != remarkName
            }.orEmpty()
            return cleanGroupNick.ifBlank {
                wechatNick.ifBlank { remarkName.ifBlank { memberId } }
            }
        }

        fun inviteDetailName(): String {
            val cleanRemarkName = remarkName.takeIf {
                it.isNotBlank() && it != memberId
            }.orEmpty()
            val cleanGroupNick = groupNick.takeIf {
                it.isNotBlank() && it != memberId
            }.orEmpty()
            return cleanRemarkName.ifBlank {
                cleanGroupNick.ifBlank {
                    wechatNick.ifBlank { memberId }
                }
            }
        }

        fun replyUserName(): String {
            return wechatNick.ifBlank {
                remarkName.ifBlank {
                    observedName.ifBlank { groupNick.ifBlank { memberId } }
                }
            }
        }

        fun hasResolvedName(): Boolean {
            return groupNick.isNotBlank() || wechatNick.isNotBlank() ||
                remarkName.isNotBlank() || observedName.isNotBlank()
        }

        fun shouldShowWechatNick(primaryName: String): Boolean {
            return groupNick.isNotBlank() &&
                wechatNick.isNotBlank() &&
                primaryName != wechatNick
        }

        fun shouldShowRemark(primaryName: String, fallbackWechatNick: String): Boolean {
            return remarkName.isNotBlank() &&
                remarkName != primaryName &&
                remarkName != fallbackWechatNick
        }
    }

    private companion object {
        const val DEDUP_WINDOW_MS = 5000L
        const val NEW_GROUP_SNAPSHOT_WARMUP_MS = 15 * 1000L
        const val BULK_MEMBER_SYNC_THRESHOLD = 10
        const val PENDING_INVITE_WINDOW_MS = 120000L
        const val OBSERVED_MEMBER_NAME_TTL_MS = 120000L
        const val JOIN_NAME_RESOLVE_TIMEOUT_MS = 2000L
        const val JOIN_NAME_RETRY_INTERVAL_MS = 100L
        val INVITE_KEYWORDS = listOf("邀请", "invited")
        val INVITE_JOIN_KEYWORDS = listOf("加入了群聊", "joined the group chat", "join the group chat")
        val INVITE_LEFT_KEYWORDS = listOf("移出了群聊", "退出了群聊", "left the group chat", "removed from the group chat")
        val JOIN_NOTICE_KEYWORDS = INVITE_JOIN_KEYWORDS + listOf(
            "加入群聊",
            "二维码",
            "通过扫描",
            "scan the qr code",
            "invited"
        )
        val INVITE_MEMBER_TAG = Regex("""<(?:link|member)\b[^>]*>""", RegexOption.IGNORE_CASE)
        val INVITE_TAG_ATTR = Regex("([A-Za-z0-9_:-]+)\\s*=\\s*\"([^\"]*)\"")
    }

    private data class InviteMember(
        val wxid: String,
        val name: String
    )

    private data class InviteDetail(
        val inviter: InviteMember,
        val invitees: List<InviteMember>
    )

    private data class ObservedMemberName(
        val name: String,
        val expiresAt: Long
    )

    private data class PendingInviteDetail(
        val count: Int,
        val updatedAt: Long
    )

    private data class ReplyVariables(
        val userWxid: String,
        val userName: String,
        val groupNickname: String,
        val realNameTail: String,
        val gender: String,
        val region: String,
        val groupName: String,
        val time: String
    )

    private data class ReplyTask(
        val type: String,
        val delayMs: Long,
        val action: () -> Unit
    )

    private data class ParsedAtContent(
        val content: String,
        val atList: List<String>
    )
}
