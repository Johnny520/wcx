package com.Johnny.wcx.features.items.contacts

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.ChatroomSyncStateReadResult
import com.Johnny.wcx.features.api.core.models.WeChatroomSyncState
import com.Johnny.wcx.features.core.Feature

import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.HookCallback
import com.Johnny.wcx.utils.HookParam
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookDirectly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.Locale

@Feature(
    name = "加入群聊自动免打扰",
    categories = ["联系人与群组"],
    description = "加入新的群聊后自动开启消息免打扰"
)
object AutoDndAfterJoinGroup : SwitchFeature(), IResolveDex {

    private const val TAG = "AutoDndAfterJoinGroup"
    private const val MAX_SNAPSHOTS = 128
    private const val MAX_DEDUP_KEYS = 256

    private val methodSyncChatroomMembers by dexMethod()
    private val stateLock = Any()
    private val snapshots = IdentityHashMap<de.robv.android.xposed.XC_MethodHook.MethodHookParam, SyncSnapshot>()
    private val dedupKeys = LinkedHashMap<String, Unit>(MAX_DEDUP_KEYS, 0.75f, true)
    private var observedSelfWxId: String? = null
    private var scope = newScope()

    private data class SyncSnapshot(
        val roomId: String,
        val oldState: ChatroomSyncStateReadResult,
        val selfWxId: String,
    )

    override fun resolveDex(dexKit: DexKitBridge) {
        val matches = dexKit.findMethod {
            matcher {
                returnType = "boolean"
                paramCount(10, 11)
                usingStrings("MicroMsg.ChatroomMembersLogic", "SyncAddChatroomMember")
            }
        }.filter { method ->
            val params = method.paramTypeNames
            params[0] == "java.lang.String" &&
                params[1] == "java.lang.String" &&
                params[3] == "int" &&
                params[4] == "int" &&
                params[5] == "int" &&
                params[6] == "java.lang.String" &&
                params[8] == "boolean" &&
                params[9] == "boolean" &&
                (params.size == 10 || params[10] == "int") &&
                params[2] !in PRIMITIVE_TYPE_NAMES &&
                params[7] !in PRIMITIVE_TYPE_NAMES
        }

        check(matches.size == 1) {
            "expected one ChatroomMembersLogic sync method, found ${matches.size}: " +
                matches.joinToString { it.descriptor }
        }
        methodSyncChatroomMembers.setDescriptor(matches.single())
    }

    override fun onEnable() {
        if (!scope.coroutineContext[Job]!!.isActive) scope = newScope()

        registerUnhook(methodSyncChatroomMembers.method.hookDirectly(object : de.robv.android.xposed.XC_MethodHook() {
            override fun beforeHookedMethod(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                val roomId = param.args[0] as String
                if (!roomId.isSupportedChatroomId()) return

                val selfWxId = WeApi.selfWxId
                if (selfWxId.isEmpty()) return

                val snapshot = SyncSnapshot(roomId, WeDatabaseApi.getChatroomSyncState(roomId), selfWxId)
                synchronized(stateLock) {
                    observeAccount(selfWxId)
                    if (snapshots.size >= MAX_SNAPSHOTS) snapshots.entries.iterator().run {
                        next()
                        remove()
                    }
                    snapshots[param] = snapshot
                }
            }

            override fun afterHookedMethod(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                val selfWxId = WeApi.selfWxId
                val snapshot = synchronized(stateLock) {
                    val pendingSnapshot = snapshots.remove(param) ?: return
                    if (selfWxId != pendingSnapshot.selfWxId) {
                        observeAccount(selfWxId)
                        return
                    }
                    observeAccount(selfWxId)
                    pendingSnapshot
                }
                if (param.throwable != null) return

                val oldState = when (snapshot.oldState) {
                    ChatroomSyncStateReadResult.MissingRow -> null
                    is ChatroomSyncStateReadResult.Available -> snapshot.oldState.state
                    ChatroomSyncStateReadResult.Unavailable -> {
                        WeLogger.d(TAG, "skip unavailable pre-sync state for ${snapshot.roomId}")
                        return
                    }
                }
                val newState = when (val result = WeDatabaseApi.getChatroomSyncState(snapshot.roomId)) {
                    is ChatroomSyncStateReadResult.Available -> result.state
                    ChatroomSyncStateReadResult.MissingRow -> {
                        WeLogger.d(TAG, "skip missing post-sync state for ${snapshot.roomId}")
                        return
                    }
                    ChatroomSyncStateReadResult.Unavailable -> {
                        WeLogger.d(TAG, "skip unavailable post-sync state for ${snapshot.roomId}")
                        return
                    }
                }

                if (!shouldMuteJoinedGroup(oldState, newState, snapshot.selfWxId)) return

                submitDnd(snapshot.roomId, newState, snapshot.selfWxId)
            }
        }))
    }

    override fun onDisable() {
        scope.cancel()
        synchronized(stateLock) {
            snapshots.clear()
            dedupKeys.clear()
            observedSelfWxId = null
        }
    }

    private fun submitDnd(roomId: String, state: WeChatroomSyncState, selfWxId: String) {
        val key = dedupKey(state)
        if (!markDedupKey(key)) {
            WeLogger.d(TAG, "skip duplicate DND room=$roomId key=$key version=${state.memberVersion}")
            return
        }

        scope.launch {
            try {
                if (WeApi.selfWxId != selfWxId) {
                    WeLogger.d(TAG, "skip stale DND room=$roomId key=$key")
                    return@launch
                }
                if (WeConversationApi.isDnd(roomId)) {
                    WeLogger.d(TAG, "skip already-muted room=$roomId key=$key version=${state.memberVersion}")
                    return@launch
                }
                WeConversationApi.setDnd(roomId, true)
                WeLogger.i(TAG, "submitted DND room=$roomId key=$key version=${state.memberVersion}")
            } catch (e: Exception) {
                WeLogger.w(TAG, "DND submission failed room=$roomId key=$key version=${state.memberVersion}", e)
            }
        }
    }

    private fun observeAccount(selfWxId: String) {
        if (observedSelfWxId != null && observedSelfWxId != selfWxId) {
            snapshots.clear()
            dedupKeys.clear()
        }
        observedSelfWxId = selfWxId
    }

    private fun markDedupKey(key: String): Boolean = synchronized(stateLock) {
        if (dedupKeys.containsKey(key)) return@synchronized false
        if (dedupKeys.size >= MAX_DEDUP_KEYS) dedupKeys.entries.iterator().run {
            next()
            remove()
        }
        dedupKeys[key] = Unit
        true
    }

    private fun String.isSupportedChatroomId(): Boolean {
        val lowerCaseId = lowercase(Locale.ROOT)
        return lowerCaseId.endsWith("@chatroom") || lowerCaseId.endsWith("@im.chatroom")
    }

    private fun newScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val PRIMITIVE_TYPE_NAMES = setOf(
        "boolean",
        "byte",
        "char",
        "double",
        "float",
        "int",
        "long",
        "short",
        "void",
        "java.lang.String",
    )
}
