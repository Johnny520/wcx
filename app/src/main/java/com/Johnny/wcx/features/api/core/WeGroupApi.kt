package com.Johnny.wcx.features.api.core

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexConstructor
import com.Johnny.wcx.features.api.net.WeNetSceneApi
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.BInt
import com.Johnny.wcx.utils.reflection.BString

@Feature(name = "群聊管理服务", categories = ["API"], description = "提供添加/删除/邀请群成员能力")
object WeGroupApi : ApiFeature(), IResolveDex {

    private const val TAG = "WeGroupApi"

    // ul.m(String chatRoomName, List<String> members, String sceneNote, Object historyInfo)
    private val ctorAddMember by dexConstructor {
        matcher {
            usingEqStrings("MicroMsg.NetSceneAddChatRoomMember")
            paramCount(4)
            paramTypes(
                BString,
                List::class.java,
                BString,
                Any::class.java
            )
        }
    }

    // ul.p(String chatRoomName, List<String> members, int scene)
    private val ctorDelMember by dexConstructor {
        matcher {
            usingStrings("delchatroommember")
            paramCount(3)
            paramTypes(
                BString,
                List::class.java,
                BInt
            )
        }
    }

    // ul.x(String chatRoomName, List<String> members, int scene, Object historyInfo)
    private val ctorInviteMember by dexConstructor {
        matcher {
            usingEqStrings("MicroMsg.NetSceneInviteChatRoomMember")
            paramCount(4)
            paramTypes(
                BString,
                List::class.java,
                BInt,
                Any::class.java
            )
        }
    }

    fun addMember(groupId: String, memberWxId: String) {
        addMembers(groupId, listOf(memberWxId))
    }

    fun addMembers(groupId: String, memberWxIds: List<String>) {
        try {
            val netScene = ctorAddMember.newInstance(groupId, memberWxIds, null, null)
            WeNetSceneApi.sendNetScene(netScene)
            WeLogger.i(TAG, "addMembers sent: group=$groupId count=${memberWxIds.size}")
        } catch (e: Exception) {
            WeLogger.e(TAG, "addMembers failed for $groupId", e)
        }
    }

    fun delMember(groupId: String, memberWxId: String) {
        delMembers(groupId, listOf(memberWxId))
    }

    fun delMembers(groupId: String, memberWxIds: List<String>) {
        try {
            val netScene = ctorDelMember.newInstance(groupId, memberWxIds, 0)
            WeNetSceneApi.sendNetScene(netScene)
            WeLogger.i(TAG, "delMembers sent: group=$groupId count=${memberWxIds.size}")
        } catch (e: Exception) {
            WeLogger.e(TAG, "delMembers failed for $groupId", e)
        }
    }

    fun inviteMember(groupId: String, memberWxId: String) {
        inviteMembers(groupId, listOf(memberWxId))
    }

    fun inviteMembers(groupId: String, memberWxIds: List<String>) {
        try {
            val netScene = ctorInviteMember.newInstance(groupId, memberWxIds, 0, null)
            WeNetSceneApi.sendNetScene(netScene)
            WeLogger.i(TAG, "inviteMembers sent: group=$groupId count=${memberWxIds.size}")
        } catch (e: Exception) {
            WeLogger.e(TAG, "inviteMembers failed for $groupId", e)
        }
    }
}
