package h.Hchat.preferences

import android.content.Context
import h.Hchat.utils.HLog

object TermsGate {
    const val AGREEMENT_TEXT = "我同意"
    const val TERMS_VERSION = 1

    private const val KEY_ACCEPTED = "terms_accepted"
    private const val KEY_VERSION = "terms_version"

    fun isAccepted(context: Context): Boolean {
        val store = ConfigStore(context)
        return store.getGlobalBoolean(KEY_ACCEPTED, false) &&
            store.getGlobalInt(KEY_VERSION, 0) == TERMS_VERSION
    }

    fun isAccepted(store: ConfigStore): Boolean {
        return store.getGlobalBoolean(KEY_ACCEPTED, false) &&
            store.getGlobalInt(KEY_VERSION, 0) == TERMS_VERSION
    }

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
