package h.Hchat.hooks.items.momentsfake

import android.app.Activity
import android.content.Context
import android.widget.Toast
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.sns.SnsContextMenuTarget
import h.Hchat.preferences.HchatStorage
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog
import h.Hchat.utils.KavaReflector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

internal class MomentsFakeInteractionDialogs(
    context: Context,
    private val store: MomentsFakeInteractionStore,
    private val runtime: MomentsFakeInteractionRuntime,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = HchatStorage.preferences(context, MomentsFakeInteractionSettings.PREFS_NAME)
    private val legacyDisplayNameCache = ConcurrentHashMap<String, String>()

    fun showFakeForward(
        activity: Activity,
        target: SnsContextMenuTarget,
        forwardRuntime: MomentsFakeForwardRuntime
    ) {
        val initialText = fakeForwardEditableText(target)
        VoiceForwardMiuixDialog.showTextInput(
            activity = activity,
            title = "伪转发朋友圈",
            summary = "文字内容可修改；媒体和卡片保持原样，仅保存到本机",
            initialValue = initialText,
            placeholder = "朋友圈文字",
            maxLength = 10000,
            singleLine = false,
            allowEmpty = true,
            onConfirm = { text ->
                VoiceForwardMiuixDialog.showDateTimeInput(
                    activity = activity,
                    title = "选择显示日期和时间",
                    initialTimeMillis = System.currentTimeMillis(),
                    onConfirm = { time ->
                        Thread({
                            val textOverride = if (initialText.isBlank() && text.isBlank()) null else text
                            val success = forwardRuntime.create(target, textOverride, time)
                            activity.runOnUiThread {
                                if (success) runtime.refreshTimelineViews(activity, target.anchorView?.get())
                                toast(activity, if (success) "伪转发已保存到本机" else "伪转发失败，请查看错误日志")
                            }
                        }, "Hchat-MomentsFakeForward").apply { isDaemon = true }.start()
                    },
                    onDismiss = {}
                )
            },
            onDismiss = {}
        )
    }

    private fun fakeForwardEditableText(target: SnsContextMenuTarget): String {
        if (target.snapshot.type in EDITABLE_FORWARD_TYPES) return target.snapshot.text
        val timeline = KavaReflector.invokeMethod(target.nativeInfo, "getTimeLine") ?: return ""
        return (KavaReflector.readField(timeline, "ContentDesc") as? String)
            .orEmpty()
            .takeUnless(::isHttpUrl)
            .orEmpty()
    }

    private fun isHttpUrl(value: String): Boolean {
        val normalized = value.trim().lowercase(Locale.ROOT)
        return normalized.startsWith("http://") || normalized.startsWith("https://")
    }

    fun showFakeLikes(activity: Activity, target: SnsContextMenuTarget) {
        val snsId = target.snsId ?: return
        val includeNonFriends = prefs.getBoolean(
            MomentsFakeInteractionSettings.KEY_FAKE_LIKE_USE_NON_FRIENDS,
            MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_USE_NON_FRIENDS
        )
        loadLikeCandidates(activity, includeNonFriends) { loadedContacts ->
            val current = store.entry(snsId)
            val contacts = mergeStoredLikes(loadedContacts, current.likes)
            if (prefs.getBoolean(
                    MomentsFakeInteractionSettings.KEY_FAKE_LIKE_AUTO_SELECT,
                    MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_AUTO_SELECT
                )
            ) {
                val count = prefs.getInt(
                    MomentsFakeInteractionSettings.KEY_FAKE_LIKE_AUTO_SELECT_COUNT,
                    MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_AUTO_SELECT_COUNT
                ).coerceAtLeast(1)
                val selected = generateLikeSelection(
                    contacts = loadedContacts,
                    count = count,
                    allowVirtualFallback = includeNonFriends,
                    virtualOnly = false
                )
                if (selected.isEmpty()) {
                    toast(activity, "没有可自动勾选的点赞人")
                } else {
                    showFakeLikePicker(
                        activity = activity,
                        target = target,
                        contacts = (selected + contacts).distinctBy { it.id },
                        initialSelectedIds = selected.mapTo(linkedSetOf()) { it.id },
                        title = "自动勾选伪集赞"
                    )
                    if (selected.size < count) {
                        toast(activity, "可选人数不足，已勾选 ${selected.size} 人")
                    }
                }
                return@loadLikeCandidates
            }
            val actions = buildList {
                add(
                    "选择点赞好友" to if (current.likes.isEmpty()) {
                        if (includeNonFriends) "从好友及非好友中手动选择" else "从好友列表手动选择"
                    } else {
                        "当前已选择 ${current.likes.size} 人"
                    }
                )
                add("随机选择好友" to "按数量随机勾选，候选不足时可生成虚拟点赞人")
                add("凭空生成点赞" to "无需真实好友，随机生成指定数量的虚拟点赞人")
                if (current.likes.isNotEmpty()) {
                    add("清空伪集赞" to "移除该朋友圈的全部本地点赞")
                }
            }
            VoiceForwardMiuixDialog.showListChoices(
                activity = activity,
                title = "朋友圈伪集赞",
                summary = if (current.likes.isEmpty()) {
                    "选择好友或按数量随机生成"
                } else {
                    "当前共 ${current.likes.size} 个伪造点赞"
                },
                choices = actions,
                onSelected = { index ->
                    when (actions[index].first) {
                        "选择点赞好友" -> showFakeLikePicker(
                            activity,
                            target,
                            contacts,
                            current.likes.mapTo(linkedSetOf()) { it.wxId }
                        )
                        "随机选择好友" -> showRandomFakeLikes(
                            activity,
                            target,
                            loadedContacts,
                            current.likes.size,
                            includeNonFriends,
                            virtualOnly = false
                        )
                        "凭空生成点赞" -> showRandomFakeLikes(
                            activity,
                            target,
                            emptyList(),
                            current.likes.size,
                            allowVirtualFallback = true,
                            virtualOnly = true
                        )
                        else -> clearFakeLikes(activity, target)
                    }
                },
                onDismiss = {}
            )
        }
    }

    private fun showRandomFakeLikes(
        activity: Activity,
        target: SnsContextMenuTarget,
        contacts: List<VoiceForwardMiuixDialog.ContactItem>,
        currentCount: Int,
        allowVirtualFallback: Boolean,
        virtualOnly: Boolean
    ) {
        val candidates = eligibleRealCandidates(contacts)
        if (candidates.isEmpty() && !allowVirtualFallback) {
            toast(activity, "没有可选择的好友")
            return
        }
        val maxCount = if (allowVirtualFallback) {
            null
        } else {
            candidates.size.coerceAtLeast(1)
        }
        VoiceForwardMiuixDialog.showNumberInput(
            activity = activity,
            title = if (virtualOnly) "凭空生成点赞数量" else "设置伪造点赞数量",
            initialValue = currentCount.takeIf { it > 0 }?.let { count ->
                maxCount?.let { count.coerceAtMost(it) } ?: count
            } ?: maxCount?.let {
                minOf(MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_AUTO_SELECT_COUNT, it)
            } ?: MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_AUTO_SELECT_COUNT,
            minValue = 1,
            maxValue = maxCount,
            onConfirm = { count ->
                val selected = generateLikeSelection(
                    contacts = candidates,
                    count = count,
                    allowVirtualFallback = allowVirtualFallback,
                    virtualOnly = virtualOnly
                )
                showFakeLikePicker(
                    activity = activity,
                    target = target,
                    contacts = (selected + candidates).distinctBy { it.id },
                    initialSelectedIds = selected.mapTo(linkedSetOf()) { it.id },
                    title = if (virtualOnly) "凭空生成伪集赞" else "随机伪集赞"
                )
            },
            onDismiss = {}
        )
    }

    private fun generateLikeSelection(
        contacts: List<VoiceForwardMiuixDialog.ContactItem>,
        count: Int,
        allowVirtualFallback: Boolean,
        virtualOnly: Boolean
    ): List<VoiceForwardMiuixDialog.ContactItem> {
        val safeCount = count.coerceAtLeast(1)
        val realSelection = if (virtualOnly) {
            emptyList()
        } else {
            eligibleRealCandidates(contacts).shuffled().take(safeCount)
        }
        val virtualCount = if (virtualOnly || allowVirtualFallback) {
            (safeCount - realSelection.size).coerceAtLeast(0)
        } else {
            0
        }
        val generated = generateVirtualLikes(
            virtualCount,
            realSelection.mapTo(hashSetOf()) { it.label }
        )
        val combined = realSelection + generated
        return if (prefs.getBoolean(
                MomentsFakeInteractionSettings.KEY_FAKE_LIKE_RANDOM_ORDER,
                MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_RANDOM_ORDER
            )
        ) combined.shuffled() else combined
    }

    private fun eligibleRealCandidates(
        contacts: List<VoiceForwardMiuixDialog.ContactItem>
    ): List<VoiceForwardMiuixDialog.ContactItem> {
        val excluded = prefs.getStringSet(
            MomentsFakeInteractionSettings.KEY_FAKE_LIKE_EXCLUDED_IDS,
            emptySet()
        ).orEmpty()
        return contacts.asSequence()
            .filterNot { it.id.startsWith(VIRTUAL_LIKE_PREFIX) || excluded.contains(it.id) }
            .distinctBy { it.id }
            .toList()
    }

    private fun generateVirtualLikes(
        count: Int,
        usedLabels: MutableSet<String>
    ): List<VoiceForwardMiuixDialog.ContactItem> {
        return buildList {
            repeat(count) {
                var label = randomVirtualName()
                var attempt = 0
                while (usedLabels.contains(label) && attempt < 20) {
                    label = randomVirtualName()
                    attempt++
                }
                if (usedLabels.contains(label)) {
                    val base = label
                    var suffix = 1
                    do {
                        label = "$base$suffix"
                        suffix++
                    } while (usedLabels.contains(label))
                }
                usedLabels.add(label)
                add(
                    VoiceForwardMiuixDialog.ContactItem(
                        id = VIRTUAL_LIKE_PREFIX + UUID.randomUUID().toString().replace("-", ""),
                        label = label,
                        group = false,
                        searchAliases = listOf("虚拟点赞人")
                    )
                )
            }
        }
    }

    private fun randomVirtualName(): String {
        val surname = VIRTUAL_SURNAMES.random()
        val first = VIRTUAL_GIVEN_NAMES.random()
        val second = if (Random.nextBoolean()) VIRTUAL_GIVEN_NAMES.random() else ""
        return surname + first + second
    }

    private fun mergeStoredLikes(
        contacts: List<VoiceForwardMiuixDialog.ContactItem>,
        likes: List<FakeSnsLike>
    ): List<VoiceForwardMiuixDialog.ContactItem> {
        val existingIds = contacts.mapTo(hashSetOf()) { it.id }
        val stored = likes.filterNot { existingIds.contains(it.wxId) }.map { like ->
            VoiceForwardMiuixDialog.ContactItem(
                id = like.wxId,
                label = like.displayName,
                group = false,
                searchAliases = if (like.wxId.startsWith(VIRTUAL_LIKE_PREFIX)) {
                    listOf("虚拟点赞人")
                } else {
                    emptyList()
                }
            )
        }
        return stored + contacts
    }

    private fun loadLikeCandidates(
        activity: Activity,
        includeNonFriends: Boolean,
        onLoaded: (List<VoiceForwardMiuixDialog.ContactItem>) -> Unit
    ) {
        val loading = VoiceForwardMiuixDialog.showLoading(
            activity = activity,
            title = "朋友圈伪集赞",
            message = if (includeNonFriends) "正在载入好友和非好友..." else "正在载入好友列表...",
            onDismiss = {}
        )
        Thread({
            val contacts = runCatching {
                MomentsFakeLikeCandidateRepository.loadForLikes(includeNonFriends)
            }.onFailure {
                logger("加载朋友圈伪集赞候选人失败", it)
            }.getOrDefault(emptyList())
            activity.runOnUiThread {
                loading.close()
                activity.window?.decorView?.postOnAnimation {
                    if (!activity.isFinishing && !activity.isDestroyed) onLoaded(contacts)
                }
            }
        }, "Hchat-MomentsFakeLikeContacts").apply { isDaemon = true }.start()
    }

    private fun showFakeLikePicker(
        activity: Activity,
        target: SnsContextMenuTarget,
        contacts: List<VoiceForwardMiuixDialog.ContactItem>,
        initialSelectedIds: Set<String>,
        title: String = "朋友圈伪集赞"
    ) {
        val snsId = target.snsId ?: return
        val orderedContacts = contacts.sortedByDescending { initialSelectedIds.contains(it.id) }
        VoiceForwardMiuixDialog.showContacts(
            activity = activity,
            contacts = orderedContacts,
            title = title,
            confirmText = "保存",
            showGroupFilter = false,
            initialSelectedIds = initialSelectedIds,
            allowEmpty = true,
            showClearSelectionAction = true,
            onConfirm = { selected ->
                val previous = store.entry(snsId)
                val likes = selected.map { FakeSnsLike(it.id, it.label) }.let { values ->
                    if (prefs.getBoolean(
                            MomentsFakeInteractionSettings.KEY_FAKE_LIKE_RANDOM_ORDER,
                            MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_RANDOM_ORDER
                        )
                    ) values.shuffled() else values
                }
                store.setLikes(snsId, likes)
                runtime.apply(activity, target, previous)
                toast(activity, if (selected.isEmpty()) "已恢复真实点赞" else "伪集赞已更新")
            },
            onDismiss = {}
        )
    }

    private fun clearFakeLikes(activity: Activity, target: SnsContextMenuTarget) {
        VoiceForwardMiuixDialog.showConfirm(
            activity = activity,
            title = "清空伪集赞",
            message = "清空后恢复该朋友圈的真实点赞显示。",
            onResult = { confirmed ->
                if (!confirmed) return@showConfirm
                val snsId = target.snsId ?: return@showConfirm
                val previous = store.entry(snsId)
                store.setLikes(snsId, emptyList())
                runtime.apply(activity, target, previous)
                toast(activity, "已恢复真实点赞")
            },
            onDismiss = {}
        )
    }

    fun showFakeComments(activity: Activity, target: SnsContextMenuTarget) {
        val snsId = target.snsId ?: return
        val comments = store.entry(snsId).comments
        val choices = buildList {
            add("新增伪评论" to "选择好友、填写评论并设置评论时间")
            comments.forEach { comment ->
                add(
                    "${displayName(comment)} · ${formatTime(comment.createTimeMillis)}" to
                        comment.content
                )
            }
            if (comments.isNotEmpty()) {
                add("清空伪评论" to "移除该朋友圈的全部伪评论，恢复真实评论显示")
            }
        }
        VoiceForwardMiuixDialog.showListChoices(
            activity = activity,
            title = "朋友圈伪评论",
            summary = if (comments.isEmpty()) "暂无伪评论" else "共 ${comments.size} 条，列表顺序即显示顺序",
            choices = choices,
            onSelected = { index ->
                when {
                    index == 0 -> addComment(activity, target)
                    index in 1..comments.size -> editComment(activity, target, index - 1)
                    else -> clearComments(activity, target)
                }
            },
            onDismiss = {}
        )
    }

    private fun addComment(activity: Activity, target: SnsContextMenuTarget) {
        if (target.snsId == null) return
        loadFriends(activity, "选择评论好友") { contacts ->
            VoiceForwardMiuixDialog.showContacts(
                activity = activity,
                contacts = contacts,
                title = "选择评论好友",
                confirmText = "下一步",
                showGroupFilter = false,
                singleSelection = false,
                onConfirm = selected@ { selected ->
                    if (selected.isEmpty()) return@selected
                    val contents = MomentsFakeInteractionSettings.commentContents(prefs)
                    val randomContent = prefs.getBoolean(
                        MomentsFakeInteractionSettings.KEY_FAKE_COMMENT_RANDOM_CONTENT,
                        MomentsFakeInteractionSettings.DEFAULT_FAKE_COMMENT_RANDOM_CONTENT
                    ) && contents.isNotEmpty()
                    if (randomContent) {
                        showCommentTimePicker(activity, target, selected) { contents.random() }
                        return@selected
                    }
                    VoiceForwardMiuixDialog.showTextInput(
                        activity = activity,
                        title = "填写伪评论",
                        summary = if (selected.size == 1) selected.single().label else "已选择 ${selected.size} 位好友",
                        placeholder = "请输入评论内容",
                        maxLength = MAX_COMMENT_LENGTH,
                        onConfirm = { content ->
                            showCommentTimePicker(activity, target, selected) { content }
                        },
                        onDismiss = {}
                    )
                },
                onDismiss = {}
            )
        }
    }

    private fun showCommentTimePicker(
        activity: Activity,
        target: SnsContextMenuTarget,
        selected: List<VoiceForwardMiuixDialog.ContactItem>,
        contentProvider: () -> String
    ) {
        val snsId = target.snsId ?: return
        VoiceForwardMiuixDialog.showDateTimeInput(
            activity = activity,
            title = "设置评论时间",
            initialTimeMillis = System.currentTimeMillis(),
            onConfirm = { time ->
                val previous = store.entry(snsId)
                val comments = previous.comments.toMutableList()
                selected.forEach { friend ->
                    comments += FakeSnsComment.create(
                        friend.id,
                        friend.label,
                        contentProvider(),
                        time
                    )
                }
                store.setComments(snsId, comments)
                runtime.apply(activity, target, previous)
                toast(activity, "已添加 ${selected.size} 条伪评论")
                showFakeComments(activity, target)
            },
            onDismiss = {}
        )
    }

    private fun editComment(activity: Activity, target: SnsContextMenuTarget, index: Int) {
        val snsId = target.snsId ?: return
        val comments = store.entry(snsId).comments
        val comment = comments.getOrNull(index) ?: return
        val actions = buildList {
            add("修改评论内容" to comment.content)
            add("修改评论时间" to formatTime(comment.createTimeMillis))
            if (index > 0) add("上移" to "提前一位显示")
            if (index < comments.lastIndex) add("下移" to "延后一位显示")
            add("删除伪评论" to "恢复该位置的真实显示")
        }
        VoiceForwardMiuixDialog.showChoices(
            activity = activity,
            title = displayName(comment),
            summary = "${formatTime(comment.createTimeMillis)} · 第 ${index + 1} 条",
            choices = actions,
            onSelected = { selected ->
                when (actions[selected].first) {
                    "修改评论内容" -> editCommentText(activity, target, index, comment)
                    "修改评论时间" -> editCommentTime(activity, target, index, comment)
                    "上移" -> moveComment(activity, target, index, index - 1)
                    "下移" -> moveComment(activity, target, index, index + 1)
                    else -> deleteComment(activity, target, index)
                }
            },
            onDismiss = {}
        )
    }

    private fun editCommentText(
        activity: Activity,
        target: SnsContextMenuTarget,
        index: Int,
        comment: FakeSnsComment
    ) {
        VoiceForwardMiuixDialog.showTextInput(
            activity = activity,
            title = "修改评论内容",
            summary = displayName(comment),
            initialValue = comment.content,
            maxLength = MAX_COMMENT_LENGTH,
            onConfirm = { content ->
                updateComment(activity, target, index) { it.copy(content = content) }
            },
            onDismiss = {}
        )
    }

    private fun editCommentTime(
        activity: Activity,
        target: SnsContextMenuTarget,
        index: Int,
        comment: FakeSnsComment
    ) {
        VoiceForwardMiuixDialog.showDateTimeInput(
            activity = activity,
            title = "修改评论时间",
            initialTimeMillis = comment.createTimeMillis,
            onConfirm = { time ->
                updateComment(activity, target, index) { it.copy(createTimeMillis = time) }
            },
            onDismiss = {}
        )
    }

    private fun updateComment(
        activity: Activity,
        target: SnsContextMenuTarget,
        index: Int,
        transform: (FakeSnsComment) -> FakeSnsComment
    ) {
        val snsId = target.snsId ?: return
        val previous = store.entry(snsId)
        val comments = previous.comments.toMutableList()
        val old = comments.getOrNull(index) ?: return
        comments[index] = transform(old)
        store.setComments(snsId, comments)
        runtime.apply(activity, target, previous)
        showFakeComments(activity, target)
    }

    private fun moveComment(
        activity: Activity,
        target: SnsContextMenuTarget,
        from: Int,
        to: Int
    ) {
        val snsId = target.snsId ?: return
        val previous = store.entry(snsId)
        val comments = previous.comments.toMutableList()
        if (from !in comments.indices || to !in comments.indices) return
        val value = comments.removeAt(from)
        comments.add(to, value)
        store.setComments(snsId, comments)
        runtime.apply(activity, target, previous)
        showFakeComments(activity, target)
    }

    private fun deleteComment(activity: Activity, target: SnsContextMenuTarget, index: Int) {
        VoiceForwardMiuixDialog.showConfirm(
            activity = activity,
            title = "删除伪评论",
            message = "只会删除这条本地伪评论。",
            onResult = { confirmed ->
                if (!confirmed) return@showConfirm
                val snsId = target.snsId ?: return@showConfirm
                val previous = store.entry(snsId)
                val comments = previous.comments.toMutableList()
                if (index in comments.indices) comments.removeAt(index)
                store.setComments(snsId, comments)
                runtime.apply(activity, target, previous)
                showFakeComments(activity, target)
            },
            onDismiss = {}
        )
    }

    private fun clearComments(activity: Activity, target: SnsContextMenuTarget) {
        VoiceForwardMiuixDialog.showConfirm(
            activity = activity,
            title = "清空伪评论",
            message = "清空后恢复该朋友圈的真实评论显示。",
            onResult = { confirmed ->
                if (!confirmed) return@showConfirm
                val snsId = target.snsId ?: return@showConfirm
                val previous = store.entry(snsId)
                store.setComments(snsId, emptyList())
                runtime.apply(activity, target, previous)
                toast(activity, "已恢复真实评论")
            },
            onDismiss = {}
        )
    }

    private fun loadFriends(
        activity: Activity,
        title: String,
        onLoaded: (List<VoiceForwardMiuixDialog.ContactItem>) -> Unit
    ) {
        val includeNonFriends = prefs.getBoolean(
            MomentsFakeInteractionSettings.KEY_FAKE_COMMENT_USE_NON_FRIENDS,
            MomentsFakeInteractionSettings.DEFAULT_FAKE_COMMENT_USE_NON_FRIENDS
        )
        val loading = VoiceForwardMiuixDialog.showLoading(
            activity = activity,
            title = title,
            message = if (includeNonFriends) "正在载入好友和非好友..." else "正在载入好友列表...",
            onDismiss = {}
        )
        Thread({
            val contacts = runCatching {
                MomentsFakeLikeCandidateRepository.loadForComments(includeNonFriends)
            }.onFailure {
                logger("加载朋友圈伪互动好友失败", it)
            }.getOrDefault(emptyList())
            activity.runOnUiThread {
                loading.close()
                activity.window?.decorView?.postOnAnimation {
                    if (activity.isFinishing || activity.isDestroyed) return@postOnAnimation
                    if (contacts.isEmpty()) toast(activity, "没有可选择的好友") else onLoaded(contacts)
                }
            }
        }, "Hchat-MomentsFakeContacts").apply { isDaemon = true }.start()
    }

    private fun formatTime(timeMillis: Long): String {
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(timeMillis))
        }.getOrDefault("")
    }

    private fun displayName(comment: FakeSnsComment): String {
        val stored = comment.authorDisplayName.trim()
        if (stored.isNotEmpty() && stored != comment.authorWxId) return stored
        return legacyDisplayNameCache.getOrPut(comment.authorWxId) {
            runCatching { WeChatApis.contacts()?.getDisplayName(comment.authorWxId) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: comment.authorWxId
        }
    }

    private fun toast(activity: Activity, text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val EDITABLE_FORWARD_TYPES = setOf(1, 2, 5, 15, 54)
        private const val MAX_COMMENT_LENGTH = 1000
        internal const val VIRTUAL_LIKE_PREFIX = "wxid_hchat_fake_like_"
        private val VIRTUAL_SURNAMES = arrayOf(
            "林", "陈", "周", "吴", "许", "沈", "顾", "江", "苏", "叶",
            "陆", "夏", "宋", "唐", "程", "韩", "乔", "余", "温", "方"
        )
        private val VIRTUAL_GIVEN_NAMES = arrayOf(
            "安", "宁", "然", "辰", "希", "言", "清", "予", "景", "知",
            "一", "若", "雨", "星", "月", "晨", "乐", "念", "可", "禾"
        )
    }
}
