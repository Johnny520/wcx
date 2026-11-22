package com.Johnny.wcx.features.items.contacts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.ui.WeContactPrefsScreenApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.SingleContactSelector
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger

/**
 * 临时通话：在**真实群**的资料页放一个入口，点一下用假群 ID 发起一次性语音通话。
 *
 * 设计取舍：
 * - 不打开 ChattingUI、不写 rconversation/rcontact → 会话列表里不会多出假群；
 * - 选成员页按 `chatroom.memberlist` 取人，所以进页前把真实群那条 chatroom 记录临时复制给假群；
 * - 通话用假群 ID 发起，真实群里不会出现通话记录；
 * - **通话一结束就清掉假群**（拨通时微信会自己写会话行，不清理会残留）；
 * - 启动时再扫一遍历史遗留的 `@@chatroom`，避免旧版本残留。
 *
 * 这是一个普通开关功能：**关掉就不注册群资料入口**。
 */
@Feature(
    name = "临时通话",
    categories = ["音视频通话"],
    description = "在真群资料页发起一次性假群语音通话; 不创建会话，也不会在真群留下通话记录",
)
object TemporaryCall : ClickableFeature(), WeContactPrefsScreenApi.IContactInfoProvider {

    private const val TAG = "TemporaryCall"

    private const val FAKE_SUFFIX = "@@chatroom"
    private const val REAL_SUFFIX = "@chatroom"

    /** 群通话的「选择成员」页。 */
    private const val SELECT_MEMBER_UI = "com.tencent.mm.voipmp.v2.multitalk.ui.MultiTalkSelectContactUI"

    private const val ITEM_CALL = "wcx_temp_call"

    /** 放开上限后返回给微信的值：只要足够大，实际能选多少取决于服务端。 */
    private const val MAX_CALL_MEMBERS = 9999

    /** VoIPMP 服务接口 / 实现类 / 退出群通话方法（8.0.79 混淆名，换版本需重新核对）。 */
    private const val HANGUP_SERVICE = "d06.p"
    private const val HANGUP_IMPL = "ry5.i3"
    private const val HANGUP_METHOD = "u4"

    /** 通话结束后等多久清理假群（留时间让微信自己收尾，避免边写边删）。 */
    private const val PURGE_DELAY_MS = 1_500L

    /** 启动后延迟多久做一次遗留假群清理（等微信存储层初始化完）。 */
    private const val STARTUP_PURGE_DELAY_MS = 5_000L

    /** 当前打开的是不是我们发起的「假群」选成员页（只在这个页面放开 15 人上限）。 */
    @Volatile
    private var fakePickerActive = false

    /** 本次发起用的假群 ID；通话结束时按它清理会话与库表。 */
    @Volatile
    private var lastFakeChatroomId: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onEnable() {
        WeLogger.i(TAG, "TemporaryCall enabled: 注册真群资料入口")
        WeContactPrefsScreenApi.addProvider(this)
        hookPickerLifecycle()
        hookMemberLimit()
        hookCallEnd()
        mainHandler.postDelayed({ purgeAllFakeChatrooms() }, STARTUP_PURGE_DELAY_MS)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }

    // ------------------------------------------------------------------
    //  群资料入口：只出现在真实群
    // ------------------------------------------------------------------

    override fun getContactInfoItem(activity: Activity): List<WeContactPrefsScreenApi.PreferenceItem> {
        val chatroomId = resolveChatroomId(activity) ?: return emptyList()
        // 假群资料页什么都不显示；非群聊也不显示。
        if (isFakeChatroom(chatroomId)) return emptyList()
        if (!chatroomId.endsWith(REAL_SUFFIX)) return emptyList()

        // 页面加载时就把假群的 chatroom 行补好，点进去时微信的存储层已经能查到成员。
        ensureFakeChatroomRow(fakeChatIdOf(chatroomId), chatroomId)

        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(
                key = ITEM_CALL,
                title = "发起临时通话",
                position = 1,
            ),
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != ITEM_CALL) return false
        val chatroomId = resolveChatroomId(activity) ?: return false
        if (isFakeChatroom(chatroomId) || !chatroomId.endsWith(REAL_SUFFIX)) return false
        launchCall(activity, chatroomId)
        return true
    }

    /** 设置页里的入口：先选群，再进选成员页。 */
    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            SingleContactSelector(
                "临时通话",
                WeDatabaseApi.getGroups(),
                initialSelectedWxId = null,
                onDismiss = onDismiss,
            ) {
                onDismiss()
                launchCall(context, it)
            }
        }
    }

    // ------------------------------------------------------------------
    //  发起通话
    // ------------------------------------------------------------------

    private fun launchCall(context: Context, realId: String) {
        runCatching {
            val fakeId = fakeChatIdOf(realId)
            // 先清掉上一次可能残留的会话行（不刷列表），再补本次要用的 chatroom 记录。
            // 顺序不能反：purge 会删 chatroom 行，ensure 负责插回去。
            purgeFakeChatroom(fakeId, notify = false)
            ensureFakeChatroomRow(fakeId, realId)
            lastFakeChatroomId = fakeId

            val members = loadMemberIds(realId)
            val intent = Intent().apply {
                setClassName(context, SELECT_MEMBER_UI)
                putExtra("chatroomName", fakeId)
                // 关键：这个 extra 决定「确定」是直接发起通话，还是只把选中的人回传给调用方。
                // 不传它，MultiTalkSelectContactUI 会在点确定后 setResult + finish()，通话根本不会发起。
                putExtra("key_need_gallery", true)
                // 默认勾选全部成员（可手动取消）；超过 15 人的限制由 hookMemberLimit 放开。
                if (members.isNotEmpty()) putExtra("default_select_contact", members.joinToString(","))
            }
            context.startActivity(intent)
            WeLogger.i(
                TAG,
                "已打开选成员页，临时假群=$fakeId（真实群=$realId，默认勾选 ${members.size} 人）",
            )
        }.onFailure {
            WeLogger.e(TAG, "打开选成员页失败: $SELECT_MEMBER_UI", it)
        }
    }

    // ------------------------------------------------------------------
    //  通话结束：清掉假群
    // ------------------------------------------------------------------

    /**
     * 通话结束（我们挂断、用户点挂断、对方挂断）后清掉假群。
     *
     * 拨通时微信会用 `chatroomName`（假群 ID）写会话与联系人行，于是会话列表里会多出一个
     * 「假群」。这里在 `d06.p.u4`（退出群通话）返回后延时清理，避免边写边删。
     *
     * 注意：`u4` 声明在接口 `d06.p` 上是**抽象方法**，对它 hook 永远不会触发，
     * 必须挂到实现类 `ry5.i3` 的同名方法上。
     */
    private fun hookCallEnd() {
        runCatching {
            val impl = Class.forName(HANGUP_IMPL)
            val method = impl.declaredMethods.firstOrNull {
                it.name == HANGUP_METHOD && it.parameterTypes.size == 1
            } ?: run {
                WeLogger.w(TAG, "通话结束 hook：$HANGUP_IMPL.$HANGUP_METHOD 不存在")
                return@runCatching
            }
            method.isAccessible = true
            method.hookAfter {
                val fakeId = lastFakeChatroomId ?: return@hookAfter
                lastFakeChatroomId = null
                mainHandler.postDelayed({ purgeFakeChatroom(fakeId, notify = true) }, PURGE_DELAY_MS)
            }
            WeLogger.i(TAG, "通话结束清理 hook 已安装: ${impl.name}.${method.name}")
        }.onFailure { WeLogger.w(TAG, "hook 通话结束失败", it) }
    }

    /** 启动后扫一遍会话表，清掉旧版本遗留的假群。 */
    private fun purgeAllFakeChatrooms() {
        runCatching {
            val ids = WeDatabaseApi.executeQuery(
                "SELECT username FROM rconversation WHERE username LIKE '%@@chatroom'",
            ).mapNotNull { it["username"]?.toString() }.filter { isFakeChatroom(it) }
            if (ids.isEmpty()) return
            ids.forEach { purgeFakeChatroom(it, notify = false) }
            WeConversationApi.reloadConversations()
            WeLogger.i(TAG, "启动清理：已清除 ${ids.size} 个遗留假群 ${ids.joinToString()}")
        }.onFailure { WeLogger.w(TAG, "启动清理假群失败", it) }
    }

    /**
     * 清掉假群在微信里的所有痕迹。
     *
     * 表清单沿用原项目 `DeleteFakeGroups`（11 张），按「依赖 → 主体」顺序删，
     * 单表失败不影响其他表。`hideConversation` 先走微信自己的删除路径，让会话列表立刻刷新。
     * 只处理 `@@chatroom`，绝不动真实群。
     */
    private fun purgeFakeChatroom(fakeId: String, notify: Boolean) {
        if (!isFakeChatroom(fakeId)) return
        runCatching {
            val hidden = WeConversationApi.hideConversation(fakeId)
            var rows = 0
            val steps = listOf(
                "message" to ("talker = ?" to arrayOf(fakeId)),
                "ImgInfo2" to ("msgTalker = ?" to arrayOf(fakeId)),
                "MsgQuote" to ("quotedMsgTalker = ?" to arrayOf(fakeId)),
                "GroupBindApp" to ("chatRoomName = ?" to arrayOf(fakeId)),
                "GroupSolitatire" to ("username = ?" to arrayOf(fakeId)),
                "GroupTodo" to ("roomname = ?" to arrayOf(fakeId)),
                "GroupTools" to ("chatroomname = ?" to arrayOf(fakeId)),
                "chatroom" to ("chatroomname = ?" to arrayOf(fakeId)),
                "rconversation" to ("username = ?" to arrayOf(fakeId)),
                "img_flag" to ("username = ?" to arrayOf(fakeId)),
                "rcontact" to ("username = ?" to arrayOf(fakeId)),
            )
            for ((table, cond) in steps) {
                rows += runCatching { WeDatabaseApi.delete(table, cond.first, cond.second) }.getOrDefault(0)
            }
            if (notify) WeConversationApi.reloadConversations()
            WeLogger.i(TAG, "已清理临时假群: $fakeId（hidden=$hidden, rows=$rows）")
        }.onFailure { WeLogger.w(TAG, "清理临时假群失败: $fakeId", it) }
    }

    // ------------------------------------------------------------------
    //  选成员页：只在这个页面放开 15 人上限
    // ------------------------------------------------------------------

    private fun hookPickerLifecycle() {
        runCatching {
            val cls = Class.forName(SELECT_MEMBER_UI)
            findMethod(cls, "onCreate", 1)?.let { m ->
                m.isAccessible = true
                m.hookAfter {
                    val activity = thisObject as? Activity
                    val chatroom = runCatching { activity?.intent?.getStringExtra("chatroomName") }.getOrNull()
                    fakePickerActive = chatroom != null && isFakeChatroom(chatroom)
                }
            }
            findMethod(cls, "onDestroy", 0)?.let { m ->
                m.isAccessible = true
                m.hookBefore { fakePickerActive = false }
            }
            WeLogger.i(TAG, "选成员页生命周期 hook 已安装")
        }.onFailure { WeLogger.w(TAG, "hook 选成员页失败", it) }
    }

    /** 15 人上限来自 `bz5.f.b()`（读 ABTest 配置，默认 15）。 */
    private fun hookMemberLimit() {
        runCatching {
            val cls = Class.forName("bz5.f")
            val method = cls.declaredMethods.firstOrNull {
                it.name == "b" && it.parameterTypes.isEmpty() &&
                    (it.returnType == Int::class.javaPrimitiveType || it.returnType == Integer::class.java)
            } ?: run {
                WeLogger.w(TAG, "未找到 bz5.f.b()，15 人上限无法放开")
                return@runCatching
            }
            method.isAccessible = true
            method.hookAfter {
                if (fakePickerActive) result = MAX_CALL_MEMBERS
            }
            WeLogger.i(TAG, "人数上限 hook 已安装")
        }.onFailure { WeLogger.w(TAG, "hook 人数上限失败", it) }
    }

    // ------------------------------------------------------------------
    //  工具
    // ------------------------------------------------------------------

    /** 从 chatroom.memberlist 读出群成员 wxid 列表（分号分隔）。 */
    private fun loadMemberIds(realId: String): List<String> = runCatching {
        WeDatabaseApi.executeQuery(
            "SELECT memberlist FROM chatroom WHERE chatroomname = ?",
            arrayOf(realId),
        ).firstOrNull()?.get("memberlist")?.toString()
            ?.split(";")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
    }.getOrDefault(emptyList())

    /**
     * 给假群补一条 `chatroom` 记录（memberlist / roomdata / memberCount 从真实群复制）。
     * 已有非空 memberlist 时不动。这条记录不进会话列表，也不影响真实群。
     */
    private fun ensureFakeChatroomRow(fakeId: String, realId: String) {
        runCatching {
            val existing = WeDatabaseApi.executeQuery(
                "SELECT memberlist FROM chatroom WHERE chatroomname = ?",
                arrayOf(fakeId),
            )
            if (existing.firstOrNull()?.get("memberlist")?.toString()?.isNotBlank() == true) return

            val db = WeDatabaseApi.databaseOrNull() ?: run {
                WeLogger.w(TAG, "ensureFakeChatroomRow: 数据库不可用")
                return
            }
            db.execSQL(
                "INSERT OR REPLACE INTO chatroom " +
                    "(chatroomname, memberlist, roomdata, chatroomVersion, memberCount) " +
                    "SELECT ?, memberlist, roomdata, chatroomVersion, memberCount " +
                    "FROM chatroom WHERE chatroomname = ?",
                arrayOf(fakeId, realId),
            )
            WeLogger.i(TAG, "已为临时假群复制 chatroom 记录: $fakeId <- $realId")
        }.onFailure { WeLogger.w(TAG, "ensureFakeChatroomRow failed", it) }
    }

    private fun findMethod(type: Class<*>, name: String, paramCount: Int): java.lang.reflect.Method? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods
                .firstOrNull { it.name == name && it.parameterTypes.size == paramCount }
                ?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun fakeChatIdOf(realId: String): String = "${realId.substringBefore("@")}$FAKE_SUFFIX"

    private fun isFakeChatroom(chatroomId: String): Boolean = chatroomId.endsWith(FAKE_SUFFIX)

    /** 群资料页的群 ID 有几个可能的 extra key，按微信实际下发的顺序取第一个非空的。 */
    private fun resolveChatroomId(activity: Activity): String? {
        val intent = activity.intent ?: return null
        for (key in CHATROOM_ID_EXTRAS) {
            val value = runCatching { intent.getStringExtra(key) }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private val CHATROOM_ID_EXTRAS = listOf(
        "RoomInfo_Id",
        "RoomInfo_Contact_User",
        "Chat_User",
        "Contact_User",
    )
}
