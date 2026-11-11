package h.Hchat.hooks.items.groupnicknamecolor

import android.content.Context
import android.content.SharedPreferences
import h.Hchat.hooks.items.membertitle.MemberTitleStore
import h.Hchat.hooks.items.realtail.RealNameTailStore
import h.Hchat.preferences.HchatStorage

class GroupNicknameColorStore(context: Context) {
    private val prefs: SharedPreferences =
        HchatStorage.preferences(context, GroupNicknameColorSettings.PREFS_NAME)

    fun isEnabled(): Boolean =
        prefs.getBoolean(GroupNicknameColorSettings.KEY_ENABLE, GroupNicknameColorSettings.DEFAULT_ENABLE)

    fun color(): MemberTitleStore.ColorSpec? = MemberTitleStore.parseColorSpec(
        prefs.getString(GroupNicknameColorSettings.KEY_COLOR, GroupNicknameColorSettings.DEFAULT_COLOR)
    )

    fun weight(): Int = RealNameTailStore.cleanWeight(
        prefs.getInt(GroupNicknameColorSettings.KEY_WEIGHT, GroupNicknameColorSettings.DEFAULT_WEIGHT)
    )

    companion object {
        fun cleanColorSpec(value: String?): String = RealNameTailStore.cleanColorSpec(value)

        fun cleanWeight(value: Int): Int = RealNameTailStore.cleanWeight(value)
    }
}
