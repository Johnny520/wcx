package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.TextView
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.TextPaint
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.robv.android.xposed.XC_MethodHook
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.conversation.BaseConversationUI
import com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI
import com.tencent.mm.ui.conversation.MainUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.SelfProfileField
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.models.IWeContact
import com.Johnny.wcx.features.api.ui.WeStartActivityApi
import com.Johnny.wcx.features.api.ui.WeMainActivityBeautifyApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.items.chat.ConversationAggregation.syncFoldersToDatabase
import com.Johnny.wcx.features.items.contacts.CustomLocalFriendAvatars
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.BaseContactSelector
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.EditIcon
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.HookParam
import com.Johnny.wcx.utils.hookAfterDirectly
import com.Johnny.wcx.utils.hookBeforeDirectly
import com.Johnny.wcx.utils.reflection.ClassLoaders
import com.Johnny.wcx.utils.HostInfo
import androidx.core.graphics.toColorInt
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.WeColorField
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.captureOriginalMethod
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.reflection.BString
import com.Johnny.wcx.utils.serialization.DefaultJson
import kotlinx.serialization.Serializable
import java.lang.reflect.Proxy
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import java.lang.reflect.Modifier as JavaModifier

@Feature(name = "对话归拢", categories = ["聊天"], description = "将多个对话归拢在一个文件夹内\n设置对话头像需同时启用「自定义好友本地头像」")
object ConversationAggregation : ClickableFeature(),
    WeDatabaseListenerApi.IQueryListener,
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener,
    WeStartActivityApi.IStartActivityListener,
    IResolveDex {

    private const val TAG = "AggregateChats"
    const val FOLDER_PREFIX = "wekit_folder_"
    private const val FOLDER_CONFIG_MENU_ID = 0x0721C0DE
    private const val MOVE_TO_FOLDER_MENU_ID = 777021
    private const val MOVE_TO_FOLDER_MENU_ORDER = 1001
    private const val REMOVE_FROM_FOLDER_MENU_ID = 777020

    // Order pushes our item to the end of the container's context menu (its own items use 0).
    private const val REMOVE_FROM_FOLDER_MENU_ORDER = 1000

    // rconversation.flag packing (see WeChat xg3.b.c): high 8 bits = pin / move-up state
    // owned by WeChat (setPlacedTop / unSetPlacedTop), low 56 bits = conversationTime.
    private const val FLAG_TIME_MASK = 0x00FFFFFFFFFFFFFFL
    private const val FLAG_HIGH_MASK = FLAG_TIME_MASK.inv()

    // attrflag bit the conversation box uses to mark "has muted unread" so the homepage
    // badge renders a small dot instead of a number (WeChat w3.b / s2 require this bit set
    // alongside unReadMuteCount > 0 when unReadCount == 0).
    private const val ATTR_FLAG_MUTE_BIT = 2097152

    // Truncation + tint used by the FunBox-style "someone @ me" prefix on folder rows.
    private const val MAX_DIGEST_NAME_LEN = 8
    private const val MAX_FOLDER_DISPLAY_NAME = 12
    // 括号内发送者名截断长度（Eatmelons → Eatm...），括号内空间小，比群名更短
    private const val MAX_SENDER_NAME_LEN = 4
    // 归拢摘要红绿灯配色：[全体]/[有人@我] 红、[N个聊天]/[N个消息] 黄、[自己] 绿
    private const val DEFAULT_AT_COLOR = "#FF2E78E6"
    private const val DEFAULT_COUNT_COLOR = "#FFF2D200"
    private const val DEFAULT_SELF_COLOR = "#FF222222"
    private const val DEFAULT_MEMBER_COLOR = "#FFE8E8E8"
    private const val DEFAULT_TITLE_COLOR = "#FFFF8800"
    private var mentionAtColor by WePrefs.prefOption("agg_mention_at_color", DEFAULT_AT_COLOR)
    private var mentionCountColor by WePrefs.prefOption("agg_mention_count_color", DEFAULT_COUNT_COLOR)
    private var mentionSelfColor by WePrefs.prefOption("agg_mention_self_color", DEFAULT_SELF_COLOR)
    private var mentionMemberColor by WePrefs.prefOption("agg_mention_member_color", DEFAULT_MEMBER_COLOR)
    private var folderTitleColor by WePrefs.prefOption("agg_folder_title_color", DEFAULT_TITLE_COLOR)
    private var folderTitleEnabled by WePrefs.prefOption("agg_folder_title_enabled", true)
    private var mentionSelfEnabled by WePrefs.prefOption("agg_mention_self_enabled", true)
    private var mentionMemberEnabled by WePrefs.prefOption("agg_mention_member_enabled", true)
    private fun parseColor(value: String, fallback: String): Int =
        runCatching { value.toColorInt() }.getOrElse { fallback.toColorInt() }
    /** 暗色/亮色模式适配：暗色模式将染色提亮（HSV 明度下限 0.78），保证深底可读（类似微信原生暗色白字）；亮色模式返回原色 */
    private fun adaptNight(ctx: Context?, color: Int): Int {
        if (ctx == null) return color
        val night = ctx.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (!night) return color
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv[2] = maxOf(hsv[2], 0.78f)
        return android.graphics.Color.HSVToColor(android.graphics.Color.alpha(color), hsv)
    }

    private val MENTION_RED: Int get() = parseColor(mentionAtColor, DEFAULT_AT_COLOR)
    private val MENTION_YELLOW: Int get() = parseColor(mentionCountColor, DEFAULT_COUNT_COLOR)
    private val MENTION_GREEN: Int get() = parseColor(mentionSelfColor, DEFAULT_SELF_COLOR)
    /** 归拢文件夹标题蓝色 */
    private val MENTION_TITLE_BLUE: Int get() = parseColor(folderTitleColor, DEFAULT_TITLE_COLOR)
    private val MENTION_MEMBER: Int get() = parseColor(mentionMemberColor, DEFAULT_MEMBER_COLOR)
    private val MEMBER_PAREN_REGEX = Regex("[（(][^（）()]+[）()]")
    private val CHAT_COUNT_REGEX = Regex("\\[[^\\]]*\\u4e2a(?:\\u804a\\u5929|\\u6d88\\u606f)\\]")

    // 归拢配置按账号隔离：每个账号独立配置文件，避免切换账号后
    // 显示其他账号的归拢文件夹（成员存的是该账号的联系人/群聊）。
    private val legacyFoldersFile by lazy { KnownPaths.moduleData / "chat_folders.json" }

    private fun foldersFileFor(wxid: String?) = if (!wxid.isNullOrBlank()) {
        KnownPaths.moduleData / "chat_folders_$wxid.json"
    } else {
        legacyFoldersFile
    }

    private fun currentAccountWxid(): String? = runCatching {
        WeDatabaseApi.getSelfProfileField(SelfProfileField.WXID)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()


    private const val CONTAINER_UI_NAME = "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI"
    private val methodSqliteWrapperRawQuery by dexMethod(allowFailure = true) {
        matcher {
            modifiers = JavaModifier.PUBLIC
            usingEqStrings("sql is null ", "DB IS CLOSED ! {%s}")
            paramTypes("java.lang.String", "java.lang.String[]", "int")
            returnType("android.database.Cursor")
        }
    }
    private val methodConversationStorageQueryByParent by dexMethod(allowFailure = true) {
        matcher {
            // 8.0.80 起 "select * from rconversation where " 是孤儿串（无 const-string 引用），
            // 只保留仍存活的排序子句。真实签名在两版都是 5 参：
            // (int, List, String, boolean, String) → Cursor
            // （原写的 4 参 (int,List,String,int) 一直匹配不上）。
            // 8.0.76/8.0.79 = storage.l4.s、8.0.80 = storage.m4.s，三版均唯一命中。
            usingStrings(" order by flag desc, conversationTime desc")
            paramTypes(
                "int",
                "java.util.List",
                "java.lang.String",
                "boolean",
                "java.lang.String"
            )
            returnType("android.database.Cursor")
        }
    }

    // SelectConversationUI#doClickUser(username) — the single entry point for all conversation
    // taps in the "share to conversation" picker. WeChat only intercepts known virtual usernames
    // ("conversationboxservice", "opencustomerservicemsg") before forwarding to its share logic.
    // Our folder rows (wekit_folder_XXX) pass those guards and reach the share machinery, which
    // tries to open a chat thread for a non-existent contact → crash.
    private val methodSelectConversationDoClickUser by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.SelectConversationUI", "doClickUser=%s")
            paramTypes("java.lang.String")
            returnType("void")
        }
    }

    // The MVVM "select contact" picker (com.tencent.mm.ui.mvvm.MvvmContactListUI) used for in-app
    // forwarding routes every row tap through its list item-click listener cj5.g2#g(View, item, int)
    // (interface in5.u). A tap on a normal conversation dispatches wi5.c0(listOf(username)) to the
    // state center, which sets the "Select_Conv_User" result extra and finishes. Our folder rows
    // (wekit_folder_XXX) reach that same path with a non-existent username → crash downstream.
    // We match the two concrete listeners (main list + search results) by their unique log tags.
    private val methodMvvmMainListItemClick by dexMethod {
        matcher {
            usingStrings("MicroMsg.SelectContactMainRecycleViewUIC", "onItemClickListener data.type")
        }
    }
    private val methodMvvmSearchItemClick by dexMethod {
        matcher {
            usingStrings("MicroMsg.SelectContactSearchMvvmListUIC", "onItemClick: isAlwaysCheck=")
            paramTypes("android.view.View", null, "int")
            returnType("void")
        }
    }

    // com.tencent.mm.storage.m4 (ConversationStorage)#b0(username) — "updateUnreadByTalker".
    // The folder container (ConvBoxServiceConversationUI) sets its superUsername to our folder id
    // (via the Contact_User extra we inject). WeChat's ConvBoxServiceConversationFmUI.onPause()
    // then calls b0(superUsername), which zeroes unReadCount / unReadMuteCount and clears the mute
    // attrflag bit on that exact row — wiping our folder's badge just for opening and leaving the
    // folder without touching any member. We no-op it for folder ids so the aggregate row keeps
    // reflecting its members' (still-unread) state.
    private val methodConversationStorageUpdateUnreadByTalker by dexMethod(allowFailure = true) {
        matcher {
            usingStrings("MicroMsg.ConversationStorage", "update conversation failed")
            paramTypes("java.lang.String")
            returnType("boolean")
        }
    }

    // com.tencent.mm.ui.widget.menu.MMPopupMenu#showMenu(view, pos, id, onCreateListener, selectCb, x, y)
    // The shared long-press popup used by both the homepage list and the folder container. We hook
    // it (gated on activeFolderId) to inject a "remove from folder" item only inside our folders.
    private val methodShowPopupMenu by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingStrings("MicroMsg.MMPopupMenu")
            }
            paramTypes(
                "android.view.View", "int", "long",
                $$"android.view.View$OnCreateContextMenuListener", null, "int", "int"
            )
            returnType("void")
        }
    }

    @Volatile
    private var activeFolderId: String? = null

    @Volatile
    private var folderSchemaReady: Boolean? = null

    @Volatile
    private var foldersCache: List<ChatFolder>? = null

    @Volatile
    private var foldersCacheWxid: String? = null

    private val folderMembersCache = ConcurrentHashMap<String, List<String>>()

    @Volatile
    private var membersByFolder: Map<String, List<String>> = emptyMap()

    @Volatile
    private var folderByMember: Map<String, String> = emptyMap()

    private val suppressQueryRewrite = ThreadLocal.withInitial { false }

    // Reactive refresh: WeChat updates member conversation rows (new message / read state)
    // through the ContentValues insert/update path, but our materialized folder rows are
    // written via raw execSQL and never recomputed until MainUI.onResume. We listen for
    // member-row writes and debounce a lightweight summary recompute so the homepage folder
    // row tracks its members in real time.
    private const val REFRESH_DEBOUNCE_MS = 250L
    private val REFRESH_TASK_TOKEN = Any()
    private val RECONCILE_TASK_TOKEN = Any()
    private const val SQLITE_BIND_CHUNK_SIZE = 900
    private val pendingRefreshMembers = ConcurrentHashMap.newKeySet<String>()
    private val pendingRefreshLock = Any()
    private val refreshAllFolders = AtomicBoolean(false)

    @Volatile
    private var refreshThread: HandlerThread? = null

    @Volatile
    private var refreshHandler: Handler? = null

    override fun onEnable() {
        WeLogger.i(TAG, "onEnable: begin")
        diagFile("onEnable: begin")
        WeDatabaseListenerApi.addListener(this)
        WeStartActivityApi.addListener(this)
        // 切换微信账号后 storage 重新初始化（新账号数据库），需要把归拢文件夹
        // 对账写入新账号的库，否则新账号页面看不到归拢。
        WeDatabaseApi.addDatabaseSwitchListener(::onDatabaseSwitched)

        startRefreshThread()

        hookMainUiRefresh()
        WeLogger.i(TAG, "onEnable: after hookMainUiRefresh")
        diagFile("onEnable: after hookMainUiRefresh")
        hookColdStartResidualRecovery()
        hookOpenFolder()
        WeLogger.i(TAG, "onEnable: after hookOpenFolder")
        diagFile("onEnable: after hookOpenFolder")
        hookConversationPages()
        WeLogger.i(TAG, "onEnable: after hookConversationPages")
        diagFile("onEnable: after hookConversationPages")
        hookFolderContextMenu()
        WeLogger.i(TAG, "onEnable: after hookFolderContextMenu")
        diagFile("onEnable: after hookFolderContextMenu")
        hookSelectConversationUi()
        WeLogger.i(TAG, "onEnable: after hookSelectConversationUi")
        diagFile("onEnable: after hookSelectConversationUi")
        hookMvvmContactListItemClick()
        WeLogger.i(TAG, "onEnable: after hookMvvmContactListItemClick")
        diagFile("onEnable: after hookMvvmContactListItemClick")
        hookSqliteWrapperQuery()
        hookSqliteExec()
        WeLogger.i(TAG, "onEnable: after hookSqliteWrapperQuery")
        diagFile("onEnable: after hookSqliteWrapperQuery")
        hookConversationStorageParentQuery()
        WeLogger.i(TAG, "onEnable: after hookConversationStorageParentQuery")
        diagFile("onEnable: after hookConversationStorageParentQuery")
        hookConversationStorageUpdateUnread()
        WeLogger.i(TAG, "onEnable: after hookConversationStorageUpdateUnread")
        diagFile("onEnable: after hookConversationStorageUpdateUnread")
        hookMentionTint()
        WeLogger.i(TAG, "onEnable: after hookMentionTint")
        diagFile("onEnable: after hookMentionTint")
        hookTextViewSetText()
        hookAllTextViewDraw()
        WeLogger.i(TAG, "onEnable: done")
        diagFile("onEnable: done")

        CustomLocalFriendAvatars.fallbackUsernameProvider = { folderId ->
            if (isFolderId(folderId) && !CustomLocalFriendAvatars.avatarMap.containsKey(folderId)) {
                getFallbackAvatarMember(folderId)
            } else {
                null
            }
        }

        // Restore the materialized folder rows when re-enabled at runtime (DB already up), since
        // onDisable released them. On cold startup the DB isn't ready yet and this is a no-op —
        // MainUI.onResume (hookMainUiRefresh) runs the first sync once WeChat is up.
        if (WeDatabaseApi.isReady) {
            syncFoldersToDatabase()
        }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        WeStartActivityApi.removeListener(this)
        WeDatabaseApi.removeDatabaseSwitchListener(::onDatabaseSwitched)
        CustomLocalFriendAvatars.fallbackUsernameProvider = null
        stopRefreshThread()

        // Release every folder back to the homepage — unmap members and delete all wekit_folder_*
        // rows — so disabling doesn't leave ghost aggregate conversations behind, exactly as if the
        // user had deleted every folder. The saved config is left untouched so onEnable can restore.
        releaseAllFolders()
    }

    /**
     * Reverses [syncFoldersToDatabase]: returns every folder member to the root homepage list and
     * removes all folder rows (rconversation / rcontact / img_flag). Mirrors deleting every folder
     * by hand, but keeps the on-disk config so the folders come back on the next onEnable.
     */
    /**
     * 微信切换账号后 WeDatabaseApi 已把 db 引用切到新账号：
     * 重新对账 folder 行到新库（reconcile 是差异写入，新库无行则全量重建），
     * 并刷新会话列表让归拢立即显示。
     */
    private fun onDatabaseSwitched() {
        WeLogger.i(TAG, "account/database switched, re-syncing folders to new database")
        runCatching {
            // 配置按账号隔离：db 变化说明账号已切换，清缓存按新账号重新加载
            foldersCache = null
            foldersCacheWxid = null
            folderMembersCache.clear()
            if (WeDatabaseApi.isReady && isFolderSchemaReady()) {
                syncFoldersToDatabase()
                WeConversationApi.reloadConversations()
            }
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to re-sync folders after account switch", e)
        }
    }

    private fun releaseAllFolders() {
        if (!WeDatabaseApi.isReady) return
        runCatching {
            withQueryRewriteSuppressed {
                if (!isFolderSchemaReady()) return@withQueryRewriteSuppressed
                val folders = loadFolders()
                persistChangedPinFlags(folders, readStoredFolderRows().mapValues { it.value.flag })
                WeDatabaseApi.transaction { clearStaleFolderMappings() }
                membersByFolder = emptyMap()
                folderByMember = emptyMap()
                folderMembersCache.clear()
            }
            WeConversationApi.reloadConversations()
            WeLogger.i(TAG, "released all folders on disable")
        }.onFailure {
            WeLogger.e(TAG, "failed to release folders on disable", it)
        }
    }

    /**
     * 冷启动残留回收。
     *
     * 归拢把成员会话的 parentRef 改写成文件夹 id、并在 rconversation/rcontact/img_flag
     * 里插入文件夹行 —— 这些都是**写进微信库的持久状态**，微信自己就认 parentRef，
     * 所以模块在不在它都照样折叠成员。而唯一的清理入口 [releaseAllFolders] 只挂在
     * `onDisable` 上：用户在管理器里取消勾选本模块时，模块一行代码都不跑，残留就永远
     * 留在库里 —— 表现为几百个会话从主页消失、只剩几个空壳文件夹。
     *
     * 这里把回收挂到 [WeMainActivityBeautifyApi.methodDoOnCreate]（每次冷启动必跑），
     * 不再依赖 `onDisable`。
     */
    private fun hookColdStartResidualRecovery() {
        runCatching {
            WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
                recoverResidualFolders()
            }
        }.onFailure {
            WeLogger.w(TAG, "残留回收 hook 安装失败（不影响其他功能）", it)
        }
    }

    /**
     * 释放库里残留的归拢痕迹。
     *
     * 与 [releaseAllFolders] 的区别：后者是「用户主动关闭归拢」的正常路径，这里只关心
     * **库里是否还有残留** —— 有就清，没有就什么也不做。所以本函数**不依赖 isEnabled**：
     * 正常启用的归拢，其文件夹行会被下次 [syncFoldersToDatabase] 重建，清了也不影响。
     *
     * 不能只在首次启动跑一次：用户可能先回退旧版本模块又切回来，所以每次冷启动都查一次
     * （代价是一条 SELECT COUNT，可忽略）。
     */
    private fun recoverResidualFolders() {
        // DB 未就绪就等下次冷启动。这是唯一允许失败的路径：残留不会自己消失，
        // 但此刻强行操作会拿到空结果或报错，反而危险。
        if (!WeDatabaseApi.isReady) {
            WeLogger.w(TAG, "残留回收: DB 未就绪，跳过（下次冷启动重试）")
            return
        }

        runCatching {
            val folderRows = mutableListOf<String>()
            withQueryRewriteSuppressed {
                WeDatabaseApi.rawQuery(
                    "SELECT ${ConversationTable.USERNAME} FROM ${ConversationTable.NAME} WHERE ${ConversationTable.USERNAME} LIKE ?",
                    arrayOf("$FOLDER_PREFIX%")
                ).use { c ->
                    while (c.moveToNext()) folderRows += c.getString(0)
                }
            }

            if (folderRows.isEmpty()) return@runCatching

            val foldedCount = withQueryRewriteSuppressed {
                WeDatabaseApi.rawQuery(
                    "SELECT COUNT(*) FROM ${ConversationTable.NAME} WHERE ${ConversationTable.PARENT_REF} LIKE ?",
                    arrayOf("$FOLDER_PREFIX%")
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            }

            if (foldedCount == 0) return@runCatching

            WeLogger.w(
                TAG,
                "残留回收: 发现残留文件夹=${folderRows.size} 被折叠会话=$foldedCount，开始释放"
            )

            withQueryRewriteSuppressed {
                if (!isFolderSchemaReady()) {
                    WeLogger.e(TAG, "残留回收: schema 未就绪，放弃（下次冷启动重试）")
                    return@withQueryRewriteSuppressed
                }
                WeDatabaseApi.transaction { clearStaleFolderMappings() }
            }
            membersByFolder = emptyMap()
            folderByMember = emptyMap()
            folderMembersCache.clear()

            WeConversationApi.reloadConversations()
            WeLogger.i(TAG, "残留回收: 已释放 $foldedCount 个会话，它们应已回到主页")
        }.onFailure {
            WeLogger.e(TAG, "残留回收失败（不影响其他功能）", it)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showManagerDialog(context)
    }

    /** Whether [username] is one of our materialized folder rows (vs. a real conversation). */
    fun isAggregationFolderId(username: String): Boolean = isFolderId(username)

    /** A folder choice exposed to other features (e.g. the "add to folder" conversation menu). */
    data class FolderChoice(val id: String, val name: String, val isAuto: Boolean)

    /** Public snapshot of the configured folders, for features that let the user pick one. */
    fun aggregationFolders(): List<FolderChoice> =
        loadFolders().map { FolderChoice(it.id, it.name, it.type != FolderType.MANUAL) }

    /** Public member list of [folderId] (ContactSelectors' "add to folder" picker depends on it). */
    fun folderMembers(folderId: String): List<String> =
        loadFolders().firstOrNull { it.id == folderId }?.members ?: emptyList()

    /**
     * Adds [talker] to the manual folder [folderId] and opens the existing edit dialog so the
     * user can review and save. Returns false without acting when the folder is missing or in an
     * auto mode (members are computed, not hand-picked); callers surface that to the user.
     */
    fun showAddToFolderDialog(context: Context, folderId: String, talker: String): Boolean {
        val folder = folderById(folderId) ?: return false
        if (folder.type != FolderType.MANUAL) return false
        val updated = folder.copy(members = (folder.members + talker).distinct().sorted())
        showEditFolderDialog(
            context = context,
            folder = updated,
            onFolderUpdated = {
                syncFoldersToDatabase()
            },
            onFolderDeleted = {
                syncFoldersToDatabase()
            }
        )
        return true
    }

    /**
     * Adds [talker] to the manual folder [folderId] and persists immediately (no dialog),
     * rebuilding the index so the row appears in the folder. Returns false without acting when the
     * folder is missing or in an auto mode (members are computed, not hand-picked).
     */
    fun addToFolder(folderId: String, talker: String): Boolean {
        val folder = folderById(folderId) ?: return false
        if (folder.type != FolderType.MANUAL) return false
        if (talker !in folder.members) {
            val updated = folder.copy(members = (folder.members + talker).distinct().sorted())
            saveFolders(loadFolders().map { if (it.id == updated.id) updated else it })
            syncFoldersToDatabase()
        }
        return true
    }

    /**
     * Removes [talker] from the manual folder [folderId], persists, and rebuilds the index so the
     * row disappears from the folder immediately. No-op for missing / auto folders, or when the
     * talker isn't actually a member.
     */
    private fun removeMemberFromFolder(folderId: String, talker: String) {
        val folder = folderById(folderId) ?: return
        if (folder.type != FolderType.MANUAL || talker !in folder.members) {
            showToast("该对话不在此手动文件夹中!")
            return
        }
        val updated = folder.copy(members = folder.members.filterNot { it == talker })
        saveFolders(loadFolders().map { if (it.id == updated.id) updated else it })
        syncFoldersToDatabase()
        showToast("已移出「${folder.name}」")
    }
    /** Long-press menu "move to folder": move talker from its current manual folder to another manual folder. */
    private fun showMoveToFolderDialog(context: Context, talker: String) {
        val source = loadFolders().firstOrNull { talker in it.members && it.type == FolderType.MANUAL } ?: run {
            showToast("该对话不在手动文件夹中!")
            return
        }
        val targets = loadFolders().filter { it.id != source.id && it.type == FolderType.MANUAL }
        if (targets.isEmpty()) {
            showToast("没有其他手动文件夹可移入!")
            return
        }
        showComposeDialog(context) {
            val dismiss = this.onDismiss
            AlertDialogContent(
                title = { Text("移到文件夹") },
                text = {
                    LazyColumn {
                        items(targets) { target ->
                            Text(
                                target.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val current = loadFolders()
                                        val srcRemoved = current.map {
                                            if (it.id == source.id) it.copy(members = it.members.filterNot { m -> m == talker }) else it
                                        }
                                        val finalList = srcRemoved.map {
                                            if (it.id == target.id) it.copy(members = (it.members + talker).distinct().sorted()) else it
                                        }
                                        saveFolders(finalList)
                                        syncFoldersToDatabase()
                                        showToast("已移到「${target.name}」")
                                        dismiss()
                                    }
                                    .padding(12.dp)
                            )
                        }
                    }
                }
            )
        }
    }

    // Called by WeDatabaseListenerApi when WeChat inserts a conversation row
    override fun onInsert(table: String, values: ContentValues) {
        if (table != ConversationTable.NAME) return
        val username = values.getAsString(ConversationTable.USERNAME) ?: return
        if (isFolderId(username)) return  // skip our own folder row writes
        scheduleRefresh(username)
    }

    // Called by WeDatabaseListenerApi when WeChat updates conversation rows
    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int
    ) {
        if (table != ConversationTable.NAME) return
        // Skip updates that target only folder rows
        val targetUsername = values.getAsString(ConversationTable.USERNAME)
            ?: whereArgs?.singleOrNull()?.takeIf {
                whereClause?.contains(ConversationTable.USERNAME, ignoreCase = true) == true
            }
        if (targetUsername != null && isFolderId(targetUsername)) return
        scheduleRefresh(targetUsername)
    }

    private fun scheduleRefresh(username: String?) {
        val handler = refreshHandler ?: return
        if (loadFolders().isEmpty()) return
        if (username == null) {
            refreshAllFolders.set(true)
        } else {
            synchronized(pendingRefreshLock) { pendingRefreshMembers += username }
        }
        handler.removeCallbacksAndMessages(REFRESH_TASK_TOKEN)
        handler.postAtTime(
            ::doRefreshFolderSummaries,
            REFRESH_TASK_TOKEN,
            SystemClock.uptimeMillis() + REFRESH_DEBOUNCE_MS
        )
    }

    private fun doRefreshFolderSummaries() {
        if (!WeDatabaseApi.isReady) return
        val folders = loadFolders()
        if (folders.isEmpty()) return
        val changedMembers = synchronized(pendingRefreshLock) {
            pendingRefreshMembers.toSet().also { pendingRefreshMembers.clear() }
        }
        val refreshAll = refreshAllFolders.getAndSet(false)

        // A custom SQL rule may depend on any rconversation column. Reconcile it before using the
        // reverse index, because this write may have changed membership rather than just a summary.
        if (folders.any { it.type == FolderType.SQL } ||
            changedMembers.any { it !in folderByMember } && folders.any { it.type != FolderType.MANUAL }
        ) {
            reconcileFolders(folders)
            return
        }

        val affectedFolderIds = if (refreshAll) {
            membersByFolder.keys
        } else {
            changedMembers.mapNotNullTo(linkedSetOf()) { folderByMember[it] }
        }
        if (affectedFolderIds.isEmpty()) return

        runCatching {
            val startedAt = SystemClock.elapsedRealtime()
            withQueryRewriteSuppressed {
                if (!isFolderSchemaReady()) return@withQueryRewriteSuppressed
                val affectedMembers = membersByFolder.filterKeys { it in affectedFolderIds }
                WeDatabaseApi.transaction {
                    affectedMembers.forEach { (folderId, members) ->
                        reanchorFolderMembers(folderId, members)
                    }
                    val summaries = readFolderSummaries(affectedMembers)
                    affectedFolderIds.forEach { folderId ->
                        writeFolderSummaryRow(folderId, summaries[folderId] ?: FolderSummary())
                    }
                }
            }
            WeConversationApi.reloadConversations()
            WeLogger.d(
                TAG,
                "refreshed ${affectedFolderIds.size} folders for ${changedMembers.size} members in " +
                        "${SystemClock.elapsedRealtime() - startedAt}ms"
            )
        }.onFailure {
            WeLogger.e(TAG, "failed to refresh folder summaries", it)
        }
    }

    /**
     * Restores [ConversationTable.PARENT_REF] = [folderId] for any member whose row was
     * replaced by WeChat's own conversation update without a parentRef. Only rows where
     * parentRef is currently NULL or '' are touched — rows already mapped to this folder
     * (or to another folder) are left unchanged.
     */
    private fun reanchorFolderMembers(folderId: String, members: List<String>) {
        if (members.isEmpty()) return
        members.chunked(SQLITE_BIND_CHUNK_SIZE - 1).forEach { chunk ->
            WeDatabaseApi.execStatement(
                """
                UPDATE ${ConversationTable.NAME}
                SET ${ConversationTable.PARENT_REF}=?
                WHERE ${ConversationTable.USERNAME} IN (${placeholders(chunk.size)})
                  AND (${ConversationTable.PARENT_REF} IS NULL OR ${ConversationTable.PARENT_REF}='')
                """.trimIndent(),
                arrayOf(folderId, *chunk.toTypedArray())
            )
        }
    }

    private fun startRefreshThread() {
        val thread = HandlerThread("wekit-folder-refresh").also {
            it.start()
            refreshThread = it
        }
        refreshHandler = Handler(thread.looper)
    }

    private fun stopRefreshThread() {
        refreshHandler?.removeCallbacksAndMessages(null)
        refreshHandler = null
        refreshThread?.quitSafely()
        refreshThread = null
        synchronized(pendingRefreshLock) { pendingRefreshMembers.clear() }
        refreshAllFolders.set(false)
    }

    override fun onQuery(sql: String): String? {
        if (suppressQueryRewrite.get()!!) return null

        val folderId = activeFolderId ?: return null
        return rewriteContainerSql(sql, folderId).takeIf { it != sql }
    }

    override fun onStartActivity(param: XC_MethodHook.MethodHookParam, intent: Intent) {
        val folderId = readFolderIdFromIntent(intent) ?: return
        val componentName = intent.component?.className
        if (componentName != CONTAINER_UI_NAME) {
            activeFolderId = folderId
            intent.setClassName(param.thisObject as? Context ?: return, CONTAINER_UI_NAME)
        }
        applyFolderContainerIntent(intent, folderId)
    }

    private fun hookMainUiRefresh() {
        MainUI::class.reflekt().firstMethod("onResume").hookAfter {
            syncFoldersToDatabase()
        }
    }

    private fun hookOpenFolder() {
        LauncherUI::class.reflekt().firstMethod("startChatting").hookBefore {
            interceptFolderChatOpen(args.firstOrNull() as? String, thisObject) {
                result = null
            }
        }

        BaseConversationUI::class.reflekt().firstMethod("startChatting").hookBefore {
            interceptFolderChatOpen(args.firstOrNull() as? String, thisObject) {
                result = null
            }
        }
    }

    private inline fun interceptFolderChatOpen(
        username: String?,
        source: Any?,
        cancelOriginal: () -> Unit
    ) {
        if (username == null || !isFolderId(username)) return
        activeFolderId = username
        launchFolderContainer(source, username)
        cancelOriginal()
    }

    private fun hookConversationPages() {
        ConvBoxServiceConversationUI::class.hookBeforeOnCreate {
            val activity = thisObject as? Activity ?: return@hookBeforeOnCreate
            activeFolderId = readFolderIdFromIntent(activity.intent) ?: activeFolderId
        }

        // 成员聊天返回容器时，系统使用的是聊天页的退出动画；只关容器页的 windowAnimations
        // 不够，因为返回动画已经由 ChattingUI 发起。这里在任意页离开时取消这次转场，
        // 防止状态栏从灰色遮罩下滑回文件夹，也避免文件夹标题再次抖动。
        // 按类名 hook ChattingUI.onPause 在 8.0.78 上打不中（onPause 不在该类里），
        // 所以直接在 Activity 层兜底，容器页自身除外。
        runCatching {
            val guard = object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    disableMemberTransition(param.thisObject as? Activity ?: return)
                }
            }
            de.robv.android.xposed.XposedBridge.hookAllMethods(Activity::class.java, "onPause", guard)
            de.robv.android.xposed.XposedBridge.hookAllMethods(Activity::class.java, "finish", guard)
            WeLogger.i(TAG, "activity transition guard installed")
        }.onFailure { WeLogger.w(TAG, "activity transition guard failed", it) }

        BaseConversationUI::class.reflekt().apply {
            firstMethod("onResume").hookAfter {
                val activity = thisObject as? BaseConversationUI ?: return@hookAfter
                activeFolderId = activeFolderId ?: readFolderIdFromIntent(activity.intent)
                configureFolderActivity(activity)
            }

            firstMethod("onDestroy").hookAfter {
                activeFolderId = null
            }
        }
    }

    /** 会话页离开时取消窗口转场，避免返回文件夹时露出一帧灰遮罩/整页下沉。 */
    private fun disableMemberTransition(activity: Activity) {
        if (activeFolderId == null) return
        if (activity is ConvBoxServiceConversationUI) return
        runCatching {
            activity.overridePendingTransition(0, 0)
            activity.window?.setWindowAnimations(0)
            activity.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            activity.window?.attributes = activity.window?.attributes?.apply { dimAmount = 0f }
        }.onFailure { WeLogger.d(TAG, "disableMemberTransition failed: $it") }
    }

    // The folder container (ConvBoxServiceConversationUI) does NOT use the homepage's
    // ConversationLongClickListener that WeConversationContextMenuApi hooks; it builds its long-press
    // menu through the shared MMPopupMenu.showMenu(...). We hook that chokepoint, gated on
    // activeFolderId (null on the homepage, so that path is untouched), and inject a "remove from
    // folder" item by wrapping the menu-create listener and the (obfuscated) select callback.
    private fun hookFolderContextMenu() {
        if (methodShowPopupMenu.isPlaceholder) return

        // The 5th parameter's declared type is the obfuscated select-callback interface (db5.t4,
        // with the single method onMMMenuItemSelected). We proxy it to intercept our own item.
        val selectCallbackInterface = methodShowPopupMenu.method.parameterTypes[4]

        methodShowPopupMenu.hookBefore {
            val folderId = activeFolderId ?: return@hookBefore
            val menuContext = (args[0] as? android.view.View)?.context ?: return@hookBefore
            val folder = folderById(folderId) ?: return@hookBefore
            if (folder.type != FolderType.MANUAL) return@hookBefore

            val createListener = args[3] as? View.OnCreateContextMenuListener ?: return@hookBefore
            val originalSelect = args[4] ?: return@hookBefore
            val position = args[1] as? Int ?: return@hookBefore

            val talker = runCatching { extractFolderTalker(createListener, position) }
                .onFailure { WeLogger.w(TAG, "failed to resolve long-pressed conversation", it) }
                .getOrNull() ?: return@hookBefore

            // Only offer removal on a row that is actually a member of this manual folder.
            if (talker !in folder.members) return@hookBefore

            args[3] = View.OnCreateContextMenuListener { menu, view, menuInfo ->
                createListener.onCreateContextMenu(menu, view, menuInfo)
                runCatching {
                    menu.add(0, REMOVE_FROM_FOLDER_MENU_ID, REMOVE_FROM_FOLDER_MENU_ORDER, "移出文件夹")
                    menu.add(0, MOVE_TO_FOLDER_MENU_ID, MOVE_TO_FOLDER_MENU_ORDER, "移到文件夹")
                }.onFailure { WeLogger.e(TAG, "failed to add folder menu item", it) }
            }

            args[4] = Proxy.newProxyInstance(
                selectCallbackInterface.classLoader,
                arrayOf(selectCallbackInterface)
            ) { _, method, methodArgs ->
                val params = methodArgs ?: emptyArray()
                if (method.name == "onMMMenuItemSelected") {
                    val menuItem = params.getOrNull(0) as? MenuItem
                    if (menuItem?.itemId == REMOVE_FROM_FOLDER_MENU_ID) {
                        runCatching { removeMemberFromFolder(folderId, talker) }
                            .onFailure { WeLogger.e(TAG, "failed to remove from folder", it) }
                        return@newProxyInstance null
                    }
                    if (menuItem?.itemId == MOVE_TO_FOLDER_MENU_ID) {
                        runCatching { showMoveToFolderDialog(menuContext, talker) }
                            .onFailure { WeLogger.e(TAG, "failed to move to folder", it) }
                        return@newProxyInstance null
                    }
                }
                method.invoke(originalSelect, *params)
            }
        }
    }

    // Intercepts the "share to conversation" picker (SelectConversationUI) before WeChat's share
    // machinery runs. Our folder rows appear in that list because their parentRef is '' (root-level),
    // but they have no real chat thread — forwarding to one crashes. We cancel the call, show a
    // picker scoped to that folder's members, then re-invoke doClickUser with the chosen member so
    // the original share flow proceeds normally.
    private fun hookSelectConversationUi() {
        if (methodSelectConversationDoClickUser.isPlaceholder) return
        methodSelectConversationDoClickUser.hookBefore {
            val username = args.firstOrNull() as? String ?: return@hookBefore
            if (!isFolderId(username)) return@hookBefore

            val folder = folderById(username) ?: return@hookBefore
            val context = thisObject as? Context ?: return@hookBefore
            val originalMethod = captureOriginalMethod()

            // Cancel forwarding to the folder row itself — it has no real chat thread.
            result = null

            showFolderMemberPicker(context, folder) { selectedWxId ->
                runCatching {
                    originalMethod(arrayOf(selectedWxId))
                }.onFailure {
                    WeLogger.e(TAG, "failed to forward share to member $selectedWxId", it)
                }
            }
        }
    }

    // Same folder-row problem as SelectConversationUI, but for the MVVM contact picker
    // (com.tencent.mm.ui.mvvm.MvvmContactListUI) used by in-app forwarding. Every row tap goes
    // through a list item-click listener (cj5.g2#g for the main list, cj5.e4#g for search) whose
    // 2nd arg is the tapped item model (ri5.j). A normal conversation is forwarded by dispatching
    // wi5.c0(listOf(username)); our folder rows reach that path with a non-existent username →
    // crash. We cancel the tap and re-run the ORIGINAL listener with the model's username rewritten
    // to the chosen member so WeChat's own forward flow proceeds.
    private fun hookMvvmContactListItemClick() {
        listOf(
            methodMvvmMainListItemClick,
            methodMvvmSearchItemClick
        ).forEach { method ->
            if (method.isPlaceholder) return@forEach
            method.hookBefore { handleMvvmFolderTap(this) }
        }
    }

    private fun handleMvvmFolderTap(param: XC_MethodHook.MethodHookParam) {
        val itemView = param.args[0] as View
        val data = param.args[1]!!

        val folderField = data.reflekt().fields {
            type = BString
            modifiers(Modifiers.FINAL)
        }[1]
        val folderId = folderField.get()!! as String

        val folder = folderById(folderId) ?: return
        val originalMethod = param.captureOriginalMethod()

        // Cancel the tap on the folder row itself — it has no real chat thread.
        param.result = null

        showFolderMemberPicker(itemView.context, folder) { selectedWxId ->
            runCatching {
                folderField.set(selectedWxId)
                try {
                    // Re-run the ORIGINAL listener (bypasses this hook → no recursion) so WeChat
                    // forwards to the real member exactly as if that row had been tapped.
                    originalMethod()
                } finally {
                    folderField.set(folderId)
                }
            }.onFailure {
                WeLogger.e(TAG, "failed to forward folder tap to member $selectedWxId", it)
            }
        }
    }

    // Shows a picker scoped to a folder's members and invokes onMemberSelected with the chosen
    // member's wxid. Shared by both the SelectConversationUI and MvvmContactListUI interceptions.
    private fun showFolderMemberPicker(
        context: Context,
        folder: ChatFolder,
        onMemberSelected: (String) -> Unit
    ) {
        val members = getFolderMembers(folder).filterNot(::isFolderId).distinct()
        if (members.isEmpty()) {
            showToast("文件夹中没有对话")
            return
        }

        val membersSet = members.toHashSet()
        val contacts = runCatching {
            withQueryRewriteSuppressed {
                WeDatabaseApi.getContacts().filter { it.wxId in membersSet }
            }
        }.getOrDefault(emptyList())

        showComposeDialog(context) {
            FolderShareTargetSelector(
                contacts = contacts,
                onDismiss = onDismiss,
                onSelect = { selectedWxId ->
                    onDismiss()
                    onMemberSelected(selectedWxId)
                }
            )
        }
    }

    // A member picker for the "share to conversation" folder interception. Mirrors the
    // CustomLocalFriendAvatars pattern: no confirm button, each row carries a "选择" trailing
    // button that fires the forward immediately (onItemclick does the same for convenience).
    @Composable
    private fun FolderShareTargetSelector(
        contacts: List<IWeContact>,
        onDismiss: () -> Unit,
        onSelect: (String) -> Unit
    ) {
        var searchQuery by remember { mutableStateOf("") }
        val chinaCollator = remember { Collator.getInstance(Locale.CHINA) }

        val filteredContacts = remember(searchQuery, contacts, chinaCollator) {
            contacts.filter {
                it.displayName.contains(searchQuery, ignoreCase = true) ||
                        it.wxId.contains(searchQuery, ignoreCase = true)
            }.sortedWith(
                compareBy<IWeContact> { it.displayName.isBlank() }
                    .thenComparator { c1, c2 -> chinaCollator.compare(c1.displayName, c2.displayName) }
            )
        }

        BaseContactSelector(
            title = "选择文件夹里的转发对象",
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            filteredContacts = filteredContacts,
            confirmButtonText = "",
            confirmButtonEnabled = false,
            showConfirmButton = false,
            dismissButtonText = "取消",
            onDismiss = onDismiss,
            onConfirm = {},
            selectionKey = Unit,
            isSelected = { false },
            trailingControl = { contact ->
                TextButton(onClick = { onSelect(contact.wxId) }) { Text("选择") }
            },
            onItemClick = { contact -> onSelect(contact.wxId) }
        )
    }

    // Resolves the long-pressed conversation's username from the menu-create listener WeChat passes
    // into MMPopupMenu.showMenu. Chain: createListener -> its OnItemLongClickListener -> the
    // container fragment -> its list adapter -> adapter.getItem(position) (an rconversation row) ->
    // its field_username (kept unobfuscated by WeChat's auto-DB ORM).
    private fun extractFolderTalker(createListener: Any, position: Int): String? {
        val longClickListener = createListener.reflekt()
            .firstFieldOrNull { type { it isSubclassOf AdapterView.OnItemLongClickListener::class } }
            ?.get() ?: return null

        val fragment = longClickListener.reflekt()
            .firstFieldOrNull { type { it.name.endsWith("ConvBoxServiceConversationFmUI") } }
            ?.get() ?: return null

        val adapter = fragment.reflekt()
            .firstFieldOrNull { type { it isSubclassOf android.widget.Adapter::class } }
            ?.get() as? android.widget.Adapter ?: return null

        if (position < 0 || position >= adapter.count) return null
        val conversation = adapter.getItem(position) ?: return null

        return conversation.reflekt()
            .firstFieldOrNull { name = "field_username"; superclass() }
            ?.get() as? String
    }

    private fun hookSqliteWrapperQuery() {
        if (methodSqliteWrapperRawQuery.isPlaceholder) return
        methodSqliteWrapperRawQuery.hookBefore {
            if (suppressQueryRewrite.get()!!) return@hookBefore
            val sql = args.firstOrNull() as? String ?: return@hookBefore
            if (sql.contains("rconversation") && (sql.contains("update", true) || sql.contains("unread", true))) {
                WeLogger.d(TAG, "rawQuery: $sql")
            }
            onQuery(sql)?.let { args[0] = it }
        }
    }

    private fun hookConversationStorageParentQuery() {
        if (methodConversationStorageQueryByParent.isPlaceholder) return
        methodConversationStorageQueryByParent.hookBefore {
            val folderId = activeFolderId ?: return@hookBefore
            val parentRef = args.getOrNull(2) as? String ?: return@hookBefore
            if (parentRef == WeChatFolderPlaceholder.CONVERSATION_BOX ||
                parentRef == WeChatFolderPlaceholder.MESSAGE_FOLD
            ) {
                args[2] = folderId
            }
        }
    }

    // See methodConversationStorageUpdateUnreadByTalker: cancel the "mark box read on leave" that
    // WeChat's folder container fires against our folder id, so exiting a folder without opening any
    // member never clears the aggregate row's unread badge.
    private val methodMvvmConversationAdapterGetView by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.ConversationAdapter.MvvmConversationAdapter", "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d")
            }
            name = "getView"
        }
    }

    private val methodConversationWithCacheAdapterGetView by dexMethod(allowFailure = true, allowMultiple = true) {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            name = "getView"
            paramTypes("int", "android.view.View", "android.view.ViewGroup")
            returnType("android.view.View")
        }
    }

    /** tint the injected "someone @ me" prefix blue (matches FunBox) on both conversation adapters */
    private fun hookMentionTint() {
        WeLogger.i(TAG, "tint hooks: mvvmGetView placeholder=${methodMvvmConversationAdapterGetView.isPlaceholder}, cacheGetView placeholder=${methodConversationWithCacheAdapterGetView.isPlaceholder}, textViewSetText placeholder=${methodTextViewSetText.isPlaceholder}")
        diagFile("tint hooks: mvvm=${methodMvvmConversationAdapterGetView.isPlaceholder} cache=${methodConversationWithCacheAdapterGetView.isPlaceholder} setText=${methodTextViewSetText.isPlaceholder}")
        if (!methodMvvmConversationAdapterGetView.isPlaceholder) {
            methodMvvmConversationAdapterGetView.hookAfter {
                val root = result as? ViewGroup ?: return@hookAfter
                tintMentionLabels(root, "getView")
                markVirtualRow(root)
                tintFolderTitleByText(root)
                root.post {
                    tintMentionLabels(root, "getView-post")
                    markVirtualRow(root)
                    tintFolderTitleByText(root)
                }
            }
        }
        if (!methodConversationWithCacheAdapterGetView.isPlaceholder) {
            methodConversationWithCacheAdapterGetView.hookAfter {
                val root = result as? ViewGroup ?: return@hookAfter
                tintMentionLabels(root, "getView")
                markVirtualRow(root)
                tintFolderTitleByText(root)
                root.post {
                    tintMentionLabels(root, "getView-post")
                    markVirtualRow(root)
                    tintFolderTitleByText(root)
                }
            }
        }
        hookConversationListDraw()
        // FunBox 同款：hook RecyclerView.Adapter.bindViewHolder（framework 基类方法，微信 adapter 不 override，
        // 一定触发，覆盖微信主列表与归拢内部列表的 RecyclerView 行渲染）
        runCatching {
            val holderCls = Class.forName(
                "androidx.recyclerview.widget.RecyclerView\$ViewHolder",
                false, ClassLoaders.HOST
            )
            val adapterCls = Class.forName(
                "androidx.recyclerview.widget.RecyclerView\$Adapter",
                false, ClassLoaders.HOST
            )
            adapterCls.getMethod(
                "bindViewHolder", holderCls, Int::class.javaPrimitiveType, java.util.List::class.java
            ).hookAfterDirectly { tintHolder() }
            runCatching {
                adapterCls.getMethod(
                    "bindViewHolder", holderCls, Int::class.javaPrimitiveType
                ).hookAfterDirectly { tintHolder() }
                WeLogger.i(TAG, "bindViewHolder(vh,int) androidx hook registered")
                diagFile("bindViewHolder(vh,int) androidx registered")
            }.onFailure { WeLogger.w(TAG, "hook androidx bindViewHolder(2-arg) failed", it) }
            WeLogger.i(TAG, "bindViewHolder androidx hook registered")
            diagFile("bindViewHolder androidx registered")
        }.onFailure { WeLogger.w(TAG, "hook androidx bindViewHolder failed", it); diagFile("bindViewHolder androidx FAILED: $it") }
        runCatching {
            val holderCls = Class.forName(
                "android.support.v7.widget.RecyclerView\$ViewHolder",
                false, ClassLoaders.HOST
            )
            val adapterCls = Class.forName(
                "android.support.v7.widget.RecyclerView\$Adapter",
                false, ClassLoaders.HOST
            )
            adapterCls.getMethod(
                "bindViewHolder", holderCls, Int::class.javaPrimitiveType, java.util.List::class.java
            ).hookAfterDirectly { tintHolder() }
            WeLogger.i(TAG, "bindViewHolder support hook registered")
            diagFile("bindViewHolder support registered")
        }.onFailure { WeLogger.w(TAG, "hook support bindViewHolder failed", it); diagFile("bindViewHolder support FAILED: $it") }
    }


    private val hookedTextClasses = java.util.Collections.synchronizedSet(java.util.HashSet<Class<*>>())

    /** hook 微信具体 TextView 类的 setText(CharSequence) —— 微信 digest 是 override setText 的自定义类，
     *  不走基类；绑定到 Item 行内每个 TextView 的具体类，一次注册全部生效 */
    private fun hookTextViewClass(v: TextView) {
        val cls = v.javaClass
        if (!hookedTextClasses.add(cls)) return
        runCatching {
            cls.getMethod("setText", java.lang.CharSequence::class.java).hookAfterDirectly {
                val tv = thisObject as? TextView ?: return@hookAfterDirectly
                val s = tv.text?.toString().orEmpty()
                val tinted = tintMention(s, tv.context)
                if (tinted != null) setTextSpanDirect(tv, tinted)
            }
        }.onFailure { diagFile("clsSetText FAILED ${cls.name}: $it") }
        runCatching {
            val bufType = Class.forName("android.widget.TextView\$BufferType", false, ClassLoaders.HOST)
            cls.getMethod("setText", java.lang.CharSequence::class.java, bufType).hookAfterDirectly {
                val tv = thisObject as? TextView ?: return@hookAfterDirectly
                val s = tv.text?.toString().orEmpty()
                val tinted = tintMention(s, tv.context)
                if (tinted != null) {
                    setTextSpanDirect(tv, tinted)
                }
            }
            diagFile("clsSetText(CS,BT) hooked: ${cls.name}")
        }.onFailure { diagFile("clsSetText(CS,BT) FAILED ${cls.name}: $it") }
    }

    /** 反射直写 TextView 私有 mText/mTransformedText，绕过所有 setText 重载（方案 B） */
    private fun setTextSpanDirect(tv: TextView, spannable: CharSequence) {
        runCatching {
            val f = android.widget.TextView::class.java.getDeclaredField("mText")
            f.isAccessible = true
            f.set(tv, spannable)
            val tf = android.widget.TextView::class.java.getDeclaredField("mTransformedText")
            tf.isAccessible = true
            tf.set(tv, spannable)
            tv.invalidate()
            tv.requestLayout()
        }.onFailure { WeLogger.w(TAG, "setTextSpanDirect failed", it) }
    }

    /**
     * 归拢摘要彩色（FunBox 叠加模式）：hook NoMeasuredTextView.onDraw **after**，
     * 不拦截原生绘制（灰色原文照常输出），在原绘制完成后用同一 Canvas 叠加彩色标签
     * （蓝 [有人@我]/[全体]、黄 [N个聊天]），彩色文字盖在灰色文字上方。
     * 优点：不依赖 Item 根类 / adapter 渲染路径 / Tag 向上遍历，摘要文本出现即染色。
     */
    /**
     * 归拢摘要彩色（FunBox 叠加模式）：全局 hook TextView.onDraw **after**。
     * 已证实微信主列表摘要不经过 NoMeasuredTextView（其 onDraw 从不触发），改为捕获所有
     * TextView 子类绘制：文本含归拢标记（[有人@我]/[全体]/[N个聊天]）即用控件本地坐标
     * 画布叠加彩色标签（原生灰色保留）。同时全量输出绘制诊断以定位真实摘要控件类名。
     */
    private fun hookAllTextViewDraw() {
        // 归拢摘要染色（微信主列表）：摘要控件 = NoMeasuredTextView（extends X2CView，非 TextView，
        // getText() 为空）。归拢摘要经 setText(CharSequence) 注入；hookBefore 直接替换为 Spannable
        // 上色（蓝 [有人@我]/[全体]、黄 [N个聊天]），微信自绘渲染 span 颜色即上色。
        // 已实测：系统 Framework 类 hook（View.draw/onAttachedToWindow）对微信无效，不再使用。
        runCatching {
            val nmtCls = Class.forName("com.tencent.mm.ui.base.NoMeasuredTextView")
            nmtCls.declaredMethods.filter { it.name == "setText" }.forEach { m ->
                m.apply { isAccessible = true }.hookBeforeDirectly {
                    val text = args.getOrNull(0)?.toString() ?: return@hookBeforeDirectly
                    if (folderTitleNames().contains(text.trim())) {
                        if (folderTitleEnabled) {
                        args[0] = android.text.SpannableString(text).apply {
                            setSpan(android.text.style.ForegroundColorSpan(MENTION_TITLE_BLUE), 0, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        }
                        return@hookBeforeDirectly
                    }
                    if (isAggSummary(text)) {
                        args[0] = tintAggSummary(text, (thisObject as? View)?.context)
                    }
                    if (CHAT_COUNT_REGEX.containsMatchIn(text)) {
                        tintFolderTitle(thisObject as? View, text)
                    }
                    if (CHAT_COUNT_REGEX.containsMatchIn(text)) {
                        tintFolderTitle(thisObject as? View, text)
                    }
                }
            }
            diagFile("NMTV tint hook installed")
            WeLogger.i(TAG, "NoMeasuredTextView.setText tint hook installed")
        }.onFailure {
            diagFile("NMTV tint hook FAILED: $it")
            WeLogger.e(TAG, "NoMeasuredTextView.setText tint hook failed", it)
        }
    }

    private fun tintFolderTitleByText(root: ViewGroup) {
        runCatching {
            if (!folderTitleEnabled) {
                val orig = root.getTag(TAG_KEY_TITLE_ORIG) as? Int
                val title = root.getTag(TAG_KEY_TITLE_TV) as? TextView
                if (orig != null && title != null && title.currentTextColor != orig) title.setTextColor(orig)
                return
            }
            val queue = java.util.ArrayDeque<View>()
            queue.add(root)
            var guard = 0
            while (queue.isNotEmpty() && guard++ < 200) {
                val v = queue.removeFirst()
                if (v is TextView) {
                    val t = v.text?.toString()?.trim().orEmpty()
                    if (t.isNotEmpty() && folderTitleNames().contains(t) && v.currentTextColor != adaptNight(root.context, MENTION_TITLE_BLUE)) {
                        v.setTextColor(adaptNight(root.context, MENTION_TITLE_BLUE))
                    }
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) queue.addLast(v.getChildAt(i))
            }
            diagFile("ConversationList dispatchDraw hooked")
            WeLogger.i(TAG, "ConversationList dispatchDraw hooked")
        }.onFailure {
            diagFile("ConversationList dispatchDraw FAILED: $it")
            WeLogger.w(TAG, "ConversationList dispatchDraw hook fail", it)
        }
    }
    /** Hook WeChat conversation list dispatchDraw: tint folder-title rows every frame (idempotent) */
    private fun hookConversationListDraw() {
        runCatching {
            val cls = Class.forName("com.tencent.mm.ui.conversation.ConversationListView")
            cls.getMethod("dispatchDraw", android.graphics.Canvas::class.java).hookAfterDirectly {
                val list = thisObject as? ViewGroup ?: return@hookAfterDirectly
                runCatching {
                    for (i in 0 until list.childCount) {
                        val row = list.getChildAt(i)
                        if (row is ViewGroup) tintFolderTitleByText(row)
                    }
                }
            }
            diagFile("ConversationList dispatchDraw hooked")
            WeLogger.i(TAG, "ConversationList dispatchDraw hooked")
        }.onFailure {
            diagFile("ConversationList dispatchDraw FAILED: $it")
            WeLogger.w(TAG, "ConversationList dispatchDraw hook fail", it)
        }
    }

    /** Find title TextView in a row (largest textSize, not aggregate summary, contains letters) */
    private fun findTitleTextView(root: ViewGroup): TextView? {
        var title: TextView? = null
        val queue = java.util.ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v is TextView) {
                val t = v.text?.toString().orEmpty()
                if (!isAggSummary(t) && t.any { it.isLetter() } && (title == null || v.textSize > title.textSize)) {
                    title = v
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) queue.addLast(v.getChildAt(i))
        }
        return title
    }

    /** Fallback for cache-adapter rows: tint the row title with folder-title color */
    private fun tintFolderTitle(summaryView: View?, text: String) {
        if (summaryView == null) { diagFile("tintFolderTitle: summaryView null"); return }
        if (!CHAT_COUNT_REGEX.containsMatchIn(text)) return
        diagFile("tintFolderTitle: start sv=" + summaryView.javaClass.simpleName)
        runCatching {
            var v: View? = summaryView
            var guard = 0
            var firstGroup: ViewGroup? = null
            while (v != null && guard++ < 8) {
                v = v.parent as? View ?: break
                diagFile("tintFolderTitle: parent#" + guard + " " + v.javaClass.simpleName + " vg=" + (v is ViewGroup))
                if (v is ViewGroup) {
                    if (firstGroup == null) firstGroup = v
                    val title = findTitleTextView(v)
                    diagFile("tintFolderTitle: title=" + (title?.text?.toString()?.take(15) ?: "NULL"))
                    if (title == null) continue
                    val t = title.text?.toString() ?: continue
                    if (!folderTitleNames().contains(t)) { diagFile("tintFolderTitle: skip not-whitelist: " + t.take(15)); continue }
                    title.setTextColor(MENTION_TITLE_BLUE)
                    if (folderTitleEnabled) title.setTextColor(adaptNight(summaryView.context, MENTION_TITLE_BLUE))
                    diagFile("tintFolderTitle: tinted " + t.take(20) + " color=" + Integer.toHexString(MENTION_TITLE_BLUE))
                    return@runCatching
                }
            }
        }
    }
    @Volatile
    private var folderNameCache: Set<String>? = null
    @Volatile
    private var lastFolderNameLoad = 0L

    /** Folder-name whitelist (cached 5s) so native WeChat fold rows like "fold top chats" are never tinted */
    private fun folderTitleNames(): Set<String> {
        val now = System.currentTimeMillis()
        val cached = folderNameCache
        if (cached != null && now - lastFolderNameLoad < 5000) return cached
        val names = runCatching { loadFolders().map { it.name }.toSet() }.getOrDefault(emptySet())
        diagFile("folderNames: " + names.sorted().joinToString(","))
        folderNameCache = names
        lastFolderNameLoad = now
        return names
    }

    /** 归拢摘要标记判断 */
    private fun isAggSummary(text: String): Boolean =
        text.contains("[有人@我]") || text.contains("[全体]") || text.contains("[@全体]") || text.contains("[自己]") || CHAT_COUNT_REGEX.containsMatchIn(text)

    /** 归拢摘要 Spannable 上色：蓝 [有人@我]/[全体]、黄 [N个聊天]（其余保持微信原生颜色） */
    private fun tintAggSummary(text: String, ctx: Context?): CharSequence {
        val sp = SpannableString(text)
        val atIdx = text.indexOf("[有人@我]")
        if (atIdx >= 0) sp.setSpan(ForegroundColorSpan(adaptNight(ctx, MENTION_RED)), atIdx, atIdx + "[有人@我]".length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val allIdx = text.indexOf("[@全体]").let { if (it >= 0) it else text.indexOf("[全体]") }
        if (allIdx >= 0) sp.setSpan(ForegroundColorSpan(adaptNight(ctx, MENTION_RED)), allIdx, text.indexOf(']', allIdx) + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val selfIdx = text.indexOf("[自己]")
        if (selfIdx >= 0 && mentionSelfEnabled) sp.setSpan(ForegroundColorSpan(adaptNight(ctx, MENTION_GREEN)), selfIdx, selfIdx + "[自己]".length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val m = CHAT_COUNT_REGEX.find(text)
        if (m != null) sp.setSpan(ForegroundColorSpan(adaptNight(ctx, MENTION_YELLOW)), m.range.first, m.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val member = MEMBER_PAREN_REGEX.find(text)
        if (member != null && mentionMemberEnabled) sp.setSpan(ForegroundColorSpan(adaptNight(ctx, MENTION_MEMBER)), member.range.first, member.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sp
    }

    /** 反射 dump 实例字段（含父类 4 层），定位 paint/baseline 字段 */
    private fun dumpFields(obj: Any) {
        runCatching {
            val sb = StringBuilder()
            var cls: Class<*>? = obj.javaClass
            var guard = 0
            while (cls != null && guard++ < 4) {
                cls.declaredFields.forEach { f ->
                    runCatching {
                        f.isAccessible = true
                        val v = f.get(obj)
                        val vs = v?.toString()?.take(40) ?: "null"
                        sb.append(f.name).append(':').append(f.type.simpleName).append('=').append(vs).append("; ")
                    }
                }
                cls = cls.superclass
            }
            diagFile("NMTV_FIELDS[${obj.javaClass.simpleName}] $sb")
        }
    }


    /** 归拢摘要分段着色：[@] 蓝、[N个聊天] 黄、其余恢复微信原生颜色，尾部超宽省略 */
    private fun drawTintedSummary(tv: TextView, canvas: Canvas, text: String) {
        runCatching {
            val paint = TextPaint(tv.paint)
            val originalColor = tv.currentTextColor
            val baseX = tv.paddingLeft.toFloat()
            val baseY = tv.baseline.toFloat()
            val maxWidth = tv.width - tv.paddingLeft - tv.paddingRight
            val red = adaptNight(tv.context, MENTION_RED)
            val yellow = adaptNight(tv.context, MENTION_YELLOW)
            val green = adaptNight(tv.context, MENTION_GREEN)
            val memberColor = adaptNight(tv.context, MENTION_MEMBER)

            data class Seg(val start: Int, val end: Int, val color: Int)
            val segs = mutableListOf<Seg>()
            val atIdx = text.indexOf("[有人@我]")
            if (atIdx >= 0) segs.add(Seg(atIdx, atIdx + "[有人@我]".length, red))
            val allIdx = text.indexOf("[@全体]").let { if (it >= 0) it else text.indexOf("[全体]") }
            if (allIdx >= 0) segs.add(Seg(allIdx, text.indexOf(']', allIdx) + 1, red))
            val selfIdx = text.indexOf("[自己]")
            if (selfIdx >= 0 && mentionSelfEnabled) segs.add(Seg(selfIdx, selfIdx + "[自己]".length, green))
            val m = Regex("""\[[^\]]*个(?:聊天|消息)\]""").find(text)
            if (m != null) segs.add(Seg(m.range.first, m.range.last + 1, yellow))
            segs.sortBy { it.start }
            val member = MEMBER_PAREN_REGEX.find(text)
            if (member != null && mentionMemberEnabled) segs.add(Seg(member.range.first, member.range.last + 1, memberColor))

            var x = baseX
            var cur = 0
            for (seg in segs) {
                if (seg.start > cur) {
                    // 段间未着色文本：原色
                    paint.color = originalColor
                    val s = text.substring(cur, seg.start)
                    canvas.drawText(s, x, baseY, paint)
                    x += paint.measureText(s)
                }
                paint.color = seg.color
                val s = text.substring(seg.start, seg.end)
                canvas.drawText(s, x, baseY, paint)
                x += paint.measureText(s)
                cur = seg.end
            }
            if (cur < text.length) {
                val rest = text.substring(cur)
                val remain = maxWidth - (x - baseX)
                val shown = if (remain > 0) {
                    TextUtils.ellipsize(rest, paint, remain, TextUtils.TruncateAt.END).toString()
                } else ""
                if (shown.isNotEmpty()) {
                    paint.color = originalColor
                    canvas.drawText(shown, x, baseY, paint)
                }
            }
        }
    }

    private fun diagFile(msg: String) {
        // 仅调试构建落盘：release 下这是纯 IO 开销（会话列表每绑定一项都会走到这里）。
        if (!com.Johnny.wcx.BuildConfig.DEBUG) return
        runCatching {
            val f = java.io.File("/sdcard/Android/data/com.tencent.mm/WCX/diag.log")
            f.parentFile?.mkdirs()
            f.appendText(System.currentTimeMillis().toString() + " " + msg + "\n")
        }
    }

    // ==================== FunBox 同款：归拢摘要叠加染色（Item 根 View dispatchDraw after） ====================
    // 方案：不拦截 NoMeasuredTextView 原生绘制（灰色原文照常输出），
    // 在 Item 根 View dispatchDraw 之后用 Canvas 叠加彩色标签（蓝 [有人@我]/[全体]、黄 [N个聊天]），
    // 彩色文字盖在灰色文字上方。bind 阶段（getView）给 Item 根 setTag，无需向上遍历父布局。

    /** Item 根 View 的 Tag key：标记归拢虚拟行 */
    private const val TAG_KEY_VIRTUAL = 0x5A110001
    /** Item 根 View 的 Tag key：归拢摘要着色状态 */
    private const val TAG_KEY_STATE = 0x5A110002
    /** Item 根 View 的 Tag key：内部摘要 NoMeasuredTextView 引用 */
    private const val TAG_KEY_SUMMARY_TV = 0x5A110003
    /** Item 根 View 的 Tag key：内部标题 TextView 引用（文件夹标题染蓝） */
    private const val TAG_KEY_TITLE_TV = 0x5A110004
    private const val TAG_KEY_TITLE_ORIG = 0x5A110005

    /** 归拢摘要着色状态（bind 阶段解析，onDraw 阶段直接读取） */
    private class MergeUiState(
        val atAll: Boolean,
        val atMe: Boolean,
        val self: Boolean,
        val chatCount: Int,
        val memberName: String?,
        val fullText: String
    )

    /** 已 hook dispatchDraw 的 Item 根类（去重） */
    private val hookedItemDrawClasses = java.util.Collections.synchronizedSet(java.util.HashSet<Class<*>>())

    /**
     * bind 阶段（getView 后）识别归拢虚拟行：找内部 NoMeasuredTextView 摘要控件，
     * 文本含 [有人@我]/[全体]/[N个聊天] 归拢标记即标记该行为虚拟行并记录着色状态。
     * RecyclerView 复用旧 item 时先清 tag（未命中即普通会话）。
     */
    private fun markVirtualRow(root: ViewGroup) {
        runCatching {
            val queue = java.util.ArrayDeque<View>()
            queue.add(root)
            var summaryTv: TextView? = null
            var titleTv: TextView? = null
            var fullText = ""
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()
                if (v is TextView) {
                    val t = v.text?.toString().orEmpty()
                    // 标题：非摘要、含文字（排除未读数角标等纯数字/时间控件）、字号最大的 TextView
                    if (!isAggSummary(t) && t.any { it.isLetter() } && (titleTv == null || v.textSize > titleTv.textSize)) {
                        titleTv = v
                        WeLogger.i(TAG, "titleTv candidate: class=${v.javaClass.simpleName} textSize=${v.textSize} text=${t.take(20)}")
                    }
                    // 归拢摘要标记命中即识别（摘要控件不限于 NoMeasuredTextView）
                    if (isAggSummary(t) && summaryTv == null) {
                        summaryTv = v
                        fullText = t
                    }
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) queue.addLast(v.getChildAt(i))
            }
            if (summaryTv == null && fullText.isEmpty()) {
                root.setTag(TAG_KEY_VIRTUAL, null)
                root.setTag(TAG_KEY_STATE, null)
                root.setTag(TAG_KEY_SUMMARY_TV, null)
                root.setTag(TAG_KEY_TITLE_TV, null)
                return
            }
            val atAll = fullText.contains("[全体]") || fullText.contains("[@全体]")
            val atMe = fullText.contains("[有人@我]")
            val chatCount = runCatching {
                CHAT_COUNT_REGEX.find(fullText)?.value
                    ?.trim('[', ']')?.replace("个聊天", "")?.replace("个消息", "")?.toIntOrNull() ?: 0
            }.getOrDefault(0)
            root.setTag(TAG_KEY_VIRTUAL, true)
            root.setTag(TAG_KEY_STATE, MergeUiState(atAll, atMe, fullText.contains("[自己]"), chatCount, MEMBER_PAREN_REGEX.find(fullText)?.value, fullText))
            root.setTag(TAG_KEY_SUMMARY_TV, summaryTv)
            root.setTag(TAG_KEY_TITLE_TV, titleTv)
            root.setTag(TAG_KEY_TITLE_ORIG, titleTv?.currentTextColor)
            ensureItemDispatchDrawHook(root.javaClass)
        }
    }

    /** 首次遇到某 Item 根类时 hook 其 dispatchDraw(after)：子 View（含灰色摘要）画完后叠加彩色 */
    private fun ensureItemDispatchDrawHook(cls: Class<*>) {
        if (!hookedItemDrawClasses.add(cls)) return
        runCatching {
            cls.getMethod("dispatchDraw", android.graphics.Canvas::class.java).hookAfterDirectly {
                val list = thisObject as? ViewGroup ?: return@hookAfterDirectly
                runCatching {
                    for (i in 0 until list.childCount) {
                        val row = list.getChildAt(i)
                        if (row is ViewGroup) tintFolderTitleByText(row)
                    }
                }
            }
            diagFile("ItemDispatchDraw hook: ${cls.name}")
            WeLogger.i(TAG, "ItemDispatchDraw hook: ${cls.name}")
        }.onFailure {
            diagFile("ItemDispatchDraw hook FAIL ${cls.name}: $it")
            WeLogger.w(TAG, "ItemDispatchDraw hook fail", it)
        }
    }



    private fun XC_MethodHook.MethodHookParam.tintHolder() {
        val holder = args?.getOrNull(0) ?: return
        val itemView = runCatching {
            holder.javaClass.getMethod("getItemView").invoke(holder) as? View
        }.getOrNull() ?: return
        val root = itemView as? ViewGroup ?: return
        tintMentionLabels(root, "bind")
        markVirtualRow(root)
        root.post {
            tintMentionLabels(root, "post")
            markVirtualRow(root)
        }
    }

    private fun tintMention(text: String, ctx: Context?): CharSequence? {
        val atIdx = text.indexOf("[\u6709\u4eba@\u6211]")
        val selfIdx = text.indexOf("[自己]")
        val chatMatch = CHAT_COUNT_REGEX.find(text)
        if (atIdx < 0 && chatMatch == null && selfIdx < 0) return null
        val spannable = SpannableString(text)
        if (atIdx >= 0) {
            spannable.setSpan(
                ForegroundColorSpan(MENTION_RED),
                atIdx,
                (atIdx + 6).coerceAtMost(text.length),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (selfIdx >= 0 && mentionSelfEnabled) {
            spannable.setSpan(
                ForegroundColorSpan(MENTION_GREEN),
                selfIdx,
                selfIdx + "[自己]".length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (chatMatch != null) {
            spannable.setSpan(
                ForegroundColorSpan(MENTION_YELLOW),
                chatMatch.range.first,
                chatMatch.range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    private fun tintMentionLabels(root: ViewGroup, tag: String) {
        var tvCount = 0
        var hit = 0
        val queue = java.util.ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v is TextView) {
                tvCount++
                hookTextViewClass(v)
                val text = v.text?.toString().orEmpty()
                val tinted = tintMention(text, root.context)
                if (tinted != null) {
                    hit++
                    v.setText(tinted)
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) queue.addLast(v.getChildAt(i))
            }
        }
        if (hit > 0) WeLogger.i(TAG, "tint[$tag] done tv=$tvCount hit=$hit")
    }

    private fun hookSqliteExec() {
        runCatching {
            android.database.sqlite.SQLiteDatabase::class.java
                .getMethod("execSQL", String::class.java)
                .hookBeforeDirectly {
                    val sql = args?.getOrNull(0) as? String ?: return@hookBeforeDirectly
                    val low = sql.lowercase()
                    if (low.contains("rconversation") && (low.contains("update") || low.contains("unread"))) {
                        WeLogger.d(TAG, "execSQL: $sql")
                    }
                }
            WeLogger.i(TAG, "execSQL hook registered")
            diagFile("execSQL hook registered")
        }.onFailure { WeLogger.w(TAG, "hook execSQL failed", it) }
    }

    private val methodRecyclerOnBind by dexMethod(allowFailure = true, allowMultiple = true) {
        matcher {
            name = "onBindViewHolder"
            paramTypes("androidx.recyclerview.widget.RecyclerView\$ViewHolder", "int")
        }
    }

    private val methodSupportRecyclerOnBind by dexMethod(allowFailure = true, allowMultiple = true) {
        matcher {
            name = "onBindViewHolder"
            paramTypes("android.support.v7.widget.RecyclerView\$ViewHolder", "int")
        }
    }

    private val methodTextViewSetText by dexMethod(allowFailure = true, allowMultiple = true) {
        matcher {
            name = "setText"
            paramTypes("java.lang.CharSequence")
        }
    }

    /** Global fallback: tint injected mention/chat-count text wherever a TextView renders it. */
    private fun hookTextViewSetText() {
        if (methodTextViewSetText.isPlaceholder) return
        methodTextViewSetText.hookBefore {
            val a = args ?: return@hookBefore
            val text = a.getOrNull(0) as? CharSequence ?: return@hookBefore
            val s0 = text.toString()
            if (folderTitleNames().contains(s0)) {
                if (folderTitleEnabled) {
                a[0] = android.text.SpannableString(s0).apply { setSpan(android.text.style.ForegroundColorSpan(MENTION_TITLE_BLUE), 0, s0.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                }
                return@hookBefore
            }
            val tinted = tintMention(s0, (thisObject as? View)?.context)
            if (tinted != null) {
                a[0] = tinted
            }
        }

        // 兜底：hook 基类 TextView.setText，覆盖不 override 的 TextView 子类
        runCatching {
            val baseSetText = android.widget.TextView::class.java
                .getMethod("setText", java.lang.CharSequence::class.java)
            baseSetText.hookBeforeDirectly {
                val a = args ?: return@hookBeforeDirectly
                val text = a.getOrNull(0) as? CharSequence ?: return@hookBeforeDirectly
                val tinted = tintMention(text.toString(), (thisObject as? View)?.context)
                if (tinted != null) {
                    a[0] = tinted
                }
            }
        }.onFailure { WeLogger.w(TAG, "hook TextView.setText failed", it) }

    }

    private fun hookConversationStorageUpdateUnread() {
        if (methodConversationStorageUpdateUnreadByTalker.isPlaceholder) return
        methodConversationStorageUpdateUnreadByTalker.hookBefore {
            val username = args.firstOrNull() as? String ?: return@hookBefore
            WeLogger.i(TAG, "updateUnreadByTalker: $username folder=${isFolderId(username)}")
            diagFile("updateUnreadByTalker: $username folder=${isFolderId(username)}")
            if (isFolderId(username)) result = true
        }
    }

    private fun launchFolderContainer(source: Any?, folderId: String) {
        val context = source as? Context ?: return
        val intent = Intent().apply {
            setClassName(context, CONTAINER_UI_NAME)
            applyFolderContainerIntent(this, folderId)
        }
        context.startActivity(intent)
    }

    private fun applyFolderContainerIntent(intent: Intent, folderId: String) {
        intent.putExtra(WeChatIntentExtra.CONTACT_USER, folderId)
        intent.putExtra(WeChatIntentExtra.CONTACT_CHAT_ROOM_ID, folderId)
        intent.putExtra(WeChatIntentExtra.ROOM_NAME, folderId)
    }

    private fun configureFolderActivity(activity: BaseConversationUI) {
        val folder = folderById(activeFolderId ?: return) ?: return
        activity.setTitle(folder.name)

        val fragment = activity.conversationFm

        // onResume may fire repeatedly; drop any previous entry before re-adding
        fragment.removeOptionMenu(FOLDER_CONFIG_MENU_ID)

        val listener = MenuItem.OnMenuItemClickListener {
            showEditFolderDialog(
                context = activity,
                folder = folder,
                onFolderUpdated = {
                    syncFoldersToDatabase()
                    configureFolderActivity(activity)
                },
                onFolderDeleted = {
                    syncFoldersToDatabase()
                    activity.finish()
                }
            )
            true
        }

        fragment.addIconOptionMenu(FOLDER_CONFIG_MENU_ID, "配置", EditIcon, listener)
    }

    private fun syncFoldersToDatabase() {
        val handler = refreshHandler ?: return
        handler.removeCallbacksAndMessages(RECONCILE_TASK_TOKEN)
        handler.postAtTime(
            { reconcileFolders(loadFolders()) },
            RECONCILE_TASK_TOKEN,
            SystemClock.uptimeMillis()
        )
    }

    private fun reconcileFolders(folders: List<ChatFolder>) {
        if (!WeDatabaseApi.isReady) return
        val startedAt = SystemClock.elapsedRealtime()
        var databaseChanged = false
        runCatching {
            withQueryRewriteSuppressed {
                if (!isFolderSchemaReady()) return@withQueryRewriteSuppressed
                folderMembersCache.clear()
                val desiredMembers = resolveOwnedMembers(folders)
                val desiredOwners = reverseMemberIndex(desiredMembers)
                val currentOwners = readCurrentMemberOwners()
                val storedRows = readStoredFolderRows()
                val liveFlags = storedRows.mapValues { it.value.flag }
                persistChangedPinFlags(folders, liveFlags)

                val desiredFolderIds = folders.mapTo(linkedSetOf()) { it.id }
                val storedFolderIds = readStoredFolderIds()
                val removedFolderIds = storedFolderIds - desiredFolderIds
                val changedOwnerMembers = currentOwners.filter { (member, owner) ->
                    desiredOwners[member] != owner
                }.keys
                val removedMembers = changedOwnerMembers.filterTo(linkedSetOf()) { it !in desiredOwners }
                val changedBindings = desiredOwners.filter { (member, owner) ->
                    currentOwners[member] != owner
                }
                val existingContacts = readFolderContactNames(desiredFolderIds)
                val existingAvatarRows = readExistingAvatarRows(desiredFolderIds)
                val summaries = readFolderSummaries(desiredMembers, storedRows)
                val changedSummaries = folders.mapNotNull { folder ->
                    val summary = summaries[folder.id] ?: FolderSummary()
                    val stored = storedRows[folder.id]
                    if (stored == null ||
                        stored.summary != summary ||
                        stored.attrFlag != summary.attrFlag ||
                        stored.flag and FLAG_TIME_MASK != summary.conversationTime and FLAG_TIME_MASK
                    ) {
                        folder.id to summary
                    } else {
                        null
                    }
                }
                databaseChanged = changedBindings.isNotEmpty() || changedSummaries.isNotEmpty() ||
                        removedMembers.isNotEmpty() || removedFolderIds.isNotEmpty() ||
                        folders.any { it.id !in storedRows || existingContacts[it.id] != it.name } ||
                        desiredFolderIds.any { it !in existingAvatarRows }

                if (databaseChanged) {
                    WeDatabaseApi.transaction {
                        deleteEmptyPlaceholderRows(removedMembers)
                        unbindMembers(removedMembers)
                        ensureManualMemberRows(folders, changedBindings.keys)
                        bindMembers(changedBindings)
                        deleteStoredFolders(removedFolderIds)

                        folders.forEach { folder ->
                            if (folder.id !in storedRows) {
                                ensureFolderConversationRow(folder)
                            }
                            if (existingContacts[folder.id] != folder.name) {
                                writeFolderContact(folder)
                            }
                            if (folder.id !in existingAvatarRows) {
                                writeFolderAvatar(folder.id)
                            }
                        }

                        changedSummaries.forEach { (folderId, summary) ->
                            writeFolderSummaryRow(folderId, summary)
                        }
                    }
                }

                membersByFolder = desiredMembers
                folderByMember = desiredOwners
                desiredMembers.forEach { (folderId, members) ->
                    folderMembersCache[folderId] = members
                }

                WeLogger.i(
                    TAG,
                    "reconciled ${folders.size} folders: bindings=${changedBindings.size}, " +
                    "unbound=${removedMembers.size}, removed=${removedFolderIds.size}, " +
                            "elapsed=${SystemClock.elapsedRealtime() - startedAt}ms"
                )
            }
            if (databaseChanged) WeConversationApi.reloadConversations()
        }.onFailure {
            WeLogger.e(TAG, "failed to sync folders", it)
        }
    }

    private fun resolveOwnedMembers(folders: List<ChatFolder>): Map<String, List<String>> {
        val candidates = linkedMapOf<String, List<String>>()
        val ownerByMember = linkedMapOf<String, String>()
        folders.forEach { folder ->
            val members = resolveFolderMembers(folder).filterNot(::isFolderId).distinct()
            candidates[folder.id] = members
            members.forEach { ownerByMember[it] = folder.id }
        }
        return candidates.mapValues { (folderId, members) ->
            members.filter { ownerByMember[it] == folderId }
        }
    }

    private fun reverseMemberIndex(byFolder: Map<String, List<String>>): Map<String, String> =
        buildMap { byFolder.forEach { (folderId, members) -> members.forEach { put(it, folderId) } } }

    private fun readCurrentMemberOwners(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        WeDatabaseApi.rawQuery(
            "SELECT ${ConversationTable.USERNAME}, ${ConversationTable.PARENT_REF} " +
                    "FROM ${ConversationTable.NAME} WHERE ${ConversationTable.PARENT_REF} LIKE ?",
            arrayOf("$FOLDER_PREFIX%")
        ).use { cursor ->
            while (cursor.moveToNext()) result[cursor.getString(0)] = cursor.getString(1)
        }
        return result
    }

    private fun readStoredFolderRows(): Map<String, StoredFolderRow> {
        val result = linkedMapOf<String, StoredFolderRow>()
        WeDatabaseApi.rawQuery(
            """
            SELECT ${ConversationTable.USERNAME}, ${ConversationTable.FLAG}, ${ConversationTable.DIGEST},
                   ${ConversationTable.DIGEST_USER}, ${ConversationTable.IS_SEND}, ${ConversationTable.STATUS},
                   ${ConversationTable.CONVERSATION_TIME}, ${ConversationTable.UNREAD_COUNT},
                   ${ConversationTable.UNREAD_MUTE_COUNT}, ${ConversationTable.CONTENT},
                   ${ConversationTable.MSG_TYPE}, ${ConversationTable.CHAT_MODE}, ${ConversationTable.ATTR_FLAG}, ${ConversationTable.AT_COUNT}
            """.trimIndent() + " " +
                    "FROM ${ConversationTable.NAME} WHERE ${ConversationTable.USERNAME} LIKE ?",
            arrayOf("$FOLDER_PREFIX%")
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getString(0)] = StoredFolderRow(
                    flag = cursor.getLongOrZero(ConversationTable.FLAG),
                    attrFlag = cursor.getIntOrZero(ConversationTable.ATTR_FLAG),
                    summary = FolderSummary(
                        digest = cursor.getStringOrEmpty(ConversationTable.DIGEST),
                        digestUser = cursor.getStringOrEmpty(ConversationTable.DIGEST_USER),
                        isSend = cursor.getIntOrZero(ConversationTable.IS_SEND),
                        status = cursor.getIntOrZero(ConversationTable.STATUS),
                        conversationTime = cursor.getLongOrZero(ConversationTable.CONVERSATION_TIME),
                        unreadCount = cursor.getIntOrZero(ConversationTable.UNREAD_COUNT),
                        unreadMuteCount = cursor.getIntOrZero(ConversationTable.UNREAD_MUTE_COUNT),
                        content = cursor.getStringOrEmpty(ConversationTable.CONTENT),
                        msgType = cursor.getStringOrEmpty(ConversationTable.MSG_TYPE),
                        chatMode = cursor.getIntOrZero(ConversationTable.CHAT_MODE),
                        atMeCount = cursor.getIntOrZero(ConversationTable.AT_COUNT)
                    )
                )
            }
        }
        return result
    }

    private fun readStoredFolderIds(): Set<String> {
        val result = linkedSetOf<String>()
        listOf(ConversationTable.NAME, ContactTable.NAME, "img_flag").forEach { table ->
            WeDatabaseApi.rawQuery(
                "SELECT username FROM $table WHERE username LIKE ?",
                arrayOf("$FOLDER_PREFIX%")
            ).use { cursor -> while (cursor.moveToNext()) result += cursor.getString(0) }
        }
        return result
    }

    private fun readFolderContactNames(folderIds: Set<String>): Map<String, String> {
        if (folderIds.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, String>()
        folderIds.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { ids ->
            WeDatabaseApi.rawQuery(
                "SELECT ${ContactTable.USERNAME}, ${ContactTable.NICKNAME} FROM ${ContactTable.NAME} " +
                        "WHERE ${ContactTable.USERNAME} IN (${placeholders(ids.size)})",
                ids.toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) result[cursor.getString(0)] = cursor.getString(1) ?: ""
            }
        }
        return result
    }

    private fun readExistingAvatarRows(folderIds: Set<String>): Set<String> {
        if (folderIds.isEmpty()) return emptySet()
        val result = linkedSetOf<String>()
        folderIds.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { ids ->
            WeDatabaseApi.rawQuery(
                "SELECT username FROM img_flag WHERE username IN (${placeholders(ids.size)})",
                ids.toTypedArray()
            ).use { cursor -> while (cursor.moveToNext()) result += cursor.getString(0) }
        }
        return result
    }

    private fun persistChangedPinFlags(folders: List<ChatFolder>, liveFlags: Map<String, Long>) {
        var changed = false
        val updated = folders.map { folder ->
            val liveHigh = liveFlags[folder.id]?.and(FLAG_HIGH_MASK) ?: return@map folder
            if (liveHigh == folder.pinFlag) return@map folder
            changed = true
            folder.copy(pinFlag = liveHigh)
        }
        if (changed) saveFolders(updated)
    }

    private fun clearStaleFolderMappings() {
        listOf(FOLDER_PREFIX).forEach { prefix ->
            WeDatabaseApi.execStatement(
                """
                DELETE FROM ${ConversationTable.NAME}
                WHERE ${ConversationTable.PARENT_REF} LIKE ?
                  AND ${ConversationTable.DIGEST}=''
                  AND ${ConversationTable.CONTENT}=''
                  AND ${ConversationTable.UNREAD_COUNT}=0
                  AND ${ConversationTable.CONVERSATION_TIME}=0
                  AND ${ConversationTable.FLAG}=0
                  AND ${ConversationTable.MSG_TYPE}=''
                  AND ${ConversationTable.STATUS}=0
                  AND ${ConversationTable.IS_SEND}=0
                """.trimIndent(),
                arrayOf("$prefix%")
            )
            WeDatabaseApi.execStatement(
                "UPDATE ${ConversationTable.NAME} SET ${ConversationTable.PARENT_REF}='' WHERE ${ConversationTable.PARENT_REF} LIKE ?",
                arrayOf("$prefix%")
            )
            WeDatabaseApi.execStatement(
                "DELETE FROM ${ConversationTable.NAME} WHERE ${ConversationTable.USERNAME} LIKE ?",
                arrayOf("$prefix%")
            )
            WeDatabaseApi.execStatement(
                "DELETE FROM ${ContactTable.NAME} WHERE ${ContactTable.USERNAME} LIKE ?",
                arrayOf("$prefix%")
            )
            WeDatabaseApi.execStatement(
                "DELETE FROM img_flag WHERE username LIKE ?",
                arrayOf("$prefix%")
            )
        }
    }

    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(",")

    private fun deleteEmptyPlaceholderRows(members: Collection<String>) {
        members.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { chunk ->
            WeDatabaseApi.execStatement(
                """
                DELETE FROM ${ConversationTable.NAME}
                WHERE ${ConversationTable.USERNAME} IN (${placeholders(chunk.size)})
                  AND ${ConversationTable.PARENT_REF} LIKE ?
                  AND ${ConversationTable.DIGEST}='' AND ${ConversationTable.CONTENT}=''
                  AND ${ConversationTable.UNREAD_COUNT}=0 AND ${ConversationTable.CONVERSATION_TIME}=0
                  AND ${ConversationTable.FLAG}=0 AND ${ConversationTable.MSG_TYPE}=''
                  AND ${ConversationTable.STATUS}=0 AND ${ConversationTable.IS_SEND}=0
                """.trimIndent(),
                arrayOf(*chunk.toTypedArray(), "$FOLDER_PREFIX%")
            )
        }
    }

    private fun unbindMembers(members: Collection<String>) {
        members.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { chunk ->
            WeDatabaseApi.execStatement(
                "UPDATE ${ConversationTable.NAME} SET ${ConversationTable.PARENT_REF}='' " +
                        "WHERE ${ConversationTable.USERNAME} IN (${placeholders(chunk.size)}) " +
                        "AND ${ConversationTable.PARENT_REF} LIKE ?",
                arrayOf(*chunk.toTypedArray(), "$FOLDER_PREFIX%")
            )
        }
    }

    private fun ensureManualMemberRows(folders: List<ChatFolder>, changedMembers: Collection<String>) {
        val changed = changedMembers.toHashSet()
        folders.filter { it.type == FolderType.MANUAL }.forEach { folder ->
            folder.members.asSequence()
                .filter { it in changed && !isFolderId(it) }
                .distinct()
                .chunked(SQLITE_BIND_CHUNK_SIZE / 2)
                .forEach { chunk ->
                    val values = chunk.joinToString(",") { "(?, ?, '', '', 0, 0, 0, 0, 0, 0, '', '', 0)" }
                    val args: Array<Any> = chunk.flatMap { listOf<Any>(it, folder.id) }.toTypedArray()
                    WeDatabaseApi.execStatement(
                        """
                        INSERT OR IGNORE INTO ${ConversationTable.NAME} (
                            ${ConversationTable.USERNAME}, ${ConversationTable.PARENT_REF}, ${ConversationTable.DIGEST},
                            ${ConversationTable.DIGEST_USER}, ${ConversationTable.IS_SEND}, ${ConversationTable.STATUS},
                            ${ConversationTable.CONVERSATION_TIME}, ${ConversationTable.FLAG}, ${ConversationTable.UNREAD_COUNT},
                            ${ConversationTable.UNREAD_MUTE_COUNT}, ${ConversationTable.CONTENT},
                            ${ConversationTable.MSG_TYPE}, ${ConversationTable.CHAT_MODE}
                        ) VALUES $values
                        """.trimIndent(),
                        args
                    )
                }
        }
    }

    private fun bindMembers(bindings: Map<String, String>) {
        bindings.entries.groupBy({ it.value }, { it.key }).forEach { (folderId, members) ->
            members.chunked(SQLITE_BIND_CHUNK_SIZE - 1).forEach { chunk ->
                WeDatabaseApi.execStatement(
                    "UPDATE ${ConversationTable.NAME} SET ${ConversationTable.PARENT_REF}=? " +
                            "WHERE ${ConversationTable.USERNAME} IN (${placeholders(chunk.size)})",
                    arrayOf(folderId, *chunk.toTypedArray())
                )
            }
        }
    }

    private fun deleteStoredFolders(folderIds: Set<String>) {
        folderIds.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { chunk ->
            val where = "username IN (${placeholders(chunk.size)})"
            listOf(ConversationTable.NAME, ContactTable.NAME, "img_flag").forEach { table ->
                WeDatabaseApi.execStatement("DELETE FROM $table WHERE $where", chunk.toTypedArray())
            }
        }
    }

    private fun ensureFolderConversationRow(folder: ChatFolder) {
        WeDatabaseApi.execStatement(
            """
            INSERT OR IGNORE INTO ${ConversationTable.NAME} (
                ${ConversationTable.USERNAME}, ${ConversationTable.PARENT_REF}, ${ConversationTable.FLAG},
                ${ConversationTable.CONVERSATION_TIME}, ${ConversationTable.DIGEST}, ${ConversationTable.CONTENT}
            ) VALUES (?, '', ?, 0, '', '')
            """.trimIndent(),
            arrayOf(folder.id, folder.pinFlag and FLAG_HIGH_MASK)
        )
    }

    private fun writeFolderContact(folder: ChatFolder) {
        WeDatabaseApi.execStatement(
            """
            REPLACE INTO ${ContactTable.NAME} (
                ${ContactTable.USERNAME}, ${ContactTable.NICKNAME}, ${ContactTable.TYPE}, ${ContactTable.VERIFY_FLAG}
            ) VALUES (?, ?, 3, 0)
            """.trimIndent(),
            arrayOf(
                folder.id,
                folder.name.take(MAX_FOLDER_DISPLAY_NAME) +
                    if (folder.name.length > MAX_FOLDER_DISPLAY_NAME) "\u2026" else ""
            )
        )
    }

    private fun writeFolderAvatar(folderId: String) {
        WeDatabaseApi.execStatement(
            """
            INSERT OR IGNORE INTO img_flag (username, imgflag, lastupdatetime, reserved1, reserved2)
            VALUES (?, 3, ?, 0, ?)
            """.trimIndent(),
            arrayOf(folderId, System.currentTimeMillis() / 1000, "http://wekit.local/avatar/$folderId")
        )
    }

    /** Updates a materialized folder row while preserving WeChat's live pin bits. */
    private fun writeFolderSummaryRow(folderId: String, summary: FolderSummary) {
        WeDatabaseApi.execStatement(
            """
            UPDATE ${ConversationTable.NAME} SET
                ${ConversationTable.DIGEST}=?, ${ConversationTable.DIGEST_USER}=?,
                ${ConversationTable.IS_SEND}=?, ${ConversationTable.STATUS}=?,
                ${ConversationTable.CONVERSATION_TIME}=?,
                ${ConversationTable.FLAG}=(${ConversationTable.FLAG} & ?) | ?,
                ${ConversationTable.UNREAD_COUNT}=?, ${ConversationTable.UNREAD_MUTE_COUNT}=?,
                ${ConversationTable.CONTENT}=?, ${ConversationTable.MSG_TYPE}=?,
                ${ConversationTable.CHAT_MODE}=?, ${ConversationTable.ATTR_FLAG}=?,
                ${ConversationTable.AT_COUNT}=?
            WHERE ${ConversationTable.USERNAME}=?
            """.trimIndent(),
            arrayOf(
                summary.digest,
                summary.digestUser,
                summary.isSend,
                summary.status,
                summary.conversationTime,
                FLAG_HIGH_MASK,
                summary.conversationTime and FLAG_TIME_MASK,
                summary.unreadCount,
                summary.unreadMuteCount,
                summary.content,
                summary.msgType,
                summary.chatMode,
                summary.attrFlag,
                summary.atMeCount,
                folderId
            )
        )
    }

    private fun readFolderSummaries(
        byFolder: Map<String, List<String>>,
        storedRows: Map<String, StoredFolderRow> = emptyMap()
    ): Map<String, FolderSummary> {
        val ownerByMember = reverseMemberIndex(byFolder)
        val states = byFolder.mapValuesTo(linkedMapOf()) { SummaryAccumulator() }
        val members = ownerByMember.keys.toList()

        members.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { chunk ->
            WeDatabaseApi.rawQuery(
                """
                SELECT r.${ConversationTable.USERNAME}, r.${ConversationTable.DIGEST},
                       r.${ConversationTable.DIGEST_USER}, r.${ConversationTable.IS_SEND},
                       r.${ConversationTable.STATUS}, r.${ConversationTable.CONVERSATION_TIME},
                       r.${ConversationTable.UNREAD_COUNT}, r.${ConversationTable.UNREAD_MUTE_COUNT},
                       r.${ConversationTable.CONTENT},
                       r.${ConversationTable.MSG_TYPE}, r.${ConversationTable.CHAT_MODE},
                       r.${ConversationTable.AT_COUNT},
                       c.${ContactTable.TYPE}, c.${ContactTable.LV_BUFF},
                       c.${ContactTable.CON_REMARK}, c.${ContactTable.NICKNAME}
                FROM ${ConversationTable.NAME} r
                LEFT JOIN ${ContactTable.NAME} c
                  ON c.${ContactTable.USERNAME}=r.${ConversationTable.USERNAME}
                WHERE r.${ConversationTable.USERNAME} IN (${placeholders(chunk.size)})
                """.trimIndent(),
                chunk.toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val username = cursor.getStringOrEmpty(ConversationTable.USERNAME)
                    val folderId = ownerByMember[username] ?: continue
                    val state = states.getValue(folderId)
                    val unread = cursor.getIntOrZero(ConversationTable.UNREAD_COUNT).coerceAtLeast(0)
                    // 微信权威免打扰未读数：lvbuff 解析失败/缺失时兜底判定免打扰，避免免打扰群聊错误显示角标
                    val muteUnread = cursor.getIntOrZero(ConversationTable.UNREAD_MUTE_COUNT).coerceAtLeast(0)
                    if (unread > 0) {
                        val muted = if (muteUnread > 0) true else if (username.endsWith("@chatroom")) {
                            val index = cursor.getColumnIndex(ContactTable.LV_BUFF)
                            val lvBuff = if (index >= 0 && !cursor.isNull(index)) cursor.getBlob(index) else null
                            WeConversationApi.parseChatRoomNotify(lvBuff) == 0
                        } else {
                            cursor.getIntOrZero(ContactTable.TYPE) and 512 != 0
                        }
                        if (muted) {
                            state.mutedUnread += unread
                        } else {
                            state.normalUnread += unread
                        }
                        // [N个聊天] = 归拢文件夹里有未读的聊天数（FunBox 语义，不限免打扰）
                        state.unreadChatCount++
                    }
                    state.atMeCount += cursor.getIntOrZero(ConversationTable.AT_COUNT).coerceAtLeast(0)

                    val time = cursor.getLongOrZero(ConversationTable.CONVERSATION_TIME)
                    if (state.latest == null || time > state.latest!!.conversationTime) {
                        val nickname = cursor.getStringOrEmpty(ContactTable.NICKNAME)
                        val remark = cursor.getStringOrEmpty(ContactTable.CON_REMARK)
                        val displayName = if (username.endsWith("@chatroom")) nickname else remark.ifBlank { nickname }
                        state.latest = MemberSummaryRow(
                            digest = prefixWithConversationName(
                                displayName.takeIf { it.isNotBlank() && it != username },
                                stripWxidPrefix(cursor.getStringOrEmpty(ConversationTable.DIGEST)),
                                username.endsWith("@chatroom"),
                                cursor.getStringOrEmpty(ConversationTable.DIGEST_USER).ifBlank { null },
                                username
                            ),
                            digestUser = cursor.getStringOrEmpty(ConversationTable.DIGEST_USER),
                            isSend = cursor.getIntOrZero(ConversationTable.IS_SEND),
                            status = cursor.getIntOrZero(ConversationTable.STATUS),
                            conversationTime = time,
                            content = cursor.getStringOrEmpty(ConversationTable.CONTENT),
                            msgType = cursor.getStringOrEmpty(ConversationTable.MSG_TYPE),
                            chatMode = cursor.getIntOrZero(ConversationTable.CHAT_MODE)
                        )
                    }
                }
            }
        }

        return states.mapValues { (folderId, state) ->
            val latest = state.latest
            if (latest == null) {
                FolderSummary(
                    conversationTime = storedRows[folderId]?.summary?.conversationTime
                        ?: System.currentTimeMillis()
                )
            } else {
                FolderSummary(
                    digest = (
                        if (isEveryoneMention(latest.digest, latest.content) && (state.atMeCount > 0 || state.unreadChatCount > 0)) "[@全体]"
                        else if (state.atMeCount > 0) "[有人@我]"
                        else ""
                    ) + (
                        if (state.unreadChatCount > 0)
                            "[${state.unreadChatCount}个聊天]" else ""
                    ) + (
                        if (latest.isSend == 1) "[自己]" else ""
                    ) + latest.digest,
                    digestUser = latest.digestUser,
                    isSend = latest.isSend,
                    status = latest.status,
                    conversationTime = latest.conversationTime.takeIf { it > 0L }
                        ?: storedRows[folderId]?.summary?.conversationTime
                        ?: System.currentTimeMillis(),
                    unreadCount = state.normalUnread,
                    unreadMuteCount = state.mutedUnread,
                    content = latest.content,
                    msgType = latest.msgType,
                    chatMode = latest.chatMode
                )
            }
        }
    }

    /** 判断最新摘要/消息是否为「@所有人」群发提及，用于把 [有人@我] 换成 [全体]。
     *  微信摘要的 @所有人 形式多样（"@所有人"、"所有人:"、"全体成员" 等），
     *  因此按「所有人/全体」关键词匹配而非要求带 @ 符号。 */
    private fun isEveryoneMention(digest: String, content: String): Boolean =
        containsEveryone(digest) || containsEveryone(content)

    private fun containsEveryone(s: String): Boolean {
        val t = s.lowercase()
        return t.contains("所有人") || t.contains("全体") || t.contains("全部人") ||
            t.contains("全员") || t.contains("@all") ||
            t.contains("@everyone") || t.contains("all members")
    }
    /**
     * Prefixes the folder digest with the originating conversation's display name, so the
     * homepage folder row reads like "群聊名: 最新一条消息" instead of a bare message whose
     * source is ambiguous once several chats are aggregated. Returns the digest untouched
     * when it is blank or the name can't be resolved, to avoid a dangling "name: " prefix.
     */
    private val SENDER_PREFIX_REGEX = Regex("^(?:\uFF08([^\uFF09]+)\uFF09\uFF1A|([^:\uFF1A]+)[:\uFF1A])")

    private val WXID_PREFIX_REGEX = Regex("^wxid_[A-Za-z0-9_]+:\\s*")

    private fun stripWxidPrefix(digest: String): String {
        if (digest.isBlank()) return digest
        val m = WXID_PREFIX_REGEX.find(digest) ?: return digest
        return digest.substring(m.value.length)
    }

    private fun chineseNumber(n: Int): String = when (n) {
        in 1..9 -> arrayOf("\u4e00", "\u4e8c", "\u4e09", "\u56db", "\u4e94", "\u516d", "\u4e03", "\u516b", "\u4e5d")[n - 1]
        in 10..99 -> {
            val tens = arrayOf("", "\u5341", "\u4e8c\u5341", "\u4e09\u5341", "\u56db\u5341", "\u4e94\u5341", "\u516d\u5341", "\u4e03\u5341", "\u516b\u5341", "\u4e5d\u5341")[n / 10]
            val ones = n % 10
            if (ones == 0) tens else "$tens${arrayOf("\u4e00", "\u4e8c", "\u4e09", "\u56db", "\u4e94", "\u516d", "\u4e03", "\u516b", "\u4e5d")[ones - 1]}"
        }
        else -> n.toString()
    }

    private fun prefixWithConversationName(
        displayName: String?,
        digest: String,
        isChatroom: Boolean,
        senderWxid: String? = null,
        groupId: String? = null
    ): String {
        if (digest.isBlank() || displayName.isNullOrBlank()) return digest
        val name = displayName.take(MAX_DIGEST_NAME_LEN) +
            if (displayName.length > MAX_DIGEST_NAME_LEN) "\u2026" else ""
        if (isChatroom) {
            val m = SENDER_PREFIX_REGEX.find(digest)
            if (m != null) {
                val sender = m.groupValues[1].ifEmpty { m.groupValues[2] }
                val rawSender = resolveSenderDisplayName(sender, senderWxid, groupId)
                val senderName = rawSender.take(MAX_SENDER_NAME_LEN) +
                    if (rawSender.length > MAX_SENDER_NAME_LEN) "\u2026" else ""
                val rest = digest.substring(m.value.length)
                // 发送者名无法解析（无备注、无群名片）时不显示空括号
                return if (senderName.isBlank()) "$name: $rest" else "$name($senderName):$rest"
            }
        }
        return "$name: $digest"
    }

    private val senderNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Resolve the digest's raw sender handle to the display name shown in the folder digest.
     *  与微信群聊一致：备注 → 群名片（该群内，chatroom.roomdata displayName）→ 微信昵称。
     *  不直接显示微信号/用户 ID（wxid_、带 @ 的账号标识）。
     *  [senderWxid] 来自微信 digestUser（发送者账号，最精确）；[groupId] 为所在群（@chatroom），
     *  用于取群名片（如无群名片时微信摘要可能显示昵称，但群名片才是用户在该群的名字）。 */
    private fun resolveSenderDisplayName(sender: String, senderWxid: String? = null, groupId: String? = null): String {
        if (sender.isBlank() && senderWxid.isNullOrBlank()) return ""
        val cacheKey = "$senderWxid|$sender|$groupId"
        senderNameCache[cacheKey]?.let { return it }
        val final = runCatching {
            val wxid = senderWxid?.takeIf { it.isNotBlank() } ?: sender
            val isAccount = wxid.startsWith("wxid_") || wxid.contains("@")
            // 1) 备注（个人备注优先，与微信一致）
            val remark = queryContactField(wxid, ContactTable.CON_REMARK)
            if (remark.isNotBlank()) return@runCatching remark
            // 2) 群名片：发送者是该群成员时的群内显示名（优先于昵称）
            if (groupId != null && wxid.startsWith("wxid_")) {
                val card = WeDatabaseApi.getGroupMemberDisplayName(groupId, wxid)
                if (card.isNotBlank()) return@runCatching card
            }
            // 3) 微信昵称
            val nickname = queryContactField(wxid, ContactTable.NICKNAME)
            if (nickname.isNotBlank()) return@runCatching nickname
            // 4) 账号标识：查不到名字则不显示（避免暴露用户 ID）
            if (isAccount) return@runCatching ""
            // 5) sender 是显示名文本（微信摘要里的群名片/昵称）——反查昵称得账号后取群名片
            val nicknameWxid = runCatching {
                WeDatabaseApi.rawQuery(
                    "SELECT ${ContactTable.USERNAME} FROM ${ContactTable.NAME} WHERE ${ContactTable.NICKNAME}=? LIMIT 1",
                    arrayOf(sender)
                ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
            if (nicknameWxid != null && groupId != null) {
                val card = WeDatabaseApi.getGroupMemberDisplayName(groupId, nicknameWxid)
                if (card.isNotBlank()) return@runCatching card
            }
            sender
        }.getOrDefault("")
        senderNameCache[cacheKey] = final
        return final
    }

    /** 查询联系人单字段（备注或昵称），无值返回空串。 */
    private fun queryContactField(username: String, column: String): String = runCatching {
        WeDatabaseApi.rawQuery(
            "SELECT $column FROM ${ContactTable.NAME} WHERE ${ContactTable.USERNAME}=?",
            arrayOf(username)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getStringOrEmpty(column) else ""
        }
    }.getOrDefault("")

    private fun isFolderSchemaReady(): Boolean {
        folderSchemaReady?.let { return it }
        val result = runCatching {
            val conversationColumns = tableColumns(ConversationTable.NAME)
            WeLogger.i(TAG, "rconversation columns: $conversationColumns")
            runCatching {
                WeDatabaseApi.rawQuery("SELECT name FROM sqlite_master WHERE type='table'").use { tc ->
                    val tns = mutableListOf<String>()
                    while (tc.moveToNext()) tns += tc.getString(0)
                    WeLogger.i(TAG, "sqlite tables: ${tns.joinToString()}")
                }
            }.onFailure { WeLogger.w(TAG, "list tables failed", it) }
            val contactColumns = tableColumns(ContactTable.NAME)
            val missingConversationColumns = ConversationTable.REQUIRED_COLUMNS - conversationColumns
            val missingContactColumns = ContactTable.REQUIRED_COLUMNS - contactColumns
            if (missingConversationColumns.isNotEmpty() || missingContactColumns.isNotEmpty()) {
                WeLogger.w(
                    TAG,
                    "skip folders sync, schema mismatch: " +
                            "rconversation missing=${missingConversationColumns.joinToString()}, " +
                            "rcontact missing=${missingContactColumns.joinToString()}"
                )
                false
            } else {
                true
            }
        }.onFailure {
            WeLogger.w(TAG, "skip folders sync, failed to inspect WeChat database schema", it)
        }.getOrNull()
        // Only latch the outcome when the check actually completed. A transient failure (the
        // database being briefly locked or closing right after WeDatabaseApi.isReady flips)
        // must not permanently disable folder sync for the rest of the process — leave the
        // cached value unset so the next call retries.
        if (result != null) {
            folderSchemaReady = result
        }
        return result == true
    }

    private fun tableColumns(table: String): Set<String> {
        val columns = linkedSetOf<String>()
        val cursor = WeDatabaseApi.rawQuery("PRAGMA table_info($table)")
        cursor.use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
        }
        return columns
    }

    private fun rewriteContainerSql(sql: String, folderId: String): String {
        if (!sql.contains(ConversationTable.NAME, ignoreCase = true) ||
            !sql.contains(ConversationTable.PARENT_REF, ignoreCase = true)
        ) {
            return sql
        }
        if (!sql.contains(WeChatFolderPlaceholder.CONVERSATION_BOX) && !sql.contains(WeChatFolderPlaceholder.MESSAGE_FOLD)) {
            return sql
        }
        return sql
            .replace(WeChatFolderPlaceholder.CONVERSATION_BOX, folderId)
            .replace(WeChatFolderPlaceholder.MESSAGE_FOLD, folderId)
    }

    private fun readFolderIdFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        return WeChatIntentExtra.ALL
            .asSequence()
            .mapNotNull { intent.getStringExtra(it) }
            .firstOrNull(::isFolderId)
    }

    /** 供「悬浮标题栏」等特性判断这个页面是不是一个归拢分组页。 */
    fun folderIdFromIntent(intent: Intent?): String? = readFolderIdFromIntent(intent)

    private inline fun <T> withQueryRewriteSuppressed(action: () -> T): T {
        val oldValue = suppressQueryRewrite.get()
        suppressQueryRewrite.set(true)
        return try {
            action()
        } finally {
            suppressQueryRewrite.set(oldValue)
        }
    }

    private fun showManagerDialog(context: Context) {
        showComposeDialog(context) {
            var folders by remember { mutableStateOf(loadFolders()) }
            var atColor by remember { mutableStateOf(mentionAtColor) }
            var countColor by remember { mutableStateOf(mentionCountColor) }
            var selfColor by remember { mutableStateOf(mentionSelfColor) }
            var memberColor by remember { mutableStateOf(mentionMemberColor) }
            var titleColor by remember { mutableStateOf(folderTitleColor) }
            var titleEnabled by remember { mutableStateOf(folderTitleEnabled) }
            var selfEnabled by remember { mutableStateOf(mentionSelfEnabled) }
            var memberEnabled by remember { mutableStateOf(mentionMemberEnabled) }

            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text("对话归拢") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("摘要颜色")
                                    WeColorField(label = "[全体]/[有人@我]", value = atColor, onValueChange = { atColor = it })
                                    WeColorField(label = "[N个聊天]/[N个消息]", value = countColor, onValueChange = { countColor = it })
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("[自己]", modifier = Modifier.weight(1f))
                                        Switch(checked = selfEnabled, onCheckedChange = { selfEnabled = it })
                                    }
                                    if (selfEnabled) {
                                    WeColorField(label = "[自己]", value = selfColor, onValueChange = { selfColor = it })
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("(群成员)", modifier = Modifier.weight(1f))
                                        Switch(checked = memberEnabled, onCheckedChange = { memberEnabled = it })
                                    }
                                    if (memberEnabled) {
                                    WeColorField(label = "(群成员)", value = memberColor, onValueChange = { memberColor = it })
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("文件夹标题染色", modifier = Modifier.weight(1f))
                                        Switch(checked = titleEnabled, onCheckedChange = { titleEnabled = it })
                                    }
                                    if (titleEnabled) {
                                        WeColorField(label = "文件夹标题", value = titleColor, onValueChange = { titleColor = it })
                                    }
                                }
                            }
                            if (folders.isEmpty()) {
                                item {
                                    Text("暂无文件夹, 点击「新建」来创建一个")
                                }
                            }
                            items(folders, key = { it.id }) { folder ->
                                FolderRow(folder) {
                                    showEditFolderDialog(
                                        context = context,
                                        folder = folder,
                                        onFolderUpdated = { folders = loadFolders() },
                                        onFolderDeleted = { folders = loadFolders() }
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("关闭") }
                    TextButton(onClick = {
                        syncFoldersToDatabase()
                        showToast("已重建文件夹索引")
                    }) { Text("重载") }
                    TextButton(onClick = {
                        showCreateFolderDialog(context) {
                            folders = loadFolders()
                        }
                    }) { Text("新建") }
                },
                confirmButton = {
                    Button(onClick = {
                        mentionAtColor = atColor
                        mentionCountColor = countColor
                        mentionSelfColor = selfColor
                        mentionMemberColor = memberColor
                        folderTitleColor = titleColor
                        folderTitleEnabled = titleEnabled
                        mentionSelfEnabled = selfEnabled
                        mentionMemberEnabled = memberEnabled
                        saveFolders(folders)
                        syncFoldersToDatabase()
                        showToast(context, "已保存, 重启微信生效")
                        onDismiss()
                    }) { Text("保存") }
                }
            )
        }
    }

    private fun showCreateFolderDialog(context: Context, onFolderCreated: () -> Unit) {
        showComposeDialog(context) {
            FolderEditorDialog(
                title = "新建文件夹",
                folder = null,
                onDismiss = onDismiss,
                onSave = { folder ->
                    val currentFolders = loadFolders()
                    saveFolders(currentFolders + folder)
                    onFolderCreated()
                    onDismiss()
                }
            )
        }
    }

    private fun showEditFolderDialog(
        context: Context,
        folder: ChatFolder,
        onFolderUpdated: () -> Unit,
        onFolderDeleted: () -> Unit
    ) {
        showComposeDialog(context) {
            FolderEditorDialog(
                title = "编辑文件夹",
                folder = folder,
                onDismiss = onDismiss,
                onDelete = {
                    val currentFolders = loadFolders()
                    saveFolders(currentFolders.filterNot { it.id == folder.id })
                    onFolderDeleted()
                    onDismiss()
                },
                onSave = { updatedFolder ->
                    val currentFolders = loadFolders()
                    saveFolders(currentFolders.map { if (it.id == updatedFolder.id) updatedFolder else it })
                    onFolderUpdated()
                    onDismiss()
                }
            )
        }
    }

    @Composable
    private fun FolderRow(folder: ChatFolder, onClick: () -> Unit) {
        val count = remember(folder) { getFolderMembers(folder).size }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp)
        ) {
            Text(folder.name)
            val desc = when (folder.type) {
                FolderType.MANUAL -> "手动选择: $count 个对话"
                FolderType.PRESET_GROUPS -> "所有群聊: $count 个对话"
                FolderType.PRESET_OFFICIALS -> "所有公众号: $count 个对话"
                FolderType.SQL -> "SQL规则: $count 个对话"
            }
            Text(desc)
        }
    }

    @Composable
    private fun FolderEditorDialog(
        title: String,
        folder: ChatFolder?,
        onDismiss: () -> Unit,
        onDelete: (() -> Unit)? = null,
        onSave: (ChatFolder) -> Unit
    ) {
        val folderId = remember(folder) { folder?.id ?: newFolderId() }
        var name by remember(folder) { mutableStateOf(folder?.name ?: "") }
        var members by remember(folder) { mutableStateOf(folder?.members?.toSet().orEmpty()) }

        var type by remember(folder) { mutableStateOf(folder?.type ?: FolderType.MANUAL) }
        var selectFields by remember(folder) { mutableStateOf(folder?.selectFields ?: "r.username") }
        var whereClause by remember(folder) { mutableStateOf(folder?.whereClause ?: "") }

        val matchedCount = remember(type, members, selectFields, whereClause) {
            val tempFolder = ChatFolder(
                id = folderId,
                name = name,
                members = members.toList(),
                type = type,
                selectFields = selectFields,
                whereClause = whereClause
            )
            // Resolve directly instead of going through getFolderMembers: that cache is keyed
            // by folder id, and this preview folder reuses the id of the folder being edited,
            // so the cached (stale) member list would freeze the count at the first result.
            resolveFolderMembers(tempFolder).size
        }

        var hasAvatar by remember(folderId) {
            mutableStateOf(CustomLocalFriendAvatars.avatarMap.containsKey(folderId))
        }

        AlertDialogContent(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            title = { Text(title) },
            text = {
                DefaultColumn {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("文件夹名称") },
                        singleLine = true
                    )

                    var typeExpanded by remember { mutableStateOf(false) }
                    Column {
                        Text("归拢模式", style = MaterialTheme.typography.labelSmall)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { typeExpanded = true }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = when (type) {
                                    FolderType.MANUAL -> "手动选择"
                                    FolderType.PRESET_GROUPS -> "自动所有群聊"
                                    FolderType.PRESET_OFFICIALS -> "自动所有公众号"
                                    FolderType.SQL -> "自定义 SQL 规则"
                                },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        DropdownMenu(
                            expanded = typeExpanded,
                            onDismissRequest = { typeExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("手动选择") },
                                onClick = {
                                    type = FolderType.MANUAL
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("自动所有群聊") },
                                onClick = {
                                    type = FolderType.PRESET_GROUPS
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("自动所有公众号") },
                                onClick = {
                                    type = FolderType.PRESET_OFFICIALS
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("自定义 SQL 规则") },
                                onClick = {
                                    type = FolderType.SQL
                                    typeExpanded = false
                                }
                            )
                        }
                    }

                    when (type) {
                        FolderType.MANUAL -> {
                            Text("已选择 $matchedCount 个对话")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val context = LocalContext.current
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        showComposeDialog(context) {
                                            ContactsSelector(
                                                title = "选择对话",
                                                contacts = remember { WeDatabaseApi.getContacts() },
                                                initialSelectedWxIds = members,
                                                onDismiss = this.onDismiss,
                                                onConfirm = {
                                                    members = it
                                                    this.onDismiss()
                                                }
                                            )
                                        }
                                    }
                                ) {
                                    Text("选择对话")
                                }

                                if (hasAvatar) {
                                    Button(onClick = {
                                        CustomLocalFriendAvatars.removeAvatar(folderId)
                                        hasAvatar = false
                                    }) {
                                        Text("清除头像")
                                    }
                                }
                                Button(onClick = {
                                    if (!CustomLocalFriendAvatars.isEnabled) {
                                        showToast("请启用「自定义好友本地头像」以使用头像相关功能!")
                                    }

                                    CustomLocalFriendAvatars.selectAvatarImage(HostInfo.application, folderId)
                                }) {
                                    Text(if (hasAvatar) "更换头像" else "设置头像")
                                }
                            }
                            val context = LocalContext.current
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    if (members.isEmpty()) {
                                        showToast("文件夹暂无成员可移出")
                                        return@Button
                                    }
                                    val others = loadFolders().filter { it.id != folderId && it.type == FolderType.MANUAL }
                                    if (others.isEmpty()) {
                                        showToast("没有其他手动文件夹可移出")
                                        return@Button
                                    }
                                    showComposeDialog(context) {
                                        val dismiss = this.onDismiss
                                        ContactsSelector(
                                            title = "选择要移出的对话",
                                            contacts = remember { WeDatabaseApi.getContacts().filter { it.wxId in members } },

                                            initialSelectedWxIds = emptySet(),
                                            onDismiss = dismiss,
                                            onConfirm = { toMove ->
                                                dismiss()
                                                showComposeDialog(context) {
                                                    val innerDismiss = this.onDismiss
                                                    AlertDialogContent(
                                                        title = { Text("移出到其他文件夹") },
                                                        text = {
                                                            LazyColumn {
                                                                items(others) { target ->
                                                                    Text(
                                                                        target.name,
                                                                        modifier = Modifier
                                                                            .fillMaxWidth()
                                                                            .clickable {
                                                                                val current = loadFolders()
                                                                                val curList = current.map { if (it.id == folderId) it.copy(members = it.members - toMove) else it }
                                                                                val finalList = curList.map { if (it.id == target.id) it.copy(members = (it.members + toMove).distinct().sorted()) else it }
                                                                                saveFolders(finalList)
                                                                                members = members - toMove
                                                                                innerDismiss()
                                                                                dismiss()
                                                                                onSave(folder!!.copy(members = (members - toMove).sorted()))
                                                                            }
                                                                            .padding(12.dp)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            ) {
                                Text("移出到其他文件夹")
                            }
                        }

                        FolderType.PRESET_GROUPS -> {
                            Text("自动归拢所有群聊（当前匹配到 $matchedCount 个对话）")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (hasAvatar) {
                                    Button(onClick = {
                                        CustomLocalFriendAvatars.removeAvatar(folderId)
                                        hasAvatar = false
                                    }) {
                                        Text("清除头像")
                                    }
                                }
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        CustomLocalFriendAvatars.selectAvatarImage(HostInfo.application, folderId)
                                    }
                                ) {
                                    Text(if (hasAvatar) "更换头像" else "设置头像")
                                }
                            }
                        }

                        FolderType.PRESET_OFFICIALS -> {
                            Text("自动归拢所有公众号（当前匹配到 $matchedCount 个对话）")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (hasAvatar) {
                                    Button(onClick = {
                                        CustomLocalFriendAvatars.removeAvatar(folderId)
                                        hasAvatar = false
                                    }) {
                                        Text("清除头像")
                                    }
                                }
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        CustomLocalFriendAvatars.selectAvatarImage(HostInfo.application, folderId)
                                    }
                                ) {
                                    Text(if (hasAvatar) "更换头像" else "设置头像")
                                }
                            }
                        }

                        FolderType.SQL -> {
                            OutlinedTextField(
                                value = selectFields,
                                onValueChange = { selectFields = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("SELECT 字段") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = whereClause,
                                onValueChange = { whereClause = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("WHERE 条件") },
                                singleLine = false,
                                maxLines = 4
                            )
                            Text(
                                text = "当前匹配到 $matchedCount 个对话",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "数据源自 rcontact r, img_flag i, rconversation c\n示例: c.unReadCount > 0 AND r.username LIKE '%@chatroom'",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (hasAvatar) {
                                    Button(onClick = {
                                        CustomLocalFriendAvatars.removeAvatar(folderId)
                                        hasAvatar = false
                                    }) {
                                        Text("清除头像")
                                    }
                                }
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        CustomLocalFriendAvatars.selectAvatarImage(HostInfo.application, folderId)
                                    }
                                ) {
                                    Text(if (hasAvatar) "更换头像" else "设置头像")
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = {
                if (onDelete != null) {
                    TextButton(onDelete) { Text("删除") }
                }
                TextButton(onDismiss) { Text("取消") }
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val next = ChatFolder(
                            id = folderId,
                            name = name.trim(),
                            members = members.toList().sorted(),
                            type = type,
                            selectFields = selectFields.trim(),
                            whereClause = whereClause.trim(),
                            // Carry the pin state forward — editing a folder must not reset its pin.
                            pinFlag = folder?.pinFlag ?: 0L
                        )
                        onSave(next)
                        showToast("已保存")
                    }
                ) { Text("确定") }
            }
        )
    }

    private fun resolveFolderMembers(folder: ChatFolder): List<String> {
        return when (folder.type) {
            FolderType.MANUAL -> folder.members
            FolderType.PRESET_GROUPS -> {
                runCatching {
                    val result = WeDatabaseApi.executeQuery(
                        "SELECT r.username FROM rcontact r WHERE r.username LIKE '%@chatroom'"
                    )
                    result.mapNotNull { it["username"]?.toString() }
                }.getOrElse {
                    WeLogger.e(TAG, "failed to query preset groups", it)
                    emptyList()
                }
            }

            FolderType.PRESET_OFFICIALS -> {
                runCatching {
                    val result = WeDatabaseApi.executeQuery(
                        "SELECT r.username FROM rcontact r WHERE r.username LIKE 'gh_%'"
                    )
                    result.mapNotNull { it["username"]?.toString() }
                }.getOrElse {
                    WeLogger.e(TAG, "failed to query preset officials", it)
                    emptyList()
                }
            }

            FolderType.SQL -> {
                runCatching {
                    val select = folder.selectFields.ifBlank { "r.username" }
                    val where = folder.whereClause.ifBlank { "1=1" }
                    val query =
                        "SELECT $select FROM rcontact r LEFT JOIN img_flag i ON r.username = i.username LEFT JOIN rconversation c ON r.username = c.username WHERE $where"
                    val result = WeDatabaseApi.executeQuery(query)
                    result.mapNotNull { row ->
                        val username = row["username"]?.toString()
                        if (username != null) return@mapNotNull username
                        row.values.firstOrNull()?.toString()
                    }
                }.getOrElse {
                    WeLogger.e(TAG, "failed to query custom sql for folder ${folder.id}", it)
                    emptyList()
                }
            }
        }
    }

    private fun getFolderMembers(folder: ChatFolder): List<String> {
        if (folder.type == FolderType.MANUAL) {
            return folder.members
        }
        val cached = folderMembersCache[folder.id]
        if (cached != null) return cached

        if (!WeDatabaseApi.isReady) {
            return emptyList()
        }
        val resolved = resolveFolderMembers(folder)
        if (resolved.isNotEmpty()) {
            folderMembersCache[folder.id] = resolved
        }
        return resolved
    }

    private fun getFallbackAvatarMember(folderId: String): String? {
        val folder = folderById(folderId) ?: return null
        val members = getFolderMembers(folder).filterNot(::isFolderId).distinct()
        if (members.isEmpty()) return null
        // Prefer the member whose conversation most recently saw activity: WeChat bumps
        // rconversation.conversationTime on every sent or received message, so the folder
        // borrows the avatar of the chat that last lit up rather than an arbitrary first
        // member. Falls back to the first member when none of them has any message yet.
        return latestActiveMember(members) ?: members.firstOrNull()
    }

    /** Member with the newest conversationTime (latest sent/received message), or null. */
    private fun latestActiveMember(members: List<String>): String? {
        if (members.isEmpty() || !WeDatabaseApi.isReady) return null
        return runCatching {
            // Suppress the container SQL fallback while querying aggregate members directly.
            withQueryRewriteSuppressed {
                val placeholders = members.joinToString(",") { "?" }
                val cursor = WeDatabaseApi.rawQuery(
                    """
                    SELECT ${ConversationTable.USERNAME}
                    FROM ${ConversationTable.NAME}
                    WHERE ${ConversationTable.USERNAME} IN ($placeholders) AND ${ConversationTable.CONVERSATION_TIME} > 0
                    ORDER BY ${ConversationTable.CONVERSATION_TIME} DESC
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(*members.toTypedArray())
                )
                cursor.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to resolve latest active member", it)
        }.getOrNull()
    }

    private fun loadFolders(): List<ChatFolder> {
        val wxid = currentAccountWxid()
        if (foldersCache != null && foldersCacheWxid == wxid) return foldersCache!!
        val file = foldersFileFor(wxid)
        val folders = runCatching {
            if (file.exists()) {
                decodeFoldersFrom(file)
            } else if (!wxid.isNullOrBlank() && legacyFoldersFile.exists()) {
                // 账号首次使用：继承旧的共享配置（一次性迁移），之后各账号独立
                decodeFoldersFrom(legacyFoldersFile)
            } else {
                emptyList()
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to decode folders config from $file", it)
        }.getOrDefault(emptyList())
        // 账号文件不存在但继承到了旧配置 -> 落盘到账号文件（一次性迁移）
        if (folders.isNotEmpty() && !file.exists()) {
            saveFoldersTo(file, folders)
        }
        foldersCache = folders
        foldersCacheWxid = wxid
        return folders
    }

    private fun decodeFoldersFrom(file: Path): List<ChatFolder> {
        if (!file.exists()) return emptyList()
        return DefaultJson.decodeFromString<List<ChatFolder>>(file.readText())
            .map { folder ->
                folder.copy(members = folder.members.filter { it.isNotBlank() })
            }
            .filter { isFolderId(it.id) && it.name.isNotBlank() }
    }

    private fun saveFoldersTo(file: Path, folders: List<ChatFolder>) {
        runCatching {
            val raw = DefaultJson.encodeToString(folders)
            file.writeText(raw)
        }.onFailure {
            WeLogger.w(TAG, "failed to save folders to $file", it)
        }
    }


    private fun saveFolders(folders: List<ChatFolder>) {
        foldersCache = folders
        foldersCacheWxid = currentAccountWxid()
        folderMembersCache.clear()
        saveFoldersTo(foldersFileFor(foldersCacheWxid), folders)
    }


    private fun folderById(folderId: String): ChatFolder? {
        return loadFolders().firstOrNull { it.id == folderId }
    }

    private fun newFolderId(): String = "$FOLDER_PREFIX${System.currentTimeMillis()}"

    private fun isFolderId(value: String): Boolean = value.startsWith(FOLDER_PREFIX)


    enum class FolderType {
        MANUAL,
        PRESET_GROUPS,
        PRESET_OFFICIALS,
        SQL
    }

    @Serializable
    private data class ChatFolder(
        val id: String = "",
        val name: String = "",
        val members: List<String> = emptyList(),
        val type: FolderType = FolderType.MANUAL,
        val selectFields: String = "",
        val whereClause: String = "",
        // High 8 bits (pin / move-up state, owned by WeChat's setPlacedTop / unSetPlacedTop) of this
        // folder's rconversation row, mirrored here so it survives onDisable deleting the row. Kept
        // in sync from the live row before a folder row is removed.
        val pinFlag: Long = 0L
    )

    private data class StoredFolderRow(
        val flag: Long,
        val attrFlag: Int,
        val summary: FolderSummary
    )

    private data class MemberSummaryRow(
        val digest: String,
        val digestUser: String,
        val isSend: Int,
        val status: Int,
        val conversationTime: Long,
        val content: String,
        val msgType: String,
        val chatMode: Int
    )

    private class SummaryAccumulator {
        var latest: MemberSummaryRow? = null
        var normalUnread: Int = 0
        var mutedUnread: Int = 0
        var unreadChatCount: Int = 0
        var atMeCount: Int = 0
    }

    private data class FolderSummary(
        val digest: String = "",
        val digestUser: String = "",
        val isSend: Int = 0,
        val status: Int = 0,
        val conversationTime: Long = System.currentTimeMillis(),
        val unreadCount: Int = 0,
        val unreadMuteCount: Int = 0,
        val atMeCount: Int = 0,
        val content: String = "",
        val msgType: String = "",
        val chatMode: Int = 0
    ) {
        /**
         * The folder row needs a mute attrflag bit set for the homepage badge to render a
         * small dot (WeChat w3.b requires unReadCount==0 && unReadMuteCount>0 && attrflag has
         * a mute bit). We add the bit only when there's muted-but-no-normal unread, and clear
         * it otherwise so a stale dot never lingers.
         */
        val attrFlag: Int
            get() = if (unreadCount == 0 && unreadMuteCount > 0) ATTR_FLAG_MUTE_BIT else 0
    }

    private object ConversationTable {
        const val NAME = "rconversation"
        const val USERNAME = "username"
        const val PARENT_REF = "parentRef"
        const val DIGEST = "digest"
        const val DIGEST_USER = "digestUser"
        const val IS_SEND = "isSend"
        const val STATUS = "status"
        const val CONVERSATION_TIME = "conversationTime"
        const val FLAG = "flag"
        const val UNREAD_COUNT = "unReadCount"
        const val UNREAD_MUTE_COUNT = "unReadMuteCount"
        const val CONTENT = "content"
        const val MSG_TYPE = "msgType"
        const val CHAT_MODE = "chatmode"
        const val ATTR_FLAG = "attrflag"
        const val AT_COUNT = "atCount"

        val REQUIRED_COLUMNS = setOf(
            USERNAME,
            PARENT_REF,
            DIGEST,
            DIGEST_USER,
            IS_SEND,
            STATUS,
            CONVERSATION_TIME,
            FLAG,
            UNREAD_COUNT,
            UNREAD_MUTE_COUNT,
            CONTENT,
            MSG_TYPE,
            CHAT_MODE,
            ATTR_FLAG
        )
    }

    private object ContactTable {
        const val NAME = "rcontact"
        const val USERNAME = "username"
        const val NICKNAME = "nickname"
        const val CON_REMARK = "conRemark"
        const val LV_BUFF = "lvbuff"
        const val TYPE = "type"
        const val VERIFY_FLAG = "verifyFlag"

        val REQUIRED_COLUMNS = setOf(
            USERNAME,
            NICKNAME,
            TYPE,
            CON_REMARK,
            LV_BUFF,
            VERIFY_FLAG
        )
    }

    private object WeChatIntentExtra {
        const val CONTACT_USER = "Contact_User"
        const val CONTACT_CHAT_ROOM_ID = "Contact_ChatRoomId"
        const val ROOM_NAME = "room_name"
        const val CHAT_USER = "Chat_User"

        val ALL = listOf(
            CONTACT_USER,
            CONTACT_CHAT_ROOM_ID,
            ROOM_NAME,
            CHAT_USER
        )
    }

    private object WeChatFolderPlaceholder {
        const val CONVERSATION_BOX = "conversationboxservice"
        const val MESSAGE_FOLD = "message_fold"
    }


    private fun android.database.Cursor.getStringOrEmpty(column: String): String {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) ?: "" else ""
    }

    private fun android.database.Cursor.getIntOrZero(column: String): Int {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else 0
    }

    private fun android.database.Cursor.getLongOrZero(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else 0L
    }

}
