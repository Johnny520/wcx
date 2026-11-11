package h.Hchat.hooks.items.conversationgroup

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.items.customfriendavatar.CustomFriendAvatarPicker
import h.Hchat.hooks.items.customfriendavatar.CustomFriendAvatarStore
import h.Hchat.hooks.items.messageblock.MessageBlockSettings
import h.Hchat.hooks.items.quickread.QuickMarkReadRuntime
import h.Hchat.ui.SettingsUI
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog
import h.Hchat.utils.HLog
import java.util.concurrent.atomic.AtomicBoolean

internal object ConversationGroupMenu {
    private const val TAG = "[Hchat:ConversationGroup]"
    private val main = Handler(Looper.getMainLooper())

    fun show(activity: Activity, groupId: String, onChanged: () -> Unit) {
        val group = group(activity, groupId) ?: return
        VoiceForwardMiuixDialog.showListChoices(
            activity = activity,
            title = group.name,
            summary = "",
            choices = listOf(
                "Hchat模块" to "打开 Hchat 设置",
                "所有消息标为已读" to "标记当前分组及子分组内全部会话",
                "批量删除消息" to "选择会话并清空聊天记录",
                "批量屏蔽消息" to "选择会话并屏蔽后续收到的消息",
                "消息免打扰" to "开启当前分组内会话的微信免打扰",
                "解除消息免打扰" to "关闭当前分组内会话的微信免打扰",
                "发送" to "批量发送文字、媒体、收藏、名片或 XML",
                "发送群聊邀请" to "选择群聊并邀请当前分组内好友",
                "添加" to "选择会话加入当前分组",
                "移出" to "将直属会话移回微信首页",
                "移至" to "将直属会话移到其他分组",
                "搜索" to "搜索当前分组及子分组内会话",
                "设置" to "设置当前分组的显示方式"
            ),
            onSelected = { index ->
                when (index) {
                    0 -> SettingsUI.show(activity)
                    1 -> markAllRead(activity, groupId, onChanged)
                    2 -> chooseAndClearMessages(activity, groupId, onChanged)
                    3 -> chooseMessageBlockAction(activity, groupId)
                    4 -> setDoNotDisturb(activity, groupId, true, onChanged)
                    5 -> setDoNotDisturb(activity, groupId, false, onChanged)
                    6 -> chooseSendType(activity, groupId)
                    7 -> sendChatroomInvitation(activity, groupId)
                    8 -> addConversations(activity, groupId, onChanged)
                    9 -> removeConversations(activity, groupId, onChanged)
                    10 -> moveConversations(activity, groupId, onChanged)
                    11 -> searchConversations(activity, groupId)
                    12 -> showSettings(activity, groupId, onChanged)
                }
            },
            onDismiss = {}
        )
    }

    private fun markAllRead(activity: Activity, groupId: String, onChanged: () -> Unit) {
        val context = activity.applicationContext
        val ids = allConversationIds(activity, groupId)
        if (ids.isEmpty()) {
            toast(activity, "当前分组没有会话")
            return
        }
        runBatch(
            activity,
            "所有消息标为已读",
            "正在标记 ${ids.size} 个会话...",
            task = { canceled ->
                var success = 0
                ids.forEach { talker ->
                    if (canceled.get()) return@forEach
                    if (QuickMarkReadRuntime.markConversationRead(context, talker, false)) success++
                }
                BatchResult(success, ids.size, "已读")
            },
            onComplete = { result ->
                onChanged()
                toastBatch(activity, result)
            }
        )
    }

    private fun chooseAndClearMessages(
        activity: Activity,
        groupId: String,
        onChanged: () -> Unit
    ) {
        val context = activity.applicationContext
        val ids = allConversationIds(activity, groupId)
        showConversationPicker(
            activity = activity,
            ids = ids,
            title = "批量删除消息",
            confirmText = "继续"
        ) { selected ->
            val selectedIds = selected.map { it.id }
            VoiceForwardMiuixDialog.showConfirm(
                activity = activity,
                title = "清空聊天记录",
                message = "将清空所选 ${selectedIds.size} 个会话的本地聊天记录，联系人、群聊和聊天分组归属不会删除。此操作无法撤销。",
                onResult = { confirmed ->
                    if (!confirmed) return@showConfirm
                    runBatch(
                        activity,
                        "批量删除消息",
                        "正在清空聊天记录...",
                        task = { canceled ->
                            val store = WeChatApis.messageStore()
                            val currentIds = allConversationIds(context, groupId).toSet()
                            val existingIds = WeChatApis.conversations()
                                ?.getRecentConversationUsernames(10000)
                                .orEmpty()
                                .toHashSet()
                            val targets = selectedIds.filter {
                                it in currentIds && it in existingIds &&
                                    !ConversationGroupRuntime.isVirtualTalker(it)
                            }
                            var submitted = 0
                            targets.chunked(CLEAR_BATCH_SIZE).forEach { batch ->
                                if (canceled.get()) return@forEach
                                if (store?.clearConversationMessages(batch) == true) {
                                    submitted += batch.size
                                }
                                if (!canceled.get()) Thread.sleep(BATCH_INTERVAL_MS)
                            }
                            BatchResult(submitted, selectedIds.size, "清理请求提交")
                        },
                        onComplete = { result ->
                            onChanged()
                            toastBatch(activity, result)
                        }
                    )
                },
                onDismiss = {}
            )
        }
    }

    private fun chooseMessageBlockAction(activity: Activity, groupId: String) {
        val ids = allConversationIds(activity, groupId)
        if (ids.isEmpty()) {
            toast(activity, "当前分组没有会话")
            return
        }
        val quickBlocked = MessageBlockSettings(activity).quickBlockedTalkers()
        val blockedCount = ids.count(quickBlocked::contains)
        VoiceForwardMiuixDialog.showListChoices(
            activity = activity,
            title = "批量屏蔽消息",
            summary = "当前分组及子分组共 ${ids.size} 个会话，已快捷屏蔽 $blockedCount 个",
            choices = listOf(
                "屏蔽所选会话" to "屏蔽后续收到的所有消息，不影响已有聊天记录",
                "解除所选会话" to "只解除聊天分组快捷屏蔽，保留原有规则"
            ),
            onSelected = { index ->
                when (index) {
                    0 -> chooseAndSetMessageBlocked(activity, ids, blocked = true)
                    1 -> chooseAndSetMessageBlocked(
                        activity,
                        ids.filter(quickBlocked::contains),
                        blocked = false
                    )
                }
            },
            onDismiss = {}
        )
    }

    private fun chooseAndSetMessageBlocked(
        activity: Activity,
        ids: List<String>,
        blocked: Boolean
    ) {
        val action = if (blocked) "屏蔽" else "解除屏蔽"
        if (ids.isEmpty()) {
            toast(activity, if (blocked) "当前分组没有会话" else "当前分组没有快捷屏蔽的会话")
            return
        }
        showConversationPicker(
            activity = activity,
            ids = ids,
            title = if (blocked) "选择要屏蔽的会话" else "选择要解除屏蔽的会话",
            confirmText = "继续"
        ) { selected ->
            VoiceForwardMiuixDialog.showConfirm(
                activity = activity,
                title = "$action ${selected.size} 个会话",
                message = if (blocked) {
                    "将屏蔽所选会话后续收到的所有消息，并自动启用屏蔽消息功能。已有聊天记录不受影响。"
                } else {
                    "将解除所选会话的聊天分组快捷屏蔽，原来单独设置的模板、排除和专属规则保持不变。"
                },
                onResult = { confirmed ->
                    if (!confirmed) return@showConfirm
                    val result = MessageBlockSettings(activity).setQuickBlockedTalkers(
                        selected.associate { it.id to it.label },
                        blocked
                    )
                    val message = when {
                        !result.success -> "批量${action}失败"
                        result.changed == 0 -> "所选会话无需更改"
                        blocked -> "已屏蔽 ${result.changed} 个会话的后续消息"
                        else -> "已解除 ${result.changed} 个会话的快捷屏蔽"
                    }
                    toast(activity, message)
                },
                onDismiss = {}
            )
        }
    }

    private fun setDoNotDisturb(
        activity: Activity,
        groupId: String,
        enabled: Boolean,
        onChanged: () -> Unit
    ) {
        val ids = allConversationIds(activity, groupId)
        if (ids.isEmpty()) {
            toast(activity, "当前分组没有会话")
            return
        }
        val action = if (enabled) "开启消息免打扰" else "解除消息免打扰"
        VoiceForwardMiuixDialog.showConfirm(
            activity = activity,
            title = action,
            message = "将对当前分组及子分组内 ${ids.size} 个会话执行此操作。",
            onResult = { confirmed ->
                if (!confirmed) return@showConfirm
                runBatch(
                    activity,
                    action,
                    "正在处理 ${ids.size} 个会话...",
                    task = { canceled ->
                        val api = WeChatApis.conversations()
                        var success = 0
                        ids.forEach { talker ->
                            if (canceled.get()) return@forEach
                            if (api?.setWechatDoNotDisturb(talker, enabled) == true) success++
                            if (!canceled.get()) Thread.sleep(BATCH_INTERVAL_MS)
                        }
                        BatchResult(success, ids.size, action)
                    },
                    onComplete = { result ->
                        onChanged()
                        toastBatch(activity, result)
                    }
                )
            },
            onDismiss = {}
        )
    }

    private fun chooseSendType(activity: Activity, groupId: String) {
        val ids = allConversationIds(activity, groupId)
        if (ids.isEmpty()) {
            toast(activity, "当前分组没有会话")
            return
        }
        VoiceForwardMiuixDialog.showListChoices(
            activity = activity,
            title = "发送",
            summary = "发送给当前分组及子分组内 ${ids.size} 个会话",
            choices = SendContentType.entries.map { it.title to it.summary },
            onSelected = { index ->
                when (val type = SendContentType.entries.getOrNull(index)) {
                    SendContentType.TEXT -> sendText(activity, ids)
                    SendContentType.IMAGE,
                    SendContentType.VOICE,
                    SendContentType.VIDEO,
                    SendContentType.EMOJI,
                    SendContentType.FILE -> chooseAndSendFile(activity, ids, type)
                    SendContentType.FAVORITE -> chooseAndSendFavorite(activity, ids)
                    SendContentType.CARD -> chooseAndSendCard(activity, ids)
                    SendContentType.XML -> sendXml(activity, ids)
                    null -> Unit
                }
            },
            onDismiss = {}
        )
    }

    private fun sendText(activity: Activity, ids: List<String>) {
        VoiceForwardMiuixDialog.showTextInput(
            activity = activity,
            title = "发送",
            summary = "发送给当前分组及子分组内 ${ids.size} 个会话",
            placeholder = "输入要发送的文字",
            maxLength = 5000,
            onConfirm = { content ->
                runBatch(
                    activity,
                    "发送",
                    "正在发送到 ${ids.size} 个会话...",
                    task = { canceled ->
                        val api = WeChatApis.messages()
                        var success = 0
                        ids.forEach { talker ->
                            if (canceled.get()) return@forEach
                            if (api?.sendText(talker, content) == true) success++
                            if (!canceled.get()) Thread.sleep(SEND_INTERVAL_MS)
                        }
                        BatchResult(success, ids.size, "发送")
                    },
                    onComplete = { result -> toastBatch(activity, result) }
                )
            },
            onDismiss = {}
        )
    }

    private fun chooseAndSendFile(
        activity: Activity,
        ids: List<String>,
        type: SendContentType
    ) {
        val context = activity.applicationContext
        ConversationGroupSendPicker.launch(
            activity = activity,
            mimeType = type.mimeType ?: return,
            chooserTitle = "选择${type.title}"
        ) { picked ->
            picked ?: return@launch
            runBatch(
                activity = activity,
                title = "发送${type.title}",
                message = "正在发送到 ${ids.size} 个会话...",
                task = { canceled ->
                    val file = ConversationGroupSendPicker.materialize(context, picked)
                    val media = WeChatApis.media()
                    var success = 0
                    ids.forEach { talker ->
                        if (canceled.get()) return@forEach
                        val sent = when (type) {
                            SendContentType.IMAGE -> media?.images()?.sendOriginal(talker, file.path) == true
                            SendContentType.VOICE -> media?.voices()?.send(talker, file.path) == true
                            SendContentType.VIDEO -> media?.videos()?.send(talker, file.path) == true
                            SendContentType.EMOJI -> media?.emojis()?.send(talker, file.path) == true
                            SendContentType.FILE -> media?.files()?.send(
                                talker,
                                file.path,
                                file.displayName
                            ) == true
                            else -> false
                        }
                        if (sent) success++
                        if (!canceled.get()) Thread.sleep(SEND_INTERVAL_MS)
                    }
                    BatchResult(success, ids.size, "发送${type.title}")
                },
                onComplete = { result -> toastBatch(activity, result) }
            )
        }
    }

    private fun sendXml(activity: Activity, ids: List<String>) {
        VoiceForwardMiuixDialog.showTextInput(
            activity = activity,
            title = "发送 XML",
            summary = "发送给当前分组及子分组内 ${ids.size} 个会话",
            placeholder = "输入 XML / AppMsg 内容",
            maxLength = 100000,
            singleLine = false,
            onConfirm = { content ->
                runBatch(
                    activity = activity,
                    title = "发送 XML",
                    message = "正在发送到 ${ids.size} 个会话...",
                    task = { canceled ->
                        val api = WeChatApis.messages()
                        var success = 0
                        ids.forEach { talker ->
                            if (canceled.get()) return@forEach
                            if (api?.sendXml(talker, content) == true) success++
                            if (!canceled.get()) Thread.sleep(SEND_INTERVAL_MS)
                        }
                        BatchResult(success, ids.size, "发送 XML")
                    },
                    onComplete = { result -> toastBatch(activity, result) }
                )
            },
            onDismiss = {}
        )
    }

    private fun chooseAndSendFavorite(activity: Activity, ids: List<String>) {
        ConversationGroupTask.launch(
            activity = activity,
            title = "选择收藏",
            message = "正在读取收藏...",
            nextFrame = true,
            task = { canceled ->
                val api = WeChatApis.media()?.favorites()
                    ?: throw IllegalStateException("收藏接口未就绪")
                if (!api.canList()) throw IllegalStateException("收藏列表接口未就绪")
                var favorites = emptyList<h.Hchat.hooks.api.media.WeChatFavoriteItem>()
                for (attempt in 0..2) {
                    if (canceled.get()) break
                    favorites = api.listAll()
                    if (favorites.isNotEmpty() || attempt == 2) break
                    Thread.sleep(350L)
                }
                favorites
            },
            onComplete = { result ->
                result.onSuccess { favorites ->
                    if (favorites.isEmpty()) {
                        toast(activity, "没有可发送的收藏")
                        return@onSuccess
                    }
                    VoiceForwardMiuixDialog.showListChoices(
                        activity = activity,
                        title = "选择收藏",
                        summary = "发送给当前分组及子分组内 ${ids.size} 个会话",
                        choices = favorites.map { favorite ->
                            favorite.displayTitle() to
                                "${favorite.typeLabel()} · ${favorite.displaySummary()}"
                        },
                        searchable = true,
                        searchPlaceholder = "搜索收藏",
                        onSelected = { index ->
                            val favorite = favorites.getOrNull(index)
                                ?: return@showListChoices
                            runBatch(
                                activity = activity,
                                title = "发送收藏",
                                message = "正在发送到 ${ids.size} 个会话...",
                                task = { batchCanceled ->
                                    val api = WeChatApis.media()?.favorites()
                                    var submitted = 0
                                    ids.forEach { talker ->
                                        if (batchCanceled.get()) return@forEach
                                        if (api?.send(talker, favorite.localId) == true) submitted++
                                        if (!batchCanceled.get()) Thread.sleep(SEND_INTERVAL_MS)
                                    }
                                    BatchResult(submitted, ids.size, "收藏发送请求提交")
                                },
                                onComplete = { batchResult -> toastBatch(activity, batchResult) }
                            )
                        },
                        onDismiss = {}
                    )
                }.onFailure {
                    HLog.e("$TAG 读取收藏失败: ${it.message}", it)
                    toast(activity, "读取收藏失败")
                }
            }
        )
    }

    private fun chooseAndSendCard(activity: Activity, ids: List<String>) {
        ConversationGroupTask.launch(
            activity = activity,
            title = "选择名片",
            message = "正在读取联系人...",
            nextFrame = true,
            task = { _ ->
                WeChatApis.contact().contacts()?.getPickerContacts().orEmpty().map { contact ->
                    VoiceForwardMiuixDialog.ContactItem(
                        id = contact.wxId,
                        label = contact.displayName(),
                        group = false,
                        avatarUrl = contact.avatarUrl,
                        avatarBackupUrl = contact.avatarBackupUrl,
                        searchAliases = listOf(contact.nickname, contact.remarkName, contact.customWxId)
                            .filter(String::isNotBlank)
                    )
                }
            },
            onComplete = { result ->
                result.onSuccess { contacts ->
                    if (contacts.isEmpty()) {
                        toast(activity, "没有可发送的联系人名片")
                        return@onSuccess
                    }
                    VoiceForwardMiuixDialog.showContacts(
                        activity = activity,
                        contacts = contacts,
                        title = "选择名片",
                        confirmText = "发送",
                        showGroupFilter = false,
                        singleSelection = true,
                        onConfirm = { selected ->
                            val contact = selected.singleOrNull() ?: return@showContacts
                            runBatch(
                                activity = activity,
                                title = "发送名片",
                                message = "正在发送到 ${ids.size} 个会话...",
                                task = { batchCanceled ->
                                    val api = WeChatApis.messages()
                                    var success = 0
                                    ids.forEach { talker ->
                                        if (batchCanceled.get()) return@forEach
                                        if (api?.sendShareCard(talker, contact.id) == true) success++
                                        if (!batchCanceled.get()) Thread.sleep(SEND_INTERVAL_MS)
                                    }
                                    BatchResult(success, ids.size, "发送名片")
                                },
                                onComplete = { batchResult -> toastBatch(activity, batchResult) }
                            )
                        },
                        onDismiss = {}
                    )
                }.onFailure {
                    HLog.e("$TAG 读取联系人失败: ${it.message}", it)
                    toast(activity, "读取联系人失败")
                }
            }
        )
    }

    private fun sendChatroomInvitation(activity: Activity, groupId: String) {
        val currentIds = allConversationIds(activity, groupId)
        val contacts = WeChatApis.contact().contacts()
        val memberIds = currentIds.filter { talker ->
            runCatching { contacts?.isFriend(talker) == true }.getOrDefault(false)
        }
        if (memberIds.isEmpty()) {
            toast(activity, "当前分组没有可邀请的好友")
            return
        }
        val rooms = runCatching { contacts?.getPickerGroups().orEmpty() }.getOrDefault(emptyList())
            .map { contact ->
                VoiceForwardMiuixDialog.ContactItem(
                    id = contact.wxId,
                    label = contact.displayName(),
                    group = true,
                    avatarUrl = contact.avatarUrl,
                    avatarBackupUrl = contact.avatarBackupUrl,
                    searchAliases = listOf(contact.nickname, contact.remarkName, contact.customWxId)
                        .filter(String::isNotBlank)
                )
            }
        VoiceForwardMiuixDialog.showContacts(
            activity = activity,
            contacts = rooms,
            title = "选择群聊",
            confirmText = "下一步",
            onConfirm = { selected ->
                val chatrooms = selected.distinctBy { it.id }
                if (chatrooms.isEmpty()) return@showContacts
                VoiceForwardMiuixDialog.showConfirm(
                    activity = activity,
                    title = "发送群聊邀请",
                    message = "将邀请当前分组中的 ${memberIds.size} 位好友加入 ${chatrooms.size} 个群聊。",
                    onResult = { confirmed ->
                        if (!confirmed) return@showConfirm
                        runBatch(
                            activity,
                            "发送群聊邀请",
                            "正在提交群聊邀请...",
                            task = { canceled ->
                                val api = WeChatApis.contact().chatrooms()
                                var success = 0
                                val chunks = memberIds.chunked(30)
                                chatrooms.forEachIndexed { roomIndex, chatroom ->
                                    if (canceled.get()) return@forEachIndexed
                                    chunks.forEachIndexed { chunkIndex, members ->
                                        if (canceled.get()) return@forEachIndexed
                                        if (api?.inviteChatroomMember(chatroom.id, members) == true) {
                                            success += members.size
                                        }
                                        val isLastRequest = roomIndex == chatrooms.lastIndex &&
                                            chunkIndex == chunks.lastIndex
                                        if (!canceled.get() && !isLastRequest) Thread.sleep(500L)
                                    }
                                }
                                BatchResult(success, memberIds.size * chatrooms.size, "邀请")
                            },
                            onComplete = { result -> toastBatch(activity, result) }
                        )
                    },
                    onDismiss = {}
                )
            },
            onDismiss = {}
        )
    }

    private fun addConversations(activity: Activity, groupId: String, onChanged: () -> Unit) {
        val currentIds = allConversationIds(activity, groupId)
        val candidates = WeChatApis.conversations()?.getRecentConversationUsernames(10000).orEmpty()
            .filter { it.isNotBlank() && !ConversationGroupRuntime.isVirtualTalker(it) && it !in currentIds }
            .distinct()
        showConversationPicker(activity, candidates, "添加到当前分组", "添加") { selected ->
            val success = ConversationGroupStore.setConversationGroups(
                activity,
                selected.map { it.id },
                groupId
            )
            toast(activity, if (success) "已添加 ${selected.size} 个会话" else "添加会话失败")
            if (success) onChanged()
        }
    }

    private fun removeConversations(activity: Activity, groupId: String, onChanged: () -> Unit) {
        val ids = group(activity, groupId)?.conversationIds.orEmpty()
        showConversationPicker(activity, ids, "移出当前分组", "移出") { selected ->
            val success = ConversationGroupStore.setConversationGroups(
                activity,
                selected.map { it.id },
                null
            )
            toast(activity, if (success) "已移出 ${selected.size} 个会话" else "移出会话失败")
            if (success) onChanged()
        }
    }

    private fun moveConversations(activity: Activity, groupId: String, onChanged: () -> Unit) {
        val ids = group(activity, groupId)?.conversationIds.orEmpty()
        showConversationPicker(activity, ids, "选择要移动的会话", "下一步") { selected ->
            val groups = ConversationGroupStore.load(activity)
            val destinations: List<Pair<String?, String>> =
                listOf(null to "微信首页") + groups
                .filterNot { it.id == groupId }
                .map { it.id to it.name }
            VoiceForwardMiuixDialog.showListChoices(
                activity = activity,
                title = "移至",
                summary = "已选择 ${selected.size} 个会话",
                choices = destinations.map { (_, name) -> name to "" },
                onSelected = { index ->
                    val targetId = destinations.getOrNull(index)?.first
                    val success = ConversationGroupStore.setConversationGroups(
                        activity,
                        selected.map { it.id },
                        targetId
                    )
                    toast(activity, if (success) "会话已移动" else "移动会话失败")
                    if (success) onChanged()
                },
                onDismiss = {}
            )
        }
    }

    private fun searchConversations(activity: Activity, groupId: String) {
        val ids = allConversationIds(activity, groupId)
        showConversationPicker(
            activity = activity,
            ids = ids,
            title = "搜索分组会话",
            confirmText = "打开",
            singleSelection = true
        ) { selected ->
            selected.singleOrNull()?.let { WeChatApis.conversations()?.openChat(it.id) }
        }
    }

    private fun showSettings(activity: Activity, groupId: String, onChanged: () -> Unit) {
        val current = group(activity, groupId) ?: return
        val hasCustomAvatar = CustomFriendAvatarStore.hasAvatar(
            activity,
            ConversationGroupRuntime.virtualTalker(groupId)
        )
        fun state(value: Boolean) = if (value) "已开启" else "已关闭"
        VoiceForwardMiuixDialog.showListChoices(
            activity = activity,
            title = "设置",
            summary = current.name,
            choices = listOf(
                "主页置顶" to state(current.pinned),
                "自定义头像" to if (hasCustomAvatar) "已设置" else "未设置",
                "命名" to current.name,
                "分组排序" to if (current.conversationOrderIds.isEmpty()) {
                    "当前跟随微信默认顺序"
                } else {
                    "已固定当前分组内会话顺序"
                },
                "分组位置" to "调整当前层级中的分组位置",
                "未读数" to current.unreadCountMode.displayName,
                "预览最新一条消息" to state(current.previewLatestMessage),
                "显示无消息" to state(current.showEmpty)
            ),
            onSelected = { index ->
                when (index) {
                    0 -> updateSetting(activity, current.copy(pinned = !current.pinned), onChanged)
                    1 -> showAvatarSettings(activity, groupId, onChanged)
                    2 -> renameGroup(activity, groupId, onChanged)
                    3 -> showConversationOrderSettings(activity, groupId, onChanged)
                    4 -> showGroupPositionSettings(activity, groupId, onChanged)
                    5 -> showUnreadCountSettings(activity, groupId, onChanged)
                    6 -> updateSetting(
                        activity,
                        current.copy(previewLatestMessage = !current.previewLatestMessage),
                        onChanged
                    )
                    7 -> updateSetting(
                        activity,
                        current.copy(showEmpty = !current.showEmpty),
                        onChanged
                    )
                }
                if (index !in setOf(1, 2, 3, 4, 5)) main.post {
                    showSettings(activity, groupId, onChanged)
                }
            },
            onDismiss = {}
        )
    }

    private fun showUnreadCountSettings(
        activity: Activity,
        groupId: String,
        onChanged: () -> Unit
    ) {
        val current = group(activity, groupId) ?: return
        val modes = ConversationGroupUnreadMode.values()
        val descriptions = mapOf(
            ConversationGroupUnreadMode.ALL to "统计普通会话和免打扰会话的未读数",
            ConversationGroupUnreadMode.EXCLUDE_MUTED to "只统计未开启免打扰的会话未读数",
            ConversationGroupUnreadMode.HIDDEN to "分组入口不显示未读数"
        )
        VoiceForwardMiuixDialog.showChoices(
            activity = activity,
            title = "未读数",
            summary = current.name,
            choices = modes.map { mode ->
                mode.displayName to buildString {
                    if (mode == current.unreadCountMode) append("当前使用；")
                    append(descriptions.getValue(mode))
                }
            },
            onSelected = { index ->
                val mode = modes.getOrNull(index) ?: return@showChoices
                val latest = group(activity, groupId) ?: return@showChoices
                if (latest.unreadCountMode != mode) {
                    updateSetting(activity, latest.copy(unreadCountMode = mode), onChanged)
                }
                main.post { showSettings(activity, groupId, onChanged) }
            },
            onDismiss = {}
        )
    }

    private fun showConversationOrderSettings(
        activity: Activity,
        groupId: String,
        onChanged: () -> Unit
    ) {
        val context = activity.applicationContext
        val groups = ConversationGroupStore.load(context)
        val current = groups.firstOrNull { it.id == groupId } ?: return
        ConversationGroupTask.launch(
            activity = activity,
            title = "分组排序",
            message = "正在载入会话...",
            nextFrame = true,
            task = { _ ->
                val effective = ConversationGroupRuntime.effectiveConversationIdsForPicker(groups)
                    .takeIf { it.containsKey(groupId) }
                    ?: ConversationGroupAutomaticResolver.resolveForSync(context, groups).conversationIds
                val items = conversationItems(effective[groupId] ?: current.conversationIds)
                orderConversationItems(current, items)
            },
            onComplete = { result ->
                result.onSuccess { items ->
                    if (items.isEmpty()) {
                        if (current.conversationOrderIds.isEmpty()) {
                            toast(activity, "当前分组没有会话")
                        } else {
                            VoiceForwardMiuixDialog.showChoices(
                                activity = activity,
                                title = "分组排序",
                                summary = "当前没有有效会话，但仍保存了固定顺序",
                                choices = listOf(
                                    "恢复默认排序" to "清除已保存的固定顺序"
                                ),
                                onSelected = {
                                    restoreDefaultConversationOrder(
                                        activity,
                                        groupId,
                                        onChanged
                                    )
                                },
                                onDismiss = {}
                            )
                        }
                        return@onSuccess
                    }
                    val fixed = current.conversationOrderIds.isNotEmpty()
                    val choices = buildList {
                        if (fixed) add("恢复默认排序" to "重新跟随微信的置顶和消息时间排序")
                        items.forEachIndexed { index, item ->
                            val section = conversationOrderSection(current, item.id)
                            add(item.label to "第 ${index + 1} 位 · $section")
                        }
                    }
                    VoiceForwardMiuixDialog.showListChoices(
                        activity = activity,
                        title = "分组排序",
                        summary = if (fixed) {
                            "顺序已固定，新消息不会改变位置"
                        } else {
                            "选择会话后调整位置，保存后将固定显示"
                        },
                        choices = choices,
                        searchable = true,
                        onSelected = { index ->
                            if (fixed && index == 0) {
                                restoreDefaultConversationOrder(
                                    activity,
                                    groupId,
                                    onChanged
                                )
                                return@showListChoices
                            }
                            val itemIndex = index - if (fixed) 1 else 0
                            val selected = items.getOrNull(itemIndex) ?: return@showListChoices
                            showConversationOrderActions(
                                activity,
                                current,
                                items,
                                selected.id,
                                onChanged
                            )
                        },
                        onDismiss = {}
                    )
                }.onFailure {
                    HLog.e("$TAG 读取分组排序失败: ${it.message}", it)
                    toast(activity, "读取分组排序失败")
                }
            }
        )
    }

    private fun restoreDefaultConversationOrder(
        activity: Activity,
        groupId: String,
        onChanged: () -> Unit
    ) {
        val success = ConversationGroupStore.restoreDefaultConversationOrder(activity, groupId)
        toast(activity, if (success) "已恢复默认排序" else "恢复默认排序失败")
        if (success) onChanged()
    }

    private fun showConversationOrderActions(
        activity: Activity,
        group: ConversationGroup,
        items: List<VoiceForwardMiuixDialog.ContactItem>,
        talker: String,
        onChanged: () -> Unit
    ) {
        val section = conversationOrderSection(group, talker)
        val sectionItems = items.filter { conversationOrderSection(group, it.id) == section }
        val currentIndex = sectionItems.indexOfFirst { it.id == talker }
        val actions = buildList {
            if (currentIndex > 0) {
                add("移到顶部" to ConversationGroupStore.ReorderAction.TOP)
                add("上移" to ConversationGroupStore.ReorderAction.UP)
            }
            if (currentIndex in 0 until sectionItems.lastIndex) {
                add("下移" to ConversationGroupStore.ReorderAction.DOWN)
                add("移到底部" to ConversationGroupStore.ReorderAction.BOTTOM)
            }
        }
        if (actions.isEmpty()) {
            toast(activity, "当前区域没有其它会话")
            return
        }
        val title = items.firstOrNull { it.id == talker }?.label.orEmpty().ifBlank { talker }
        VoiceForwardMiuixDialog.showChoices(
            activity = activity,
            title = title,
            summary = "$section 区域",
            choices = actions.map { (name, _) -> name to "调整当前分组内的固定顺序" },
            onSelected = { index ->
                val action = actions.getOrNull(index)?.second ?: return@showChoices
                val reorderedSection = sectionItems.toMutableList()
                val from = reorderedSection.indexOfFirst { it.id == talker }
                val to = when (action) {
                    ConversationGroupStore.ReorderAction.TOP -> 0
                    ConversationGroupStore.ReorderAction.UP -> from - 1
                    ConversationGroupStore.ReorderAction.DOWN -> from + 1
                    ConversationGroupStore.ReorderAction.BOTTOM -> reorderedSection.lastIndex
                }.coerceIn(reorderedSection.indices)
                reorderedSection.add(to, reorderedSection.removeAt(from))
                val sectionIterator = reorderedSection.iterator()
                val reorderedIds = items.map { item ->
                    if (conversationOrderSection(group, item.id) == section) {
                        sectionIterator.next().id
                    } else {
                        item.id
                    }
                }
                val success = ConversationGroupStore.saveConversationOrder(
                    activity,
                    group.id,
                    reorderedIds
                )
                toast(activity, if (success) "会话顺序已固定" else "保存会话顺序失败")
                if (success) onChanged()
                main.post { showConversationOrderSettings(activity, group.id, onChanged) }
            },
            onDismiss = {}
        )
    }

    private fun orderConversationItems(
        group: ConversationGroup,
        items: List<VoiceForwardMiuixDialog.ContactItem>
    ): List<VoiceForwardMiuixDialog.ContactItem> {
        val defaultRanks = items.mapIndexed { index, item -> item.id to index }.toMap()
        val customRanks = group.conversationOrderIds.withIndex()
            .associate { (index, talker) -> talker to index }
        val pinnedRanks = group.pinnedConversationIds.withIndex()
            .associate { (index, talker) -> talker to index }
        val bottomRanks = group.bottomConversationIds.withIndex()
            .associate { (index, talker) -> talker to index }
        return items.sortedWith(
            compareBy<VoiceForwardMiuixDialog.ContactItem> {
                when (conversationOrderSection(group, it.id)) {
                    "置顶" -> 0
                    "置底" -> 2
                    else -> 1
                }
            }.thenBy { item ->
                if (customRanks.isNotEmpty()) {
                    customRanks[item.id] ?: customRanks.size + (defaultRanks[item.id] ?: 0)
                } else {
                    pinnedRanks[item.id] ?: bottomRanks[item.id] ?: defaultRanks[item.id] ?: 0
                }
            }
        )
    }

    private fun conversationOrderSection(group: ConversationGroup, talker: String): String {
        return when (talker) {
            in group.pinnedConversationIds -> "置顶"
            in group.bottomConversationIds -> "置底"
            else -> "普通"
        }
    }

    private fun showGroupPositionSettings(activity: Activity, groupId: String, onChanged: () -> Unit) {
        val current = group(activity, groupId) ?: return
        val siblings = ConversationGroupStore.load(activity)
            .filter { it.parentId == current.parentId && it.pinned == current.pinned }
            .sortedBy { it.order }
        val currentIndex = siblings.indexOfFirst { it.id == groupId }
        val actions = buildList {
            if (currentIndex > 0) {
                add("移到顶部" to ConversationGroupStore.ReorderAction.TOP)
                add("上移" to ConversationGroupStore.ReorderAction.UP)
            }
            if (currentIndex in 0 until siblings.lastIndex) {
                add("下移" to ConversationGroupStore.ReorderAction.DOWN)
                add("移到底部" to ConversationGroupStore.ReorderAction.BOTTOM)
            }
        }
        if (actions.isEmpty()) {
            toast(activity, "当前区域没有其它分组")
            main.post { showSettings(activity, groupId, onChanged) }
            return
        }
        VoiceForwardMiuixDialog.showChoices(
            activity = activity,
            title = "分组位置",
            summary = current.name,
            choices = actions.map { (title, _) -> title to "调整同级分组位置" },
            onSelected = { index ->
                val action = actions.getOrNull(index)?.second ?: return@showChoices
                val success = ConversationGroupStore.reorderGroup(activity, groupId, action)
                toast(activity, if (success) "分组位置已更新" else "调整分组位置失败")
                if (success) onChanged()
                main.post { showSettings(activity, groupId, onChanged) }
            },
            onDismiss = {}
        )
    }

    private fun showAvatarSettings(activity: Activity, groupId: String, onChanged: () -> Unit) {
        val current = group(activity, groupId) ?: return
        val talker = ConversationGroupRuntime.virtualTalker(groupId)
        val hasCustomAvatar = CustomFriendAvatarStore.hasAvatar(activity, talker)
        VoiceForwardMiuixDialog.showChoices(
            activity = activity,
            title = "自定义头像",
            summary = current.name,
            choices = buildList {
                add((if (hasCustomAvatar) "更换头像" else "选择头像") to "从系统相册或文件中选择")
                if (hasCustomAvatar) add("恢复默认" to "移除当前分组头像")
            },
            onSelected = { index ->
                if (index == 0) {
                    CustomFriendAvatarPicker.launchGroup(activity, talker) { success ->
                        if (success) {
                            val latest = group(activity, groupId) ?: return@launchGroup
                            updateSetting(
                                activity,
                                latest.copy(
                                    avatarPath = CustomFriendAvatarStore.avatarPath(activity, talker)
                                ),
                                onChanged
                            )
                        } else {
                            toast(activity, "头像设置失败")
                        }
                    }
                } else {
                    val removed = CustomFriendAvatarStore.remove(activity, talker)
                    if (!removed) {
                        toast(activity, "恢复默认头像失败")
                        return@showChoices
                    }
                    val latest = group(activity, groupId) ?: return@showChoices
                    val saved = ConversationGroupStore.updateGroup(
                        activity,
                        latest.copy(avatarPath = "")
                    )
                    if (saved) onChanged()
                    toast(activity, if (saved) "已恢复默认头像" else "恢复默认头像失败")
                }
            },
            onDismiss = {}
        )
    }

    private fun renameGroup(activity: Activity, groupId: String, onChanged: () -> Unit) {
        val current = group(activity, groupId) ?: return
        VoiceForwardMiuixDialog.showTextInput(
            activity = activity,
            title = "命名",
            summary = "修改当前聊天分组名称",
            initialValue = current.name,
            placeholder = "分组名称",
            maxLength = 50,
            onConfirm = { name ->
                val groups = ConversationGroupStore.load(activity)
                val duplicate = groups.any {
                    it.id != groupId && it.parentId == current.parentId &&
                        it.name.equals(name, ignoreCase = true)
                }
                if (duplicate) {
                    toast(activity, "同一层级已存在同名分组")
                } else {
                    updateSetting(activity, current.copy(name = name), onChanged)
                }
            },
            onDismiss = {}
        )
    }

    private fun updateSetting(
        activity: Activity,
        group: ConversationGroup,
        onChanged: () -> Unit
    ) {
        val success = ConversationGroupStore.updateGroup(activity, group)
        toast(activity, if (success) "设置已保存" else "保存设置失败")
        if (success) onChanged()
    }

    private fun showConversationPicker(
        activity: Activity,
        ids: Collection<String>,
        title: String,
        confirmText: String,
        singleSelection: Boolean = false,
        onConfirm: (List<VoiceForwardMiuixDialog.ContactItem>) -> Unit
    ) {
        val normalized = ids.map(String::trim).filter(String::isNotBlank).distinct()
        if (normalized.isEmpty()) {
            toast(activity, "没有可选择的会话")
            return
        }
        ConversationGroupTask.launch(
            activity = activity,
            title = title,
            message = "正在载入会话...",
            nextFrame = true,
            task = { _ ->
                conversationItems(normalized)
            },
            onComplete = { result ->
                result.onSuccess { items ->
                    if (items.isEmpty()) {
                        toast(activity, "没有可选择的会话")
                        return@onSuccess
                    }
                    VoiceForwardMiuixDialog.showContacts(
                        activity = activity,
                        contacts = items,
                        title = title,
                        confirmText = confirmText,
                        showGroupFilter = true,
                        singleSelection = singleSelection,
                        onConfirm = onConfirm,
                        onDismiss = {}
                    )
                }.onFailure {
                    HLog.e("$TAG $title 读取会话失败: ${it.message}", it)
                    toast(activity, "读取会话失败")
                }
            }
        )
    }

    private fun conversationItems(ids: Collection<String>): List<VoiceForwardMiuixDialog.ContactItem> {
        val normalized = ids.map(String::trim).filter(String::isNotBlank).distinct()
        val contacts = WeChatApis.contact().contacts()
        val contactById = runCatching { contacts?.getContactsByIds(normalized).orEmpty() }
            .getOrDefault(emptyList())
            .associateBy { it.wxId }
        val order = WeChatApis.conversations()?.getRecentConversationUsernames(10000).orEmpty()
            .mapIndexed { index, username -> username to index }
            .toMap()
        return normalized.map { talker ->
            val contact = contactById[talker]
            val group = contact?.isGroup() == true || talker.endsWith("@chatroom") ||
                talker.endsWith("@im.chatroom")
            VoiceForwardMiuixDialog.ContactItem(
                id = talker,
                label = contact?.displayName().orEmpty().ifBlank {
                    WeChatApis.conversations()?.getConversationTitle(talker).orEmpty().ifBlank { talker }
                },
                group = group,
                avatarUrl = contact?.avatarUrl.orEmpty(),
                avatarBackupUrl = contact?.avatarBackupUrl.orEmpty(),
                official = talker.startsWith("gh_"),
                searchAliases = listOfNotNull(
                    contact?.nickname,
                    contact?.remarkName,
                    contact?.customWxId
                ).filter(String::isNotBlank)
            )
        }.sortedWith(
            compareBy<VoiceForwardMiuixDialog.ContactItem> { order[it.id] ?: Int.MAX_VALUE }
                .thenBy { it.label }
        )
    }

    private fun allConversationIds(context: Context, groupId: String): List<String> {
        val groups = ConversationGroupStore.load(context)
        val effectiveConversationIds = ConversationGroupRuntime.effectiveConversationIdsForPicker(groups)
        return ConversationGroupRuntime.descendantConversationIds(
            groups,
            groupId,
            effectiveConversationIds
        ).toList()
    }

    private fun group(activity: Activity, groupId: String): ConversationGroup? {
        return ConversationGroupStore.load(activity).firstOrNull { it.id == groupId }
            ?: run {
                toast(activity, "聊天分组不存在")
                null
            }
    }

    private fun runBatch(
        activity: Activity,
        title: String,
        message: String,
        task: (AtomicBoolean) -> BatchResult,
        onComplete: (BatchResult) -> Unit
    ) {
        ConversationGroupTask.launch(
            activity = activity,
            title = title,
            message = message,
            task = task,
            onComplete = { result ->
                onComplete(result.getOrElse {
                    HLog.e("$TAG $title 失败: ${it.message}", it)
                    BatchResult(0, 0, title, failed = true)
                })
            }
        )
    }

    private fun toastBatch(activity: Activity, result: BatchResult) {
        val message = when {
            result.failed -> "${result.action}失败"
            result.success == result.total -> "${result.action}完成: ${result.success}/${result.total}"
            else -> "${result.action}部分完成: ${result.success}/${result.total}"
        }
        toast(activity, message)
    }

    private fun toast(activity: Activity, message: String) {
        main.post {
            if (!activity.isFinishing && !activity.isDestroyed) {
                Toast.makeText(activity.applicationContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private data class BatchResult(
        val success: Int,
        val total: Int,
        val action: String,
        val failed: Boolean = false
    )

    private enum class SendContentType(
        val title: String,
        val summary: String,
        val mimeType: String? = null
    ) {
        TEXT("文字", "输入文字后批量发送"),
        IMAGE("图片", "选择图片并按原图发送", "image/*"),
        VOICE("语音", "选择音频并转换为微信语音发送", "audio/*"),
        VIDEO("视频", "选择本地视频发送", "video/*"),
        EMOJI("表情", "选择图片作为微信表情发送", "image/*"),
        FILE("文件", "选择任意文件作为附件发送", "*/*"),
        FAVORITE("收藏", "从微信收藏中选择一项发送"),
        CARD("名片", "选择好友或企业微信联系人名片发送"),
        XML("XML", "输入 XML / AppMsg 内容后发送")
    }

    private const val CLEAR_BATCH_SIZE = 50
    private const val BATCH_INTERVAL_MS = 300L
    private const val SEND_INTERVAL_MS = 500L
}
