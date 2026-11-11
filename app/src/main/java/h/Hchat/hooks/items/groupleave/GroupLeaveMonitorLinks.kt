package h.Hchat.hooks.items.groupleave

import android.net.Uri

object GroupLeaveMonitorLinks {
    const val PROFILE_URI_PREFIX = "weixin://weixinhongbao/hchat/group_leave_profile/"

    fun profileUri(memberId: String): String = PROFILE_URI_PREFIX + Uri.encode(memberId)

    fun memberIdFromUri(uri: String): String {
        if (!uri.startsWith(PROFILE_URI_PREFIX)) return ""
        return Uri.decode(uri.substring(PROFILE_URI_PREFIX.length)).orEmpty().trim()
    }
}
