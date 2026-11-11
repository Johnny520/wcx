package h.Hchat.preferences

import android.content.Context
import android.content.SharedPreferences
import h.Hchat.utils.HLog

class ConfigStore(private val hostContext: Context) {
    fun getFeaturePrefs(featureId: String): SharedPreferences {
        return HchatStorage.preferences(hostContext, FEATURE_PREFS_PREFIX + featureId)
    }

    fun getBoolean(featureId: String, key: String, def: Boolean): Boolean {
        return try {
            getFeaturePrefs(featureId).getBoolean(key, def)
        } catch (_: Throwable) {
            def
        }
    }

    fun getInt(featureId: String, key: String, def: Int): Int {
        return try {
            getFeaturePrefs(featureId).getInt(key, def)
        } catch (_: Throwable) {
            def
        }
    }

    fun getLong(featureId: String, key: String, def: Long): Long {
        return try {
            getFeaturePrefs(featureId).getLong(key, def)
        } catch (_: Throwable) {
            def
        }
    }

    fun getString(featureId: String, key: String, def: String): String {
        return try {
            getFeaturePrefs(featureId).getString(key, def) ?: def
        } catch (_: Throwable) {
            def
        }
    }

    fun getFloat(featureId: String, key: String, def: Float): Float {
        return try {
            getFeaturePrefs(featureId).getFloat(key, def)
        } catch (_: Throwable) {
            def
        }
    }

    fun putBoolean(featureId: String, key: String, value: Boolean) {
        try {
            getFeaturePrefs(featureId).edit().putBoolean(key, value).apply()
        } catch (e: Throwable) {
            HLog.e("$TAG putBoolean 失败: $featureId/$key ${e.message}", e)
        }
    }

    fun putInt(featureId: String, key: String, value: Int) {
        try {
            getFeaturePrefs(featureId).edit().putInt(key, value).apply()
        } catch (e: Throwable) {
            HLog.e("$TAG putInt 失败: $featureId/$key ${e.message}", e)
        }
    }

    fun putLong(featureId: String, key: String, value: Long) {
        try {
            getFeaturePrefs(featureId).edit().putLong(key, value).apply()
        } catch (e: Throwable) {
            HLog.e("$TAG putLong 失败: $featureId/$key ${e.message}", e)
        }
    }

    fun putString(featureId: String, key: String, value: String?) {
        try {
            getFeaturePrefs(featureId).edit().putString(key, value).apply()
        } catch (e: Throwable) {
            HLog.e("$TAG putString 失败: $featureId/$key ${e.message}", e)
        }
    }

    fun putFloat(featureId: String, key: String, value: Float) {
        try {
            getFeaturePrefs(featureId).edit().putFloat(key, value).apply()
        } catch (e: Throwable) {
            HLog.e("$TAG putFloat 失败: $featureId/$key ${e.message}", e)
        }
    }

    fun getGlobalPrefs(): SharedPreferences {
        return HchatStorage.preferences(hostContext, GLOBAL_PREFS)
    }

    fun reopenGlobalPrefsIfFilesMissing(): SharedPreferences {
        return HchatStorage.reopenIfFilesMissing(hostContext, GLOBAL_PREFS)
    }

    fun getGlobalBoolean(key: String, def: Boolean): Boolean {
        return try {
            getGlobalPrefs().getBoolean(key, def)
        } catch (_: Throwable) {
            def
        }
    }

    fun getGlobalInt(key: String, def: Int): Int {
        return try {
            getGlobalPrefs().getInt(key, def)
        } catch (_: Throwable) {
            def
        }
    }

    fun getGlobalString(key: String, def: String): String {
        return try {
            getGlobalPrefs().getString(key, def) ?: def
        } catch (_: Throwable) {
            def
        }
    }

    fun putGlobalBoolean(key: String, value: Boolean) {
        try {
            getGlobalPrefs().edit().putBoolean(key, value).apply()
        } catch (e: Throwable) {
            HLog.e("$TAG putGlobalBoolean 失败: $key ${e.message}", e)
        }
    }

    fun putGlobalInt(key: String, value: Int) {
        try {
            getGlobalPrefs().edit().putInt(key, value).apply()
        } catch (e: Throwable) {
            HLog.e("$TAG putGlobalInt 失败: $key ${e.message}", e)
        }
    }

    fun putGlobalString(key: String, value: String?) {
        try {
            getGlobalPrefs().edit().putString(key, value).apply()
        } catch (e: Throwable) {
            HLog.e("$TAG putGlobalString 失败: $key ${e.message}", e)
        }
    }

    fun clearFeature(featureId: String) {
        try {
            getFeaturePrefs(featureId).edit().clear().apply()
        } catch (e: Throwable) {
            HLog.e("$TAG clearFeature 失败: $featureId ${e.message}", e)
        }
    }

    fun clearGlobal() {
        try {
            getGlobalPrefs().edit().clear().apply()
        } catch (e: Throwable) {
            HLog.e("$TAG clearGlobal 失败: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "[Hchat:ConfigStore]"
        private const val GLOBAL_PREFS = "Hchat_global_config"
        private const val FEATURE_PREFS_PREFIX = "Hchat_feature_"
    }
}
