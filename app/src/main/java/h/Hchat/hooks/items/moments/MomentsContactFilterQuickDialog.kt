package h.Hchat.hooks.items.moments

import android.app.Activity
import android.widget.Toast
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.model.WeChatContact
import h.Hchat.preferences.HchatStorage
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog
import h.Hchat.utils.HLog
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal object MomentsContactFilterQuickDialog {
    private const val TAG = "[Hchat:${MomentsContactFilterFeature.ID}]"

    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val prefs = HchatStorage.preferences(activity, MomentsContactFilterSettings.PREFS_NAME)
        val enabled = prefs.getBoolean(
            MomentsContactFilterSettings.KEY_ENABLE,
            MomentsContactFilterSettings.DEFAULT_ENABLE
        )
        val mode = prefs.getInt(
            MomentsContactFilterSettings.KEY_MODE,
            MomentsContactFilterSettings.DEFAULT_MODE
        )
        val targets = parseTargets(
            prefs.getString(
                MomentsContactFilterSettings.KEY_TARGETS,
                MomentsContactFilterSettings.DEFAULT_TARGETS
            ).orEmpty()
        )
        VoiceForwardMiuixDialog.showChoices(
            activity = activity,
            title = "朋友圈过滤",
            summary = buildString {
                append(if (enabled) "已启用" else "未启用")
                append(" · ")
                append(if (mode == MomentsContactFilterSettings.MODE_INCLUDE_ONLY) "只看所选好友" else "过滤所选好友")
                append(" · 已选 ${targets.size} 人")
            },
            choices = listOf(
                (if (enabled) "关闭朋友圈过滤" else "开启朋友圈过滤") to
                    if (enabled) "保留当前模式和名单" else "使用当前模式和名单立即生效",
                "过滤所选好友" to
                    if (mode == MomentsContactFilterSettings.MODE_EXCLUDE) "当前模式" else "隐藏所选好友的朋友圈",
                "只看所选好友" to
                    if (mode == MomentsContactFilterSettings.MODE_INCLUDE_ONLY) "当前模式" else "只显示所选好友的朋友圈",
                "修改过滤对象" to if (targets.isEmpty()) {
                    "选择好友、标签或聊天分组"
                } else {
                    "已选择 ${targets.size} 人"
                }
            ),
            onSelected = { index ->
                when (index) {
                    0 -> {
                        prefs.edit()
                            .putBoolean(MomentsContactFilterSettings.KEY_ENABLE, !enabled)
                            .apply()
                        toast(activity, if (enabled) "朋友圈过滤已关闭" else "朋友圈过滤已开启")
                    }
                    1 -> {
                        prefs.edit()
                            .putBoolean(MomentsContactFilterSettings.KEY_ENABLE, true)
                            .putInt(
                                MomentsContactFilterSettings.KEY_MODE,
                                MomentsContactFilterSettings.MODE_EXCLUDE
                            )
                            .apply()
                        toast(activity, "已切换为过滤所选好友")
                    }
                    2 -> {
                        prefs.edit()
                            .putBoolean(MomentsContactFilterSettings.KEY_ENABLE, true)
                            .putInt(
                                MomentsContactFilterSettings.KEY_MODE,
                                MomentsContactFilterSettings.MODE_INCLUDE_ONLY
                            )
                            .apply()
                        toast(activity, "已切换为只看所选好友")
                    }
                    3 -> showTargetPicker(activity, targets)
                }
            },
            onDismiss = {}
        )
    }

    private fun showTargetPicker(activity: Activity, initialTargets: Set<String>) {
        val canceled = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val loading = VoiceForwardMiuixDialog.showLoading(
            activity = activity,
            title = "朋友圈过滤",
            message = "正在载入好友...",
            onDismiss = { if (!finished.get()) canceled.set(true) }
        )
        Thread({
            val result = runCatching { loadContacts(initialTargets) }
            activity.runOnUiThread {
                finished.set(true)
                loading.close()
                val decor = activity.window?.decorView ?: return@runOnUiThread
                decor.postOnAnimation {
                    if (canceled.get() || activity.isFinishing || activity.isDestroyed) {
                        return@postOnAnimation
                    }
                    result.onSuccess { contacts ->
                        VoiceForwardMiuixDialog.showContacts(
                            activity = activity,
                            contacts = contacts,
                            title = "选择朋友圈过滤对象",
                            confirmText = "保存",
                            showGroupFilter = false,
                            initialSelectedIds = initialTargets,
                            allowEmpty = true,
                            showClearSelectionAction = true,
                            onConfirm = { selected ->
                                HchatStorage.preferences(
                                    activity,
                                    MomentsContactFilterSettings.PREFS_NAME
                                ).edit()
                                    .putString(
                                        MomentsContactFilterSettings.KEY_TARGETS,
                                        selected.joinToString("|") { it.id }
                                    )
                                    .apply()
                                toast(activity, "过滤对象已更新")
                            },
                            onDismiss = {}
                        )
                    }.onFailure {
                        HLog.e("$TAG 读取朋友圈过滤对象失败", it)
                        toast(activity, "好友列表载入失败")
                    }
                }
            }
        }, "HchatMomentsFilterPicker").start()
    }

    private fun loadContacts(initialTargets: Set<String>): List<VoiceForwardMiuixDialog.ContactItem> {
        val contacts = WeChatApis.contact().contacts()
            ?: throw IllegalStateException("联系人接口尚未就绪")
        if (!contacts.isAvailable) throw IllegalStateException("联系人接口不可用")

        val labelsByUser = linkedMapOf<String, MutableList<String>>()
        runCatching { contacts.getContactLabelList() }.getOrDefault(emptyList()).forEach { label ->
            val labelName = label.labelName.trim().ifBlank { label.labelId.trim() }
            if (labelName.isBlank()) return@forEach
            label.userNameList.forEach { wxId ->
                val id = wxId.trim()
                if (id.isNotEmpty()) labelsByUser.getOrPut(id) { arrayListOf() }.add(labelName)
            }
        }

        val byId = linkedMapOf<String, WeChatContact>()
        contacts.getPickerContacts().forEach { contact ->
            if (contact.wxId.isNotBlank()) byId[contact.wxId] = contact
        }
        val missing = initialTargets.filterNot(byId::containsKey)
        if (missing.isNotEmpty()) {
            contacts.getContactsByIds(missing).forEach { contact ->
                if (contact.wxId.isNotBlank() && !contact.isGroup() && !contact.isOfficialAccount()) {
                    byId[contact.wxId] = contact
                }
            }
        }
        val conversationOrder = WeChatApis.conversations()
            ?.getRecentConversationUsernames(10000)
            .orEmpty()
            .mapIndexed { index, username -> username to index }
            .toMap()
        return byId.values
            .sortedWith(
                compareBy<WeChatContact> { conversationOrder[it.wxId] ?: Int.MAX_VALUE }
                    .thenBy { it.displayName().lowercase(Locale.US) }
            )
            .map { contact ->
                VoiceForwardMiuixDialog.ContactItem(
                    id = contact.wxId,
                    label = contact.displayName().ifBlank { contact.wxId },
                    group = false,
                    avatarUrl = contact.avatarUrl,
                    avatarBackupUrl = contact.avatarBackupUrl,
                    labels = labelsByUser[contact.wxId].orEmpty().distinct(),
                    searchAliases = listOf(contact.remarkName, contact.nickname, contact.customWxId)
                        .filter(String::isNotBlank)
                        .distinct()
                )
            }
    }

    private fun parseTargets(raw: String): Set<String> {
        return raw.split('|')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toCollection(linkedSetOf())
    }

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
