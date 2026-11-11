package h.Hchat.hooks.items.momentsfake

import android.content.SharedPreferences
import org.json.JSONArray

object MomentsFakeInteractionSettings {
    const val PREFS_NAME = "Hchat_moments_fake_interaction_config"
    const val KEY_FAKE_LIKE_ENABLE = "fake_like_enable"
    const val KEY_FAKE_COMMENT_ENABLE = "fake_comment_enable"
    const val KEY_FAKE_FORWARD_ENABLE = "fake_forward_enable"
    const val KEY_FAKE_FORWARD_HIDE_MENU = "fake_forward_hide_menu"
    const val KEY_FAKE_FORWARD_MENU_TEXT = "fake_forward_menu_text"
    const val KEY_FAKE_FORWARD_DEBUG_LOG = "fake_forward_debug_log"
    const val KEY_FAKE_FORWARD_IDS = "fake_forward_ids_v1"
    const val KEY_ENTRIES = "entries_v1"
    const val KEY_PENDING_RESTORE_ALL = "pending_restore_all_v1"
    const val KEY_PENDING_RESTORE_LIKES = "pending_restore_likes_v1"
    const val KEY_PENDING_RESTORE_COMMENTS = "pending_restore_comments_v1"
    const val KEY_FAKE_LIKE_USE_NON_FRIENDS = "fake_like_use_non_friends"
    const val KEY_FAKE_COMMENT_USE_NON_FRIENDS = "fake_comment_use_non_friends"
    const val KEY_FAKE_LIKE_RANDOM_ORDER = "fake_like_random_order"
    const val KEY_FAKE_LIKE_AUTO_SELECT = "fake_like_auto_select"
    const val KEY_FAKE_LIKE_AUTO_SELECT_COUNT = "fake_like_auto_select_count"
    const val KEY_FAKE_LIKE_EXCLUDED_IDS = "fake_like_excluded_ids"
    const val KEY_FAKE_LIKE_HIDE_MENU = "fake_like_hide_menu"
    const val KEY_FAKE_COMMENT_HIDE_MENU = "fake_comment_hide_menu"
    const val KEY_FAKE_LIKE_MENU_TEXT = "fake_like_menu_text"
    const val KEY_FAKE_COMMENT_MENU_TEXT = "fake_comment_menu_text"
    const val KEY_FAKE_COMMENT_RANDOM_CONTENT = "fake_comment_random_content"
    const val KEY_FAKE_COMMENT_CONTENTS = "fake_comment_contents_v1"

    const val DEFAULT_FAKE_LIKE_ENABLE = false
    const val DEFAULT_FAKE_COMMENT_ENABLE = false
    const val DEFAULT_FAKE_FORWARD_ENABLE = false
    const val DEFAULT_FAKE_FORWARD_HIDE_MENU = false
    const val DEFAULT_FAKE_FORWARD_MENU_TEXT = "伪转发[H]"
    const val DEFAULT_FAKE_FORWARD_DEBUG_LOG = false
    const val DEFAULT_FAKE_LIKE_USE_NON_FRIENDS = false
    const val DEFAULT_FAKE_COMMENT_USE_NON_FRIENDS = false
    const val DEFAULT_FAKE_LIKE_RANDOM_ORDER = false
    const val DEFAULT_FAKE_LIKE_AUTO_SELECT = false
    const val DEFAULT_FAKE_LIKE_AUTO_SELECT_COUNT = 50
    const val DEFAULT_FAKE_LIKE_HIDE_MENU = false
    const val DEFAULT_FAKE_COMMENT_HIDE_MENU = false
    const val DEFAULT_FAKE_LIKE_MENU_TEXT = "伪集赞[H]"
    const val DEFAULT_FAKE_COMMENT_MENU_TEXT = "伪评论[H]"
    const val DEFAULT_FAKE_COMMENT_RANDOM_CONTENT = false

    fun commentContents(prefs: SharedPreferences): List<String> {
        val raw = prefs.getString(KEY_FAKE_COMMENT_CONTENTS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveCommentContents(prefs: SharedPreferences, values: List<String>): Boolean {
        val normalized = values.map { it.trim() }.filter { it.isNotEmpty() }
        return prefs.edit().putString(
            KEY_FAKE_COMMENT_CONTENTS,
            JSONArray().apply { normalized.forEach { put(it) } }.toString()
        ).commit()
    }
}
