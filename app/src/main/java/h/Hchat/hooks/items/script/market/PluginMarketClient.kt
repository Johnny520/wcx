package h.Hchat.hooks.items.script.market

import android.content.Context
import h.Hchat.utils.HLog
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

object PluginMarketClient {
    private const val TAG = "[Hchat:PluginMarket]"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    fun moduleUpdate(context: Context): Result<JSONObject> = runCatching {
        request(context, HttpMethod.GET, listOf("v1", "module", "update"), null, null).dataObject()
    }

    fun moduleVersionHistory(context: Context): Result<List<JSONObject>> = runCatching {
        val root = request(context, HttpMethod.GET, listOf("v1", "module", "versions"), null, null).dataObject()
        val items = root.optJSONArray("items") ?: JSONArray()
        buildList {
            for (index in 0 until items.length()) items.optJSONObject(index)?.let(::add)
        }
    }

    fun health(context: Context): Result<PluginMarketHealth> = runCatching {
        val data = request(context, HttpMethod.GET, listOf("health"), null, null).dataObject()
        PluginMarketHealth(
            status = data.optString("status"),
            database = data.optString("database"),
            message = data.optString("message", "服务正常")
        )
    }

    fun list(
        context: Context,
        query: String = "",
        sort: String = "latest",
        limit: Int = Int.MAX_VALUE
    ): Result<PluginMarketPage> = runCatching {
        val root = request(
            context = context,
            method = HttpMethod.GET,
            path = listOf("v1", "plugins"),
            body = null,
            ownerToken = null,
            query = mapOf(
                "q" to query.trim(),
                "sort" to sort.takeIf { it == "latest" || it == "downloads" }.orEmpty().ifBlank { "latest" },
                "limit" to limit.coerceAtLeast(1).toString()
            )
        )
        val data = root.optJSONObject("data")
        val array = root.optJSONArray("data") ?: data?.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(PluginMarketPlugin.fromJson(item))
            }
        }
        PluginMarketPage(
            items = items,
            count = data?.optInt("count", items.size) ?: items.size,
            limit = data?.optInt("limit", limit) ?: limit
        )
    }

    fun detail(context: Context, remotePluginId: String): Result<PluginMarketPlugin> = runCatching {
        require(remotePluginId.isNotBlank()) { "远程插件 ID 不能为空" }
        val root = request(context, HttpMethod.GET, listOf("v1", "plugins", remotePluginId), null, null)
        PluginMarketPlugin.fromJson(root.dataObject())
    }

    fun followStatus(context: Context, remotePluginId: String, identity: PluginMarketUserIdentity): Result<Boolean> = runCatching {
        request(context, HttpMethod.GET, listOf("v1", "plugins", remotePluginId, "follow"), null, null,
            query = mapOf("userWxId" to identity.wxId)).dataObject().optBoolean("following", false)
    }

    fun follow(context: Context, remotePluginId: String, identity: PluginMarketUserIdentity): Result<Boolean> = runCatching {
        request(context, HttpMethod.POST, listOf("v1", "plugins", remotePluginId, "follow"), identity.toJson(), null)
            .dataObject().optBoolean("following", false)
    }

    fun unfollow(context: Context, remotePluginId: String, identity: PluginMarketUserIdentity): Result<Boolean> = runCatching {
        request(context, HttpMethod.DELETE, listOf("v1", "plugins", remotePluginId, "follow"), identity.toJson(), null)
            .dataObject().optBoolean("following", false)
    }

    fun like(
context: Context,
        remotePluginId: String,
        identity: PluginMarketUserIdentity
    ): Result<PluginMarketLikeResult> = runCatching {
        require(remotePluginId.isNotBlank()) { "远程插件 ID 不能为空" }
        val data = request(
            context,
            HttpMethod.POST,
            listOf("v1", "plugins", remotePluginId, "likes"),
            identity.toJson(),
            null
        ).dataObject()
        PluginMarketLikeResult.fromJson(data)
    }

    fun likeStatus(
        context: Context,
        remotePluginId: String,
        identity: PluginMarketUserIdentity
    ): Result<PluginMarketLikeResult> = runCatching {
        require(remotePluginId.isNotBlank()) { "远程插件 ID 不能为空" }
        val data = request(
            context = context,
            method = HttpMethod.GET,
            path = listOf("v1", "plugins", remotePluginId, "likes"),
            body = null,
            ownerToken = null,
            query = mapOf("userWxId" to identity.wxId)
        ).dataObject()
        PluginMarketLikeResult.fromJson(data)
    }

    fun unlike(
        context: Context,
        remotePluginId: String,
        identity: PluginMarketUserIdentity
    ): Result<PluginMarketLikeResult> = runCatching {
        require(remotePluginId.isNotBlank()) { "远程插件 ID 不能为空" }
        val data = request(
            context,
            HttpMethod.DELETE,
            listOf("v1", "plugins", remotePluginId, "likes"),
            identity.toJson(),
            null
        ).dataObject()
        PluginMarketLikeResult.fromJson(data)
    }

    fun comments(
        context: Context,
        remotePluginId: String,
        identity: PluginMarketUserIdentity?,
        limit: Int = Int.MAX_VALUE
    ): Result<PluginMarketCommentPage> = runCatching {
        require(remotePluginId.isNotBlank()) { "远程插件 ID 不能为空" }
        val safeLimit = limit.coerceAtLeast(1)
        val data = request(
            context = context,
            method = HttpMethod.GET,
            path = listOf("v1", "plugins", remotePluginId, "comments"),
            body = null,
            ownerToken = null,
            query = buildMap {
                put("limit", safeLimit.toString())
                identity?.wxId?.takeIf { it.isNotBlank() }?.let { put("userWxId", it) }
            }
        ).dataObject()
        val items = data.optJSONArray("items") ?: JSONArray()
        PluginMarketCommentPage(
            items = buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    add(PluginMarketComment.fromJson(item))
                }
            },
            total = data.optLong("total", 0L).coerceAtLeast(0L),
            limit = data.optInt("limit", safeLimit)
        )
    }

    fun addComment(
        context: Context,
        remotePluginId: String,
        identity: PluginMarketUserIdentity,
        content: String
    ): Result<PluginMarketCommentMutation> = runCatching {
        require(remotePluginId.isNotBlank()) { "远程插件 ID 不能为空" }
        val data = request(
            context,
            HttpMethod.POST,
            listOf("v1", "plugins", remotePluginId, "comments"),
            identity.toJson().apply { put("content", content) },
            null
        ).dataObject()
        val comment = data.optJSONObject("comment")?.let(PluginMarketComment::fromJson)
            ?: error("插件仓库未返回新评论")
        PluginMarketCommentMutation(
            comment = comment,
            commentCount = data.optLong("commentCount", 0L).coerceAtLeast(0L)
        )
    }

    fun replyComment(
        context: Context,
        remotePluginId: String,
        parentCommentId: String,
        identity: PluginMarketUserIdentity,
        content: String
    ): Result<PluginMarketCommentMutation> = runCatching {
        require(remotePluginId.isNotBlank()) { "远程插件 ID 不能为空" }
        require(parentCommentId.isNotBlank()) { "被回复评论 ID 不能为空" }
        val data = request(
            context,
            HttpMethod.POST,
            listOf(
                "v1",
                "plugins",
                remotePluginId,
                "comments",
                parentCommentId,
                "replies"
            ),
            identity.toJson().apply { put("content", content) },
            null
        ).dataObject()
        val comment = data.optJSONObject("comment")?.let(PluginMarketComment::fromJson)
            ?: error("插件仓库未返回新回复")
        PluginMarketCommentMutation(
            comment = comment,
            commentCount = data.optLong("commentCount", 0L).coerceAtLeast(0L)
        )
    }

    fun notifications(
        context: Context,
        identity: PluginMarketUserIdentity,
        limit: Int = Int.MAX_VALUE
    ): Result<PluginMarketNotificationPage> = runCatching {
        val safeLimit = limit.coerceAtLeast(1)
        val data = request(
            context = context,
            method = HttpMethod.GET,
            path = listOf("v1", "notifications"),
            body = null,
            ownerToken = null,
            query = mapOf(
                "userWxId" to identity.wxId,
                "limit" to safeLimit.toString()
            )
        ).dataObject()
        val items = data.optJSONArray("items") ?: JSONArray()
        PluginMarketNotificationPage(
            items = buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    add(PluginMarketNotification.fromJson(item))
                }
            },
            total = data.optLong("total", 0L).coerceAtLeast(0L),
            unreadCount = data.optLong("unreadCount", 0L).coerceAtLeast(0L),
            limit = data.optInt("limit", safeLimit)
        )
    }

    fun markNotificationsRead(
        context: Context,
        identity: PluginMarketUserIdentity,
        notificationIds: Collection<String>? = null
    ): Result<PluginMarketNotificationReadResult> = runCatching {
        val body = identity.toJson()
        notificationIds?.let { ids ->
            body.put("notificationIds", JSONArray().apply { ids.forEach { put(it) } })
        }
        val data = request(
            context,
            HttpMethod.POST,
            listOf("v1", "notifications", "read"),
            body,
            null
        ).dataObject()
        PluginMarketNotificationReadResult(
            markedCount = data.optLong("markedCount", 0L).coerceAtLeast(0L),
            unreadCount = data.optLong("unreadCount", 0L).coerceAtLeast(0L),
            readAt = data.optString("readAt")
        )
    }

    fun deleteComment(
        context: Context,
        remotePluginId: String,
        commentId: String,
        identity: PluginMarketUserIdentity
    ): Result<PluginMarketCommentMutation> = runCatching {
        require(remotePluginId.isNotBlank()) { "远程插件 ID 不能为空" }
        require(commentId.isNotBlank()) { "评论 ID 不能为空" }
        val data = request(
            context,
            HttpMethod.DELETE,
            listOf("v1", "plugins", remotePluginId, "comments", commentId),
            identity.toJson(),
            null
        ).dataObject()
        require(data.optBoolean("deleted", false)) { "插件仓库未确认评论已删除" }
        PluginMarketCommentMutation(
            commentCount = data.optLong("commentCount", 0L).coerceAtLeast(0L)
        )
    }

    fun history(context: Context, remotePluginId: String): Result<List<PluginMarketHistoryVersion>> = runCatching {
        require(remotePluginId.isNotBlank()) { "远程插件 ID 不能为空" }
        val data = request(
            context,
            HttpMethod.GET,
            listOf("v1", "plugins", remotePluginId, "snapshots"),
            null,
            null
        ).dataObject()
        val items = data.optJSONArray("items") ?: JSONArray()
        buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                add(PluginMarketHistoryVersion.fromJson(item))
            }
        }
    }

    fun historyDetail(
        context: Context,
        remotePluginId: String,
        versionId: String
    ): Result<PluginMarketPlugin> = runCatching {
        require(remotePluginId.isNotBlank()) { "远程插件 ID 不能为空" }
        require(versionId.isNotBlank()) { "历史版本 ID 不能为空" }
        val root = request(
            context,
            HttpMethod.GET,
            listOf("v1", "plugins", remotePluginId, "snapshots", versionId),
            null,
            null
        )
        PluginMarketPlugin.fromJson(root.dataObject())
    }

    fun recordDownload(
        context: Context,
        remotePluginId: String,
        versionId: String,
        eventId: String
    ): Result<Long> = runCatching {
        require(remotePluginId.isNotBlank()) { "远程插件 ID 不能为空" }
        require(versionId.isNotBlank()) { "远程插件版本 ID 不能为空" }
        require(eventId.isNotBlank()) { "下载事件 ID 不能为空" }
        val data = request(
            context = context,
            method = HttpMethod.POST,
            path = listOf("v1", "plugins", remotePluginId, "downloads"),
            body = JSONObject().apply {
                put("versionId", versionId)
                put("eventId", eventId)
            },
            ownerToken = null
        ).dataObject()
        data.optLong("downloadCount", -1L).also {
            require(it >= 0L) { "插件仓库未返回有效下载量" }
        }
    }

    fun upload(
        context: Context,
        packageData: PluginMarketUploadPackage,
        existingOwnership: PluginMarketOwnership? = null
    ): Result<PluginMarketOwnership> = runCatching {
        val root = request(
            context = context,
            method = HttpMethod.POST,
            path = listOf("v1", "plugins"),
            body = packageData.toJson(existingOwnership?.remotePluginId),
            ownerToken = existingOwnership?.ownerToken
        )
        val data = root.dataObject()
        val ownership = data.optJSONObject("ownership") ?: data
        val remoteId = ownership.optString("remotePluginId")
            .ifBlank { ownership.optString("pluginId") }
            .ifBlank { ownership.optString("id") }
            .ifBlank { existingOwnership?.remotePluginId.orEmpty() }
            .trim()
        val ownerToken = ownership.optString("ownerToken")
            .ifBlank { existingOwnership?.ownerToken.orEmpty() }
            .trim()
        require(remoteId.isNotBlank()) { "上传成功但服务端未返回 remotePluginId" }
        require(ownerToken.isNotBlank()) { "上传成功但服务端未返回 ownerToken" }
        PluginMarketOwnership(
            remotePluginId = remoteId,
            ownerToken = ownerToken,
            reviewStatus = PluginMarketReviewStatus.fromWireValue(data.optString("reviewStatus"))
        )
    }

    fun delete(
        context: Context,
        ownership: PluginMarketOwnership
    ): Result<Unit> = runCatching {
        require(ownership.remotePluginId.isNotBlank()) { "远程插件 ID 不能为空" }
        request(
            context = context,
            method = HttpMethod.DELETE,
            path = listOf("v1", "plugins", ownership.remotePluginId),
            body = JSONObject().apply {
                put("installId", PluginMarketSettings.installId(context))
                put("ownerToken", ownership.ownerToken)
            },
            ownerToken = ownership.ownerToken
        )
        Unit
    }

    private fun request(
        context: Context,
        method: HttpMethod,
        path: List<String>,
        body: JSONObject?,
        ownerToken: String?,
        query: Map<String, String> = emptyMap()
    ): JSONObject {
        val url = buildUrl(context, path, query)
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Hchat-Install-Id", PluginMarketSettings.installId(context))
        ownerToken?.trim()?.takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Bearer $it")
        }
        when (method) {
            HttpMethod.GET -> builder.get()
            HttpMethod.POST -> builder.post((body ?: JSONObject()).toString().toRequestBody(JSON_MEDIA_TYPE))
            HttpMethod.DELETE -> builder.delete((body ?: JSONObject()).toString().toRequestBody(JSON_MEDIA_TYPE))
        }
        try {
            httpClient.newCall(builder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val root = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrElse {
                    throw PluginMarketException(
                        "插件仓库返回了无效 JSON: HTTP ${response.code}",
                        response.code,
                        it
                    )
                }
                if (!root.has("ok")) {
                    throw PluginMarketException("插件仓库响应缺少 ok 字段", response.code)
                }
                val ok = root.optBoolean("ok", false)
                if (!response.isSuccessful || !ok) {
                    throw PluginMarketException(
                        errorMessage(root, response.code),
                        response.code,
                        errorCode = errorCode(root)
                    )
                }
                return root
            }
        } catch (error: PluginMarketException) {
            HLog.e("$TAG 请求失败 method=${method.name} path=/${path.joinToString("/")} ${error.message}", error)
            throw error
        } catch (error: IOException) {
            val message = if (error is SocketTimeoutException) {
                "插件仓库请求超时"
            } else {
                "插件仓库网络请求失败: ${error.message ?: "网络不可用"}"
            }
            val wrapped = PluginMarketException(message, cause = error)
            HLog.e("$TAG 网络请求失败 method=${method.name} path=/${path.joinToString("/")}", wrapped)
            throw wrapped
        } catch (error: Throwable) {
            val wrapped = PluginMarketException("插件仓库请求失败: ${error.message ?: error.javaClass.simpleName}", cause = error)
            HLog.e("$TAG 请求异常 method=${method.name} path=/${path.joinToString("/")}", wrapped)
            throw wrapped
        }
    }

    private fun buildUrl(context: Context, path: List<String>, query: Map<String, String>): HttpUrl {
        val rawBase = PluginMarketSettings.serviceUrl(context).trim()
        val base = runCatching { rawBase.toHttpUrlOrNull() }.getOrNull()
            ?: throw PluginMarketException("插件仓库地址无效，请先配置完整的 HTTPS 地址")
        require(base.scheme == "https" || base.scheme == "http") { "插件仓库只支持 HTTP/HTTPS 地址" }
        val builder = base.newBuilder()
        path.forEach { segment ->
            require(segment.isNotBlank() && segment != "." && segment != "..") { "插件仓库路径无效" }
            builder.addPathSegment(segment)
        }
        query.forEach { (key, value) ->
            if (value.isNotBlank()) builder.addQueryParameter(key, value)
        }
        return builder.build()
    }

    private fun errorMessage(root: JSONObject, httpCode: Int): String {
        val error = root.opt("error")
        val detail = when (error) {
            is JSONObject -> error.optString("message").ifBlank { error.toString() }
            null, JSONObject.NULL -> ""
            else -> error.toString()
        }
        return detail.ifBlank { "插件仓库请求失败: HTTP $httpCode" }
    }

    private fun errorCode(root: JSONObject): String? = root.optJSONObject("error")
        ?.optString("code")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun JSONObject.dataObject(): JSONObject {
        val data = opt("data")
        return when (data) {
            is JSONObject -> data
            else -> JSONObject()
        }
    }

    private enum class HttpMethod {
        GET,
        POST,
        DELETE
    }
}
