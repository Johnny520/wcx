package h.Hchat.hooks.items.script

import android.app.Activity
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Looper
import android.text.TextUtils
import h.Hchat.hooks.api.contact.WeChatContactApi
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.media.WeChatFavoriteItem
import h.Hchat.hooks.api.media.WeChatMediaApi
import h.Hchat.hooks.api.model.ContactLabelBean
import h.Hchat.hooks.api.model.WeChatChatroom
import h.Hchat.hooks.api.model.WeChatContact
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.api.model.WeChatSnsPost
import h.Hchat.hooks.api.model.WeChatSnsPrepareResult
import h.Hchat.hooks.api.runtime.WeChatDatabaseApi
import h.Hchat.hooks.items.quickread.QuickMarkReadRuntime
import h.Hchat.hooks.items.shortvideo.FinderMediaDownloadSupport
import h.Hchat.utils.HchatMediaDownloader
import me.hd.wauxv.data.bean.info.FriendInfo
import me.hd.wauxv.data.bean.info.GroupData
import me.hd.wauxv.data.bean.info.GroupInfo
import me.hd.wauxv.plugin.api.callback.PluginCallBack
import me.yun.silk.SilkCodec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.regex.Pattern

class ScriptWaBridge @JvmOverloads internal constructor(
    private val bridge: ScriptPluginBridge,
    delayOnMainThread: Boolean = true
) {
    private var currentPluginName: String? = null
    private var currentPluginDir: File? = null
    private val atPattern: Pattern = Pattern.compile("\\[AtWx=([^\\]]+)]")
    private val httpClients = Collections.synchronizedMap(LinkedHashMap<Long, OkHttpClient>())
    private val httpBaseClient = OkHttpClient()
    private val durationCodec: SilkCodec by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SilkCodec() }

    private val delays = ScriptDelayScope(delayOnMainThread) { currentPluginName.orEmpty() }
    private val asyncScope = ScriptAsyncScope { currentPluginName.orEmpty() }

    private companion object {
        const val MAX_HTTP_CLIENTS = 8
        const val SCRIPT_CONTACT_READ_ATTEMPTS = 5
        const val SCRIPT_CONTACT_READ_DELAY_MS = 250L
        const val VIDEO_DOWNLOAD_TIMEOUT_MS = 60_000L
        const val VIDEO_METADATA_WAIT_MS = 15_000L
        const val VIDEO_METADATA_RETRY_MS = 500L
    }

    fun bindPluginLog(pluginName: String?, pluginDir: File?) {
        currentPluginName = pluginName
        currentPluginDir = pluginDir
    }

    fun getLoginWxid(): String {
        repeat(SCRIPT_CONTACT_READ_ATTEMPTS) { attempt ->
            val wxId = WeChatApis.contact().account()?.selfWxId().orEmpty().trim()
            if (wxId.isNotEmpty()) return wxId
            waitForContactData(attempt)
        }
        return ""
    }

    fun getLoginAlias(): String {
        val alias = WeChatApis.contact().account()?.customWxId().orEmpty()
        return alias.ifBlank { getLoginWxid() }
    }

    fun getTargetTalker(): String {
        WeChatApis.interaction().chatPage()?.currentTalker()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val intent = getTopActivity()?.intent ?: return ""
        return listOf("Chat_User", "Chat_UserName", "Contact_User", "Contact_Username")
            .firstNotNullOfOrNull { key ->
                intent.getStringExtra(key)?.trim()?.takeIf { it.isNotBlank() }
            }
            .orEmpty()
    }

    fun getTopActivity(): Activity? = WeChatApis.currentActivity()?.currentActivity()

    fun getDatabaseApi(): WeChatDatabaseApi? = WeChatApis.database()

    fun getOfficialList(): List<WeChatContact> {
        return WeChatApis.contact().contacts()?.getOfficialAccounts() ?: emptyList()
    }

    private fun rawFriendList(): List<WeChatContact> {
        return WeChatApis.contact().contacts()?.getFriends() ?: emptyList()
    }

    fun getFriendList(): List<FriendInfo> {
        return rawFriendList().map { contact ->
            // TODO(migration): 路线 A —— 改用 WCX 版 FriendInfo 的成员（同名 FQN）。
            //   Hchat 版 FriendInfo 为 11 参构造：(wxid, nickname, remark, alias, avatarUrl, avatarBackupUrl,
            //     encryptedUsername, province, city, gender, type)
            //   WCX 版 FriendInfo 仅有：wxid/alias/remark/nickname/type/sourceExtInfo/createTime
            //   下列成员在 WCX 版中缺失、此处未透出，脚本若依赖需人工确认（必要时在 WCX 版补成员）：
            //     avatarUrl / avatarBackupUrl / encryptedUsername / province / city / gender
            FriendInfo(
                wxid = contact.wxId,
                alias = contact.customWxId,
                remark = contact.remarkName,
                nickname = contact.nickname,
                type = contact.type
            )
        }
    }

    fun getFriendListInfo(): List<Map<String, Any?>> {
        val result = ArrayList<Map<String, Any?>>()
        for (contact in rawFriendList()) {
            val item = LinkedHashMap<String, Any?>()
            item["wxid"] = contact.wxId
            item["nickname"] = contact.nickname
            item["remarkName"] = contact.remarkName
            item["displayName"] = contact.displayName()
            item["customWxId"] = contact.customWxId
            item["gender"] = contact.gender
            item["province"] = contact.province
            item["city"] = contact.city
            item["region"] = contact.getRegion()
            item["avatarUrl"] = contact.avatarUrl
            item["avatarBackupUrl"] = contact.avatarBackupUrl
            item["type"] = contact.type
            result.add(item)
        }
        return result
    }

    private fun rawGroupList(): List<WeChatChatroom> {
        return WeChatApis.contact().chatrooms()?.getChatrooms() ?: emptyList()
    }

    private fun rawGroupContactMap(): Map<String, WeChatContact> {
        val groups = WeChatApis.contact().contacts()?.getGroups() ?: emptyList()
        return groups.associateBy { it.wxId }
    }

    fun getGroupList(): List<GroupInfo> {
        val groupContacts = rawGroupContactMap()
        return rawGroupList().map { chatroom ->
            val contact = groupContacts[chatroom.chatroomId]
            val groupName = firstNotBlank(chatroom.name, contact?.nickname, chatroom.chatroomId)
            // TODO(migration): 路线 A —— 改用 WCX 版 GroupInfo 的成员（同名 FQN）。
            //   Hchat 版 GroupInfo 为 7 参构造：(roomId, name, nickname, remarkName, owner, memberList, rawDisplayNames)
            //   WCX 版 GroupInfo 为：(roomId, remark, name, groupData)
            //   原直接透出的 owner/memberList/memberCount 改由 groupData 承载（用 Hchat 已取到的群数据填充，
            //   避免新增 DB 查询）；原 nickname / displayName / rawDisplayNames 在 WCX 版中无对应成员、此处未透出，
            //   脚本若依赖需人工确认。
            GroupInfo(
                roomId = chatroom.chatroomId,
                name = groupName,
                remark = contact?.remarkName.orEmpty(),
                groupData = GroupData(
                    roomId = chatroom.chatroomId,
                    memberIds = chatroom.memberIds,
                    memberCount = chatroom.memberCount(),
                    owner = chatroom.owner
                )
            )
        }
    }

    fun getGroupListInfo(): List<Map<String, Any?>> {
        val result = ArrayList<Map<String, Any?>>()
        val groupContacts = rawGroupContactMap()
        for (chatroom in rawGroupList()) {
            val contact = groupContacts[chatroom.chatroomId]
            val groupName = firstNotBlank(chatroom.name, contact?.nickname, chatroom.chatroomId)
            val remarkName = contact?.remarkName.orEmpty()
            val item = LinkedHashMap<String, Any?>()
            item["roomId"] = chatroom.chatroomId
            item["name"] = groupName
            item["nickname"] = firstNotBlank(contact?.nickname, groupName)
            item["remarkName"] = remarkName
            item["displayName"] = groupDisplayName(chatroom.chatroomId, groupName, remarkName)
            item["owner"] = chatroom.owner
            item["memberCount"] = chatroom.memberCount()
            item["memberList"] = chatroom.memberIds
            item["rawDisplayNames"] = chatroom.rawDisplayNames
            result.add(item)
        }
        return result
    }

    fun getGroupMemberListInfo(groupWxid: String?): List<Map<String, Any?>> {
        if (groupWxid.isNullOrBlank()) return emptyList()
        val result = ArrayList<Map<String, Any?>>()
        val contacts = WeChatApis.contact().contacts()
        val chatrooms = WeChatApis.contact().chatrooms()
        val memberIds = readGroupMemberIds(groupWxid, retry = true)
        if (memberIds.isEmpty()) return result
        val displayNames = contacts?.getGroupMemberDisplayNames(groupWxid).orEmpty()
        val rawRoomDisplayNames = contacts?.getGroupMemberRoomDisplayNames(groupWxid).orEmpty()
        for (memberWxid in memberIds) {
            val contact = contacts?.getContact(memberWxid)
            val rawGroupNickName = if (rawRoomDisplayNames.containsKey(memberWxid)) {
                rawRoomDisplayNames[memberWxid].orEmpty()
            } else {
                ScriptMemberChangeHook.cachedGroupNickName(groupWxid, memberWxid).orEmpty()
            }
            val finalGroupDisplayName = firstNotBlank(
                rawGroupNickName,
                displayNames[memberWxid],
                chatrooms?.getMemberDisplayName(groupWxid, memberWxid),
                contacts?.getGroupMemberDisplayName(groupWxid, memberWxid)
            )
            val item = LinkedHashMap<String, Any?>()
            item["wxid"] = memberWxid
            item["displayName"] = firstNotBlank(
                finalGroupDisplayName,
                contact?.displayName(),
                memberWxid
            )
            item["groupNick"] = finalGroupDisplayName
            item["groupNickName"] = finalGroupDisplayName
            item["rawGroupNickName"] = rawGroupNickName
            item["nickname"] = contact?.nickname.orEmpty()
            item["remarkName"] = contact?.remarkName.orEmpty()
            item["customWxId"] = contact?.customWxId.orEmpty()
            item["gender"] = contact?.gender ?: 0
            item["province"] = contact?.province.orEmpty()
            item["city"] = contact?.city.orEmpty()
            item["region"] = contact?.getRegion().orEmpty()
            item["avatarUrl"] = contact?.avatarUrl.orEmpty()
            result.add(item)
        }
        return result
    }

    fun getContactLabelList(): List<ContactLabelBean> {
        return WeChatApis.contact().contacts()?.getContactLabelList() ?: emptyList()
    }

    fun getContactLabelListInfo(): List<Map<String, Any?>> {
        val result = ArrayList<Map<String, Any?>>()
        for (label in getContactLabelList()) {
            val item = LinkedHashMap<String, Any?>()
            item["labelId"] = label.labelId
            item["id"] = label.labelId
            item["labelName"] = label.labelName
            item["name"] = label.labelName
            item["userNameList"] = label.userNameList
            item["usernameList"] = label.userNameList
            item["contactList"] = label.userNameList
            result.add(item)
        }
        return result
    }

    fun getContactByLabelId(labelId: String?): List<String> {
        if (labelId.isNullOrBlank()) return emptyList()
        return WeChatApis.contact().contacts()?.getContactByLabelId(labelId) ?: emptyList()
    }

    fun getContactByLabelName(labelName: String?): List<String> {
        if (labelName.isNullOrBlank()) return emptyList()
        return WeChatApis.contact().contacts()?.getContactByLabelName(labelName) ?: emptyList()
    }

    fun addContactLabel(labelName: String?): String {
        if (labelName.isNullOrBlank()) return ""
        return WeChatApis.contact().contacts()?.addContactLabel(labelName).orEmpty()
    }

    fun modifyContactLabelList(username: String?, labelName: String?): Boolean {
        if (username.isNullOrBlank()) return false
        return WeChatApis.contact().contacts()?.modifyContactLabelList(username, labelName) == true
    }

    fun modifyContactLabelList(username: String?, labelNames: List<String>?): Boolean {
        if (username.isNullOrBlank()) return false
        return WeChatApis.contact().contacts()?.modifyContactLabelList(username, labelNames) == true
    }

    fun verifyUser(wxid: String?, ticket: String?, scene: Int): Boolean {
        val verifyUsername = ScriptNewFriendHook.resolveVerifyUsername(wxid, ticket, scene)
        return WeChatApis.contact().verifyUser()?.verifyUser(verifyUsername, ticket, scene) == true
    }

    fun verifyUser(wxid: String?, ticket: String?, scene: Int, privacy: Int): Boolean {
        val verifyUsername = ScriptNewFriendHook.resolveVerifyUsername(wxid, ticket, scene)
        return WeChatApis.contact().verifyUser()?.verifyUser(verifyUsername, ticket, scene, privacy) == true
    }

    fun getGroupMemberList(groupWxid: String?): List<String> {
        if (groupWxid.isNullOrBlank()) return emptyList()
        return readGroupMemberIds(groupWxid, retry = true)
    }

    private fun readGroupMemberIds(groupWxid: String, retry: Boolean): List<String> {
        val attempts = if (retry) SCRIPT_CONTACT_READ_ATTEMPTS else 1
        repeat(attempts) { attempt ->
            val contacts = WeChatApis.contact().contacts()
            val chatrooms = WeChatApis.contact().chatrooms()
            val result = LinkedHashSet<String>()
            chatrooms?.getMemberIds(groupWxid)
                ?.filterTo(result) { it.isNotBlank() }
            contacts?.getGroupMemberIds(groupWxid)
                ?.filterTo(result) { it.isNotBlank() }
            contacts?.getGroupMemberRoomDisplayNames(groupWxid)
                ?.keys
                ?.filterTo(result) { it.isNotBlank() }
            if (result.isNotEmpty()) return result.toList()
            waitForContactData(attempt)
        }
        return emptyList()
    }

    private fun waitForContactData(attempt: Int) {
        if (attempt >= SCRIPT_CONTACT_READ_ATTEMPTS - 1 || Looper.myLooper() == Looper.getMainLooper()) return
        runCatching { Thread.sleep(SCRIPT_CONTACT_READ_DELAY_MS) }
            .onFailure { Thread.currentThread().interrupt() }
    }

    fun getGroupMemberCount(groupWxid: String?): Int {
        if (groupWxid.isNullOrBlank()) return 0
        val chatrooms = WeChatApis.contact().chatrooms()
        return if (chatrooms != null) chatrooms.getMemberCount(groupWxid) else getGroupMemberList(groupWxid).size
    }

    fun getGroupName(groupWxid: String?): String {
        if (groupWxid.isNullOrBlank()) return ""
        return firstNotBlank(
            WeChatApis.contact().chatrooms()?.getChatroomName(groupWxid),
            WeChatApis.contact().contacts()?.getDisplayName(groupWxid),
            groupWxid
        )
    }

    fun getChatroomName(chatroomId: String?): String = getGroupName(chatroomId)

    fun getGroupRemarkName(groupWxid: String?): String {
        if (groupWxid.isNullOrBlank()) return ""
        return WeChatApis.contact().contacts()?.getContact(groupWxid)?.remarkName.orEmpty()
    }

    fun getGroupMemberName(groupWxid: String?, memberWxid: String?): String {
        if (groupWxid.isNullOrBlank() || memberWxid.isNullOrBlank()) return ""
        return firstNotBlank(
            WeChatApis.contact().chatrooms()?.getRoomDisplayName(groupWxid, memberWxid),
            ScriptMemberChangeHook.cachedGroupNickName(groupWxid, memberWxid),
            WeChatApis.contact().chatrooms()?.getMemberDisplayName(groupWxid, memberWxid),
            WeChatApis.contact().contacts()?.getGroupMemberDisplayName(groupWxid, memberWxid),
            getFriendName(memberWxid),
            memberWxid
        )
    }
    fun getGroupNickName(groupWxid: String?, memberWxid: String?): String {
        if (groupWxid.isNullOrBlank() || memberWxid.isNullOrBlank()) return ""
        val rawRoomDisplayNames = WeChatApis.contact().contacts()?.getGroupMemberRoomDisplayNames(groupWxid).orEmpty()
        if (rawRoomDisplayNames.containsKey(memberWxid)) {
            return rawRoomDisplayNames[memberWxid].orEmpty()
        }
        return ScriptMemberChangeHook.cachedGroupNickName(groupWxid, memberWxid).orEmpty()
    }

    fun getFriendNickName(friendWxid: String?): String {
        if (friendWxid.isNullOrBlank()) return ""
        return WeChatApis.contact().contacts()?.getContact(friendWxid)?.nickname.orEmpty()
    }

    fun getFriendRemarkName(friendWxid: String?): String {
        if (friendWxid.isNullOrBlank()) return ""
        return WeChatApis.contact().contacts()?.getContact(friendWxid)?.remarkName.orEmpty()
    }

    fun getFriendGender(friendWxid: String?): Int {
        if (friendWxid.isNullOrBlank()) return 0
        return WeChatApis.contact().contacts()?.getGender(friendWxid) ?: 0
    }

    fun getFriendProvince(friendWxid: String?): String {
        if (friendWxid.isNullOrBlank()) return ""
        return WeChatApis.contact().contacts()?.getProvince(friendWxid).orEmpty()
    }

    fun getFriendCity(friendWxid: String?): String {
        if (friendWxid.isNullOrBlank()) return ""
        return WeChatApis.contact().contacts()?.getCity(friendWxid).orEmpty()
    }

    fun getFriendRegion(friendWxid: String?): String {
        if (friendWxid.isNullOrBlank()) return ""
        return WeChatApis.contact().contacts()?.getRegion(friendWxid).orEmpty()
    }

    fun getFriendDisplayName(friendWxid: String?, roomId: String?): String {
        if (friendWxid.isNullOrBlank()) return ""
        if (!roomId.isNullOrBlank()) {
            val groupName = firstNotBlank(
                WeChatApis.contact().chatrooms()?.getRoomDisplayName(roomId, friendWxid),
                ScriptMemberChangeHook.cachedGroupNickName(roomId, friendWxid),
                WeChatApis.contact().chatrooms()?.getMemberDisplayName(roomId, friendWxid),
                WeChatApis.contact().contacts()?.getGroupMemberDisplayName(roomId, friendWxid)
            )
            if (groupName.isNotBlank()) return groupName
        }
        val contacts = WeChatApis.contact().contacts() ?: return friendWxid
        return firstNotBlank(
            contacts.getContact(friendWxid)?.nickname,
            contacts.getContact(friendWxid)?.customWxId,
            friendWxid
        )
    }

    fun getFriendName(friendWxid: String?): String {
        if (friendWxid.isNullOrBlank()) return ""
        val contact = WeChatApis.contact().contacts()?.getContact(friendWxid)
        return firstNotBlank(
            contact?.remarkName,
            contact?.nickname,
            contact?.customWxId,
            friendWxid
        )
    }

    fun getFriendName(friendWxid: String?, roomId: String?): String {
        if (friendWxid.isNullOrBlank()) return ""
        return firstNotBlank(
            if (roomId.isNullOrBlank()) "" else getFriendDisplayName(friendWxid, roomId),
            getFriendRemarkName(friendWxid),
            getFriendNickName(friendWxid),
            friendWxid
        )
    }

    fun getGroupMemberGender(groupWxid: String?, memberWxid: String?): Int {
        if (groupWxid.isNullOrBlank() || memberWxid.isNullOrBlank()) return 0
        return getFriendGender(memberWxid)
    }

    fun getGroupMemberProvince(groupWxid: String?, memberWxid: String?): String {
        if (groupWxid.isNullOrBlank() || memberWxid.isNullOrBlank()) return ""
        return getFriendProvince(memberWxid)
    }

    fun getGroupMemberCity(groupWxid: String?, memberWxid: String?): String {
        if (groupWxid.isNullOrBlank() || memberWxid.isNullOrBlank()) return ""
        return getFriendCity(memberWxid)
    }

    fun getGroupMemberRegion(groupWxid: String?, memberWxid: String?): String {
        if (groupWxid.isNullOrBlank() || memberWxid.isNullOrBlank()) return ""
        return getFriendRegion(memberWxid)
    }

    fun addChatroomMember(chatroomId: String?, addMember: String?): Boolean {
        if (chatroomId.isNullOrBlank() || addMember.isNullOrBlank()) return false
        return WeChatApis.contact().chatrooms()?.addChatroomMember(chatroomId, addMember) == true
    }

    fun addChatroomMember(chatroomId: String?, addMemberList: List<String>?): Boolean {
        if (chatroomId.isNullOrBlank() || addMemberList.isNullOrEmpty()) return false
        return WeChatApis.contact().chatrooms()?.addChatroomMember(chatroomId, addMemberList) == true
    }

    fun inviteChatroomMember(chatroomId: String?, inviteMember: String?): Boolean {
        if (chatroomId.isNullOrBlank() || inviteMember.isNullOrBlank()) return false
        return WeChatApis.contact().chatrooms()?.inviteChatroomMember(chatroomId, inviteMember) == true
    }

    fun inviteChatroomMember(chatroomId: String?, inviteMemberList: List<String>?): Boolean {
        if (chatroomId.isNullOrBlank() || inviteMemberList.isNullOrEmpty()) return false
        return WeChatApis.contact().chatrooms()?.inviteChatroomMember(chatroomId, inviteMemberList) == true
    }

    fun delChatroomMember(chatroomId: String?, delMember: String?): Boolean {
        if (chatroomId.isNullOrBlank() || delMember.isNullOrBlank()) return false
        return WeChatApis.contact().chatrooms()?.delChatroomMember(chatroomId, delMember) == true
    }

    fun delChatroomMember(chatroomId: String?, delMemberList: List<String>?): Boolean {
        if (chatroomId.isNullOrBlank() || delMemberList.isNullOrEmpty()) return false
        return WeChatApis.contact().chatrooms()?.delChatroomMember(chatroomId, delMemberList) == true
    }

    fun getAvatarUrl(username: String?): String = getAvatarUrl(username, true)

    fun getAvatarUrl(username: String?, isBigHeadImg: Boolean): String {
        if (username.isNullOrBlank()) return ""
        return WeChatApis.contact().contacts()?.getAvatarUrl(username, isBigHeadImg).orEmpty()
    }

    fun sendText(talker: String?, content: String?): Boolean {
        if (talker.isNullOrBlank() || content.isNullOrBlank()) return false
        val sender = WeChatApis.message().sender() ?: return false
        val parsed = parseAtContent(talker, content)
        return when {
            parsed == null -> sender.sendText(talker, content)
            parsed.atList.isEmpty() -> sender.sendText(talker, parsed.content)
            else -> sender.sendTextWithAtList(talker, parsed.content, parsed.atList)
        }
    }

    fun sendText(talker: String?, content: String?, callback: Consumer<Any?>?) {
        async(callback) { completion ->
            val ok = runCatching { sendText(talker, content) }.getOrDefault(false)
            completion.complete { it.accept(if (ok) java.lang.Long.valueOf(0L) else null) }
        }
    }

    fun sendQuoteMsg(talker: String?, msgId: Long, content: String?): Boolean {
        if (talker.isNullOrBlank() || msgId <= 0L) return false
        val sender = WeChatApis.message().sender() ?: return false
        return sender.sendQuote(talker, msgId, content)
    }

    fun sendQuoteMsg(talker: String?, content: String?, msgId: Long): Boolean {
        return sendQuoteMsg(talker, msgId, content)
    }

    fun revokeMsg(msgId: Long): Boolean {
        if (msgId <= 0L) return false
        val sender = WeChatApis.message().sender() ?: return false
        return sender.revoke(msgId)
    }

    fun uploadDeviceStep(step: Long): Boolean {
        if (step <= 0L) return false
        val sender = WeChatApis.message().sender() ?: return false
        return sender.uploadDeviceStep(step)
    }

    fun getSnsPostList(): List<WeChatSnsPost> = getSnsPostList(50)

    fun getSnsPostList(limit: Int): List<WeChatSnsPost> {
        return WeChatApis.interaction().sns()?.getSnsPostList(limit) ?: emptyList()
    }

    fun getSnsPostList(userName: String?, limit: Int): List<WeChatSnsPost> {
        return WeChatApis.interaction().sns()?.getSnsPostList(userName, limit) ?: emptyList()
    }

    fun getSnsPost(snsId: String?): WeChatSnsPost? {
        return WeChatApis.interaction().sns()?.getSnsPost(snsId)
    }

    fun publishSnsPost(prepared: Any?): Boolean {
        return WeChatApis.interaction().sns()
            ?.publishSnsPost(prepared as? WeChatSnsPrepareResult) == true
    }

    fun refreshSnsTimeline(): Boolean {
        return WeChatApis.interaction().sns()?.refreshTimeline() == true
    }

    fun uploadText(content: String?): Boolean =
        WeChatApis.interaction().sns()?.uploadText(content) == true

    fun uploadText(content: String?, sdkId: String?, sdkAppName: String?): Boolean =
        WeChatApis.interaction().sns()?.uploadText(content, sdkId, sdkAppName) == true

    fun uploadText(jsonObj: JSONObject?): Boolean =
        WeChatApis.interaction().sns()?.uploadText(jsonObj) == true

    fun uploadTextAndPicList(content: String?, picPath: String?): Boolean =
        WeChatApis.interaction().sns()?.uploadTextAndPicList(content, picPath) == true

    fun uploadTextAndPicList(content: String?, picPath: String?, sdkId: String?, sdkAppName: String?): Boolean =
        WeChatApis.interaction().sns()?.uploadTextAndPicList(content, picPath, sdkId, sdkAppName) == true

    fun uploadTextAndPicList(content: String?, picPathList: List<*>?): Boolean =
        WeChatApis.interaction().sns()?.uploadTextAndPicList(content, picPathList) == true

    fun uploadTextAndPicList(content: String?, picPathList: List<*>?, sdkId: String?, sdkAppName: String?): Boolean =
        WeChatApis.interaction().sns()?.uploadTextAndPicList(content, picPathList, sdkId, sdkAppName) == true

    fun uploadTextAndPicList(jsonObj: JSONObject?): Boolean =
        WeChatApis.interaction().sns()?.uploadTextAndPicList(jsonObj) == true

    fun uploadLivePhoto(livePhotoPath: String?): Boolean =
        WeChatApis.interaction().sns()?.uploadLivePhoto(livePhotoPath) == true

    fun uploadLivePhoto(imagePath: String?, videoPath: String?): Boolean =
        WeChatApis.interaction().sns()?.uploadLivePhoto(imagePath, videoPath) == true

    fun uploadLivePhoto(jsonObj: JSONObject?): Boolean =
        WeChatApis.interaction().sns()?.uploadLivePhoto(jsonObj) == true

    fun uploadLivePhotoList(livePhotoList: List<*>?): Boolean =
        WeChatApis.interaction().sns()?.uploadLivePhotoList(livePhotoList) == true

    fun uploadLivePhotoList(jsonObj: JSONObject?): Boolean =
        WeChatApis.interaction().sns()?.uploadLivePhotoList(jsonObj) == true

    fun uploadTextAndLivePhoto(content: String?, livePhotoPath: String?): Boolean =
        WeChatApis.interaction().sns()?.uploadTextAndLivePhoto(content, livePhotoPath) == true

    fun uploadTextAndLivePhoto(
        content: String?,
        livePhotoPath: String?,
        sdkId: String?,
        sdkAppName: String?
    ): Boolean = WeChatApis.interaction().sns()?.uploadTextAndLivePhoto(
        content,
        livePhotoPath,
        sdkId,
        sdkAppName
    ) == true

    fun uploadTextAndLivePhoto(content: String?, imagePath: String?, videoPath: String?): Boolean =
        WeChatApis.interaction().sns()
            ?.uploadTextAndLivePhoto(content, imagePath, videoPath) == true

    fun uploadTextAndLivePhoto(
        content: String?,
        imagePath: String?,
        videoPath: String?,
        sdkId: String?,
        sdkAppName: String?
    ): Boolean = WeChatApis.interaction().sns()?.uploadTextAndLivePhoto(
        content,
        imagePath,
        videoPath,
        sdkId,
        sdkAppName
    ) == true

    fun uploadTextAndLivePhoto(jsonObj: JSONObject?): Boolean =
        WeChatApis.interaction().sns()?.uploadTextAndLivePhoto(jsonObj) == true

    fun uploadTextAndLivePhotoList(content: String?, livePhotoList: List<*>?): Boolean =
        WeChatApis.interaction().sns()?.uploadTextAndLivePhotoList(content, livePhotoList) == true

    fun uploadTextAndLivePhotoList(
        content: String?,
        livePhotoList: List<*>?,
        sdkId: String?,
        sdkAppName: String?
    ): Boolean = WeChatApis.interaction().sns()?.uploadTextAndLivePhotoList(
        content,
        livePhotoList,
        sdkId,
        sdkAppName
    ) == true

    fun uploadTextAndLivePhotoList(jsonObj: JSONObject?): Boolean =
        WeChatApis.interaction().sns()?.uploadTextAndLivePhotoList(jsonObj) == true

    fun uploadVideo(videoPath: String?): Boolean =
        WeChatApis.interaction().sns()?.uploadVideo(videoPath) == true

    fun uploadVideo(jsonObj: JSONObject?): Boolean =
        WeChatApis.interaction().sns()?.uploadVideo(jsonObj) == true

    fun uploadTextAndVideo(content: String?, videoPath: String?): Boolean =
        WeChatApis.interaction().sns()?.uploadTextAndVideo(content, videoPath) == true

    fun uploadTextAndVideo(content: String?, videoPath: String?, sdkId: String?, sdkAppName: String?): Boolean =
        WeChatApis.interaction().sns()?.uploadTextAndVideo(content, videoPath, sdkId, sdkAppName) == true

    fun uploadTextAndVideo(jsonObj: JSONObject?): Boolean =
        WeChatApis.interaction().sns()?.uploadTextAndVideo(jsonObj) == true

    fun sendPat(talker: String?, pattedUser: String?): Boolean {
        if (talker.isNullOrBlank() || pattedUser.isNullOrBlank()) return false
        val sender = WeChatApis.message().sender() ?: return false
        return sender.sendPat(talker, pattedUser)
    }

    fun sendShareCard(talker: String?, wxid: String?): Boolean {
        if (talker.isNullOrBlank() || wxid.isNullOrBlank()) return false
        val sender = WeChatApis.message().sender() ?: return false
        return sender.sendShareCard(talker, wxid)
    }

    fun sendImage(talker: String?, sendPath: String?): Boolean {
        return sendMedia { it.sendImage(talker.orEmpty(), sendPath.orEmpty()) }
    }

    fun sendImage(talker: String?, sendPath: String?, appId: String?): Boolean {
        return sendMedia { it.sendImage(talker.orEmpty(), sendPath.orEmpty(), appId.orEmpty()) }
    }

    fun sendOriginalImage(talker: String?, sendPath: String?): Boolean {
        return sendMedia { it.sendOriginalImage(talker.orEmpty(), sendPath.orEmpty()) }
    }

    fun sendVoice(talker: String?, sendPath: String?): Boolean {
        return sendMedia { it.sendVoice(talker.orEmpty(), sendPath.orEmpty()) }
    }

    fun sendVoice(talker: String?, sendPath: String?, durationSeconds: Int): Boolean {
        return sendMedia {
            val durationMillis = durationSeconds
                .coerceIn(0, Int.MAX_VALUE / 1000) * 1000
            it.sendVoice(talker.orEmpty(), sendPath.orEmpty(), durationMillis)
        }
    }

    fun sendVideo(talker: String?, sendPath: String?): Boolean {
        return sendMedia { it.videos().send(talker.orEmpty(), sendPath.orEmpty()) }
    }

    fun sendEmoji(talker: String?, sendPath: String?): Boolean {
        return sendMedia { it.sendEmoji(talker.orEmpty(), sendPath.orEmpty()) }
    }

    fun sendFile(talker: String?, sendPath: String?): Boolean {
        return sendMedia { it.sendFile(talker.orEmpty(), sendPath.orEmpty()) }
    }

    fun sendFile(talker: String?, sendPath: String?, title: String?): Boolean {
        return sendMedia { it.sendFile(talker.orEmpty(), sendPath.orEmpty(), title.orEmpty()) }
    }

    fun getFavoriteList(limit: Int): List<Map<String, Any?>> {
        val favoriteApi = WeChatApis.interaction().media()?.favorites() ?: return emptyList()
        return runCatching {
            favoriteApi.listRecent(limit).map { favoriteItemMap(it) }
        }.getOrDefault(emptyList())
    }

    fun getFavorite(localId: Long): Map<String, Any?>? {
        if (localId <= 0L) return null
        val favoriteApi = WeChatApis.interaction().media()?.favorites() ?: return null
        return runCatching {
            favoriteApi.get(localId)?.let { favoriteItemMap(it) }
        }.getOrNull()
    }

    fun sendFavorite(talker: String?, localId: Long): Boolean {
        if (talker.isNullOrBlank() || localId <= 0L) return false
        return sendMedia { it.sendFavorite(talker, localId) }
    }

    fun sendFavorite(talker: String?, localId: String?): Boolean {
        if (talker.isNullOrBlank() || localId.isNullOrBlank()) return false
        return sendMedia { it.sendFavorite(talker, localId) }
    }

    fun sendMediaMsg(talker: String?, mediaMessage: Any?, appId: String?): Boolean {
        if (talker.isNullOrBlank() || mediaMessage == null) return false
        return sendMedia { it.sendMediaMessage(talker, mediaMessage, appId.orEmpty()) }
    }

    fun shareFile(talker: String?, title: String?, filePath: String?, appId: String?): Boolean {
        if (talker.isNullOrBlank() || filePath.isNullOrBlank()) return false
        return sendMedia { it.shareFile(talker, title.orEmpty(), filePath, appId.orEmpty()) }
    }

    fun shareMiniProgram(
        talker: String?,
        title: String?,
        description: String?,
        userName: String?,
        path: String?,
        thumbData: ByteArray?,
        appId: String?
    ): Boolean {
        if (talker.isNullOrBlank() || userName.isNullOrBlank()) return false
        return sendMedia {
            it.shareMiniProgram(
                talker,
                title.orEmpty(),
                description.orEmpty(),
                userName,
                path.orEmpty(),
                thumbData,
                appId.orEmpty()
            )
        }
    }

    fun sendAppBrandMsg(talker: String?, title: String?, pagePath: String?, ghName: String?): Boolean {
        return shareMiniProgram(talker, title, "", ghName, pagePath, null, "")
    }

    fun shareMusic(
        talker: String?,
        title: String?,
        description: String?,
        musicUrl: String?,
        musicDataUrl: String?,
        thumbData: ByteArray?,
        appId: String?
    ): Boolean {
        if (talker.isNullOrBlank() || musicUrl.isNullOrBlank() || musicDataUrl.isNullOrBlank()) return false
        return sendMedia {
            it.shareMusic(
                talker,
                title.orEmpty(),
                description.orEmpty(),
                musicUrl,
                musicDataUrl,
                thumbData,
                appId.orEmpty()
            )
        }
    }

    fun shareMusicVideo(
        talker: String?,
        title: String?,
        description: String?,
        musicUrl: String?,
        musicDataUrl: String?,
        singerName: String?,
        duration: Int,
        songLyric: String?,
        thumbData: ByteArray?,
        appId: String?
    ): Boolean {
        if (talker.isNullOrBlank() || musicUrl.isNullOrBlank() || musicDataUrl.isNullOrBlank()) return false
        return sendMedia {
            it.shareMusicVideo(
                talker,
                title.orEmpty(),
                description.orEmpty(),
                musicUrl,
                musicDataUrl,
                singerName.orEmpty(),
                duration,
                songLyric.orEmpty(),
                thumbData,
                appId.orEmpty()
            )
        }
    }

    fun shareText(talker: String?, text: String?, appId: String?): Boolean {
        if (talker.isNullOrBlank() || text.isNullOrBlank()) return false
        return sendMedia { it.shareText(talker, text, appId.orEmpty()) }
    }

    fun shareVideo(
        talker: String?,
        title: String?,
        description: String?,
        videoUrl: String?,
        thumbData: ByteArray?,
        appId: String?
    ): Boolean {
        if (talker.isNullOrBlank() || videoUrl.isNullOrBlank()) return false
        return sendMedia {
            it.shareVideo(
                talker,
                title.orEmpty(),
                description.orEmpty(),
                videoUrl,
                thumbData,
                appId.orEmpty()
            )
        }
    }

    fun shareWebpage(
        talker: String?,
        title: String?,
        description: String?,
        webpageUrl: String?,
        thumbData: ByteArray?,
        appId: String?
    ): Boolean {
        if (talker.isNullOrBlank() || webpageUrl.isNullOrBlank()) return false
        return sendMedia {
            it.shareWebpage(
                talker,
                title.orEmpty(),
                description.orEmpty(),
                webpageUrl,
                thumbData,
                appId.orEmpty()
            )
        }
    }

    fun sendXmlMsg(talker: String?, content: String?): Boolean {
        if (talker.isNullOrBlank() || content.isNullOrBlank()) return false
        val sender = WeChatApis.message().sender() ?: return false
        return sender.sendXml(talker, content)
    }

    fun sendLocation(
        talker: String?,
        poiName: String?,
        label: String?,
        x: String?,
        y: String?,
        scale: String?
    ): Boolean {
        if (talker.isNullOrBlank() || x.isNullOrBlank() || y.isNullOrBlank()) return false
        val sender = WeChatApis.message().sender() ?: return false
        return sender.sendLocation(talker, poiName.orEmpty(), label.orEmpty(), x, y, scale.orEmpty())
    }

    fun sendLocation(talker: String?, jsonObj: JSONObject?): Boolean {
        if (jsonObj == null) return false
        return sendLocation(
            talker,
            jsonObj.optString("poiName"),
            jsonObj.optString("label"),
            jsonObj.optString("x"),
            jsonObj.optString("y"),
            jsonObj.optString("scale")
        )
    }

    fun insertSystemMsg(talker: String?, content: String?, createTime: Long): Long {
        return runCatching {
            WeChatApis.message().local()?.insertSystemMessage(talker, content, createTime) ?: 0L
        }.getOrDefault(0L)
    }

    fun queryHistoryMsg(talker: String?, startTime: Long, count: Int): List<ScriptMessageBean> {
        if (talker.isNullOrBlank()) return emptyList()
        return WeChatApis.message().store()
            ?.queryHistoryMsg(talker, startTime, count)
            ?.map { ScriptMessageBean(it) }
            ?: emptyList()
    }

    fun getUnreadCount(talker: String?): Int {
        val target = talker?.trim().orEmpty()
        if (target.isEmpty()) return 0
        return WeChatApis.message().conversations()?.getUnreadCount(target) ?: 0
    }

    fun deleteConversation(talker: String?): Boolean {
        val target = talker?.trim().orEmpty()
        if (target.isEmpty()) return false
        return WeChatApis.message().conversations()?.deleteConversation(target) ?: false
    }

    fun getAllUnreadCount(): Int {
        return WeChatApis.message().conversations()?.getTotalUnreadCount() ?: 0
    }

    fun clearUnread(talker: String?): Boolean {
        if (talker.isNullOrBlank()) return false
        return QuickMarkReadRuntime.markConversationRead(bridge.hostContext, talker, false)
    }

    fun clearAllUnread(): Boolean {
        if (QuickMarkReadRuntime.markAllRead(bridge.hostContext, false) < 0) return false
        return WeChatApis.message().conversations()?.getTotalUnreadCount() == 0
    }

    fun delay(millis: Long, action: Runnable?) = delays.delay(millis, action)

    internal fun dispose() {
        asyncScope.dispose()
        delays.dispose()
        synchronized(httpClients) {
            httpClients.clear()
        }
        runCatching { httpBaseClient.dispatcher.cancelAll() }
        runCatching { httpBaseClient.dispatcher.executorService.shutdownNow() }
        httpBaseClient.connectionPool.evictAll()
        currentPluginName = null
        currentPluginDir = null
    }

    fun notify(title: String?, text: String?) {
        WeChatApis.interaction().notifier()?.sendNotice(
            title.orEmpty(),
            text.orEmpty(),
            text.orEmpty(),
            true,
            false
        )
    }

    fun getDuration(filePath: String?): Long {
        if (filePath.isNullOrBlank()) return 0L
        val file = File(filePath)
        if (!file.isFile) return 0L
        runCatching {
            durationCodec.getDuration(file.absolutePath)
        }.getOrDefault(0L).takeIf { it > 0L }?.let { return it }
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
        }.onFailure {
            bridge.log("读取音频时长失败: ${it.message}")
        }.getOrDefault(0L).also {
            runCatching { retriever.release() }
        }
    }

    fun get(url: String?, headerMap: Map<*, *>?, callback: Consumer<String?>?) {
        get(url, headerMap, 30L, callback)
    }

    fun get(url: String?, headerMap: Map<*, *>?, timeoutSeconds: Long, callback: Consumer<String?>?) {
        async(callback) { completion ->
            val result = httpText("GET", url, null, headerMap, timeoutSeconds)
            completion.complete { it.accept(result) }
        }
    }

    fun post(
        url: String?,
        paramMap: Map<*, *>?,
        headerMap: Map<*, *>?,
        callback: Consumer<String?>?
    ) {
        post(url, paramMap, headerMap, 30L, callback)
    }

    fun post(
        url: String?,
        paramMap: Map<*, *>?,
        headerMap: Map<*, *>?,
        timeoutSeconds: Long,
        callback: Consumer<String?>?
    ) {
        async(callback) { completion ->
            val result = httpText("POST", url, paramMap, headerMap, timeoutSeconds)
            completion.complete { it.accept(result) }
        }
    }

    fun download(url: String?, path: String?, headerMap: Map<*, *>?, callback: Consumer<File?>?) {
        download(url, path, headerMap, 30L, callback)
    }

    fun download(
        url: String?,
        path: String?,
        headerMap: Map<*, *>?,
        timeoutSeconds: Long,
        callback: Consumer<File?>?
    ) {
        async(callback) { completion ->
            val result = downloadFile(url, path, headerMap, timeoutSeconds)
            completion.complete { it.accept(result) }
        }
    }

    fun downloadImage(url: String?, callback: Consumer<File?>?) {
        async(callback) { completion ->
            val result = HchatMediaDownloader.downloadImage(bridge.hostContext, url)
            completion.complete { it.accept(result) }
        }
    }

    fun downloadImage(url: String?, fileName: String?, callback: Consumer<File?>?) {
        async(callback) { completion ->
            val result = HchatMediaDownloader.downloadImage(bridge.hostContext, url, fileName)
            completion.complete { it.accept(result) }
        }
    }

    fun downloadImg(md5: String?, cdnUrl: String?, aesKey: String?, savePath: String?) {
        downloadImgInternal(md5, cdnUrl, aesKey, savePath, 2)
    }

    fun downloadImg(
        md5: String?,
        cdnUrl: String?,
        aesKey: String?,
        savePath: String?,
        callback: PluginCallBack.DownloadCallback?
    ) {
        async(callback) { completion ->
            val file = downloadImgInternal(md5, cdnUrl, aesKey, savePath, 2)
            if (file != null && file.isFile && file.length() > 0L) {
                completion.complete { it.onSuccess(file) }
            } else {
                completion.complete { it.onError(Exception("Image download failed")) }
            }
        }
    }

    fun downloadImg(imageMsg: Any?, savePath: String?) {
        val request = imageDownloadRequest(imageMsg) ?: return
        downloadImgInternal(
            request.md5,
            request.url,
            request.aesKey,
            savePath,
            request.fileType,
            request.totalLen
        )
    }

    fun downloadImg(
        imageMsg: Any?,
        savePath: String?,
        callback: PluginCallBack.DownloadCallback?
    ) {
        async(callback) { completion ->
            val request = imageDownloadRequest(imageMsg)
            if (request == null) {
                completion.complete { it.onError(IllegalArgumentException("Invalid image message")) }
                return@async
            }
            val file = downloadImgInternal(
                request.md5,
                request.url,
                request.aesKey,
                savePath,
                request.fileType,
                request.totalLen
            )
            if (file != null && file.isFile && file.length() > 0L) {
                completion.complete { it.onSuccess(file) }
            } else {
                completion.complete { it.onError(Exception("Image download failed")) }
            }
        }
    }

    fun downloadVideo(
        md5: String?,
        cdnUrl: String?,
        aesKey: String?,
        savePath: String?,
        callback: PluginCallBack.DownloadCallback?
    ) {
        async(callback) { completion ->
            downloadVideoInternal(
                VideoDownloadRequest(
                    md5 = md5.orEmpty(),
                    url = cdnUrl.orEmpty(),
                    aesKey = aesKey.orEmpty(),
                    expectedLength = 0L,
                    localFile = null
                ),
                savePath,
                completion
            )
        }
    }

    fun downloadVideo(
        videoMessage: Any?,
        savePath: String?,
        callback: PluginCallBack.DownloadCallback?
    ) {
        async(callback) { completion ->
            val request = videoDownloadRequest(videoMessage)
            if (request == null) {
                completion.complete { it.onError(IllegalArgumentException("Invalid video message")) }
                return@async
            }
            downloadVideoInternal(request, savePath, completion)
        }
    }

    internal fun downloadVideoForListener(videoMessage: Any?, savePath: String?): File? {
        val deadline = System.currentTimeMillis() + VIDEO_METADATA_WAIT_MS
        var request = videoDownloadRequest(videoMessage, listenerMode = true)
        while (request == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(VIDEO_METADATA_RETRY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            request = videoDownloadRequest(videoMessage, listenerMode = true)
        }
        val resolvedRequest = request ?: return null
        val completed = CountDownLatch(1)
        var downloaded: File? = null
        downloadVideoInternal(
            resolvedRequest,
            savePath,
            asyncScope.callback(object : PluginCallBack.DownloadCallback {
                override fun onSuccess(file: File) {
                    downloaded = file
                    completed.countDown()
                }

                override fun onError(error: Exception) {
                    completed.countDown()
                }
            }) ?: return null
        )
        try {
            completed.await(VIDEO_DOWNLOAD_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return downloaded?.takeIf { it.isFile && it.length() > 0L }
    }

    fun downloadFinderMedia(
        finderFeedOrMessage: Any?,
        savePath: String?,
        callback: PluginCallBack.DownloadCallback?
    ) {
        downloadFinderMedia(finderFeedOrMessage, 0, savePath, callback)
    }

    fun downloadFinderMedia(
        finderFeedOrMessage: Any?,
        mediaIndex: Int,
        savePath: String?,
        callback: PluginCallBack.DownloadCallback?
    ) {
        async(callback) { completion ->
            runCatching {
                val media = FinderMediaDownloadSupport.extractMedia(finderFeedOrMessage)
                    ?: throw IllegalArgumentException("Invalid Finder feed or media message")
                if (media.type != FinderMediaDownloadSupport.MEDIA_TYPE_IMAGE &&
                    media.type != FinderMediaDownloadSupport.MEDIA_TYPE_VIDEO
                ) {
                    throw IllegalArgumentException("Unsupported Finder media type: ${media.type}")
                }
                if (mediaIndex !in media.items.indices) {
                    throw IndexOutOfBoundsException(
                        "Finder media index $mediaIndex is outside 0..${media.items.lastIndex}"
                    )
                }
                FinderMediaDownloadSupport.downloadItem(
                    bridge.hostContext,
                    media,
                    mediaIndex,
                    savePath
                ) ?: throw IllegalStateException("Finder media download failed")
            }.onSuccess { file ->
                completion.complete { it.onSuccess(file) }
            }.onFailure { error ->
                val exception = error as? Exception
                    ?: RuntimeException("Finder media download failed", error)
                completion.complete { it.onError(exception) }
            }
        }
    }

    private data class ImageDownloadRequest(
        val md5: String,
        val url: String,
        val aesKey: String,
        val fileType: Int,
        val totalLen: Int
    )

    private data class VideoDownloadRequest(
        val md5: String,
        val url: String,
        val aesKey: String,
        val expectedLength: Long,
        val localFile: File?
    )

    private fun downloadImgInternal(
        md5: String?,
        cdnUrl: String?,
        aesKey: String?,
        savePath: String?,
        fileType: Int,
        totalLen: Int = 0
    ): File? {
        return runCatching {
            val url = normalizeDownloadUrl(cdnUrl).takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val target = targetFile(savePath, md5, url)
            if (isHttpUrl(url)) {
                val file = HchatMediaDownloader.downloadToFileAtomically(url, target)
                if (file == null || !file.isFile || file.length() <= 0L) {
                    logDownload("downloadImg失败: ${url.take(120)} -> ${target.absolutePath}")
                }
                return@runCatching file?.takeIf { it.isFile && it.length() > 0L }
            }

            val media = WeChatApis.media()?.images()
            if (media == null) {
                logDownload("downloadImg失败: 图片API未就绪 -> ${target.absolutePath}")
                return@runCatching null
            }
            if (target.exists() && !target.delete()) {
                logDownload("downloadImg失败: 无法清理旧文件 -> ${target.absolutePath}")
                return@runCatching null
            }
            val submitted = media.downloadCdn(
                md5.orEmpty(),
                url,
                aesKey.orEmpty(),
                target.absolutePath,
                fileType,
                totalLen
            )
            if (!submitted) {
                logDownload(
                    "downloadImg失败: CDN任务提交失败 fileType=$fileType totalLen=$totalLen " +
                        "${media.cdnDiagnostics()} url=${url.take(120)} -> ${target.absolutePath}"
                )
                return@runCatching null
            }
            if (!waitDownloadedFile(target, 60_000L)) {
                logDownload(
                    "downloadImg失败: CDN下载超时 fileType=$fileType totalLen=$totalLen " +
                        "${media.cdnDiagnostics()} url=${url.take(120)} -> ${target.absolutePath}"
                )
                null
            } else {
                target
            }
        }.onFailure {
            logDownload("downloadImg异常: ${it.javaClass.name} ${it.message}")
        }.getOrNull()
    }

    private fun imageDownloadRequest(imageMsg: Any?): ImageDownloadRequest? {
        if (imageMsg == null) return null
        val bigUrl = firstNotBlank(
            callString(imageMsg, "getBigImgUrl"),
            fieldString(imageMsg, "bigImgUrl")
        )
        val midUrl = firstNotBlank(
            callString(imageMsg, "getMidImgUrl"),
            fieldString(imageMsg, "midImgUrl")
        )
        val thumbUrl = firstNotBlank(
            callString(imageMsg, "getThumbUrl"),
            fieldString(imageMsg, "thumbUrl")
        )
        val url = firstNotBlank(bigUrl, midUrl, thumbUrl)
        if (url.isBlank()) return null
        val fileType = if (bigUrl.isNotBlank()) 1 else 2
        val totalLen = when {
            bigUrl.isNotBlank() -> firstPositiveInt(
                callAny(imageMsg, "getBigLength"),
                fieldAny(imageMsg, "bigLength")
            )
            midUrl.isNotBlank() -> firstPositiveInt(
                callAny(imageMsg, "getMidLength"),
                fieldAny(imageMsg, "midLength")
            )
            else -> firstPositiveInt(
                callAny(imageMsg, "getThumbLength"),
                fieldAny(imageMsg, "thumbLength")
            )
        }
        val md5 = firstNotBlank(
            callString(imageMsg, "getMd5"),
            fieldString(imageMsg, "md5")
        )
        val key = firstNotBlank(
            callString(imageMsg, "getKey"),
            callString(imageMsg, "getAesKey"),
            fieldString(imageMsg, "key"),
            fieldString(imageMsg, "aesKey")
        )
        return ImageDownloadRequest(md5, url, key, fileType, totalLen)
    }

    private fun videoDownloadRequest(
        source: Any?,
        listenerMode: Boolean = false
    ): VideoDownloadRequest? {
        if (source == null) return null
        val message = when (source) {
            is ScriptMessageBean -> source.getMessage() as? WeChatMessage
            is WeChatMessage -> source
            else -> null
        }
        val videoMsg = when (source) {
            is ScriptMessageBean -> source.getVideoMsg()
            is WeChatMessage -> source.getVideoMsg()
            else -> source
        }
        val videoApi = WeChatApis.media()?.videos()
        val pathToken = firstNotBlank(
            message?.imagePath,
            callString(source, "getImagePath"),
            fieldString(source, "imagePath")
        )
        val nativeInfo = if (pathToken.isNotBlank()) {
            videoApi?.resolveDownloadInfo(pathToken)
        } else {
            null
        }
        val expectedLength = firstPositiveLong(
            nativeInfo?.totalLength,
            videoMsg?.let { callAny(it, "getLength") },
            videoMsg?.let { fieldAny(it, "length") }
        )
        val allowUnverifiedLocal = !listenerMode || when (source) {
            is ScriptMessageBean -> source.isSend()
            is WeChatMessage -> source.isSend()
            else -> false
        }
        val nativeLocalComplete = expectedLength > 0L &&
            (nativeInfo?.currentLength ?: 0L) >= expectedLength
        val canResolveLocal = allowUnverifiedLocal || nativeLocalComplete
        val localFile = firstNotBlank(
            pathToken.takeIf { canResolveLocal && File(it).isFile },
            pathToken.takeIf { canResolveLocal && it.isNotBlank() }
                ?.let { videoApi?.resolvePathToken(it) }
        ).takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { file ->
                file.isFile && file.length() > 0L &&
                    (expectedLength <= 0L || file.length() >= expectedLength)
            }
        val md5 = firstNotBlank(
            nativeInfo?.md5,
            videoMsg?.let { callString(it, "getNewMd5") },
            videoMsg?.let { callString(it, "getMd5") },
            videoMsg?.let { fieldString(it, "newMd5") },
            videoMsg?.let { fieldString(it, "md5") }
        )
        val url = firstNotBlank(
            nativeInfo?.cdnUrl,
            videoMsg?.let { callString(it, "getCdnVideoUrl") },
            videoMsg?.let { callString(it, "getCdnUrl") },
            videoMsg?.let { fieldString(it, "cdnVideoUrl") },
            videoMsg?.let { fieldString(it, "cdnUrl") }
        )
        val aesKey = firstNotBlank(
            nativeInfo?.aesKey,
            videoMsg?.let { callString(it, "getAesKey") },
            videoMsg?.let { fieldString(it, "aesKey") }
        )
        if (localFile == null && url.isBlank()) return null
        return VideoDownloadRequest(md5, url, aesKey, expectedLength, localFile)
    }

    private fun downloadVideoInternal(
        request: VideoDownloadRequest,
        savePath: String?,
        completion: ScriptAsyncScope.Callback<PluginCallBack.DownloadCallback>
    ) {
        val success: (File) -> Unit = { file -> completion.complete { it.onSuccess(file) } }
        val failure: (Exception) -> Unit = { error -> completion.complete { it.onError(error) } }
        val target = videoTargetFile(savePath, request.md5)
        target.parentFile?.let { if (!it.isDirectory) it.mkdirs() }

        request.localFile?.let { source ->
            val file = runCatching {
                if (source.canonicalPath == target.canonicalPath) source else source.copyTo(target, overwrite = true)
            }.onFailure {
                if (source.absolutePath != target.absolutePath) target.delete()
            }.getOrNull()
            if (file != null && file.isFile && file.length() > 0L) {
                success(file)
            } else {
                failure(Exception("Video copy failed"))
            }
            return
        }

        val url = normalizeDownloadUrl(request.url)
        if (url.isBlank()) {
            failure(IllegalArgumentException("Video download URL is empty"))
            return
        }
        if (isHttpUrl(url) && request.aesKey.isBlank()) {
            val file = HchatMediaDownloader.downloadToFileAtomically(url, target)
            if (file != null && file.isFile && file.length() > 0L &&
                (request.expectedLength <= 0L || file.length() >= request.expectedLength)
            ) {
                success(file)
            } else {
                file?.delete()
                failure(Exception("Video download failed"))
            }
            return
        }

        val videoApi = WeChatApis.media()?.videos()
        if (videoApi == null) {
            failure(IllegalStateException("Video API is not ready"))
            return
        }
        if (target.exists() && !target.delete()) {
            failure(IllegalStateException("Unable to replace existing video file"))
            return
        }
        completion.onTimeout(VIDEO_DOWNLOAD_TIMEOUT_MS) {
            failure(Exception("Video download timed out"))
        }
        val submitted = videoApi.downloadCdn(
            request.md5,
            url,
            request.aesKey,
            target.absolutePath,
            object : h.Hchat.hooks.api.media.WeChatVideoApi.DownloadCallback {
                override fun onSuccess(file: File) {
                    if (request.expectedLength > 0L && file.length() < request.expectedLength) {
                        file.delete()
                        failure(Exception("Video download is incomplete"))
                    } else {
                        success(file)
                    }
                }

                override fun onError(message: String) {
                    failure(Exception(message.ifBlank { "Video download failed" }))
                }
            }
        )
        if (!submitted) {
            failure(Exception("Video download task submission failed"))
        }
    }

    private fun isHttpUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun waitDownloadedFile(file: File, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(1000L)
        var lastLength = -1L
        var stableCount = 0
        while (System.currentTimeMillis() < deadline) {
            val length = if (file.isFile) file.length() else -1L
            if (length > 0L && length == lastLength) {
                stableCount++
                if (stableCount >= 2) return true
            } else {
                stableCount = 0
                lastLength = length
            }
            try {
                Thread.sleep(500L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return file.isFile && file.length() > 0L
    }

    private fun logDownload(message: String) {
        val pluginDir = currentPluginDir
        if (pluginDir != null) {
            bridge.log(currentPluginName, pluginDir, message)
        } else {
            bridge.log(message)
        }
    }

    private fun normalizeDownloadUrl(raw: String?): String {
        var url = raw?.trim().orEmpty()
        if (url.isBlank()) return ""
        val matcher = atPattern.matcher(url)
        if (matcher.find()) {
            url = matcher.group(1)?.trim().orEmpty()
        }
        return unescapeXmlText(url).trim()
    }

    private fun unescapeXmlText(value: String): String {
        if (value.isBlank()) return value
        val named = value
            .replace("&quot;", "\"")
            .replace("&#x20;", " ")
            .replace("&#x0A;", "\n")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
        return Regex("&#(x[0-9a-fA-F]+|[0-9]+);").replace(named) { match ->
            val raw = match.groupValues[1]
            val codePoint = runCatching {
                if (raw.startsWith("x", ignoreCase = true)) raw.substring(1).toInt(16) else raw.toInt()
            }.getOrNull() ?: return@replace match.value
            runCatching { String(Character.toChars(codePoint)) }.getOrDefault(match.value)
        }
    }

    fun downloadImages(urlList: List<*>?, callback: Consumer<List<File>>?) {
        async(callback) { completion ->
            val result = HchatMediaDownloader.downloadImages(bridge.hostContext, urlList)
            completion.complete { it.accept(result) }
        }
    }

    fun downloadImages(urlList: List<*>?, prefix: String?, callback: Consumer<List<File>>?) {
        async(callback) { completion ->
            val result = HchatMediaDownloader.downloadImages(bridge.hostContext, urlList, prefix)
            completion.complete { it.accept(result) }
        }
    }

    private fun targetFile(savePath: String?, md5: String?, url: String): File {
        val path = savePath?.takeIf { it.isNotBlank() }
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (path.endsWith("/") || file.isDirectory) {
                val name = firstNotBlank(md5, "image_${System.currentTimeMillis()}") +
                    "." + extensionFromUrl(url)
                return File(file, name)
            }
            return file
        }
        val name = firstNotBlank(md5, "image_${System.currentTimeMillis()}") +
            "." + extensionFromUrl(url)
        return File(HchatMediaDownloader.hchatDir(bridge.hostContext, "Image"), name)
    }

    private fun videoTargetFile(savePath: String?, md5: String?): File {
        val name = firstNotBlank(md5, "video_${System.currentTimeMillis()}") + ".mp4"
        val path = savePath?.takeIf { it.isNotBlank() }
        if (!path.isNullOrBlank()) {
            val file = File(path)
            return if (path.endsWith("/") || file.isDirectory) File(file, name) else file
        }
        return File(HchatMediaDownloader.hchatDir(bridge.hostContext, "Video"), name)
    }

    private fun extensionFromUrl(url: String): String {
        val segment = runCatching { Uri.parse(url).lastPathSegment.orEmpty() }.getOrDefault("")
        return when (val ext = segment.substringAfterLast('.', "").lowercase(Locale.US)) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp" -> ext
            else -> "jpg"
        }
    }

    private fun callString(instance: Any, methodName: String): String {
        return runCatching {
            instance.javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
                ?.invoke(instance)
                ?.toString()
                .orEmpty()
        }.getOrDefault("")
    }

    private fun fieldString(instance: Any, fieldName: String): String {
        return runCatching {
            var current: Class<*>? = instance.javaClass
            while (current != null && current != Any::class.java) {
                current.declaredFields.firstOrNull { it.name == fieldName }?.let { field ->
                    field.isAccessible = true
                    return@runCatching field.get(instance)?.toString().orEmpty()
                }
                current = current.superclass
            }
            ""
        }.getOrDefault("")
    }

    private fun callAny(instance: Any, methodName: String): Any? {
        return runCatching {
            instance.javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
                ?.invoke(instance)
        }.getOrNull()
    }

    private fun fieldAny(instance: Any, fieldName: String): Any? {
        return runCatching {
            var current: Class<*>? = instance.javaClass
            while (current != null && current != Any::class.java) {
                current.declaredFields.firstOrNull { it.name == fieldName }?.let { field ->
                    field.isAccessible = true
                    return@runCatching field.get(instance)
                }
                current = current.superclass
            }
            null
        }.getOrNull()
    }

    private fun firstPositiveInt(vararg values: Any?): Int {
        for (value in values) {
            val intValue = when (value) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull() ?: 0
                else -> 0
            }
            if (intValue > 0) return intValue
        }
        return 0
    }

    private fun firstPositiveLong(vararg values: Any?): Long {
        for (value in values) {
            val longValue = when (value) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull() ?: 0L
                else -> 0L
            }
            if (longValue > 0L) return longValue
        }
        return 0L
    }

    private fun sendMedia(action: (WeChatMediaApi) -> Boolean): Boolean {
        val media = WeChatApis.interaction().media() ?: return false
        return runCatching { action(media) }.getOrDefault(false)
    }

    private fun favoriteItemMap(item: WeChatFavoriteItem): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        result["localId"] = item.localId
        result["id"] = item.localId
        result["type"] = item.type
        result["typeLabel"] = item.typeLabel()
        result["title"] = item.displayTitle()
        result["summary"] = item.displaySummary()
        result["rawTitle"] = item.title
        result["rawSummary"] = item.summary
        result["totalSizeBytes"] = item.totalSizeBytes
        result["updateTimeMillis"] = item.updateTimeMillis
        result["tags"] = item.tags
        return result
    }

    private fun <T : Any> async(callback: T?, block: (ScriptAsyncScope.Callback<T>) -> Unit) {
        val completion = asyncScope.callback(callback) ?: return
        if (!asyncScope.submit {
                try {
                    block(completion)
                } catch (error: Throwable) {
                    completion.cancel()
                    throw error
                }
            }
        ) {
            completion.cancel()
        }
    }

    private fun parseAtContent(talker: String, content: String): ParsedAtContent? {
        val contacts = WeChatApis.contact().contacts() ?: return null
        if (!contacts.isGroup(talker)) return null
        val matcher = atPattern.matcher(content)
        val atList = ArrayList<String>()
        val out = StringBuffer()
        while (matcher.find()) {
            val wxId = matcher.group(1)?.trim().orEmpty()
            if (wxId.isBlank()) {
                matcher.appendReplacement(out, "")
                continue
            }
            atList.add(wxId)
            val displayName = mentionDisplayName(contacts, talker, wxId)
            val replacement = MatcherCompat.quoteReplacement("@$displayName\u2005")
            matcher.appendReplacement(out, replacement)
        }
        matcher.appendTail(out)
        if (atList.isEmpty()) return null
        return ParsedAtContent(out.toString(), atList.distinct())
    }

    private fun mentionDisplayName(contacts: WeChatContactApi, talker: String, wxId: String): String {
        if (wxId == "notify@all") return "所有人"
        val contact = contacts.getContact(wxId)
        return firstNotBlank(
            contacts.getGroupMemberRoomDisplayName(talker, wxId),
            contact?.nickname,
            contact?.customWxId,
            wxId
        )
    }

    private fun httpText(
        method: String,
        url: String?,
        paramMap: Map<*, *>?,
        headerMap: Map<*, *>?,
        timeoutSeconds: Long
    ): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val headers = normalizeMap(headerMap)
            val requestBuilder = Request.Builder().url(url).applyHeaders(headers)
            if (method == "POST") {
                val contentType = defaultContentType(headers)
                val body = buildPostBody(paramMap, headers).toRequestBody(contentType.toMediaType())
                requestBuilder.post(body)
            } else {
                requestBuilder.get()
            }
            executeHttp(httpClient(timeoutSeconds).newCall(requestBuilder.build())) { response ->
                response.body?.string()
            }
        }.onFailure {
            bridge.log("HTTP $method 失败: ${it.message}")
        }.getOrNull()
    }

    private fun downloadFile(
        url: String?,
        path: String?,
        headerMap: Map<*, *>?,
        timeoutSeconds: Long
    ): File? {
        if (url.isNullOrBlank() || path.isNullOrBlank()) return null
        return runCatching {
            val target = resolveDownloadTarget(url, path) ?: return@runCatching null
            target.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            val request = Request.Builder()
                .url(url)
                .applyHeaders(normalizeMap(headerMap))
                .get()
                .build()
            executeHttp(httpClient(timeoutSeconds).newCall(request)) { response ->
                val body = response.body ?: throw java.io.IOException("下载响应为空")
                BufferedInputStream(body.byteStream()).use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
            }
            target
        }.onFailure {
            bridge.log("下载失败: ${it.message}")
        }.getOrNull()
    }

    private fun resolveDownloadTarget(url: String, path: String): File? {
        val raw = File(path)
        if (path.endsWith("/")) {
            return File(raw, guessFileName(url))
        }
        if (raw.isDirectory) {
            return File(raw, guessFileName(url))
        }
        if (!raw.exists() && raw.extension.isBlank()) {
            return File(raw, guessFileName(url))
        }
        return raw
    }

    private fun guessFileName(url: String): String {
        val fromUri = runCatching {
            Uri.parse(url).lastPathSegment
        }.getOrNull().orEmpty()
        return fromUri.takeIf { it.isNotBlank() } ?: "download_${System.currentTimeMillis()}"
    }

    private fun defaultContentType(headers: Map<String, String>): String {
        val contentType = headers.entries.firstOrNull {
            it.key.equals("Content-Type", ignoreCase = true)
        }?.value
        if (!contentType.isNullOrBlank()) return contentType
        return "application/x-www-form-urlencoded; charset=UTF-8"
    }

    private fun buildPostBody(paramMap: Map<*, *>?, headers: Map<String, String>): String {
        val params = normalizeMap(paramMap)
        if (params.isEmpty()) return ""
        val contentType = headers.entries.firstOrNull {
            it.key.equals("Content-Type", ignoreCase = true)
        }?.value.orEmpty().lowercase(Locale.US)
        if (contentType.contains("application/json")) {
            val json = JSONObject()
            for ((key, value) in params) json.put(key, value)
            return json.toString()
        }
        return params.entries.joinToString("&") { entry ->
            "${Uri.encode(entry.key)}=${Uri.encode(entry.value)}"
        }
    }

    private fun normalizeMap(input: Map<*, *>?): Map<String, String> {
        if (input.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for ((key, value) in input) {
            val nextKey = key?.toString()?.trim().orEmpty()
            if (nextKey.isBlank()) continue
            result[nextKey] = value?.toString().orEmpty()
        }
        return result
    }

    private fun Request.Builder.applyHeaders(headers: Map<String, String>): Request.Builder {
        for ((key, value) in headers) {
            if (key.equals("Content-Type", ignoreCase = true)) continue
            header(key, value)
        }
        return this
    }

    private fun httpClient(timeoutSeconds: Long): OkHttpClient {
        val timeout = timeoutSeconds.coerceAtLeast(1L).coerceAtMost(300L)
        return httpClients.getOrPut(timeout) {
            while (httpClients.size >= MAX_HTTP_CLIENTS) {
                val iterator = httpClients.entries.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
            httpBaseClient.newBuilder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(timeout, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    private fun <T> executeHttp(call: okhttp3.Call, action: (okhttp3.Response) -> T): T {
        val cancel = { call.cancel() }
        if (!asyncScope.registerCancellation(cancel)) {
            call.cancel()
            throw java.io.IOException("插件已卸载")
        }
        try {
            return call.execute().use(action)
        } finally {
            asyncScope.removeCancellation(cancel)
        }
    }

    private fun <K, V> MutableMap<K, V>.getOrPut(key: K, defaultValue: () -> V): V {
        synchronized(this) {
            val existing = this[key]
            if (existing != null) return existing
            val value = defaultValue()
            this[key] = value
            return value
        }
    }

    private fun firstNotBlank(vararg values: String?): String {
        for (value in values) {
            if (!TextUtils.isEmpty(value)) return value ?: ""
        }
        return ""
    }

    private fun groupDisplayName(roomId: String, name: String, remarkName: String): String {
        if (remarkName.isNotBlank() && remarkName != name) {
            return if (name.isNotBlank()) "$remarkName ($name)" else remarkName
        }
        return firstNotBlank(name, remarkName, roomId)
    }

    private data class ParsedAtContent(
        val content: String,
        val atList: List<String>
    )

    private object MatcherCompat {
        fun quoteReplacement(text: String): String {
            return java.util.regex.Matcher.quoteReplacement(text)
        }
    }
}
