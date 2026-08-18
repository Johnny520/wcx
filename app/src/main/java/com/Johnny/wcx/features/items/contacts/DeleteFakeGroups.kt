package com.Johnny.wcx.features.items.contacts

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.WeGroup
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.items.contacts.DeleteFakeGroups.GARBAGE_CHATROOM_REGEX
import com.Johnny.wcx.features.items.contacts.DeleteFakeGroups.isFakeGroup
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.runOnUiThread
import com.Johnny.wcx.utils.android.showToast
import kotlin.concurrent.thread

@Feature(
    name = "删除假群组",
    categories = ["娱乐"],
    description = "彻底清除假群组 (仅清除本地数据库，不影响原群)。识别两类假群：" +
        "①「分裂群组」产生的 xxx@@chatroom；" +
        "②wxid 形如 <数字><乱码汉字>@chatroom 的垃圾群。"
)
object DeleteFakeGroups : ClickableFeature() {

    private const val TAG = "DeleteFakeGroups"

    /**
     * Matches the "garbage-Chinese" fake-group pattern: a numeric prefix followed by one or
     * more non-ASCII characters (typically CJK) before the standard `@chatroom` suffix.
     *
     * Examples that match:  `12345678你@chatroom`, `12345678人@chatroom`
     * Examples that don't:  `12345678@chatroom` (legit), `12345678@@chatroom` (handled separately)
     */
    private val GARBAGE_CHATROOM_REGEX = Regex("""^\d+[^\x00-\x7F]+@chatroom$""")

    override fun onClick(context: ComponentActivity) {
        val fakeGroups = getFakeGroups()
        if (fakeGroups.isEmpty()) {
            showToast("未发现假群组!")
            return
        }

        showComposeDialog(context) {
            ContactsSelector(
                title = "删除假群组 (共 ${fakeGroups.size} 个)",
                contacts = fakeGroups,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedIds ->
                    if (selectedIds.isEmpty()) {
                        showToast("请选择至少一个假群")
                        return@ContactsSelector
                    }
                    onDismiss()
                    confirmAndDelete(context, selectedIds, fakeGroups)
                }
            )
        }
    }

    private fun confirmAndDelete(
        context: ComponentActivity,
        selectedIds: Set<String>,
        fakeGroups: List<WeGroup>
    ) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("确认删除") },
                text = { Text("确定要删除选中的 ${selectedIds.size} 个假群组吗? 此操作不可逆，原群不受影响。") },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        thread(name = "DeleteFakeGroupsThread") {
                            deleteFakeGroups(
                                selectedIds,
                                fakeGroups.associate { it.wxId to it.nickname },
                            )
                            runOnUiThread {
                                showToast("已清除 ${selectedIds.size} 个假群组")
                            }
                        }
                    }) { Text("删除") }
                }
            )
        }
    }

    /**
     * Hard-delete the fake groups identified by [fakeGroupIds], then refresh the conversation list
     * once after the whole batch. Callers that generated exact IDs can reuse this without scanning
     * every fake group currently present in the database.
     */
    internal fun deleteFakeGroups(
        fakeGroupIds: Collection<String>,
        displayNames: Map<String, String> = emptyMap(),
    ) {
        fakeGroupIds.distinct().forEach { id ->
            deleteFakeGroup(id, displayNames[id].orEmpty().ifEmpty { id })
        }
        if (fakeGroupIds.isNotEmpty()) WeConversationApi.reloadConversations()
    }

    /**
     * Hard-delete all DB rows belonging to [fakeGroupId].
     *
     * Handled formats:
     *   - `xxx@@chatroom`           — split-group fake (double-@ fingerprint)
     *   - `<digits><CJK>@chatroom`  — garbage-Chinese fake (numeric prefix + non-ASCII junk)
     *
     * Tables touched:
     *   rcontact        — contact/group identity row
     *   rconversation   — conversation list entry
     *   chatroom        — group metadata & roster
     *   message         — chat messages
     *   ImgInfo2        — image/video transfer metadata
     *   img_flag        — avatar cache entry
     *   GroupBindApp    — bound mini-program data
     *   GroupSolitatire — 接龙 records
     *   GroupTodo       — group to-do items
     *   GroupTools      — pinned/recent tool list
     *   MsgQuote        — quoted-message index pointing at this group
     *
     * Deliberately does NOT go through WeChat's native delete path (which would
     * sync to the server and could affect the real group sharing the same numeric ID).
     */
    private fun deleteFakeGroup(fakeGroupId: String, name: String) {
        WeLogger.i(TAG, "deleting fake group: $fakeGroupId ($name)")

        // Ordered from most-derived to most-foundational so foreign-key-like dependencies
        // (message → conversation → contact) are cleaned up outward-in.
        val steps: List<Pair<String, () -> Int>> = listOf(
            "message" to { WeDatabaseApi.delete("message", "talker=?", arrayOf(fakeGroupId)) },
            "ImgInfo2" to { WeDatabaseApi.delete("ImgInfo2", "msgTalker=?", arrayOf(fakeGroupId)) },
            "MsgQuote" to { WeDatabaseApi.delete("MsgQuote", "quotedMsgTalker=?", arrayOf(fakeGroupId)) },
            "GroupBindApp" to { WeDatabaseApi.delete("GroupBindApp", "chatRoomName=?", arrayOf(fakeGroupId)) },
            "GroupSolitatire" to { WeDatabaseApi.delete("GroupSolitatire", "username=?", arrayOf(fakeGroupId)) },
            "GroupTodo" to { WeDatabaseApi.delete("GroupTodo", "roomname=?", arrayOf(fakeGroupId)) },
            "GroupTools" to { WeDatabaseApi.delete("GroupTools", "chatroomname=?", arrayOf(fakeGroupId)) },
            "chatroom" to { WeDatabaseApi.delete("chatroom", "chatroomname=?", arrayOf(fakeGroupId)) },
            "rconversation" to { WeDatabaseApi.delete("rconversation", "username=?", arrayOf(fakeGroupId)) },
            "img_flag" to { WeDatabaseApi.delete("img_flag", "username=?", arrayOf(fakeGroupId)) },
            // rcontact last: it's the identity anchor that WeChat caches most aggressively
            "rcontact" to { WeDatabaseApi.delete("rcontact", "username=?", arrayOf(fakeGroupId)) },
        )

        var anyError = false
        for ((table, op) in steps) {
            try {
                val rows = op()
                WeLogger.d(TAG, "  $table: deleted $rows row(s)")
            } catch (e: Exception) {
                WeLogger.w(TAG, "  $table: delete failed", e)
                anyError = true
                // Continue — partial cleanup is still better than nothing
            }
        }

        WeLogger.i(TAG, "fake group deletion complete: $fakeGroupId (anyError=$anyError)")
    }

    /**
     * Returns all fake groups currently in rcontact. Two kinds are detected:
     *
     * 1. **Split-group** (`xxx@@chatroom`) — double-@ fingerprint left by [SplitGroupChats].
     * 2. **Garbage-Chinese** (`<digits><CJK>@chatroom`) — wxid with a numeric prefix followed by
     *    meaningless non-ASCII characters before the standard `@chatroom` suffix.
     *
     * SQLite's `LIKE` cannot match Unicode ranges, so we fetch all `%@chatroom` candidates and
     * then apply [isFakeGroup] in Kotlin to discriminate.
     */
    private fun getFakeGroups(): List<WeGroup> {
        return try {
            val cursor = WeDatabaseApi.rawQuery(
                """
                SELECT r.username, r.nickname, r.pyInitial, r.quanPin, i.reserved2 AS avatarUrl
                FROM rcontact r
                LEFT JOIN img_flag i ON r.username = i.username
                WHERE r.username LIKE '%@chatroom'
                """.trimIndent()
            )
            val result = mutableListOf<WeGroup>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    val wxId = c.getString(0) ?: continue
                    if (!wxId.isFakeGroup()) continue
                    result += WeGroup(
                        wxId = wxId,
                        nickname = c.getString(1) ?: "",
                        nicknameShortPinyin = c.getString(2) ?: "",
                        nicknamePinyin = c.getString(3) ?: "",
                        avatarUrl = c.getString(4) ?: ""
                    )
                }
            }
            result
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to query fake groups", e)
            emptyList()
        }
    }

    /**
     * Returns true if this chatroom wxid is a fake group that can be safely deleted.
     *
     * - `endsWith("@@chatroom")` — split-group double-@ pattern
     * - [GARBAGE_CHATROOM_REGEX] — numeric prefix + non-ASCII (CJK) junk before `@chatroom`
     */
    private fun String.isFakeGroup(): Boolean =
        endsWith("@@chatroom") || GARBAGE_CHATROOM_REGEX.matches(this)

    override val noSwitchWidget = true
}
