package com.Johnny.wcx.features.items.chat

import android.app.Activity
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.ui.WeContactPrefsScreenApi
import com.Johnny.wcx.features.api.ui.WeContactPrefsScreenApi.IContactInfoProvider
import com.Johnny.wcx.features.api.ui.WeContactPrefsScreenApi.PreferenceItem
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.currentWxId
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.android.showToastSuspend
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Feature(
    name = "查看群成员邀请者",
    categories = ["联系人与群组"],
    description = "在群成员详情页面添加入口, 可查看该成员的进群邀请者"
)
object DisplayGroupMemberInviter : SwitchFeature(), IContactInfoProvider {

    private const val TAG = "DisplayGroupMemberInviter"

    private const val PREF_KEY = "member_inviter"

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }

    // ── IContactInfoProvider ──────────────────────────────────────────────────

    /** Only shown for an individual group member (not the group itself), inside a group chat. */
    override fun getContactInfoItem(activity: Activity): List<PreferenceItem> {
        if (!WeCurrentConversationApi.value.isGroupChatWxId) return emptyList()
        val memberId = activity.currentWxId ?: return emptyList()
        if (memberId.isGroupChatWxId) return emptyList()

        return listOf(
            PreferenceItem(
                key = PREF_KEY,
                title = "查看进群邀请者",
                summary = "点击查看",
                position = 1
            )
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != PREF_KEY) return false

        val groupId = WeCurrentConversationApi.value.takeIf { it.isGroupChatWxId } ?: return true
        val memberId = activity.currentWxId ?: return true

        showToast(activity, "正在查询...")
        CoroutineScope(Dispatchers.IO).launch {
            val inviterId = runCatching { WeDatabaseApi.getGroupMemberInviter(groupId, memberId) }
                .onFailure { WeLogger.e(TAG, "failed to resolve inviter for $memberId in $groupId", it) }
                .getOrDefault("")

            val message = when {
                inviterId.isEmpty() -> "无邀请者记录 (可能是群主/前群主/早期成员)"
                inviterId == memberId -> "该成员为扫码/自行进群"
                else -> {
                    val inviterName = runCatching { WeDatabaseApi.getDisplayName(inviterId) }
                        .getOrDefault(inviterId)
                    val groupNick = runCatching {
                        WeDatabaseApi.getGroupMemberDisplayName(groupId, inviterId)
                    }.getOrDefault("")

                    val nameLabel = if (groupNick.isNotBlank() && groupNick != inviterName) {
                        "$inviterName ($groupNick)"
                    } else {
                        inviterName
                    }
                    "邀请者: $nameLabel"
                }
            }

            showToastSuspend(activity, message)
        }
        return true
    }
}
