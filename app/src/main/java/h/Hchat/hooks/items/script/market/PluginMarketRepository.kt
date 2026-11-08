package h.Hchat.hooks.items.script.market

import android.content.Context
import h.Hchat.hooks.api.core.WeChatApis
import java.util.UUID

object PluginMarketRepository {
    fun health(context: Context): Result<PluginMarketHealth> =
        PluginMarketClient.health(context)

    fun list(
        context: Context,
        query: String = "",
        sort: String = "latest",
        limit: Int = Int.MAX_VALUE
    ): Result<PluginMarketPage> = PluginMarketClient.list(context, query, sort, limit)

    fun detail(context: Context, remotePluginId: String): Result<PluginMarketPlugin> = runCatching {
        val detail = PluginMarketClient.detail(context, remotePluginId).getOrThrow()
        PluginMarketInstaller.validateDetail(detail).getOrThrow()
        detail
    }

    fun currentUserIdentity(context: Context): Result<PluginMarketUserIdentity> = runCatching {
        val account = WeChatApis.account() ?: error("当前微信账号资料尚未就绪，请重启微信后重试")
        val wxId = account.selfWxId().trim()
        require(wxId.isNotBlank()) { "无法读取当前账号 wxid，请重启微信后重试" }
        val contact = runCatching { WeChatApis.contacts()?.getContact(wxId) }.getOrNull()
        val weChatId = account.customWxId().trim().ifBlank { contact?.customWxId.orEmpty().trim() }
        val nickname = account.selfName().trim().ifBlank { contact?.nickname.orEmpty().trim() }
        require(wxId.length <= 128) { "当前账号 wxid 长度异常" }
        require(weChatId.length <= 128) { "当前账号微信号长度异常" }
        require(nickname.length <= 100) { "当前账号微信昵称过长" }
        PluginMarketUserIdentity(wxId, weChatId, nickname)
    }

    fun followStatus(context: Context, remotePluginId: String): Result<Boolean> = runCatching {
        val identity = currentUserIdentity(context).getOrThrow()
        PluginMarketClient.followStatus(context, remotePluginId, identity).getOrThrow()
    }

    fun toggleFollow(context: Context, remotePluginId: String, following: Boolean): Result<Boolean> = runCatching {
        val identity = currentUserIdentity(context).getOrThrow()
        if (following) PluginMarketClient.unfollow(context, remotePluginId, identity).getOrThrow()
        else PluginMarketClient.follow(context, remotePluginId, identity).getOrThrow()
    }
    fun likeStatus(context: Context, remotePluginId: String): Result<PluginMarketLikeResult> = runCatching {
        val identity = currentUserIdentity(context).getOrThrow()
        PluginMarketClient.likeStatus(context, remotePluginId, identity).getOrThrow()
    }

    fun like(context: Context, remotePluginId: String): Result<PluginMarketLikeResult> = runCatching {
        val identity = currentUserIdentity(context).getOrThrow()
        PluginMarketClient.like(context, remotePluginId, identity).getOrThrow()
    }

    fun unlike(context: Context, remotePluginId: String): Result<PluginMarketLikeResult> = runCatching {
        val identity = currentUserIdentity(context).getOrThrow()
        PluginMarketClient.unlike(context, remotePluginId, identity).getOrThrow()
    }

    fun comments(
        context: Context,
        remotePluginId: String,
        limit: Int = Int.MAX_VALUE
    ): Result<PluginMarketCommentPage> = runCatching {
        val identity = currentUserIdentity(context).getOrNull()
        PluginMarketClient.comments(context, remotePluginId, identity, limit).getOrThrow()
    }

    fun addComment(
        context: Context,
        remotePluginId: String,
        content: String
    ): Result<PluginMarketCommentMutation> = runCatching {
        val normalizedContent = content.trim()
        require(normalizedContent.isNotBlank()) { "评论内容不能为空" }
        require(normalizedContent.length <= 1000) { "评论内容不能超过 1000 个字符" }
        val identity = currentUserIdentity(context).getOrThrow()
        PluginMarketClient.addComment(
            context,
            remotePluginId,
            identity,
            normalizedContent
        ).getOrThrow()
    }

    fun replyComment(
        context: Context,
        remotePluginId: String,
        parentComment: PluginMarketComment,
        content: String
    ): Result<PluginMarketCommentMutation> = runCatching {
        require(remotePluginId.isNotBlank()) { "远程插件 ID 不能为空" }
        require(parentComment.commentId.isNotBlank()) { "被回复评论 ID 不能为空" }
        require(
            parentComment.remotePluginId.isBlank() ||
                parentComment.remotePluginId == remotePluginId
        ) { "被回复评论不属于当前插件" }
        val normalizedContent = content.trim()
        require(normalizedContent.isNotBlank()) { "回复内容不能为空" }
        require(normalizedContent.length <= 1000) { "回复内容不能超过 1000 个字符" }
        val identity = currentUserIdentity(context).getOrThrow()
        PluginMarketClient.replyComment(
            context,
            remotePluginId,
            parentComment.commentId,
            identity,
            normalizedContent
        ).getOrThrow()
    }

    fun notifications(
        context: Context,
        limit: Int = Int.MAX_VALUE
    ): Result<PluginMarketNotificationPage> = runCatching {
        val identity = currentUserIdentity(context).getOrThrow()
        PluginMarketClient.notifications(context, identity, limit).getOrThrow()
    }

    fun markNotificationsRead(
        context: Context,
        notificationIds: Collection<String>? = null
    ): Result<PluginMarketNotificationReadResult> = runCatching {
        val normalizedIds = notificationIds?.map { it.trim() }?.distinct()
        normalizedIds?.let { ids ->
            require(ids.size <= 100) { "单次最多标记 100 条通知" }
            require(ids.none { it.isBlank() }) { "通知 ID 不能为空" }
        }
        val identity = currentUserIdentity(context).getOrThrow()
        PluginMarketClient.markNotificationsRead(
            context,
            identity,
            normalizedIds
        ).getOrThrow()
    }

    fun deleteOwnComment(
        context: Context,
        remotePluginId: String,
        comment: PluginMarketComment
    ): Result<PluginMarketCommentMutation> = runCatching {
        require(remotePluginId.isNotBlank()) { "远程插件 ID 不能为空" }
        require(comment.commentId.isNotBlank()) { "评论 ID 不能为空" }
        require(comment.remotePluginId.isBlank() || comment.remotePluginId == remotePluginId) {
            "评论不属于当前插件"
        }
        require(comment.canDelete) { "只能删除自己的评论" }
        val identity = currentUserIdentity(context).getOrThrow()
        PluginMarketClient.deleteComment(
            context,
            remotePluginId,
            comment.commentId,
            identity
        ).getOrThrow()
    }

    fun history(context: Context, remotePluginId: String): Result<List<PluginMarketHistoryVersion>> =
        PluginMarketClient.history(context, remotePluginId)

    fun historyDetail(
        context: Context,
        remotePluginId: String,
        versionId: String
    ): Result<PluginMarketPlugin> = runCatching {
        val detail = PluginMarketClient.historyDetail(context, remotePluginId, versionId).getOrThrow()
        PluginMarketInstaller.validateDetail(detail).getOrThrow()
        detail
    }

    fun upload(
        context: Context,
        localPluginId: String,
        remoteName: String? = null,
        releaseNotes: String = "",
        extraFiles: List<PluginMarketFile> = emptyList()
    ): Result<PluginMarketOwnership> = runCatching {
        val packageData = PluginMarketInstaller.collectUploadPackage(
            context = context,
            pluginId = localPluginId,
            remoteName = remoteName,
            releaseNotes = releaseNotes,
            extraFiles = extraFiles
        ).getOrThrow()
        val existingOwnership = PluginMarketSettings.ownership(context, localPluginId)
        val ownership = try {
            PluginMarketClient.upload(context, packageData, existingOwnership).getOrThrow()
        } catch (error: PluginMarketException) {
            if (existingOwnership == null || error.errorCode != "PLUGIN_NOT_FOUND") {
                throw error
            }
            PluginMarketSettings.removeOwnership(context, localPluginId)
            PluginMarketClient.upload(context, packageData).getOrThrow()
        }
        PluginMarketSettings.saveOwnership(context, localPluginId, ownership)
        ownership
    }

    fun upload(
        context: Context,
        localPluginIds: Collection<String>,
        remoteNames: Map<String, String> = emptyMap(),
        releaseNotes: Map<String, String> = emptyMap()
    ): Map<String, Result<PluginMarketOwnership>> {
        return localPluginIds.distinct().associateWith { localId ->
            upload(context, localId, remoteNames[localId], releaseNotes[localId].orEmpty())
        }
    }

    fun install(
        context: Context,
        remotePlugin: PluginMarketPlugin,
        overwrite: Boolean = false,
        localPluginId: String? = null
    ): Result<PluginMarketInstallResult> = runCatching {
        val installed = PluginMarketInstaller.install(
            context = context,
            plugin = remotePlugin,
            overwrite = overwrite,
            localPluginId = localPluginId
        ).getOrThrow()
        val eventId = UUID.randomUUID().toString().replace("-", "")
        val downloadCount = PluginMarketClient.recordDownload(
            context,
            remotePlugin.remotePluginId,
            remotePlugin.versionId,
            eventId
        ).getOrNull()
        installed.copy(downloadCount = downloadCount)
    }

    fun downloadAndInstall(
        context: Context,
        remotePluginId: String,
        overwrite: Boolean = false,
        localPluginId: String? = null
    ): Result<PluginMarketInstallResult> = runCatching {
        val detail = detail(context, remotePluginId).getOrThrow()
        install(context, detail, overwrite, localPluginId).getOrThrow()
    }

    fun deleteRemote(context: Context, localPluginId: String): Result<Unit> = runCatching {
        val ownership = PluginMarketSettings.ownership(context, localPluginId)
            ?: error("本地没有该插件的远程归属信息")
        PluginMarketClient.delete(context, ownership).getOrThrow()
        PluginMarketSettings.removeOwnership(context, localPluginId)
    }

    fun deleteOwnedRemote(context: Context, remotePluginId: String): Result<Unit> = runCatching {
        val ownership = PluginMarketSettings.ownershipForRemote(context, remotePluginId)
            ?: error("该插件不是由当前模块安装上传的")
        PluginMarketClient.delete(context, ownership).getOrThrow()
        PluginMarketSettings.removeOwnershipForRemote(context, remotePluginId)
    }

    fun ownsRemotePlugin(context: Context, remotePluginId: String): Boolean =
        PluginMarketSettings.ownershipForRemote(context, remotePluginId) != null

    fun ownership(context: Context, localPluginId: String): PluginMarketOwnership? =
        PluginMarketSettings.ownership(context, localPluginId)
}
