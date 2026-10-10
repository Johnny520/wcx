package h.Hchat.hooks.items.script

import h.Hchat.hooks.api.contact.WeChatChatroomChangeApi
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import java.util.concurrent.ConcurrentHashMap

object ScriptMemberChangeHook {
    private const val DEDUP_WINDOW_MS = 5000L
    private const val MAX_MEMBER_SNAPSHOT_GROUPS = 2048
    private const val MAX_MEMBER_NAME_ENTRIES = 32768
    private const val MAX_RECENT_EVENTS = 2048
    @Volatile
    private var installed = false
    private val memberSnapshots = ConcurrentHashMap<String, Set<String>>()
    private val memberNameCache = ConcurrentHashMap<String, String>()
    private val recentEvents = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun install(context: FeatureContext): Boolean {
        if (installed) return true
        val changeApi = WeChatApis.chatroomChanges()
        if (changeApi == null || !changeApi.isAvailable()) {
            return false
        }
        runCatching { preloadSnapshots() }
        changeApi.subscribe { change ->
            dispatchDiff(change)
        }
        val observeApi = runCatching { WeChatApis.messageObserve() }.getOrNull()
        if (observeApi != null && observeApi.isAvailable()) {
            runCatching { observeApi.install() }
            observeApi.subscribe { message ->
                handleObservedMessage(ScriptMessageBean(message))
            }
        }
        installed = true
        return true
    }

    private fun dispatchDiff(change: WeChatChatroomChangeApi.ChatroomChange) {
        if (!change.mayMemberListChanged()) return
        val groupWxid = change.chatroomId().trim()
        if (groupWxid.isEmpty()) return
        rememberDisplayNames(groupWxid, change)
        val current = currentMembers(change)
        if (current.isEmpty()) return
        rememberCurrentGroupNickNames(groupWxid, current)
        val previous = memberSnapshots.put(groupWxid, current)
        trimCaches()
        if (previous == null) return
        val joined = current - previous
        val left = previous - current
        if (joined.isEmpty() && left.isEmpty()) return
        for (wxid in joined) {
            dispatchMemberChange(TYPE_JOIN, groupWxid, wxid, memberName(groupWxid, wxid))
        }
        for (wxid in left) {
            dispatchMemberChange(TYPE_LEFT, groupWxid, wxid, memberName(groupWxid, wxid))
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

    private fun memberName(groupWxid: String, userWxid: String): String {
        return cachedGroupNickName(groupWxid, userWxid)
            ?: WeChatApis.contact().chatrooms()?.getMemberDisplayName(groupWxid, userWxid)
            ?.takeIf { it.isNotBlank() }
            ?: WeChatApis.contact().contacts()?.getDisplayName(userWxid)
            ?.takeIf { it.isNotBlank() }
            ?: userWxid
    }

    fun cachedGroupNickName(groupWxid: String?, userWxid: String?): String? {
        val group = groupWxid?.trim().orEmpty()
        val user = userWxid?.trim().orEmpty()
        if (group.isEmpty() || user.isEmpty()) return null
        return memberNameCache[cacheKey(group, user)]?.takeIf { it.isNotBlank() && it != user }
    }

    private fun preloadSnapshots() {
        val chatrooms = WeChatApis.contact().chatrooms() ?: return
        for (room in chatrooms.getChatrooms()) {
            val groupWxid = room.chatroomId.trim()
            if (groupWxid.isEmpty()) continue
            val members = room.memberIds
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toCollection(LinkedHashSet())
            if (members.isEmpty()) continue
            memberSnapshots.putIfAbsent(groupWxid, members)
            rememberCurrentGroupNickNames(groupWxid, members)
            trimCaches()
        }
    }

    private fun rememberCurrentGroupNickNames(groupWxid: String, members: Set<String>) {
        val names = WeChatApis.contact().contacts()?.getGroupMemberRoomDisplayNames(groupWxid).orEmpty()
        for (memberWxid in members) {
            val name = names[memberWxid]?.trim().orEmpty()
            memberNameCache.remove(cacheKey(groupWxid, memberWxid))
            if (name.isNotEmpty() && name != memberWxid) {
                memberNameCache[cacheKey(groupWxid, memberWxid)] = name
            }
        }
        trimCaches()
    }

    private fun rememberDisplayNames(groupWxid: String, change: WeChatChatroomChangeApi.ChatroomChange) {
        val chatroom = change.chatroom ?: return
        val members = chatroom.memberIds
        val names = splitDisplayNames(chatroom.rawDisplayNames, members.size)
        if (members.isEmpty() || names.size != members.size) return
        members.forEachIndexed { index, wxid ->
            val name = names.getOrNull(index)?.trim().orEmpty()
            memberNameCache.remove(cacheKey(groupWxid, wxid))
            if (wxid.isNotBlank() && name.isNotBlank() && name != wxid) {
                memberNameCache[cacheKey(groupWxid, wxid)] = name
            }
        }
        trimCaches()
    }

    private fun handleObservedMessage(message: ScriptMessageBean) {
        if (!message.isSystem() || !message.isGroupChat()) return
        val groupWxid = message.getTalker().trim()
        if (groupWxid.isEmpty()) return
        val notice = parseJoinNotice(groupWxid, message) ?: return
        for ((wxid, name) in notice.members) {
            if (wxid.isBlank()) continue
            if (name.isNotBlank() && name != wxid) {
                memberNameCache[cacheKey(groupWxid, wxid)] = name
            }
            trimCaches()
            dispatchMemberChange(TYPE_JOIN, groupWxid, wxid, memberName(groupWxid, wxid))
        }
    }

    private fun parseJoinNotice(groupWxid: String, message: ScriptMessageBean): MemberNotice? {
        val xml = message.getXml()
        val content = message.getContent()
        val raw = buildString {
            if (content.isNotBlank()) append(content)
            if (xml.isNotBlank()) {
                append('\n')
                append(xml)
            }
        }
        if (!looksLikeJoinNotice(raw)) return null
        if (looksLikeLeftNotice(raw)) return null
        val linkedMembers = LinkedHashMap<String, String>()
        collectLinkedMembers(xml, linkedMembers)
        collectLinkedMembers(content, linkedMembers)
        val members = linkedMembers.entries
            .asSequence()
            .map { it.key.trim() to it.value.trim() }
            .filter { (wxid, _) -> wxid.isNotEmpty() && wxid != groupWxid && !wxid.endsWith("@chatroom") }
            .toList()
        if (members.isEmpty()) return null
        return MemberNotice(TYPE_JOIN, groupWxid, members)
    }

    private fun collectLinkedMembers(source: String, out: LinkedHashMap<String, String>) {
        if (source.isBlank()) return
        LINK_NAME_THEN_USERNAME.findAll(source).forEach { match ->
            val name = decodeXml(match.groupValues[1]).trim()
            val wxid = decodeXml(match.groupValues[2]).trim()
            if (wxid.isNotEmpty()) out[wxid] = name
        }
        LINK_USERNAME_THEN_NAME.findAll(source).forEach { match ->
            val wxid = decodeXml(match.groupValues[1]).trim()
            val name = decodeXml(match.groupValues[2]).trim()
            if (wxid.isNotEmpty()) out[wxid] = name
        }
        MEMBER_NICKNAME_THEN_USERNAME.findAll(source).forEach { match ->
            val name = decodeXml(match.groupValues[1]).trim()
            val wxid = decodeXml(match.groupValues[2]).trim()
            if (wxid.isNotEmpty()) out[wxid] = name
        }
        MEMBER_USERNAME_THEN_NICKNAME.findAll(source).forEach { match ->
            val wxid = decodeXml(match.groupValues[1]).trim()
            val name = decodeXml(match.groupValues[2]).trim()
            if (wxid.isNotEmpty()) out[wxid] = name
        }
        USERNAME_THEN_NAME.findAll(source).forEach { match ->
            val wxid = decodeXml(match.groupValues[1]).trim()
            val name = decodeXml(match.groupValues[2]).trim()
            if (wxid.isNotEmpty() && name.isNotEmpty() && !out.containsKey(wxid)) {
                out[wxid] = name
            }
        }
        NAME_THEN_USERNAME.findAll(source).forEach { match ->
            val name = decodeXml(match.groupValues[1]).trim()
            val wxid = decodeXml(match.groupValues[2]).trim()
            if (wxid.isNotEmpty() && name.isNotEmpty() && !out.containsKey(wxid)) {
                out[wxid] = name
            }
        }
    }

    private fun looksLikeJoinNotice(raw: String): Boolean {
        if (raw.isBlank()) return false
        return JOIN_KEYWORDS.any { raw.contains(it, ignoreCase = true) }
    }

    private fun looksLikeLeftNotice(raw: String): Boolean {
        if (raw.isBlank()) return false
        return LEFT_KEYWORDS.any { raw.contains(it, ignoreCase = true) }
    }

    private fun decodeXml(value: String): String {
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private fun dispatchMemberChange(type: String, groupWxid: String, userWxid: String, userName: String) {
        val wxid = userWxid.trim()
        if (wxid.isEmpty()) return
        val now = System.currentTimeMillis()
        cleanupRecentEvents(now)
        val key = "$type|$groupWxid|$wxid"
        val previous = recentEvents[key]
        if (previous != null && now - previous < DEDUP_WINDOW_MS) return
        recentEvents[key] = now
        trimCaches()
        ScriptPluginRuntime.dispatchOnMemberChange(type, groupWxid, wxid, userName)
    }

    private fun cleanupRecentEvents(now: Long) {
        if (recentEvents.size >= 128) {
            recentEvents.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
        }
        trimCaches()
    }

    private fun trimCaches() {
        while (memberSnapshots.size > MAX_MEMBER_SNAPSHOT_GROUPS) {
            val key = memberSnapshots.keys.firstOrNull() ?: break
            memberSnapshots.remove(key)
        }
        while (memberNameCache.size > MAX_MEMBER_NAME_ENTRIES) {
            val key = memberNameCache.keys.firstOrNull() ?: break
            memberNameCache.remove(key)
        }
        while (recentEvents.size > MAX_RECENT_EVENTS) {
            val oldest = recentEvents.entries.minByOrNull { it.value } ?: break
            recentEvents.remove(oldest.key, oldest.value)
        }
    }

    private fun cacheKey(groupWxid: String, userWxid: String): String = "$groupWxid|$userWxid"

    private fun splitDisplayNames(displayNames: String, expectedCount: Int): List<String> {
        if (displayNames.isBlank()) return emptyList()
        val delimiters = listOf("\u0001", "\u0002", "\n", ";")
        for (delimiter in delimiters) {
            val parts = displayNames.split(delimiter)
            if (expectedCount <= 0 || parts.size == expectedCount) return parts
        }
        return listOf(displayNames)
    }

    private data class MemberNotice(
        val type: String,
        val groupWxid: String,
        val members: List<Pair<String, String>>
    )

    private const val TYPE_JOIN = "join"
    private const val TYPE_LEFT = "left"
    private val JOIN_KEYWORDS = listOf(
        "加入了群聊",
        "joined the group chat",
        "invited",
        "邀请",
        "二维码",
        "scan the qr code",
        "通过扫描"
    )
    private val LEFT_KEYWORDS = listOf(
        "移出了群聊",
        "removed from the group chat",
        "退出了群聊",
        "left the group chat"
    )
    private val LINK_NAME_THEN_USERNAME =
        Regex("""<link\b[^>]*\bname="([^"]+)"[^>]*\busername="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val LINK_USERNAME_THEN_NAME =
        Regex("""<link\b[^>]*\busername="([^"]+)"[^>]*\bname="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val MEMBER_NICKNAME_THEN_USERNAME =
        Regex("""<member\b[^>]*\bnickname="([^"]+)"[^>]*\busername="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val MEMBER_USERNAME_THEN_NICKNAME =
        Regex("""<member\b[^>]*\busername="([^"]+)"[^>]*\bnickname="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val USERNAME_THEN_NAME =
        Regex("""\busername="([^"]+)"[^>]{0,160}?\bname="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val NAME_THEN_USERNAME =
        Regex("""\bname="([^"]+)"[^>]{0,160}?\busername="([^"]+)"""", RegexOption.IGNORE_CASE)
}
