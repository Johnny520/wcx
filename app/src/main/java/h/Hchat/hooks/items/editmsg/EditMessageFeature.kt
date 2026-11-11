package h.Hchat.hooks.items.editmsg

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.message.SingleMessageMenuLocator
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.ui.miuix.EditMessageMiuixDialog
import h.Hchat.utils.KavaReflector
import java.math.BigDecimal
import java.math.RoundingMode
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class EditMessageFeature : BaseFeature() {
    private var hooker: EditMessageHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "修改聊天记录"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(EditMessageSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        hooker = EditMessageHooker(context, ::logFeatureError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) {
            scheduleInstall()
        }
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            hooker?.install() == true
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "edit_message"
    }
}

data class EditableChatMessage(
    val msgId: Long,
    val talker: String,
    val isQuote: Boolean,
    val isTransfer: Boolean,
    val displayText: String,
    val replyText: String,
    val quotedText: String,
    val hasBackup: Boolean
)

data class EditMessageUpdate(
    val success: Boolean,
    val requiresReload: Boolean = false,
    val msgId: Long = 0L,
    val isTransfer: Boolean = false,
    val oldContent: String = "",
    val newContent: String = "",
    val oldDisplayText: String = "",
    val newDisplayText: String = ""
)

object EditMessageRepository {
    private const val MENU_EDIT_TITLE = "修改[H]"
    private const val BACKUP_PREFIX = "msg_"

    fun isEnabled(context: Context?): Boolean {
        if (context == null) return false
        val prefs = HchatStorage.preferences(context, EditMessageSettings.PREFS_NAME)
        return prefs.getBoolean(EditMessageSettings.KEY_ENABLE, EditMessageSettings.DEFAULT_ENABLE)
    }

    fun editableMessage(context: Context, msgId: Long): EditableChatMessage? {
        val message = WeChatApis.message()?.store()?.getMessageById(msgId) ?: return null
        if (!isEditableMessage(message)) return null
        val body = message.bodyContent()
        val quote = message.isQuote()
        val transfer = message.isTransfer()
        return EditableChatMessage(
            msgId = message.msgId,
            talker = message.talker,
            isQuote = quote,
            isTransfer = transfer,
            displayText = when {
                transfer -> transferDisplayText(body)
                quote -> quoteDisplayText(body)
                else -> body
            },
            replyText = when {
                transfer -> transferAmountYuan(body)
                quote -> xmlTag(body, "title")
                else -> body
            },
            quotedText = if (quote) quoteReferText(body) else "",
            hasBackup = backupPrefs(context).contains(backupKey(message.msgId))
        )
    }

    fun save(context: Context, msgId: Long, newReplyText: String, newQuotedText: String): Boolean {
        return saveWithResult(context, msgId, newReplyText, newQuotedText).success
    }

    fun saveWithResult(
        context: Context,
        msgId: Long,
        newReplyText: String,
        newQuotedText: String,
        nativeMessage: Any? = null
    ): EditMessageUpdate {
        val message = WeChatApis.message()?.store()?.getMessageById(msgId) ?: return EditMessageUpdate(false)
        if (!isEditableMessage(message)) return EditMessageUpdate(false)
        val oldContent = message.content
        backupOriginal(context, message)
        val newBody = when {
            message.isTransfer() -> updateTransferBody(message.bodyContent(), newReplyText) ?: return EditMessageUpdate(false)
            message.isQuote() -> updateQuoteBody(message.bodyContent(), newReplyText, newQuotedText)
            else -> newReplyText
        }
        val newContent = withOriginalPrefix(message, newBody)
        val write = updateMessageContent(message, newContent, nativeMessage)
        if (write.success && message.isQuote() && newQuotedText.isNotBlank()) {
            cascadeUpdateOriginalQuote(context, message, newQuotedText)
        }
        if (write.success) {
            syncConversationPreview(message, newContent)
        }
        return EditMessageUpdate(
            success = write.success,
            requiresReload = write.success && !write.notified,
            msgId = message.msgId,
            isTransfer = message.isTransfer(),
            oldContent = oldContent,
            newContent = newContent,
            oldDisplayText = displayTextForContent(message, oldContent),
            newDisplayText = displayTextForContent(message, newContent)
        )
    }

    fun restore(context: Context, msgId: Long): Boolean {
        return restoreWithResult(context, msgId).success
    }

    fun restoreWithResult(context: Context, msgId: Long, nativeMessage: Any? = null): EditMessageUpdate {
        val original = backupPrefs(context).getString(backupKey(msgId), "")?.takeIf { it.isNotEmpty() } ?: return EditMessageUpdate(false)
        val message = WeChatApis.message()?.store()?.getMessageById(msgId) ?: return EditMessageUpdate(false)
        val oldContent = message.content
        val write = updateMessageContent(message, original, nativeMessage)
        if (write.success) {
            syncConversationPreview(message, original)
            backupPrefs(context).edit().remove(backupKey(msgId)).apply()
        }
        return EditMessageUpdate(
            success = write.success,
            requiresReload = write.success && !write.notified,
            msgId = message.msgId,
            isTransfer = message.isTransfer(),
            oldContent = oldContent,
            newContent = original,
            oldDisplayText = displayTextForContent(message, oldContent),
            newDisplayText = displayTextForContent(message, original)
        )
    }

    private fun isEditableMessage(message: WeChatMessage): Boolean {
        return message.isText() || message.isQuote() || message.isTransfer()
    }

    private fun backupPrefs(context: Context) =
        HchatStorage.preferences(context, EditMessageSettings.BACKUP_PREFS_NAME)

    private fun backupKey(msgId: Long): String = BACKUP_PREFIX + msgId

    private fun backupOriginal(context: Context, message: WeChatMessage) {
        val prefs = backupPrefs(context)
        val key = backupKey(message.msgId)
        if (!prefs.contains(key)) {
            prefs.edit().putString(key, message.content).apply()
        }
    }

    private data class MessageWriteResult(val success: Boolean, val notified: Boolean)

    private fun updateMessageContent(
        message: WeChatMessage,
        content: String,
        nativeMessage: Any? = null
    ): MessageWriteResult {
        val database = WeChatApis.database() ?: return MessageWriteResult(success = false, notified = false)
        if (database.updateNativeMessageContent(message.msgId, content, nativeMessage)) {
            return MessageWriteResult(success = true, notified = true)
        }
        val values = ContentValues().apply {
            put("content", content)
        }
        val tables = linkedSetOf<String>()
        database.messageTableForTalker(message.talker).takeIf { it.isNotBlank() }?.let { tables += it }
        tables += "message"
        for (table in tables) {
            val rows = database.update(table, values, "msgId=?", arrayOf(message.msgId.toString()))
            if (rows > 0) return MessageWriteResult(success = true, notified = false)
        }
        return MessageWriteResult(success = false, notified = false)
    }

    private fun syncConversationPreview(message: WeChatMessage, content: String) {
        val database = WeChatApis.database() ?: return
        val latest = WeChatApis.message()?.store()?.getLatestMessage(message.talker)
        if (latest?.msgId != message.msgId) return
        val values = ContentValues().apply {
            put("content", content)
            put("msgType", message.type.toString())
            put("isSend", message.isSend)
            put("digest", displayTextForContent(message, content))
        }
        database.update("rconversation", values, "username=?", arrayOf(message.talker))
    }

    private fun cascadeUpdateOriginalQuote(context: Context, quoteMessage: WeChatMessage, newQuotedText: String) {
        val svrid = getQuoteSvrId(quoteMessage.bodyContent())
        if (svrid <= 0L) return
        val store = WeChatApis.message()?.store() ?: return
        val original = store.getMessageBySvrId(quoteMessage.talker, svrid)
            ?: store.getMessageBySvrId(svrid)
            ?: return
        if (!isEditableMessage(original)) return
        backupOriginal(context, original)
        val newBody = if (original.isQuote()) {
            updateQuoteBody(original.bodyContent(), xmlTag(original.bodyContent(), "title"), newQuotedText)
        } else {
            newQuotedText
        }
        updateMessageContent(original, withOriginalPrefix(original, newBody))
    }

    private fun withOriginalPrefix(message: WeChatMessage, body: String): String {
        val prefixEnd = message.content.indexOf(":\n")
        return if (message.isGroupChat() && message.isIncoming() && prefixEnd > 0) {
            message.content.substring(0, prefixEnd + 2) + body
        } else {
            body
        }
    }

    private fun displayTextForContent(message: WeChatMessage, content: String): String {
        val body = bodyContentFor(message, content)
        return when {
            message.isTransfer() -> transferDisplayText(body)
            message.isQuote() -> quoteDisplayText(body)
            else -> body
        }
    }

    private fun bodyContentFor(message: WeChatMessage, content: String): String {
        val prefixEnd = content.indexOf(":\n")
        return if (message.isGroupChat() && prefixEnd > 0) {
            content.substring(prefixEnd + 2)
        } else {
            content
        }
    }

    private fun updateQuoteBody(body: String, replyText: String, quotedText: String): String {
        var updated = replaceFirstTag(body, "title", replyText)
        val refer = xmlSection(updated, "refermsg")
        if (refer.isNotBlank() && quotedText.isNotBlank()) {
            var referUpdated = updateReferContent(refer, quotedText)
            referUpdated = replaceFirstTag(referUpdated, "title", quotedText)
            if (referUpdated != refer) {
                updated = updated.replace(refer, referUpdated)
            }
        }
        return updated
    }

    private fun quoteDisplayText(body: String): String {
        val reply = xmlTag(body, "title")
        val quoted = quoteReferText(body)
        return if (quoted.isBlank()) reply else "$reply\n\n引用：$quoted"
    }

    private fun quoteReferText(body: String): String {
        val refer = xmlSection(body, "refermsg")
        val refContent = xmlRawTag(refer, "content")
        val refType = xmlTag(refer, "type")
        if (refType == "49" || refType == "57") {
            val nested = unescapeXml(refContent)
            val nestedTitle = xmlTag(nested, "title")
            if (nestedTitle.isNotBlank()) return nestedTitle
        }
        return firstNotBlank(
            unescapeXml(refContent),
            xmlTag(refer, "title"),
            xmlTag(body, "refermsg")
        )
    }

    private fun getQuoteSvrId(body: String): Long {
        return firstNotBlank(
            xmlTag(xmlSection(body, "refermsg"), "svrid"),
            xmlTag(body, "svrid")
        ).toLongOrNull() ?: 0L
    }

    private fun transferDisplayText(body: String): String {
        val amount = transferAmountYuan(body)
        val desc = firstNotBlank(xmlTag(body, "feedesc"), xmlTag(body, "title"), xmlTag(body, "desc"))
        return if (amount.isBlank()) desc else "￥$amount"
    }

    private fun transferAmountYuan(body: String): String {
        transferDisplayAmountYuan(body)?.let { return it }
        val fen = firstNotBlank(xmlTag(body, "total_fee"), xmlTag(body, "feederval"), xmlTag(body, "fee"))
            .toLongOrNull()
            ?: amountTextToFen(firstNotBlank(xmlTag(body, "feedesc"), xmlTag(body, "title"), xmlTag(body, "desc")))
            ?: return ""
        return formatYuan(fen)
    }

    private fun updateTransferBody(body: String, amountText: String): String? {
        val yuan = amountInputToDisplayYuan(amountText) ?: return null
        val fen = amountInputToFen(yuan) ?: return null
        val amountDisplay = "￥$yuan"
        var updated = body
        var changed = false
        for (tag in listOf("total_fee", "feederval", "fee")) {
            val next = replaceTagIfPresent(updated, tag, fen.toString())
            if (next != updated) {
                changed = true
                updated = next
            }
        }
        val feedescUpdated = replaceTagIfPresent(updated, "feedesc", amountDisplay)
        if (feedescUpdated != updated) {
            changed = true
            updated = feedescUpdated
        }
        for (tag in listOf("title", "desc", "payerdes", "receiverdes")) {
            val old = xmlTag(updated, tag)
            if (old.isBlank() || !looksLikeAmountText(old)) continue
            val next = replaceTagIfPresent(updated, tag, amountDisplay)
            if (next != updated) {
                changed = true
                updated = next
            }
        }
        return if (changed) updated else body
    }

    private fun transferDisplayAmountYuan(body: String): String? {
        for (tag in listOf("feedesc", "title", "desc", "payerdes", "receiverdes")) {
            amountTextToDisplayYuan(xmlTag(body, tag))?.let { return it }
        }
        return null
    }

    private fun replaceTagIfPresent(source: String, tag: String, value: String): String {
        if (source.isBlank()) return source
        val regex = Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return regex.replace(source) { match ->
            val full = match.value
            val prefixEnd = full.indexOf('>')
            val prefix = full.substring(0, prefixEnd + 1)
            prefix + escapeXml(value) + "</$tag>"
        }
    }

    private fun updateReferContent(refer: String, newText: String): String {
        val rawContent = xmlRawTag(refer, "content")
        if (rawContent.isBlank()) return refer
        val escapedText = escapeXml(newText)
        val newContent = when {
            rawContent.contains("&lt;title&gt;", ignoreCase = true) &&
                rawContent.contains("&lt;/title&gt;", ignoreCase = true) -> {
                Regex("&lt;title&gt;.*?&lt;/title&gt;", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .replace(rawContent) { "&lt;title&gt;$escapedText&lt;/title&gt;" }
            }
            rawContent.contains("<title>", ignoreCase = true) &&
                rawContent.contains("</title>", ignoreCase = true) -> {
                Regex("<title>.*?</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .replace(rawContent) { "<title>$escapedText</title>" }
            }
            else -> escapedText
        }
        return replaceFirstTagRaw(refer, "content", newContent)
    }

    private fun amountInputToFen(value: String): Long? {
        val normalized = normalizedAmountInput(value) ?: return null
        if (normalized.isBlank()) return null
        return runCatching {
            val amount = BigDecimal(normalized)
            if (amount < BigDecimal.ZERO) return null
            amount
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        }.getOrNull()?.takeIf { it >= 0L }
    }

    private fun amountTextToFen(value: String): Long? {
        val numeric = amountTextToDisplayYuan(value) ?: return null
        return amountInputToFen(numeric)
    }

    private fun amountInputToDisplayYuan(value: String): String? {
        val normalized = normalizedAmountInput(value) ?: return null
        val scale = normalized.substringAfter('.', "").length.takeIf { normalized.contains('.') } ?: 0
        return runCatching {
            val amount = BigDecimal(normalized)
            if (amount < BigDecimal.ZERO) return null
            amount.setScale(scale, RoundingMode.UNNECESSARY).toPlainString()
        }.getOrNull()
    }

    private fun amountTextToDisplayYuan(value: String): String? {
        val normalized = value.replace(",", "")
        val numeric = Regex("[0-9]+(?:\\.[0-9]{1,2})?").find(normalized)?.value ?: return null
        return amountInputToDisplayYuan(numeric)
    }

    private fun normalizedAmountInput(value: String): String? {
        val normalized = value.trim()
            .replace("￥", "")
            .replace("¥", "")
            .replace("元", "")
            .replace(",", "")
            .trim()
        if (normalized.isBlank()) return null
        if (!normalized.matches(Regex("[0-9]+(?:\\.[0-9]{0,2})?"))) return null
        return normalized.removeSuffix(".")
    }

    private fun formatYuan(fen: Long): String {
        return BigDecimal(fen)
            .movePointLeft(2)
            .stripTrailingZeros()
            .toPlainString()
    }

    private fun looksLikeAmountText(value: String): Boolean {
        val normalized = value.trim().replace(",", "")
        return value.contains("￥") ||
            value.contains("¥") ||
            value.contains("元") ||
            normalized.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))
    }

    private fun replaceFirstTag(source: String, tag: String, value: String): String {
        return replaceFirstTagRaw(source, tag, escapeXml(value))
    }

    private fun replaceFirstTagRaw(source: String, tag: String, value: String): String {
        if (source.isBlank()) return source
        val regex = Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val match = regex.find(source) ?: return source
        val full = match.value
        val prefixEnd = full.indexOf('>')
        val prefix = full.substring(0, prefixEnd + 1)
        val suffix = "</$tag>"
        return source.replaceRange(match.range, prefix + value + suffix)
    }

    private fun xmlSection(source: String, tag: String): String {
        if (source.isBlank()) return ""
        val regex = Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return regex.find(source)?.value.orEmpty()
    }

    private fun xmlTag(source: String, tag: String): String {
        if (source.isBlank()) return ""
        val regex = Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val value = regex.find(source)?.groupValues?.getOrNull(1).orEmpty()
        return unescapeXml(value.removePrefix("<![CDATA[").removeSuffix("]]>"))
    }

    private fun xmlRawTag(source: String, tag: String): String {
        if (source.isBlank()) return ""
        val regex = Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return regex.find(source)?.groupValues?.getOrNull(1)
            ?.removePrefix("<![CDATA[")
            ?.removeSuffix("]]>")
            .orEmpty()
    }

    private fun firstNotBlank(vararg values: String): String {
        return values.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun unescapeXml(value: String): String {
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    internal fun menuTitle(): String = MENU_EDIT_TITLE
}

private class EditMessageHooker(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private data class MenuMessageBinding(val msgId: Long, val nativeMessage: Any)

    private val messagesByMenuItem = Collections.synchronizedMap(WeakHashMap<MenuItem, MenuMessageBinding>())
    private val messagesByMenuGroup = ConcurrentHashMap<Int, MenuMessageBinding>()
    private val messageIdMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val activityMethodCache = ConcurrentHashMap<Class<*>, Method>()

    fun install(): Boolean {
        val clickMethods = locateClickMethods()
        val menuMethods = locateMenuMethods()
        if (clickMethods.isEmpty()) {
            logger("修改聊天记录定位菜单点击方法失败", null)
        }
        if (menuMethods.isEmpty()) {
            logger("修改聊天记录定位菜单创建方法失败", null)
        }
        var menuHooked = 0
        var clickHooked = 0
        menuMethods.forEach { method ->
            if (hookChatMethod(method, true)) menuHooked++
        }
        clickMethods.forEach { method ->
            if (hookChatMethod(method, false)) {
                clickHooked++
            }
        }
        if (menuHooked <= 0 || clickHooked <= 0) {
            logger("修改聊天记录Hook未安装", null)
        }
        return menuHooked > 0 && clickHooked > 0
    }

    private fun hookChatMethod(method: Method, menuCreate: Boolean): Boolean {
        return hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (menuCreate) addEditMenu(param)
            }

            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!menuCreate) handleEditClick(param)
            }
        })
    }

    private fun hookMethod(method: Method, callback: XC_MethodHook): Boolean {
        if (!isHookableMethod(method)) return false
        if (!hookedMethods.add(method)) return true
        return try {
            HookRegistry.get().hook(method, callback)
            true
        } catch (e: Throwable) {
            hookedMethods.remove(method)
            logger("修改聊天记录Hook安装失败: ${method.name}", e)
            false
        }
    }

    private fun addEditMenu(param: XC_MethodHook.MethodHookParam) {
        clearMenuMessageBinding()
        if (!EditMessageRepository.isEnabled(context.hostContext())) return
        val args = param.args ?: return
        if (args.size < 3) return
        val menu = args[0] ?: return
        val view = args.getOrNull(1) as? View ?: return
        val message = resolveNativeMessage(view.tag) ?: return
        val editable = EditMessageRepository.editableMessage(context.hostContext(), messageId(message)) ?: return
        if (editable.msgId <= 0L) return
        val groupId = readMenuGroupId(menu)
        val menuItem = addMenuItem(
            menu,
            view,
            groupId,
            MENU_EDIT_ID,
            EditMessageRepository.menuTitle(),
            "icons_filled_edit_photo_pencil"
        )
        if (menuItem != null) {
            val binding = MenuMessageBinding(editable.msgId, message)
            messagesByMenuItem[menuItem] = binding
            messagesByMenuGroup[menuItem.groupId] = binding
            moveEditMenuItemAfterRepeat(menu, menuItem)
        }
    }

    private fun handleEditClick(param: XC_MethodHook.MethodHookParam) {
        if (!EditMessageRepository.isEnabled(context.hostContext())) return
        val args = param.args ?: return
        val menuItem = findMenuItem(args) ?: return
        if (menuItem.itemId != MENU_EDIT_ID) return
        val activity = resolveActivity(param.thisObject)
        val binding = consumeMenuMessageBinding(menuItem)
        val editable = binding?.msgId?.takeIf { it > 0L }
            ?.let { EditMessageRepository.editableMessage(context.hostContext(), it) }
        if (activity == null || binding == null || editable == null) {
            toast(activity, "消息不可修改")
            return
        }
        EditMessageMiuixDialog.show(
            activity = activity,
            message = editable,
            onSave = { reply, quoted ->
                val update = EditMessageRepository.saveWithResult(
                    context.hostContext(),
                    editable.msgId,
                    reply,
                    quoted,
                    binding.nativeMessage
                )
                toast(
                    activity,
                    when {
                        !update.success -> "修改失败"
                        update.requiresReload -> "已修改，退出重进聊天后生效"
                        else -> "已修改"
                    }
                )
                update.success
            },
            onRestore = {
                val update = EditMessageRepository.restoreWithResult(
                    context.hostContext(),
                    editable.msgId,
                    binding.nativeMessage
                )
                toast(
                    activity,
                    when {
                        !update.success -> "没有可恢复内容"
                        update.requiresReload -> "已恢复，退出重进聊天后生效"
                        else -> "已恢复"
                    }
                )
                update.success
            }
        )
    }

    private fun findMenuItem(args: Array<Any?>): MenuItem? {
        return args.firstNotNullOfOrNull { it as? MenuItem }
    }

    private fun clearMenuMessageBinding() {
        messagesByMenuItem.clear()
        messagesByMenuGroup.clear()
    }

    private fun consumeMenuMessageBinding(menuItem: MenuItem): MenuMessageBinding? {
        val bound = messagesByMenuItem.remove(menuItem) ?: messagesByMenuGroup.remove(menuItem.groupId)
        clearMenuMessageBinding()
        return bound
    }

    private fun findMenuItem(menu: Any, itemId: Int): MenuItem? {
        return KavaReflector.invokeMethod(menu, "findItem", itemId) as? MenuItem
    }

    private fun moveEditMenuItemAfterRepeat(menu: Any, item: MenuItem) {
        var current: Class<*>? = menu.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (!java.util.List::class.java.isAssignableFrom(field.type)) continue
                @Suppress("UNCHECKED_CAST")
                val items = KavaReflector.readField(field, menu) as? MutableList<Any?> ?: continue
                val itemIndex = items.indexOfFirst { candidate ->
                    candidate === item || (candidate as? MenuItem)?.itemId == MENU_EDIT_ID
                }
                if (itemIndex < 0) continue
                val moved = items.removeAt(itemIndex)
                val forwardIndex = items.indexOfFirst { candidate ->
                    (candidate as? MenuItem)?.itemId == SingleMessageMenuLocator.HCHAT_FORWARD_MENU_ITEM_ID
                }
                val repeatIndex = items.indexOfFirst { candidate ->
                    (candidate as? MenuItem)?.itemId == SingleMessageMenuLocator.HCHAT_REPEAT_MENU_ITEM_ID
                }
                val targetIndex = when {
                    forwardIndex >= 0 -> forwardIndex + 1
                    repeatIndex >= 0 -> repeatIndex + 1
                    else -> 0
                }
                items.add(targetIndex.coerceAtMost(items.size), moved)
                return
            }
            current = current.superclass
        }
    }

    private fun addMenuItem(menu: Any, view: View?, groupId: Int, itemId: Int, title: String, iconName: String): MenuItem? {
        findMenuItem(menu, itemId)?.let { return it }
        val iconRes = menuIconResId(view, iconName)
        if (iconRes != 0) {
            val iconMethod = KavaReflector.declaredMethods(menu.javaClass).firstOrNull { method ->
                val types = method.parameterTypes
                method.name == "c" &&
                    types.size == 5 &&
                    types[0] == java.lang.Integer.TYPE &&
                    types[1] == java.lang.Integer.TYPE &&
                    types[2] == java.lang.Integer.TYPE &&
                    types[3].isAssignableFrom(String::class.java) &&
                    types[4] == java.lang.Integer.TYPE
            }
            if (KavaReflector.invokeSuccessfully(iconMethod, menu, groupId, itemId, 0, title, iconRes)) {
                return findMenuItem(menu, itemId)
            }
        }
        val added = KavaReflector.invokeMethod(menu, "add", groupId, itemId, 0, title)
            ?: KavaReflector.invokeMethod(menu, "add", groupId, itemId, 0, title as CharSequence)
        if (added is MenuItem && iconRes != 0) {
            runCatching { added.setIcon(iconRes) }
            return added
        }
        if (added is MenuItem) return added
        if (added != null) return findMenuItem(menu, itemId)
        val fallback = KavaReflector.invokeMethod(menu, "f", itemId, title)
            ?: KavaReflector.invokeMethod(menu, "f", itemId, title as CharSequence)
        return fallback as? MenuItem ?: findMenuItem(menu, itemId)
    }

    private fun menuIconResId(view: View?, iconName: String): Int {
        val iconContext = view?.context ?: WeChatApis.currentActivity()?.currentActivity() ?: return 0
        val resources = iconContext.resources
        val packageName = iconContext.packageName
        for (type in arrayOf("raw", "drawable")) {
            val id = resources.getIdentifier(iconName, type, packageName)
            if (id != 0) return id
        }
        return 0
    }

    private fun locateClickMethods(): List<Method> {
        return SingleMessageMenuLocator.menuClickMethods(context, logger)
    }

    private fun locateMenuMethods(): List<Method> {
        return SingleMessageMenuLocator.menuCreateMethods(context, logger)
    }

    private fun resolveNativeMessage(source: Any?): Any? {
        val tag = if (source is View) source.tag else source
        if (tag == null) return null
        if (isLikelyNativeMessage(tag) && messageId(tag) > 0L) return tag

        var current: Class<*>? = tag.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (KavaReflector.isStatic(field) || !isNativeMessageClass(field.type)) continue
                val value = KavaReflector.readField(field, tag) ?: continue
                if (messageId(value) > 0L) return value
            }
            current = current.superclass
        }

        current = tag.javaClass
        while (current != null && current != Any::class.java) {
            for (method in KavaReflector.declaredMethods(current)) {
                if (KavaReflector.isStatic(method) || method.parameterTypes.isNotEmpty()) continue
                if (!isNativeMessageClass(method.returnType)) continue
                val value = KavaReflector.invoke(method, tag) ?: continue
                if (messageId(value) > 0L) return value
            }
            current = current.superclass
        }
        return null
    }

    private fun isLikelyNativeMessage(value: Any): Boolean {
        return isNativeMessageClass(value.javaClass) && messageId(value) > 0L
    }

    private fun isNativeMessageClass(clazz: Class<*>): Boolean =
        clazz.name.startsWith("com.tencent.mm.storage.")

    private fun messageId(message: Any): Long {
        cachedMethod(messageIdMethodCache, message.javaClass) {
            KavaReflector.declaredMethods(message.javaClass).firstOrNull { method ->
                method.parameterTypes.isEmpty() &&
                    (method.name == "getMsgId" || method.name == "getMsgID" || method.name == "getId") &&
                    (method.returnType == java.lang.Long.TYPE || method.returnType == java.lang.Long::class.java)
            }
        }?.let { method ->
            (KavaReflector.invoke(method, message) as? Number)?.toLong()?.let { return it }
        }
        return namedNumberField(message, "field_msgId", "msgId", "msgID")?.toLong() ?: 0L
    }

    private fun resolveActivity(chattingContext: Any?): Activity? {
        val current = WeChatApis.currentActivity()?.currentActivity()
        if (current is Activity && !current.isFinishing) return current
        val target = chattingContext ?: return null
        cachedMethod(activityMethodCache, target.javaClass) {
            KavaReflector.declaredMethods(target.javaClass).firstOrNull { method ->
                method.parameterTypes.isEmpty() && Activity::class.java.isAssignableFrom(method.returnType)
            }
        }?.let { method ->
            val activity = KavaReflector.invoke(method, target) as? Activity
            if (activity != null && !activity.isFinishing) return activity
        }
        return null
    }

    private fun readMenuGroupId(menu: Any): Int {
        val size = (KavaReflector.invokeMethod(menu, "size") as? Number)?.toInt() ?: 0
        for (index in 0 until size) {
            val item = KavaReflector.invokeMethod(menu, "getItem", index) as? MenuItem ?: continue
            return item.groupId
        }
        return 0
    }

    private fun namedNumberField(source: Any, vararg names: String): Number? {
        for (name in names) {
            val value = KavaReflector.readField(source, name)
            if (value is Number) return value
        }
        return null
    }

    private fun cachedMethod(cache: ConcurrentHashMap<Class<*>, Method>, clazz: Class<*>, finder: () -> Method?): Method? {
        cache[clazz]?.let { return it }
        val method = finder() ?: return null
        cache.putIfAbsent(clazz, method)
        return method
    }

    private fun isHookableMethod(method: Method): Boolean {
        return !Modifier.isAbstract(method.modifiers) && !method.declaringClass.isInterface
    }

    private fun toast(activity: Activity?, message: String) {
        val target = activity ?: WeChatApis.currentActivity()?.currentActivity()
        if (target is Activity) {
            target.runOnUiThread {
                Toast.makeText(target, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val MENU_EDIT_ID = 0x48434544
    }
}
