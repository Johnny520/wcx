package com.Johnny.wcx.features.items.chat

import android.annotation.SuppressLint
import android.content.ContentValues
import android.view.View
import com.Johnny.reflekt.reflekt
import com.Johnny.reflekt.utils.Modifiers
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.net.models.protobuf.ChatRoomDataProto
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.BString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf

@Feature(name = "群成员行为监控", categories = ["联系人与群组"], description = "监控群成员的退群与修改群昵称行为")
object MonitorGroupMemberOperations : SwitchFeature(), IResolveDex, WeDatabaseListenerApi.IUpdateListener {

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)

        methodHandleSpanClick.hookBefore {
            val url = args[1].reflekt().firstField {
                type = BString
                modifiers(Modifiers.FINAL)
            }.get()!! as String
            if (!url.startsWith("weixin://weixinhongbao/wekit/chatroom_userinfo/")) return@hookBefore

            val wxId = url.substringAfterLast('/')
            val context = (args[0] as View).context

            WeApi.openContact(context, wxId, WeApi.OpenContactDestination.HOMEPAGE)
        }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    private val methodHandleSpanClick by dexMethod {
        matcher {
            declaredClass = $$"com.tencent.mm.app.plugin.URISpanHandlerSet$LuckyMoneyUriSpanHandler"
            usingEqStrings("MicroMsg.URISpanHandlerSet", "LuckyMoneyUriSpanHandler handleSpanClick() clickCallback == null")
        }
    }

    @SuppressLint("Range")
    override fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int) {
        if (table != "chatroom") return

        val group = values.getAsString("chatroomname") ?: return
        val newMemberCount = values.getAsInteger("memberCount")
        val newRawMembers = values.getAsString("memberlist")
        val newRoomData = values.getAsByteArray("roomdata")

        val cursor = WeDatabaseApi.rawQuery(
            "SELECT memberlist,memberCount,roomdata FROM chatroom WHERE chatroomname = ?",
            arrayOf(group)
        )

        runCatching {
            cursor.use { cursor ->
                if (!cursor.moveToFirst()) return

                val origRawMembers = cursor.getString(cursor.getColumnIndex("memberlist"))
                if (origRawMembers.isNullOrEmpty()) return
                val origMembers = origRawMembers.split(';')

                // 群昵称（群备注）以 wxId -> displayName 的形式存于 roomdata protobuf 中，
                // displayname 列只是服务端截断后的预览（最多 4 个名字），无法可靠映射到成员。
                val origRoomData = cursor.getBlob(cursor.getColumnIndex("roomdata"))
                val origDisplayNames = parseRoomData(origRoomData)

                handleMemberLeave(
                    group, origMembers, origDisplayNames, newRawMembers, newMemberCount,
                    cursor.getInt(cursor.getColumnIndex("memberCount"))
                )
                handleDisplayNameChange(group, origDisplayNames, newRoomData)
            }
        }.onFailure { WeLogger.e(TAG, "failed to handle group member operations", it) }
    }

    private fun handleMemberLeave(
        group: String,
        origMembers: List<String>,
        origDisplayNames: Map<String, String>,
        newRawMembers: String?,
        newMemberCount: Int?,
        origMemberCount: Int
    ) {
        if (newRawMembers == null || newMemberCount == null) return
        if (origMemberCount == 0 || newMemberCount >= origMemberCount) return

        val newMembers = newRawMembers.split(';').toSet()
        val leavers = origMembers - newMembers

        leavers.forEach { wxId ->
            val displayName = (origDisplayNames[wxId] ?: "").ifEmpty { WeDatabaseApi.getDisplayName(wxId) }
            val displayString = if (displayName.isNotEmpty()) "$displayName ($wxId)" else wxId

            val href = "weixin://weixinhongbao/wekit/chatroom_userinfo/$wxId"
            val content = """<_wc_custom_link_ color="#28C445" href="$href">$displayString</_wc_custom_link_> 退出了群组"""

            WeMessageApi.createSimpleMsgInfoAndInsert(
                type = MessageType.SYSTEM.code,
                talker = group,
                content = content,
                currentTime = System.currentTimeMillis()
            )
        }
    }

    private fun handleDisplayNameChange(group: String, origDisplayNames: Map<String, String>, newRoomData: ByteArray?) {
        if (newRoomData == null) return // 本次更新未改动 roomdata

        val newDisplayNames = parseRoomData(newRoomData)
        if (origDisplayNames.isEmpty() || newDisplayNames.isEmpty()) return

        newDisplayNames.forEach { (wxId, newName) ->
            val oldName = origDisplayNames[wxId] ?: return@forEach // 新成员，非昵称修改
            if (oldName == newName) return@forEach

            val displayName = WeDatabaseApi.getDisplayName(wxId)
            val displayString = if (displayName.isNotEmpty()) "$displayName ($wxId)" else wxId

            val oldShow = oldName.ifEmpty { "(无)" }
            val newShow = newName.ifEmpty { "(无)" }

            val href = "weixin://weixinhongbao/wekit/chatroom_userinfo/$wxId"
            val content = """<_wc_custom_link_ color="#28C445" href="$href">$displayString</_wc_custom_link_> 修改群昵称：$oldShow → $newShow"""

            WeMessageApi.createSimpleMsgInfoAndInsert(
                type = MessageType.SYSTEM.code,
                talker = group,
                content = content,
                currentTime = System.currentTimeMillis()
            )
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun parseRoomData(blob: ByteArray?): Map<String, String> {
        if (blob == null || blob.isEmpty()) return emptyMap()
        return runCatching {
            ProtoBuf.decodeFromByteArray<ChatRoomDataProto>(blob)
                .members.associate { it.wxId to it.displayName }
        }.getOrElse { emptyMap() }
    }

    private const val TAG = "MonitorGroupMemberOperations"
}
