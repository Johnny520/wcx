package h.Hchat.hooks.items.zombiecheck

import android.content.Context
import h.Hchat.preferences.HchatStorage
import org.json.JSONArray
import org.json.JSONObject

class ZombieCheckSettings(context: Context) {
    private val prefs = HchatStorage.preferences(context, PREFS_NAME)

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLE, DEFAULT_ENABLE)
    fun minDelaySeconds(): Int = prefs.getInt(KEY_MIN_DELAY_SECONDS, DEFAULT_MIN_DELAY_SECONDS).coerceIn(0, 60)
    fun maxDelaySeconds(): Int = prefs.getInt(KEY_MAX_DELAY_SECONDS, DEFAULT_MAX_DELAY_SECONDS)
        .coerceIn(minDelaySeconds(), 120)
    fun timeoutSeconds(): Int = prefs.getInt(KEY_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS).coerceIn(5, 60)
    fun maxRetries(): Int = prefs.getInt(KEY_MAX_RETRIES, DEFAULT_MAX_RETRIES).coerceIn(0, 5)
    fun autoTag(): Boolean = prefs.getBoolean(KEY_AUTO_TAG, DEFAULT_AUTO_TAG)
    fun labelName(): String = prefs.getString(KEY_LABEL_NAME, DEFAULT_LABEL_NAME).orEmpty().trim()
        .ifBlank { DEFAULT_LABEL_NAME }
    fun autoDelete(): Boolean = prefs.getBoolean(KEY_AUTO_DELETE, DEFAULT_AUTO_DELETE)
    fun clearRecord(): Boolean = prefs.getBoolean(KEY_CLEAR_RECORD, DEFAULT_CLEAR_RECORD)
    fun deleteDelaySeconds(): Int = prefs.getInt(KEY_DELETE_DELAY_SECONDS, DEFAULT_DELETE_DELAY_SECONDS)
        .coerceIn(0, 300)
    fun keepAwake(): Boolean = prefs.getBoolean(KEY_KEEP_AWAKE, DEFAULT_KEEP_AWAKE)

    fun targetIds(): Set<String> = readStringSet(KEY_TARGET_IDS)
    fun excludedIds(): Set<String> = readStringSet(KEY_EXCLUDED_IDS)
    fun pendingIds(): List<String> = readStringList(KEY_PENDING_IDS)
    fun totalCount(): Int = prefs.getInt(KEY_TOTAL_COUNT, 0).coerceAtLeast(0)
    fun results(): List<ZombieCheckResult> = decodeResults(prefs.getString(KEY_RESULTS, ""))

    fun saveTargetIds(values: Set<String>) = writeStringSet(KEY_TARGET_IDS, values)
    fun saveExcludedIds(values: Set<String>) = writeStringSet(KEY_EXCLUDED_IDS, values)

    fun saveProgress(pendingIds: List<String>, totalCount: Int, results: List<ZombieCheckResult>) {
        prefs.edit()
            .putString(KEY_PENDING_IDS, encodeStrings(pendingIds))
            .putInt(KEY_TOTAL_COUNT, totalCount.coerceAtLeast(0))
            .putString(KEY_RESULTS, encodeResults(results))
            .apply()
    }

    fun clearProgress() {
        prefs.edit()
            .remove(KEY_PENDING_IDS)
            .remove(KEY_TOTAL_COUNT)
            .remove(KEY_RESULTS)
            .apply()
    }

    private fun readStringSet(key: String): Set<String> = readStringList(key).toSet()

    private fun readStringList(key: String): List<String> {
        val raw = prefs.getString(key, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }.distinct()
        }.getOrDefault(emptyList())
    }

    private fun writeStringSet(key: String, values: Set<String>) {
        prefs.edit().putString(key, encodeStrings(values)).apply()
    }

    private fun encodeStrings(values: Iterable<String>): String {
        val array = JSONArray()
        values.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { array.put(it) }
        return array.toString()
    }

    private fun encodeResults(values: List<ZombieCheckResult>): String {
        val array = JSONArray()
        values.forEach { result ->
            array.put(JSONObject().apply {
                put("wxid", result.wxid)
                put("name", result.name)
                put("type", result.type.name)
                put("message", result.message)
            })
        }
        return array.toString()
    }

    private fun decodeResults(raw: String?): List<ZombieCheckResult> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val wxid = item.optString("wxid").trim()
                    if (wxid.isEmpty()) continue
                    val type = runCatching { ZombieCheckResultType.valueOf(item.optString("type")) }
                        .getOrDefault(ZombieCheckResultType.UNKNOWN)
                    add(
                        ZombieCheckResult(
                            wxid = wxid,
                            name = item.optString("name").ifBlank { wxid },
                            type = type,
                            message = item.optString("message")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val PREFS_NAME = "Hchat_zombie_check_config"

        const val KEY_ENABLE = "zombie_check_enable"
        const val KEY_MIN_DELAY_SECONDS = "zombie_check_min_delay_seconds"
        const val KEY_MAX_DELAY_SECONDS = "zombie_check_max_delay_seconds"
        const val KEY_TIMEOUT_SECONDS = "zombie_check_timeout_seconds"
        const val KEY_MAX_RETRIES = "zombie_check_max_retries"
        const val KEY_AUTO_TAG = "zombie_check_auto_tag"
        const val KEY_LABEL_NAME = "zombie_check_label_name"
        const val KEY_AUTO_DELETE = "zombie_check_auto_delete"
        const val KEY_CLEAR_RECORD = "zombie_check_clear_record"
        const val KEY_DELETE_DELAY_SECONDS = "zombie_check_delete_delay_seconds"
        const val KEY_KEEP_AWAKE = "zombie_check_keep_awake"
        const val KEY_TARGET_IDS = "zombie_check_target_ids"
        const val KEY_EXCLUDED_IDS = "zombie_check_excluded_ids"

        private const val KEY_PENDING_IDS = "zombie_check_pending_ids"
        private const val KEY_TOTAL_COUNT = "zombie_check_total_count"
        private const val KEY_RESULTS = "zombie_check_results"

        const val DEFAULT_ENABLE = false
        const val DEFAULT_MIN_DELAY_SECONDS = 2
        const val DEFAULT_MAX_DELAY_SECONDS = 4
        const val DEFAULT_TIMEOUT_SECONDS = 15
        const val DEFAULT_MAX_RETRIES = 2
        const val DEFAULT_AUTO_TAG = true
        const val DEFAULT_LABEL_NAME = "僵尸粉"
        const val DEFAULT_AUTO_DELETE = false
        const val DEFAULT_CLEAR_RECORD = false
        const val DEFAULT_DELETE_DELAY_SECONDS = 3
        const val DEFAULT_KEEP_AWAKE = false
    }
}

enum class ZombieCheckResultType {
    NORMAL,
    DEAD,
    UNKNOWN
}

data class ZombieCheckResult(
    val wxid: String,
    val name: String,
    val type: ZombieCheckResultType,
    val message: String
)

data class ZombieCheckSnapshot(
    val ready: Boolean = false,
    val running: Boolean = false,
    val status: String = "等待检测",
    val currentName: String = "",
    val totalCount: Int = 0,
    val pendingCount: Int = 0,
    val results: List<ZombieCheckResult> = emptyList(),
    val logs: List<String> = emptyList(),
    val deleting: Boolean = false,
    val deleteTotalCount: Int = 0,
    val deleteCompletedCount: Int = 0,
    val deleteSuccessCount: Int = 0,
    val deleteFailureCount: Int = 0
) {
    val checkedCount: Int get() = results.size
    val deadCount: Int get() = results.count { it.type == ZombieCheckResultType.DEAD }
    val unknownCount: Int get() = results.count { it.type == ZombieCheckResultType.UNKNOWN }
}

data class ZombieCheckActionResult(val success: Boolean, val message: String)
