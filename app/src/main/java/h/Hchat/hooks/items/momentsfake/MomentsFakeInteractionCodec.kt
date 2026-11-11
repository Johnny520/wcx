package h.Hchat.hooks.items.momentsfake

import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.utils.KavaReflector
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap

private const val FIELD_USERNAME = 1
private const val FIELD_NICKNAME = 2
private const val FIELD_SOURCE_SCENE = 3
private const val FIELD_TYPE = 4
private const val FIELD_CONTENT = 5
private const val FIELD_CREATE_TIME = 6
private const val FIELD_COMMENT_ID = 7
private const val TYPE_LIKE = 1
private const val TYPE_COMMENT = 2
private const val FAKE_ID_PREFIX = 0x80000000.toInt()
private const val FAKE_ID_NAMESPACE_MASK = 0xC0000000.toInt()
private const val FAKE_ID_PAYLOAD_MASK = 0x3FFFFFFF

internal data class FakeInteractionMergeResult(
    val bytes: ByteArray,
    val changed: Boolean
)

internal object MomentsFakeInteractionNodeIdentity {
    data class Node(
        val username: String,
        val type: Int,
        val commentId: Int
    )

    fun read(node: Any): Node? {
        val bytes = KavaReflector.invokeMethod(node, "toByteArray") as? ByteArray ?: return null
        return Node(
            username = stringField(bytes, FIELD_USERNAME).orEmpty(),
            type = readVarintField(bytes, FIELD_TYPE)?.toInt() ?: return null,
            commentId = readVarintField(bytes, FIELD_COMMENT_ID)?.toInt() ?: return null
        )
    }

    fun isFakeLikeNode(node: Any): Boolean = isFakeNode(node, TYPE_LIKE)

    fun isFakeCommentNode(node: Any): Boolean = isFakeNode(node, TYPE_COMMENT)

    fun stableNodeId(value: String): Int {
        return FAKE_ID_PREFIX or (value.hashCode() and FAKE_ID_PAYLOAD_MASK)
    }

    fun commentNodeId(commentId: String): Int = stableNodeId("comment:$commentId")

    private fun isFakeNode(node: Any, expectedType: Int): Boolean {
        val identity = read(node) ?: return false
        return identity.type == expectedType &&
            (identity.commentId and FAKE_ID_NAMESPACE_MASK) == FAKE_ID_PREFIX
    }

    private fun stringField(bytes: ByteArray, wantedField: Int): String? {
        var position = 0
        while (position < bytes.size) {
            val tag = readVarint(bytes, position) ?: return null
            position = tag.next
            val field = (tag.value ushr 3).toInt()
            when ((tag.value and 7L).toInt()) {
                0 -> position = readVarint(bytes, position)?.next ?: return null
                1 -> position += 8
                2 -> {
                    val length = readVarint(bytes, position) ?: return null
                    position = length.next
                    val size = length.value.toInt()
                    if (size < 0 || position + size > bytes.size) return null
                    if (field == wantedField) {
                        return String(bytes, position, size, StandardCharsets.UTF_8)
                    }
                    position += size
                }
                5 -> position += 4
                else -> return null
            }
            if (position < 0 || position > bytes.size) return null
        }
        return null
    }

    private fun readVarintField(bytes: ByteArray, wantedField: Int): Long? {
        var position = 0
        while (position < bytes.size) {
            val tag = readVarint(bytes, position) ?: return null
            position = tag.next
            val field = (tag.value ushr 3).toInt()
            when ((tag.value and 7L).toInt()) {
                0 -> {
                    val value = readVarint(bytes, position) ?: return null
                    if (field == wantedField) return value.value
                    position = value.next
                }
                1 -> position += 8
                2 -> {
                    val length = readVarint(bytes, position) ?: return null
                    position = length.next + length.value.toInt()
                }
                5 -> position += 4
                else -> return null
            }
            if (position < 0 || position > bytes.size) return null
        }
        return null
    }

    private fun readVarint(bytes: ByteArray, offset: Int): Varint? {
        var position = offset
        var shift = 0
        var result = 0L
        while (position < bytes.size && shift < 64) {
            val current = bytes[position].toInt() and 0xFF
            position++
            result = result or ((current and 0x7F).toLong() shl shift)
            if ((current and 0x80) == 0) return Varint(result, position)
            shift += 7
        }
        return null
    }

    private data class Varint(val value: Long, val next: Int)
}

internal class MomentsFakeInteractionCodec(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val legacyDisplayNameCache = ConcurrentHashMap<String, String>()
    @Volatile private var interactionNodeClass: Class<*>? = null
    @Volatile private var unavailableLogged = false

    fun warmup(): Boolean {
        interactionNodeClass?.let { return true }
        val resolved = WeChatApis.snsApi()?.localInteractionNodeClass()
        if (resolved != null) {
            interactionNodeClass = resolved
            return true
        }
        return false
    }

    fun merge(
        source: ByteArray,
        entry: FakeSnsInteraction,
        knownEntry: FakeSnsInteraction = entry,
        includeLikes: Boolean,
        includeComments: Boolean
    ): FakeInteractionMergeResult {
        val snsObject = parseSnsObject(source)
            ?: return FakeInteractionMergeResult(source, false)
        val likes = mutableNodeList(snsObject, "LikeUserList")
            ?: return FakeInteractionMergeResult(source, false)
        val comments = mutableNodeList(snsObject, "CommentUserList")
            ?: return FakeInteractionMergeResult(source, false)
        val oldLikeCount = intField(snsObject, "LikeCount")
        val oldLikeListCount = intField(snsObject, "LikeUserListCount")
        val oldCommentCount = intField(snsObject, "CommentCount")
        val oldCommentListCount = intField(snsObject, "CommentUserListCount")
        val existingNodeClass = (likes.asSequence() + comments.asSequence())
            .firstOrNull()
            ?.javaClass
        val knownLikeKeys = knownEntry.likes.mapTo(hashSetOf(), ::likeKey)
        val knownCommentKeys = knownEntry.comments.mapTo(hashSetOf(), ::commentKey)
        val isKnownLike: (Any) -> Boolean = { node ->
            MomentsFakeInteractionNodeIdentity.isFakeLikeNode(node) ||
                matchesFakeLikeNode(node, knownLikeKeys)
        }
        val isKnownComment: (Any) -> Boolean = { node ->
            MomentsFakeInteractionNodeIdentity.isFakeCommentNode(node) ||
                matchesFakeCommentNode(node, knownCommentKeys)
        }
        val removedLikeCount = likes.count(isKnownLike)
        val removedCommentCount = comments.count(isKnownComment)
        var changed = likes.removeAll(isKnownLike)
        changed = comments.removeAll(isKnownComment) || changed

        val realLikeUsers = likes.mapNotNullTo(hashSetOf()) { node ->
            MomentsFakeInteractionNodeIdentity.read(node)?.username
        }
        val realLikeListSize = likes.size
        val realCommentListSize = comments.size
        val wantedLikes = if (includeLikes) {
            entry.likes.filterNot { realLikeUsers.contains(it.wxId) }
        } else {
            emptyList()
        }
        val wantedComments = if (includeComments) entry.comments else emptyList()
        if (wantedLikes.isNotEmpty() || wantedComments.isNotEmpty()) {
            val nodeClass = existingNodeClass ?: interactionNodeClass ?: run {
                warmup()
                interactionNodeClass
            }
            if (nodeClass == null) {
                if (!unavailableLogged) {
                    unavailableLogged = true
                    logger("朋友圈伪互动节点类型尚未就绪", null)
                }
                return FakeInteractionMergeResult(source, false)
            }
            interactionNodeClass = nodeClass
            wantedLikes.forEach { like ->
                newNode(
                    nodeClass = nodeClass,
                    wxId = like.wxId,
                    displayName = like.displayName,
                    content = "",
                    type = TYPE_LIKE,
                    createTimeSeconds = (System.currentTimeMillis() / 1000L).toInt(),
                    stableId = MomentsFakeInteractionNodeIdentity.stableNodeId("like:${like.wxId}")
                )?.let {
                    likes.add(it)
                    changed = true
                }
            }
            wantedComments.forEach { comment ->
                newNode(
                    nodeClass = nodeClass,
                    wxId = comment.authorWxId,
                    displayName = comment.authorDisplayName,
                    content = comment.content,
                    type = TYPE_COMMENT,
                    createTimeSeconds = (comment.createTimeMillis / 1000L)
                        .coerceIn(1L, Int.MAX_VALUE.toLong())
                        .toInt(),
                    stableId = MomentsFakeInteractionNodeIdentity.commentNodeId(comment.id)
                )?.let {
                    comments.add(it)
                    changed = true
                }
            }
        }
        if (!changed) return FakeInteractionMergeResult(source, false)
        val addedLikeCount = (likes.size - realLikeListSize).coerceAtLeast(0)
        val addedCommentCount = (comments.size - realCommentListSize).coerceAtLeast(0)
        val realLikeCount = (oldLikeCount - removedLikeCount).coerceAtLeast(realLikeListSize)
        val realLikeListCount = (oldLikeListCount - removedLikeCount)
            .coerceAtLeast(realLikeListSize)
        val realCommentCount = (oldCommentCount - removedCommentCount)
            .coerceAtLeast(realCommentListSize)
        val realCommentListCount = (oldCommentListCount - removedCommentCount)
            .coerceAtLeast(realCommentListSize)
        writeCounts(
            snsObject,
            likeCount = realLikeCount + addedLikeCount,
            likeListCount = realLikeListCount + addedLikeCount,
            commentCount = realCommentCount + addedCommentCount,
            commentListCount = realCommentListCount + addedCommentCount
        )
        val bytes = KavaReflector.invokeMethod(snsObject, "toByteArray") as? ByteArray
            ?: return FakeInteractionMergeResult(source, false)
        return FakeInteractionMergeResult(bytes, !bytes.contentEquals(source))
    }

    private fun parseSnsObject(bytes: ByteArray): Any? {
        val clazz = KavaReflector.loadClass(SNS_OBJECT_CLASS, context.hostClassLoader()) ?: return null
        val instance = KavaReflector.newInstance(KavaReflector.findConstructor(clazz)) ?: return null
        val parseMethod = KavaReflector.findCompatibleMethod(instance.javaClass, "parseFrom", bytes)
            ?: return null
        if (!KavaReflector.invokeSuccessfully(parseMethod, instance, bytes)) return null
        return instance
    }

    @Suppress("UNCHECKED_CAST")
    private fun mutableNodeList(snsObject: Any, fieldName: String): MutableList<Any>? {
        val current = KavaReflector.readField(snsObject, fieldName)
        if (current is MutableList<*>) return current as MutableList<Any>
        val replacement = LinkedList<Any>()
        return replacement.takeIf { KavaReflector.writeField(snsObject, fieldName, it) }
    }

    private fun writeCounts(
        snsObject: Any,
        likeCount: Int,
        likeListCount: Int,
        commentCount: Int,
        commentListCount: Int
    ) {
        KavaReflector.writeField(snsObject, "LikeCount", likeCount)
        KavaReflector.writeField(snsObject, "LikeUserListCount", likeListCount)
        KavaReflector.writeField(snsObject, "CommentCount", commentCount)
        KavaReflector.writeField(snsObject, "CommentUserListCount", commentListCount)
    }

    private fun intField(source: Any, name: String): Int {
        return (KavaReflector.readField(source, name) as? Number)?.toInt() ?: 0
    }

    private fun newNode(
        nodeClass: Class<*>,
        wxId: String,
        displayName: String,
        content: String,
        type: Int,
        createTimeSeconds: Int,
        stableId: Int
    ): Any? {
        val instance = KavaReflector.newInstance(KavaReflector.findConstructor(nodeClass)) ?: return null
        val bytes = buildNodeBytes(
            wxId = wxId,
            nickname = resolveDisplayName(wxId, displayName),
            content = content,
            type = type,
            createTimeSeconds = createTimeSeconds,
            stableId = stableId
        )
        val parseMethod = KavaReflector.findCompatibleMethod(nodeClass, "parseFrom", bytes) ?: return null
        return instance.takeIf { KavaReflector.invokeSuccessfully(parseMethod, instance, bytes) }
    }

    private fun resolveDisplayName(wxId: String, stored: String): String {
        val normalized = stored.trim()
        if (normalized.isNotEmpty() && normalized != wxId) return normalized
        return legacyDisplayNameCache.getOrPut(wxId) {
            runCatching { WeChatApis.contacts()?.getDisplayName(wxId) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: wxId
        }
    }

    private fun buildNodeBytes(
        wxId: String,
        nickname: String,
        content: String,
        type: Int,
        createTimeSeconds: Int,
        stableId: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeString(out, FIELD_USERNAME, wxId)
        writeString(out, FIELD_NICKNAME, nickname)
        writeInt32(out, FIELD_SOURCE_SCENE, 0)
        writeInt32(out, FIELD_TYPE, type)
        if (content.isNotEmpty()) writeString(out, FIELD_CONTENT, content)
        writeInt32(out, FIELD_CREATE_TIME, createTimeSeconds)
        writeInt32(out, FIELD_COMMENT_ID, stableId)
        return out.toByteArray()
    }

    private fun matchesFakeLikeNode(node: Any, keys: Set<LikeKey>): Boolean {
        if (keys.isEmpty()) return false
        val identity = MomentsFakeInteractionNodeIdentity.read(node) ?: return false
        if (identity.type != TYPE_LIKE) return false
        return keys.contains(LikeKey(identity.commentId, identity.username))
    }

    fun matchesFakeCommentNode(node: Any, comments: List<FakeSnsComment>): Boolean {
        if (comments.isEmpty()) return false
        return matchesFakeCommentNode(node, comments.mapTo(hashSetOf(), ::commentKey))
    }

    private fun matchesFakeCommentNode(node: Any, keys: Set<CommentKey>): Boolean {
        if (keys.isEmpty()) return false
        val identity = MomentsFakeInteractionNodeIdentity.read(node) ?: return false
        if (identity.type != TYPE_COMMENT) return false
        return keys.contains(CommentKey(identity.commentId, identity.username))
    }

    private fun likeKey(like: FakeSnsLike): LikeKey {
        return LikeKey(
            MomentsFakeInteractionNodeIdentity.stableNodeId("like:${like.wxId}"),
            like.wxId
        )
    }

    private fun commentKey(comment: FakeSnsComment): CommentKey {
        return CommentKey(
            MomentsFakeInteractionNodeIdentity.commentNodeId(comment.id),
            comment.authorWxId
        )
    }

    private fun writeString(out: ByteArrayOutputStream, field: Int, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeVarint(out, ((field shl 3) or 2).toLong())
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeInt32(out: ByteArrayOutputStream, field: Int, value: Int) {
        writeVarint(out, (field shl 3).toLong())
        writeVarint(out, value.toLong())
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var current = value
        while (true) {
            if ((current and -0x80L) == 0L) {
                out.write(current.toInt())
                return
            }
            out.write(((current and 0x7FL) or 0x80L).toInt())
            current = current ushr 7
        }
    }

    fun commentNodeId(commentId: String): Int {
        return MomentsFakeInteractionNodeIdentity.commentNodeId(commentId)
    }
    private data class LikeKey(val commentId: Int, val username: String)
    private data class CommentKey(
        val commentId: Int,
        val username: String
    )

    companion object {
        private const val SNS_OBJECT_CLASS = "com.tencent.mm.protocal.protobuf.SnsObject"
    }
}
