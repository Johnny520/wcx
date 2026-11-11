package h.Hchat.hooks.items.script.market

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class PluginMarketFile(
    val name: String,
    val content: String,
    val sha256: String,
    val size: Long,
    val encoding: String = ENCODING_UTF8
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("content", content)
        put("sha256", sha256)
        put("size", size)
        put("encoding", encoding)
    }

    fun decodedBytes(): ByteArray = when (encoding) {
        ENCODING_UTF8 -> content.toByteArray(Charsets.UTF_8)
        ENCODING_BASE64 -> Base64.decode(content, Base64.NO_WRAP)
        else -> error("不支持的插件文件编码: $encoding")
    }

    companion object {
        const val ENCODING_UTF8 = "utf8"
        const val ENCODING_BASE64 = "base64"

        fun fromJson(source: JSONObject, fallbackName: String? = null): PluginMarketFile {
            val name = source.optString("name").ifBlank { fallbackName.orEmpty() }
            val content = source.optString("content", source.optString("text"))
            val encoding = source.optString("encoding", ENCODING_UTF8).trim().lowercase()
            val decodedSize = runCatching {
                when (encoding) {
                    ENCODING_BASE64 -> Base64.decode(content, Base64.NO_WRAP).size.toLong()
                    else -> content.toByteArray(Charsets.UTF_8).size.toLong()
                }
            }.getOrDefault(-1L)
            val size = source.optLong("size", decodedSize)
            return PluginMarketFile(
                name = name,
                content = content,
                sha256 = source.optString("sha256").trim().lowercase(),
                size = size,
                encoding = encoding
            )
        }
    }
}

data class PluginMarketUploadPackage(
    val localPluginId: String,
    val name: String,
    val author: String,
    val version: String,
    val updateTime: String,
    val releaseNotes: String,
    val uploaderWxId: String,
    val uploaderWeChatId: String,
    val uploaderNickname: String,
    val files: List<PluginMarketFile>
) {
    fun toJson(remotePluginId: String? = null): JSONObject = JSONObject().apply {
        put("sourcePluginId", localPluginId)
        remotePluginId?.takeIf { it.isNotBlank() }?.let { put("pluginId", it) }
        put("displayName", name)
        put("author", author)
        put("versionName", version.ifBlank { "1.0.0" })
        put("releaseNotes", releaseNotes)
        put("uploaderWxId", uploaderWxId)
        put("uploaderWeChatId", uploaderWeChatId)
        put("uploaderNickname", uploaderNickname)
        put("files", JSONArray().apply {
            files.forEach { file ->
                put(JSONObject().apply {
                    put("name", file.name)
                    put("content", file.content)
                    put("encoding", file.encoding)
                })
            }
        })
    }
}

data class PluginMarketPlugin(
    val remotePluginId: String,
    val sourcePluginId: String,
    val name: String,
    val author: String,
    val version: String,
    val versionId: String,
    val updateTime: String,
    val downloadCount: Long,
    val likeCount: Long,
    val commentCount: Long,
    val description: String,
    val files: List<PluginMarketFile>
) {
    companion object {
        fun fromJson(source: JSONObject): PluginMarketPlugin {
            val versionObject = source.optJSONObject("latestVersion") ?: source.optJSONObject("snapshot")
            val files = source.optJSONArray("files")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        add(PluginMarketFile.fromJson(item))
                    }
                }
            } ?: emptyList()
            return PluginMarketPlugin(
                remotePluginId = source.optString("remotePluginId")
                    .ifBlank { source.optString("pluginId") }
                    .ifBlank { source.optString("id") },
                sourcePluginId = source.optString("sourcePluginId"),
                name = source.optString("displayName").ifBlank { source.optString("name") },
                author = source.optString("author"),
                version = versionObject?.optString("versionName")
                    .orEmpty().ifBlank { source.optString("version") },
                versionId = versionObject?.optString("versionId")
                    .orEmpty().ifBlank { source.optString("versionId") },
                updateTime = versionObject?.optString("createdAt")
                    .orEmpty().ifBlank { source.optString("updatedAt") }
                    .ifBlank { source.optString("updateTime") },
                downloadCount = source.optLong("downloadCount", 0L).coerceAtLeast(0L),
                likeCount = source.optLong("likeCount", 0L).coerceAtLeast(0L),
                commentCount = source.optLong("commentCount", 0L).coerceAtLeast(0L),
                description = source.optString("description", source.optString("summary")),
                files = files
            )
        }
    }
}

data class PluginMarketUserIdentity(
    val wxId: String,
    val weChatId: String,
    val nickname: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("userWxId", wxId)
        put("userWeChatId", weChatId)
        put("userNickname", nickname)
    }
}

data class PluginMarketLikeResult(
    val liked: Boolean,
    val likeCount: Long
) {
    companion object {
        fun fromJson(source: JSONObject): PluginMarketLikeResult = PluginMarketLikeResult(
            liked = source.optBoolean("liked", false),
            likeCount = source.optLong("likeCount", 0L).coerceAtLeast(0L)
        )
    }
}

data class PluginMarketComment(
    val commentId: String,
    val remotePluginId: String,
    val userNickname: String,
    val content: String,
    val createdAt: String,
    val parentCommentId: String,
    val replyToNickname: String,
    val canDelete: Boolean
) {
    companion object {
        fun fromJson(source: JSONObject): PluginMarketComment = PluginMarketComment(
            commentId = source.optString("commentId").trim(),
            remotePluginId = source.optString("pluginId").trim(),
            userNickname = source.optString("userNickname").trim(),
            content = source.optString("content"),
            createdAt = source.optString("createdAt"),
            parentCommentId = source.optString("parentCommentId").trim(),
            replyToNickname = source.optString("replyToNickname").trim(),
            canDelete = source.optBoolean("canDelete", false)
        )
    }
}

data class PluginMarketCommentPage(
    val items: List<PluginMarketComment>,
    val total: Long,
    val limit: Int
)

data class PluginMarketCommentMutation(
    val comment: PluginMarketComment? = null,
    val commentCount: Long
)

data class PluginMarketNotification(
    val notificationId: String,
    val remotePluginId: String,
    val pluginName: String,
    val replyCommentId: String,
    val parentCommentId: String,
    val actorNickname: String,
    val content: String,
    val originalContent: String,
    val createdAt: String,
    val read: Boolean,
    val type: String
) {
    companion object {
        fun fromJson(source: JSONObject): PluginMarketNotification = PluginMarketNotification(
            notificationId = source.optString("notificationId").trim(),
            remotePluginId = source.optString("pluginId").trim(),
            pluginName = source.optString("pluginName").trim(),
            replyCommentId = source.optString("replyCommentId").trim(),
            parentCommentId = source.optString("parentCommentId").trim(),
            actorNickname = source.optString("actorNickname").trim(),
            content = source.optString("content"),
            originalContent = source.optString("originalContent"),
            createdAt = source.optString("createdAt"),
            read = source.optBoolean("read", false),
            type = source.optString("type", "comment_reply")
        )
    }
}

data class PluginMarketNotificationPage(
    val items: List<PluginMarketNotification>,
    val total: Long,
    val unreadCount: Long,
    val limit: Int
)

data class PluginMarketNotificationReadResult(
    val markedCount: Long,
    val unreadCount: Long,
    val readAt: String
)

data class PluginMarketHistoryVersion(
    val versionId: String,
    val versionName: String,
    val contentHash: String,
    val totalSize: Long,
    val createdAt: String,
    val releaseNotes: String
) {
    companion object {
        fun fromJson(source: JSONObject): PluginMarketHistoryVersion = PluginMarketHistoryVersion(
            versionId = source.optString("versionId"),
            versionName = source.optString("versionName"),
            contentHash = source.optString("contentHash"),
            totalSize = source.optLong("totalSize", 0L).coerceAtLeast(0L),
            createdAt = source.optString("createdAt"),
            releaseNotes = source.optString("releaseNotes")
        )
    }
}

data class PluginMarketPage(
    val items: List<PluginMarketPlugin>,
    val count: Int,
    val limit: Int
)

enum class PluginMarketReviewStatus {
    PENDING,
    APPROVED;

    companion object {
        fun fromWireValue(value: String?): PluginMarketReviewStatus = when (value?.trim()?.lowercase()) {
            "pending" -> PENDING
            else -> APPROVED
        }
    }
}

data class PluginMarketOwnership(
    val remotePluginId: String,
    val ownerToken: String,
    val reviewStatus: PluginMarketReviewStatus = PluginMarketReviewStatus.APPROVED
)

data class PluginMarketHealth(
    val status: String,
    val database: String,
    val message: String
)

data class PluginMarketInstallResult(
    val localPluginId: String,
    val directoryPath: String,
    val replacedExisting: Boolean,
    val downloadCount: Long? = null
)

class PluginMarketException(
    message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
    val errorCode: String? = null
) : IllegalStateException(message, cause)
