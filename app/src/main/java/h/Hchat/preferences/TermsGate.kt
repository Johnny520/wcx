package h.Hchat.preferences

import android.content.Context
import h.Hchat.utils.HLog

object TermsGate {
    const val AGREEMENT_TEXT = "我同意"
    const val TERMS_VERSION = 1

    private const val KEY_ACCEPTED = "terms_accepted"
    private const val KEY_VERSION = "terms_version"

    // WCX：模块无用户协议页，直接放行（与上游语义兼容，isAccepted 恒 true）。
    fun isAccepted(context: Context): Boolean = true

    fun isAccepted(store: ConfigStore): Boolean = true

    fun accept(context: Context): Boolean {
        val store = ConfigStore(context)
        val prefs = store.reopenGlobalPrefsIfFilesMissing()
        return try {
            val committed = prefs.edit()
                .putBoolean(KEY_ACCEPTED, true)
                .putInt(KEY_VERSION, TERMS_VERSION)
                .commit()
            committed && prefs.getBoolean(KEY_ACCEPTED, false) &&
                prefs.getInt(KEY_VERSION, 0) == TERMS_VERSION
        } catch (e: Throwable) {
            HLog.e("[Hchat:TermsGate] 保存协议状态失败: ${e.message}", e)
            false
        }
    }
}
