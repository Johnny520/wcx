package h.Hchat.hooks.items.grouplabel

import android.content.Context
import h.Hchat.preferences.HchatStorage
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class GroupChatLabel(
    val id: String,
    val name: String,
    val groupIds: Set<String>
)

object GroupChatLabelStore {
    const val PREFS_NAME = "Hchat_group_chat_labels"
    private const val KEY_LABELS = "labels"

    @JvmStatic
    fun load(context: Context): List<GroupChatLabel> {
        val raw = HchatStorage.preferences(context, PREFS_NAME).getString(KEY_LABELS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val name = item.optString("name").trim()
                    if (id.isBlank() || name.isBlank()) continue
                    val groups = linkedSetOf<String>()
                    val groupArray = item.optJSONArray("groups") ?: JSONArray()
                    for (groupIndex in 0 until groupArray.length()) {
                        groupArray.optString(groupIndex).trim()
                            .takeIf { it.isNotBlank() }
                            ?.let(groups::add)
                    }
                    add(GroupChatLabel(id, name, groups))
                }
            }
        }.getOrDefault(emptyList())
    }

    @JvmStatic
    fun save(context: Context, labels: List<GroupChatLabel>) {
        val array = JSONArray()
        labels.forEach { label ->
            val name = label.name.trim()
            if (name.isBlank()) return@forEach
            val groups = JSONArray()
            label.groupIds.map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { groups.put(it) }
            array.put(
                JSONObject()
                    .put("id", label.id.ifBlank { UUID.randomUUID().toString() })
                    .put("name", name)
                    .put("groups", groups)
            )
        }
        HchatStorage.preferences(context, PREFS_NAME)
            .edit()
            .putString(KEY_LABELS, array.toString())
            .apply()
    }

    @JvmStatic
    fun newLabel(): GroupChatLabel = GroupChatLabel(UUID.randomUUID().toString(), "", emptySet())
}
