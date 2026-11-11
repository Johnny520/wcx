package h.Hchat.hooks.items.momentsfake

import android.content.Context
import h.Hchat.preferences.HchatStorage
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class FakeSnsInteraction(
    val likes: List<FakeSnsLike> = emptyList(),
    val comments: List<FakeSnsComment> = emptyList(),
    val pendingLikes: List<FakeSnsLike> = emptyList(),
    val pendingComments: List<FakeSnsComment> = emptyList()
) {
    val isEmpty: Boolean get() = likes.isEmpty() &&
        comments.isEmpty() &&
        pendingLikes.isEmpty() &&
        pendingComments.isEmpty()
}

internal data class FakeSnsLike(
    val wxId: String,
    val displayName: String
)

internal data class FakeSnsComment(
    val id: String,
    val authorWxId: String,
    val authorDisplayName: String,
    val content: String,
    val createTimeMillis: Long
) {
    companion object {
        fun create(
            authorWxId: String,
            authorDisplayName: String,
            content: String,
            createTimeMillis: Long
        ): FakeSnsComment {
            return FakeSnsComment(
                id = UUID.randomUUID().toString(),
                authorWxId = authorWxId.trim(),
                authorDisplayName = authorDisplayName.trim(),
                content = content.trim(),
                createTimeMillis = createTimeMillis.coerceAtLeast(1L)
            )
        }
    }
}

internal class MomentsFakeInteractionStore(context: Context) {
    private val prefs = HchatStorage.preferences(
        context,
        MomentsFakeInteractionSettings.PREFS_NAME
    )
    private var cachedRaw: String? = null
    private var cachedEntries: LinkedHashMap<String, FakeSnsInteraction> = linkedMapOf()

    @Synchronized
    fun entry(snsId: String): FakeSnsInteraction {
        val value = readEntries()[snsId]
        return value?.copy(
            likes = value.likes.toList(),
            comments = value.comments.toList(),
            pendingLikes = value.pendingLikes.toList(),
            pendingComments = value.pendingComments.toList()
        ) ?: FakeSnsInteraction()
    }

    @Synchronized
    fun hasEntry(snsId: String): Boolean = readEntries().containsKey(snsId)

    @Synchronized
    fun allIds(): Set<String> = LinkedHashSet(readEntries().keys)

    @Synchronized
    fun setLikes(snsId: String, likes: List<FakeSnsLike>): FakeSnsInteraction {
        val entries = readEntries()
        val old = entries[snsId] ?: FakeSnsInteraction()
        val normalized = likes.mapNotNull { like ->
            val wxId = like.wxId.trim()
            if (wxId.isEmpty()) null else like.copy(
                wxId = wxId,
                displayName = like.displayName.trim().ifEmpty { wxId }
            )
        }.distinctBy { it.wxId }
        val selectedIds = normalized.mapTo(hashSetOf()) { it.wxId }
        val pending = (old.pendingLikes + old.likes)
            .filterNot { selectedIds.contains(it.wxId) }
            .distinctBy { it.wxId }
        val next = old.copy(likes = normalized, pendingLikes = pending)
        putOrRemove(entries, snsId, next)
        saveEntries(entries)
        return next
    }

    @Synchronized
    fun setComments(snsId: String, comments: List<FakeSnsComment>): FakeSnsInteraction {
        val entries = readEntries()
        val old = entries[snsId] ?: FakeSnsInteraction()
        val normalized = comments.mapNotNull { comment ->
            val author = comment.authorWxId.trim()
            val content = comment.content.trim()
            if (author.isEmpty() || content.isEmpty()) {
                null
            } else {
                comment.copy(
                    authorWxId = author,
                    authorDisplayName = comment.authorDisplayName.trim().ifEmpty { author },
                    content = content,
                    createTimeMillis = comment.createTimeMillis.coerceAtLeast(1L)
                )
            }
        }.distinctBy { it.id }
        val currentKeys = normalized.mapTo(hashSetOf(), ::commentKey)
        val pending = (old.pendingComments + old.comments)
            .filterNot { currentKeys.contains(commentKey(it)) }
            .distinctBy(::commentKey)
        val next = old.copy(comments = normalized, pendingComments = pending)
        putOrRemove(entries, snsId, next)
        saveEntries(entries)
        return next
    }

    @Synchronized
    fun acknowledgePending(snsId: String, processed: FakeSnsInteraction) {
        val entries = readEntries()
        val old = entries[snsId] ?: return
        val processedLikeIds = processed.pendingLikes.mapTo(hashSetOf()) { it.wxId }
        val processedCommentKeys = processed.pendingComments.mapTo(hashSetOf(), ::commentKey)
        val next = old.copy(
            pendingLikes = old.pendingLikes.filterNot { processedLikeIds.contains(it.wxId) },
            pendingComments = old.pendingComments.filterNot {
                processedCommentKeys.contains(commentKey(it))
            }
        )
        putOrRemove(entries, snsId, next)
        saveEntries(entries)
    }

    @Synchronized
    fun clearEntry(snsId: String) {
        val entries = readEntries()
        if (entries.remove(snsId) != null) saveEntries(entries)
    }

    @Synchronized
    fun clearAll(): Set<String> {
        val ids = LinkedHashSet(readEntries().keys)
        prefs.edit().remove(MomentsFakeInteractionSettings.KEY_ENTRIES).commit()
        cachedRaw = ""
        cachedEntries = linkedMapOf()
        return ids
    }

    @Synchronized
    fun clearTypes(clearLikes: Boolean, clearComments: Boolean) {
        if (!clearLikes && !clearComments) return
        if (clearLikes && clearComments) {
            clearAll()
            return
        }
        val entries = readEntries()
        entries.keys.toList().forEach { snsId ->
            val old = entries[snsId] ?: return@forEach
            val next = old.copy(
                likes = if (clearLikes) emptyList() else old.likes,
                comments = if (clearComments) emptyList() else old.comments,
                pendingLikes = if (clearLikes) emptyList() else old.pendingLikes,
                pendingComments = if (clearComments) emptyList() else old.pendingComments
            )
            putOrRemove(entries, snsId, next)
        }
        saveEntries(entries)
    }

    private fun readEntries(): LinkedHashMap<String, FakeSnsInteraction> {
        val raw = prefs.getString(MomentsFakeInteractionSettings.KEY_ENTRIES, "").orEmpty()
        if (raw == cachedRaw) return cachedEntries
        val parsed = linkedMapOf<String, FakeSnsInteraction>()
        if (raw.isNotBlank()) {
            runCatching {
                val root = JSONObject(raw)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val snsId = keys.next().trim()
                    val value = root.optJSONObject(snsId) ?: continue
                    val likes = value.optJSONArray(JSON_LIKES).toLikes()
                    val comments = value.optJSONArray(JSON_COMMENTS).toComments()
                    val pendingLikes = value.optJSONArray(JSON_PENDING_LIKES).toLikes()
                    val pendingComments = value.optJSONArray(JSON_PENDING_COMMENTS).toComments()
                    val entry = FakeSnsInteraction(
                        likes.distinctBy { it.wxId },
                        comments.distinctBy { it.id },
                        pendingLikes.distinctBy { it.wxId },
                        pendingComments.distinctBy(::commentKey)
                    )
                    if (snsId.isNotEmpty() && !entry.isEmpty) parsed[snsId] = entry
                }
            }
        }
        cachedRaw = raw
        cachedEntries = parsed
        return cachedEntries
    }

    private fun saveEntries(entries: LinkedHashMap<String, FakeSnsInteraction>) {
        val root = JSONObject()
        entries.forEach { (snsId, entry) ->
            if (entry.isEmpty) return@forEach
            val value = JSONObject()
            value.put(JSON_LIKES, JSONArray().apply {
                entry.likes.forEach { like ->
                    put(
                        JSONObject()
                            .put(JSON_WX_ID, like.wxId)
                            .put(JSON_DISPLAY_NAME, like.displayName)
                    )
                }
            })
            value.put(JSON_COMMENTS, JSONArray().apply {
                entry.comments.forEach { comment ->
                    put(comment.toJson())
                }
            })
            value.put(JSON_PENDING_LIKES, JSONArray().apply {
                entry.pendingLikes.forEach { like ->
                    put(
                        JSONObject()
                            .put(JSON_WX_ID, like.wxId)
                            .put(JSON_DISPLAY_NAME, like.displayName)
                    )
                }
            })
            value.put(JSON_PENDING_COMMENTS, JSONArray().apply {
                entry.pendingComments.forEach { comment ->
                    put(comment.toJson())
                }
            })
            root.put(snsId, value)
        }
        val raw = root.toString()
        prefs.edit().putString(MomentsFakeInteractionSettings.KEY_ENTRIES, raw).commit()
        cachedRaw = raw
        cachedEntries = LinkedHashMap(entries)
    }

    private fun putOrRemove(
        entries: LinkedHashMap<String, FakeSnsInteraction>,
        snsId: String,
        entry: FakeSnsInteraction
    ) {
        if (entry.isEmpty) entries.remove(snsId) else entries[snsId] = entry
    }

    private fun JSONArray?.toLikes(): List<FakeSnsLike> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val raw = opt(index)
                val wxId = when (raw) {
                    is JSONObject -> raw.optString(JSON_WX_ID).trim()
                    else -> raw?.toString().orEmpty().trim()
                }
                if (wxId.isEmpty()) continue
                val displayName = (raw as? JSONObject)
                    ?.optString(JSON_DISPLAY_NAME)
                    ?.trim()
                    .orEmpty()
                    .ifEmpty { wxId }
                add(FakeSnsLike(wxId, displayName))
            }
        }
    }

    private fun JSONArray?.toComments(): List<FakeSnsComment> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val value = optJSONObject(index) ?: continue
                val id = value.optString(JSON_ID).trim().ifEmpty { UUID.randomUUID().toString() }
                val author = value.optString(JSON_AUTHOR).trim()
                val authorName = value.optString(JSON_AUTHOR_NAME).trim().ifEmpty { author }
                val content = value.optString(JSON_CONTENT).trim()
                val time = value.optLong(JSON_TIME, 0L)
                if (author.isNotEmpty() && content.isNotEmpty() && time > 0L) {
                    add(FakeSnsComment(id, author, authorName, content, time))
                }
            }
        }
    }

    private fun FakeSnsComment.toJson(): JSONObject {
        return JSONObject()
            .put(JSON_ID, id)
            .put(JSON_AUTHOR, authorWxId)
            .put(JSON_AUTHOR_NAME, authorDisplayName)
            .put(JSON_CONTENT, content)
            .put(JSON_TIME, createTimeMillis)
    }

    private fun commentKey(comment: FakeSnsComment): Triple<String, String, String> {
        return Triple(comment.id, comment.authorWxId, comment.content)
    }

    companion object {
        private const val JSON_LIKES = "likes"
        private const val JSON_COMMENTS = "comments"
        private const val JSON_PENDING_LIKES = "pendingLikes"
        private const val JSON_PENDING_COMMENTS = "pendingComments"
        private const val JSON_WX_ID = "wxId"
        private const val JSON_DISPLAY_NAME = "displayName"
        private const val JSON_ID = "id"
        private const val JSON_AUTHOR = "author"
        private const val JSON_AUTHOR_NAME = "authorName"
        private const val JSON_CONTENT = "content"
        private const val JSON_TIME = "time"
    }
}
