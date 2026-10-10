package h.Hchat.hooks.items.script

import android.content.ContentValues
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.model.DatabaseChange
import h.Hchat.hooks.api.model.WeChatMessageTypes
import h.Hchat.hooks.api.message.WeChatMessageParseApi
import h.Hchat.hooks.core.FeatureContext
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object ScriptNewFriendHook {
    private const val DEDUP_WINDOW_MS = 3000L
    private const val VERIFY_ALIAS_WINDOW_MS = 10 * 60 * 1000L
    private const val MAX_RECENT_EVENTS = 1024
    private const val MAX_VERIFY_ALIASES = 4096
    private val parseApi = WeChatMessageParseApi()
    private val recentEvents = ConcurrentHashMap<String, Long>()
    private val recentVerifyAliases = ConcurrentHashMap<String, VerifyAlias>()
    private val nativeListeners = CopyOnWriteArrayList<(NewFriendEvent) -> Unit>()
    @Volatile
    private var installed = false

    @Synchronized
    fun install(context: FeatureContext): Boolean {
        if (installed) return true
        installDatabaseListener()
        val observeApi = runCatching { WeChatApis.messageObserve() }.getOrNull()
        if (observeApi != null && observeApi.isAvailable()) {
            runCatching { observeApi.install() }
            observeApi.subscribe { message ->
                handleMessage(ScriptMessageBean(message))
            }
        } else {
            context.eventBus().subscribe(Events.MessageReceived::class.java) { event ->
                handleMessage(ScriptMessageBean(event))
            }
        }
        installed = true
        return true
    }

    fun handleMessage(message: ScriptMessageBean) {
        val event = parseNewFriend(message) ?: return
        val now = System.currentTimeMillis()
        cleanup(now)
        registerVerifyAlias(event, now)
        val key = dedupKey(event)
        val previous = recentEvents[key]
        if (previous != null && now - previous < DEDUP_WINDOW_MS) return
        recentEvents[key] = now
        trimCaches()
        dispatchNative(event)
        ScriptPluginRuntime.dispatchOnNewFriend(event.wxid, event.ticket, event.scene)
    }

    fun resolveVerifyUsername(wxid: String?, ticket: String?, scene: Int): String {
        val cleanWxid = wxid?.trim().orEmpty()
        if (cleanWxid.isBlank() || cleanWxid.isVerifyUsername()) return cleanWxid
        val now = System.currentTimeMillis()
        cleanup(now)
        recentVerifyAliases[aliasKey(cleanWxid, ticket.orEmpty(), scene)]?.verifyUsername?.let {
            if (it.isNotBlank()) return it
        }
        val normalizedTicket = ticket.orEmpty()
        return recentVerifyAliases.values
            .asSequence()
            .filter { alias ->
                alias.contactWxid == cleanWxid &&
                    (normalizedTicket.isBlank() || alias.ticket == normalizedTicket)
            }
            .maxByOrNull { it.time }
            ?.verifyUsername
            ?.takeIf { it.isNotBlank() }
            ?: cleanWxid
    }

    private fun installDatabaseListener() {
        val databaseChanges = runCatching { WeChatApis.runtime().databaseChanges() }.getOrNull() ?: return
        if (!databaseChanges.isAvailable) return
        runCatching { databaseChanges.install() }
        databaseChanges.subscribe { change ->
            handleDatabaseChange(change)
        }
    }

    private fun handleDatabaseChange(change: DatabaseChange?) {
        if (change == null || !change.isInsert()) return
        if (!change.table.equals("fmessage_msginfo", ignoreCase = true)) return
        val values = change.values ?: return
        if (contentValueInt(values, "isSend") != 0) return
        val raw = buildString {
            appendIfNotBlank(contentValueString(values, "msgContent"))
            appendIfNotBlank(contentValueString(values, "fmsgContent"))
            appendIfNotBlank(contentValueString(values, "content"))
            appendIfNotBlank(contentValueString(values, "msgSource"))
        }
        val event = parseNewFriend(
            raw = raw,
            type = null,
            fallbackSender = contentValueString(values, "talker"),
            trustedSource = true,
            fallbackWxid = firstNotBlank(
                contentValueString(values, "fromusername"),
                contentValueString(values, "fromUserName"),
                contentValueString(values, "encryptusername"),
                contentValueString(values, "encryptTalker"),
                contentValueString(values, "talker")
            ),
            fallbackTicket = firstNotBlank(
                contentValueString(values, "ticket"),
                contentValueString(values, "antispamticket"),
                contentValueString(values, "antispam_ticket"),
                contentValueString(values, "verifyticket"),
                contentValueString(values, "verify_ticket")
            ),
            fallbackScene = firstNotBlank(
                contentValueString(values, "scene"),
                contentValueString(values, "scence"),
                contentValueString(values, "sceneid"),
                contentValueString(values, "scene_id")
            )
        ) ?: return
        val now = System.currentTimeMillis()
        cleanup(now)
        registerVerifyAlias(event, now)
        val key = dedupKey(event)
        val previous = recentEvents[key]
        if (previous != null && now - previous < DEDUP_WINDOW_MS) return
        recentEvents[key] = now
        trimCaches()
        dispatchNative(event)
        ScriptPluginRuntime.dispatchOnNewFriend(event.wxid, event.ticket, event.scene)
    }

    fun subscribe(listener: (NewFriendEvent) -> Unit): NativeSubscription {
        nativeListeners.add(listener)
        return NativeSubscription(listener)
    }

    private fun parseNewFriend(message: ScriptMessageBean): NewFriendEvent? {
        val type = message.getMsgType().toIntOrNull()
        val raw = buildString {
            val content = message.getContent()
            val xml = message.getXml()
            val source = message.getMsgSource()
            if (content.isNotBlank()) append(content)
            if (xml.isNotBlank() && xml != content) {
                append('\n')
                append(xml)
            }
            if (source.isNotBlank()) {
                append('\n')
                append(source)
            }
        }
        return parseNewFriend(
            raw = raw,
            type = type,
            fallbackSender = message.getSender(),
            trustedSource = false
        )
    }

    private fun parseNewFriend(
        raw: String,
        type: Int?,
        fallbackSender: String,
        trustedSource: Boolean,
        fallbackWxid: String = "",
        fallbackTicket: String = "",
        fallbackScene: String = ""
    ): NewFriendEvent? {
        if (raw.isBlank() && fallbackWxid.isBlank() && fallbackTicket.isBlank()) return null
        if (!trustedSource && WeChatMessageTypes.normalize(type ?: 0) != 37) return null
        val encryptedUsername = firstNotBlank(
            tag(raw, "encryptusername"),
            tag(raw, "encryptuser"),
            attr(raw, "encryptusername"),
            query(raw, "encryptusername"),
            fallbackWxid.takeIf { it.cleanXmlValue().isVerifyUsername() }.orEmpty(),
            fallbackSender.takeIf { it.cleanXmlValue().isVerifyUsername() }.orEmpty()
        ).cleanXmlValue().takeIf { isValidRequester(it) }.orEmpty()
        val contactWxid = firstNotBlank(
            tag(raw, "fromusername"),
            tag(raw, "username"),
            attr(raw, "fromusername"),
            query(raw, "fromusername"),
            fallbackWxid.takeIf { !it.cleanXmlValue().isVerifyUsername() }.orEmpty(),
            fallbackSender.takeIf { !it.cleanXmlValue().isVerifyUsername() }.orEmpty()
        ).cleanXmlValue().takeIf { isValidRequester(it) }.orEmpty()
        val wxid = firstNotBlank(contactWxid, encryptedUsername)
        val verifyUsername = firstNotBlank(encryptedUsername, wxid)
        val ticket = firstNotBlank(
            tag(raw, "ticket"),
            tag(raw, "antispamticket"),
            tag(raw, "antispam_ticket"),
            tag(raw, "verifyticket"),
            tag(raw, "verify_ticket"),
            attr(raw, "ticket"),
            attr(raw, "antispamticket"),
            query(raw, "ticket"),
            query(raw, "antispamticket"),
            fallbackTicket
        ).cleanXmlValue()
        if (wxid.isBlank() || ticket.isBlank()) return null
        val scene = firstNotBlank(
            tag(raw, "scene"),
            tag(raw, "scence"),
            tag(raw, "sceneid"),
            tag(raw, "scene_id"),
            attr(raw, "scene"),
            query(raw, "scene"),
            query(raw, "sceneid"),
            query(raw, "scene_id"),
            fallbackScene
        ).cleanXmlValue().toIntOrNull() ?: 0
        return NewFriendEvent(wxid, verifyUsername, ticket, scene)
    }

    private fun isValidRequester(value: String): Boolean {
        if (value.isBlank()) return false
        if (value.equals("fmessage", ignoreCase = true)) return false
        if (value.endsWith("@chatroom") || value.endsWith("@openim") || value.endsWith("@im.chatroom")) {
            return false
        }
        if (value.startsWith("gh_")) return false
        return value.any { it.isLetterOrDigit() }
    }

    private fun String.isVerifyUsername(): Boolean {
        val value = trim()
        return value.endsWith("@stranger", ignoreCase = true)
            || value.startsWith("v1_", ignoreCase = true)
            || value.startsWith("v2_", ignoreCase = true)
            || value.startsWith("v3_", ignoreCase = true)
    }

    private fun tag(source: String, name: String): String {
        return parseApi.getXmlParamByTag(source, name).orEmpty()
    }

    private fun attr(source: String, name: String): String {
        if (source.isBlank()) return ""
        return Regex("""\b${Regex.escape(name)}\s*=\s*(['"])(.*?)\1""", RegexOption.IGNORE_CASE)
            .find(source)
            ?.groupValues
            ?.getOrNull(2)
            .orEmpty()
    }

    private fun query(source: String, name: String): String {
        if (source.isBlank()) return ""
        return Regex("""(?:[?&]|&amp;)${Regex.escape(name)}=([^&\s<"']+)""", RegexOption.IGNORE_CASE)
            .find(source)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { decodeUrl(it) }
            .orEmpty()
    }

    private fun firstNotBlank(vararg values: String): String {
        return values.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun StringBuilder.appendIfNotBlank(value: String) {
        if (value.isBlank()) return
        if (isNotEmpty()) append('\n')
        append(value)
    }

    private fun contentValueString(values: ContentValues, key: String): String {
        if (!values.containsKey(key)) return ""
        return runCatching { values.getAsString(key).orEmpty() }
            .getOrElse {
                values.get(key)?.toString().orEmpty()
            }
    }

    private fun contentValueInt(values: ContentValues, key: String): Int {
        if (!values.containsKey(key)) return 0
        return runCatching { values.getAsInteger(key) ?: 0 }
            .getOrElse { values.get(key)?.toString()?.toIntOrNull() ?: 0 }
    }

    private fun String.cleanXmlValue(): String {
        return decodeXml(trim())
            .trim()
            .trim('"', '\'', ' ', '\n', '\r', '\t')
    }

    private fun decodeXml(value: String): String {
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private fun decodeUrl(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    private fun cleanup(now: Long) {
        if (recentEvents.size >= 64) {
            recentEvents.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
        }
        if (recentVerifyAliases.isNotEmpty()) {
            recentVerifyAliases.entries.removeIf { now - it.value.time > VERIFY_ALIAS_WINDOW_MS }
        }
        trimCaches()
    }

    private fun registerVerifyAlias(event: NewFriendEvent, now: Long) {
        if (event.wxid.isBlank() || event.verifyUsername.isBlank()) return
        if (event.wxid == event.verifyUsername) return
        val alias = VerifyAlias(event.wxid, event.verifyUsername, event.ticket, event.scene, now)
        recentVerifyAliases[aliasKey(event.wxid, event.ticket, event.scene)] = alias
        trimCaches()
    }

    private fun trimCaches() {
        while (recentEvents.size > MAX_RECENT_EVENTS) {
            val oldest = recentEvents.entries.minByOrNull { it.value } ?: break
            recentEvents.remove(oldest.key, oldest.value)
        }
        while (recentVerifyAliases.size > MAX_VERIFY_ALIASES) {
            val oldest = recentVerifyAliases.entries.minByOrNull { it.value.time } ?: break
            recentVerifyAliases.remove(oldest.key, oldest.value)
        }
    }

    private fun dedupKey(event: NewFriendEvent): String {
        return "${firstNotBlank(event.verifyUsername, event.wxid)}|${event.ticket}|${event.scene}"
    }

    private fun aliasKey(wxid: String, ticket: String, scene: Int): String {
        return "${wxid.trim()}|${ticket.trim()}|$scene"
    }

    private fun dispatchNative(event: NewFriendEvent) {
        nativeListeners.forEach { listener ->
            runCatching { listener(event) }
        }
    }

    class NativeSubscription internal constructor(
        private val listener: (NewFriendEvent) -> Unit
    ) {
        fun unsubscribe() {
            nativeListeners.remove(listener)
        }
    }

    data class NewFriendEvent(
        val wxid: String,
        val verifyUsername: String,
        val ticket: String,
        val scene: Int
    )

    private data class VerifyAlias(
        val contactWxid: String,
        val verifyUsername: String,
        val ticket: String,
        val scene: Int,
        val time: Long
    )
}
