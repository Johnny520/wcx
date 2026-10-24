package com.Johnny.wcx.features.items.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import android.content.ContentValues
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.WeXmlParserApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.content.m3.BaseWidget
import com.Johnny.wcx.ui.content.m3.DropDownMenuWidget
import com.Johnny.wcx.ui.content.m3.DropdownOption
import com.Johnny.wcx.ui.content.m3.SegmentedColumn
import com.Johnny.wcx.ui.content.m3.SwitchWidget
import com.Johnny.wcx.ui.utils.VectorPathDrawable
import com.Johnny.wcx.ui.utils.dpToPx
import com.Johnny.wcx.ui.utils.findViewWhich
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.isDarkMode
import com.Johnny.wcx.utils.formatEpoch
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

@Feature(name = "防撤回", categories = ["聊天"], description = "阻止撤回消息")
object AntiMessageRecall : ClickableFeature(), WeXmlParserApi.IAfterParseListener,
    WeChatMessageViewApi.ICreateViewListener, WeDatabaseListenerApi.IUpdateListener, IResolveDex {

    private const val TAG = "AntiMessageRecall"

    private var recallOutgoing by prefOption("recall_outgoing", false)

    /**
     * 旧版样式开关（与徽标样式互斥，同一时间只有一种生效）：
     * - false（默认）：[STYLE_BADGE] 原消息变淡 + 气泡旁徽标（图标 / 文本二选一）
     * - true：[STYLE_LEGACY] 在会话里插入一条灰色系统提示（最开始那版防撤回的样式）
     */
    private var recallLegacyNotice by prefOption("recall_legacy_notice", false)

    private val displayStyle: String
        get() = if (recallLegacyNotice) STYLE_LEGACY else STYLE_BADGE

    /** 徽标类型，只在 [STYLE_BADGE] 下生效。 */
    private var badgeType by prefOption("recall_badge_type", BADGE_TYPE_ICON)

    /** 旧版系统提示模板（沿用历史键，未改过则用默认文案）。 */
    private var legacyPattern by prefOption("recall_pattern", "「\$sender」尝试撤回上一条消息 (已阻止)")

    /** 旧版系统提示的时间格式（沿用历史键）。 */
    private var legacyTimeFormat by prefOption("recall_time_format", LEGACY_TIME_FORMAT)
    // WeChat only marks a message as revoked AFTER overwriting its row in place (destroying the
    // content). Our blocked recalls leave the row intact and unmarked, so the recalled list must be
    // tracked here, persisted as "<talker>\u001F<msgSvrId>" entries.
    private var recalledRecords by prefOption("recall_records", emptySet())

    // In-memory mirror of [recalledRecords] for the per-bind hot path.
    @Volatile
    private var recalledKeyCache: Set<String> = emptySet()

    // Content views currently dimmed by us, keyed by their bound row, so a rebind restores the
    // exact view we changed instead of clobbering other features' animations (e.g.
    // MessageEntranceAnimation).
    private val dimmedContentViews =
        Collections.synchronizedMap(WeakHashMap<View, View>())
    private val badges = Collections.synchronizedMap(WeakHashMap<View, RecallBadge>())

    // Unique to NetSceneRevokeMsg's notice converter across the supported host range; anchors the
    // scene class without hard-coding its obfuscated name.
    private const val REVOKE_INVOKE_MESSAGE_XML =
        "<sysmsg type=\"invokeMessage\"><invokeMessage><text><![CDATA[%s]]></text><timestamp><![CDATA[%s]]></timestamp><link><text><![CDATA[%s]]></text></link><preContent><![CDATA[%s]]></preContent><type><![CDATA[%s]]></type><msgSource><![CDATA[%s]]></msgSource></invokeMessage></sysmsg>"

    private const val TYPE_KEY = "\$.sysmsg.\$type"
    private const val RECORD_SEPARATOR = "\u001F"
    private const val RECALL_ALPHA = 0.4f
    private const val BADGE_SIZE_DP = 28
    private const val BADGE_PADDING_DP = 6
    private const val BADGE_GAP_DP = 4

    private const val BADGE_TYPE_ICON = "icon"
    private const val BADGE_TYPE_TEXT = "text"

    // Badge label; upstream reads it from a string resource.
    private const val BADGE_TEXT = "已撤回"

    private const val STYLE_BADGE = "badge"
    private const val STYLE_LEGACY = "legacy"
    private const val LEGACY_TIME_FORMAT = "yyyy/MM/dd HH:mm:ss"

    /** 迁移进度要从 IO 线程回到主线程更新 Compose 状态（回调是普通函数类型，不能直接 withContext）。 */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 迁移旧提示时单次最多扫描的系统消息条数（避免大账号上长时间卡在「正在迁移」）。 */
    private const val MIGRATION_SCAN_LIMIT = 500

    // 「「xxx」尝试撤回上一条消息」的默认模板与微信自带撤回提示的名字提取
    private val NAME_REGEX = Regex("([\"「])(.*?)([」\"])")

    // Material "delete" glyph on the same 960-unit viewport as the other vector icons.
    private const val DELETE_ICON_PATH =
        "M280,840 Q247,840 223.5,816.5 T200,760 V240 H160 V160 H360 V120 H600 V160 H800 V240 H760 V760 Q760,793 736.5,816.5 T680,840 H280 Z M680,240 H280 V760 H680 Z M360,680 H440 V320 H360 Z M520,680 H600 V320 H520 Z"

    private val badgeIconLight = "#C62828".toColorInt()
    /**
     * 微信处理「对方撤回」的入口: BigBallSysCmdMsgConsumer.doRevokeMsg(...)。
     *
     * 微信就是在这里删除原消息、改写会话记录并清理本地媒体的。只还原数据库字段救不回
     * 「撤回前根本没下载过」的图片和语音, 所以直接跳过这个方法: 微信压根不认为发生过撤回,
     * 拦截后的消息才和没撤回时完全一样 —— 图片能打开、语音能播放, 且什么时候点都行。
     */
    private val methodRevokeHandler by dexMethod {
        matcher {
            usingEqStrings("doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s")
        }
    }

    private val badgeCircleLight = "#FFCDD2".toColorInt()
    private val badgeIconDark = "#EF9A9A".toColorInt()
    private val badgeCircleDark = "#3E1C1C".toColorInt()

    private class RecallBadge(
        val view: View,
        val isSelfSender: Boolean,
        var anchor: View,
        val preDrawListener: ViewTreeObserver.OnPreDrawListener,
        val rowLocation: IntArray = IntArray(2),
        val anchorLocation: IntArray = IntArray(2),
    )

    private fun encodeKey(talker: String, serverId: Long) = "$talker$RECORD_SEPARATOR$serverId"

    /**
     * SQLite 取出来的值类型不固定（Boolean / Byte / Int / Long / String 都可能），
     * 直接 `as? Number` 在某些列上会得到 null，导致「是不是自己发的」判断失效 ——
     * 表现出来就是关了「防撤回自己的消息」仍然拦自己的撤回。这里统一兜住。
     */
    private fun asSelfSent(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value == "1" || value.equals("true", ignoreCase = true)
        else -> false
    }

    override fun onEnable() {
        recalledKeyCache = recalledRecords
        WeXmlParserApi.addListener(this)
        WeChatMessageViewApi.addListener(this)
        WeDatabaseListenerApi.addListener(this)
        hookRevokeHandler()
        WeLogger.i(TAG, "enabled, style=$displayStyle, recallOutgoing=$recallOutgoing")
    }

    /**
     * 拦住微信真正的撤回处理。
     *
     * 之前只有「XML 解析后置空 type」+「数据库改写还原」两层, 消息虽然留在会话里, 但微信已经把
     * 原消息判成撤回了: 图片点开提示「图片已被清理」、语音播放失败（尤其是撤回前没点开过的媒体,
     * 本地根本没有文件, 还原字段也下载不回来）。这里把处理入口整个跳过才是真正的防撤回。
     */
    private fun hookRevokeHandler() {
        runCatching {
            methodRevokeHandler.hookBefore {
                runCatching {
                    val srvId = args.firstOrNull { it is Long } as? Long ?: 0L
                    if (shouldBlockRevoke(srvId)) {
                        result = null
                        WeLogger.i(TAG, "blocked revoke handler (msgSvrId=$srvId)")
                        // 跳过微信的处理后, 它连「这条消息被撤回了」都不会再记录, 所以标记要我们自己补。
                        runCatching { markRecalled(srvId) }
                            .onFailure { WeLogger.w(TAG, "markRecalled failed: ${it.message}") }
                    } else {
                        WeLogger.i(TAG, "let self revoke pass (msgSvrId=$srvId)")
                    }
                }.onFailure { WeLogger.w(TAG, "revoke handler hook body failed: ${it.message}") }
            }
        }.onFailure { WeLogger.e(TAG, "hook doRevokeMsg failed", it) }
    }

    /**
     * 微信被跳过之后不会再有「撤回」这一步, 徽标 / 旧版提示必须由我们自己标记。
     * 这里按 msgSvrId 反查原消息, 取出会话与发送时间。
     */
    private fun markRecalled(msgSvrId: Long) {
        if (msgSvrId == 0L) return
        val row = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT msgId,talker,createTime,isSend,content FROM message WHERE msgSvrId = ?",
                arrayOf<Any>(msgSvrId.toString())
            ).firstOrNull()
        }.getOrNull() ?: run {
            WeLogger.w(TAG, "markRecalled: original message not found (msgSvrId=$msgSvrId)")
            return
        }

        val talker = row["talker"] as? String ?: return
        val msgId = (row["msgId"] as? Number)?.toLong() ?: 0L
        val createTime = (row["createTime"] as? Number)?.toLong() ?: 0L
        val isSend = asSelfSent(row["isSend"])
        val content = row["content"] as? String ?: ""

        // 「防撤回自己的消息」没开时, 自己撤回既不该拦, 也不该插提示。
        // 放在最前面兜住所有调用路径（doRevokeMsg 放行判断、XML 路径、数据库兜底）。
        if (isSend && !recallOutgoing) {
            WeLogger.i(TAG, "markRecalled: self revoke allowed, skip (msgSvrId=$msgSvrId)")
            return
        }

        if (displayStyle == STYLE_LEGACY) {
            // 旧版样式: 插一条灰色系统提示, 原消息保持不动。
            if (msgId == 0L || !noticeNotified.add(msgId)) return
            val senderName = runCatching {
                if (isSend) "自己"
                else if (talker.isGroupChatWxId) "" else WeDatabaseApi.getDisplayName(talker)
            }.getOrNull().orEmpty().ifBlank { if (isSend) "自己" else "有人" }
            WeMessageApi.createSimpleMsgInfoAndInsert(
                MessageType.SYSTEM.code,
                talker,
                buildLegacyNotice(senderName, createTime, content),
                createTime + 1
            )
            WeLogger.i(TAG, "legacy notice inserted for msgSvrId=$msgSvrId")
            return
        }

        // 新版徽标样式: 记进持久化列表 + 立刻刷新已经绑定的气泡。
        val key = encodeKey(talker, msgSvrId)
        recalledKeyCache = recalledKeyCache + key
        recalledRecords = recalledKeyCache
        refreshBoundViews(talker, msgSvrId)
        WeLogger.i(TAG, "marked recalled: $key")
    }

    /** 自己的消息是否也要拦：由「防撤回自己的消息」开关决定；查不到原消息时按「拦」处理。 */
    private fun shouldBlockRevoke(msgSvrId: Long): Boolean {
        if (msgSvrId == 0L) return true
        val isSend = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT isSend FROM message WHERE msgSvrId = ?",
                arrayOf<Any>(msgSvrId.toString())
            ).firstOrNull()?.get("isSend")?.let { asSelfSent(it) }
        }.getOrNull()
        return when (isSend) {
            null -> true
            true -> recallOutgoing
            false -> true
        }
    }

    override fun onDisable() {
        WeXmlParserApi.removeListener(this)
        WeChatMessageViewApi.removeListener(this)
        WeDatabaseListenerApi.removeListener(this)
        rawRowCache.clear()
        noticeNotified.clear()
    }

    // ── 防撤回自己的消息（数据库更新层） ─────────────────────────────────────────
    //
    // 自己撤回走的是本地库改写（把消息行改成「你撤回了一条消息」），不会产生
    // sysmsg.revokemsg，所以上面那条 XML 分支对它无效。这里挂在 message 表的 update 上：
    // 认出撤回类更新后，把待写入的值还原成撤回前的原始值，等效阻止撤回。
    private val rawRowCache = ConcurrentHashMap<Long, Map<String, Any?>>()

    /** 数据库兜底路径下已经插过提示的消息，避免同一条撤回被改写多次时插出多条提示。 */
    private val noticeNotified: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int,
    ) {
        if (table != "message") return
        runCatching { interceptRecallUpdate(values, whereClause, whereArgs) }
            .onFailure { WeLogger.e(TAG, "recall intercept (db) failed", it) }
    }

    /**
     * 数据库兜底：微信收到撤回后，会把那条消息改写成「xxx撤回了一条消息」。
     * 这里认出这种改写、把待写入的值还原成撤回前的原始内容，等效阻止撤回。
     *
     * 这条路径与 XML 那条互相独立：XML 拦住了就不会有这次改写；XML 没拦住（例如系统版本
     * 改动导致解析钩子失效）时，这里还能把消息救回来，不至于「开了等于没开」。
     */
    private fun interceptRecallUpdate(
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
    ) {
        val content = runCatching { values.getAsString("content") }.getOrNull().orEmpty()
        if (!content.contains("撤回") && !content.contains("revokemsg", ignoreCase = true)) return

        val msgId = extractMsgId(whereClause, whereArgs) ?: return
        val raw = rawRowCache[msgId] ?: queryRawRow(msgId)?.also { rawRowCache[msgId] = it } ?: return
        // 原始行本来就是系统消息 → 不是「正常消息被改写」，不要动。
        if ((raw["type"] as? Number)?.toInt() == MessageType.SYSTEM.code) return

        val isSend = asSelfSent(raw["isSend"])
        if (isSend && !recallOutgoing) {
            WeLogger.i(TAG, "self revoke allowed (recallOutgoing=false), skip: msgId=$msgId")
            return
        }

        restoreRow(values, raw)

        val talker = raw["talker"] as? String
        val serverId = (raw["msgSvrId"] as? Number)?.toLong() ?: 0L
        val createTime = (raw["createTime"] as? Number)?.toLong() ?: 0L

        if (displayStyle == STYLE_LEGACY) {
            if (!talker.isNullOrEmpty() && noticeNotified.add(msgId)) {
                val senderName = NAME_REGEX.find(content)?.groupValues?.get(2)
                    ?: runCatching {
                        if (isSend) "自己"
                        else if (!talker.isGroupChatWxId) WeDatabaseApi.getDisplayName(talker)
                        else ""
                    }.getOrNull().orEmpty().ifBlank { if (isSend) "自己" else "有人" }
                WeMessageApi.createSimpleMsgInfoAndInsert(
                    MessageType.SYSTEM.code,
                    talker,
                    buildLegacyNotice(senderName, createTime, ""),
                    createTime + 1
                )
            }
        } else if (serverId != 0L && !talker.isNullOrEmpty()) {
            val key = encodeKey(talker, serverId)
            recalledKeyCache = recalledKeyCache + key
            recalledRecords = recalledKeyCache
            refreshBoundViews(talker, serverId)
        }

        WeLogger.i(TAG, "blocked recall via database (msgId=$msgId, isSend=$isSend)")
    }

    private fun extractMsgId(whereClause: String?, whereArgs: Array<String>?): Long? {
        if (whereClause == null || whereArgs == null) return null
        if (!whereClause.contains("msgId", ignoreCase = true)) return null
        return whereArgs.firstOrNull()?.toLongOrNull()
    }

    private fun queryRawRow(msgId: Long): Map<String, Any?>? =
        runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT * FROM message WHERE msgId = ?",
                arrayOf<Any>(msgId.toString())
            ).firstOrNull()
        }.getOrNull()

    /**
     * 撤回改写会动很多列: 除了 content / type, 还会清掉 imgPath、lvbuffer（图片与语音的媒体信息、
     * 下载状态都在这里）、msgSource、flag 等。
     *
     * 只还原白名单里的几列时, 会话里能看到的文字是回来了, 但媒体那层读到的仍是「已被清理」——
     * 图片点开提示「图片已被清理」、语音播放失败。所以这里按撤回前的整行快照还原所有列。
     */
    private fun restoreRow(values: ContentValues, raw: Map<String, Any?>) {
        var restored = 0
        for ((key, v) in raw) {
            if (key.equals("msgId", ignoreCase = true)) continue
            if (v == null) continue
            when (v) {
                is Long -> { values.put(key, v); restored++ }
                is Int -> { values.put(key, v); restored++ }
                is Short -> { values.put(key, v.toInt()); restored++ }
                is Byte -> { values.put(key, v.toInt()); restored++ }
                is String -> { values.put(key, v); restored++ }
                is Double -> { values.put(key, v); restored++ }
                is Float -> { values.put(key, v); restored++ }
                is ByteArray -> { values.put(key, v); restored++ }
                is Boolean -> { values.put(key, v); restored++ }
            }
        }
        WeLogger.i(TAG, "restoreRow: restored $restored columns from snapshot")
        logMediaAvailability(raw)
    }

    /**
     * 还原后确认图片 / 语音的本地文件是否还在。
     * 日志里 `media exists=false` 说明微信在撤回时把文件删了（只还原数据库救不回来）;
     * `media exists=true` 说明文件还在, 之前打不开只是字段被清空。
     */
    private fun logMediaAvailability(raw: Map<String, Any?>) {
        val path = raw["imgPath"] as? String ?: return
        if (path.isBlank()) return
        runCatching {
            WeLogger.i(TAG, "restoreRow: media exists=${java.io.File(path).exists()} path=$path")
        }
    }

    /** 撤回通知刚到、数据库还没被改写时, 把整行快照留给后面的数据库兜底还原用。 */
    private fun cacheRawSnapshot(serverId: Long) {
        if (serverId == 0L) return
        runCatching {
            val row = WeDatabaseApi.executeQuery(
                "SELECT * FROM message WHERE msgSvrId = ?",
                arrayOf<Any>(serverId.toString())
            ).firstOrNull() ?: return
            val msgId = (row["msgId"] as? Number)?.toLong() ?: return
            rawRowCache[msgId] = row
        }.onFailure { WeLogger.w(TAG, "cacheRawSnapshot failed: ${it.message}") }
    }

    override fun onParse(param: XC_MethodHook.MethodHookParam, result: MutableMap<String, Any?>) {
        val args = param.args
        val xmlContent = args[0] as? String ?: ""
        val rootTag = args[1] as? String ?: ""

        if (rootTag != "sysmsg" || !xmlContent.contains("revokemsg")) {
            return
        }

        WeLogger.i(TAG, "xml revokemsg seen, type=${result[TYPE_KEY]}")

        if (result[TYPE_KEY] == "revokemsg") {
            val cursor = WeDatabaseApi.rawQuery(
                "SELECT type,content,talker,createTime,lvbuffer,msgId,msgSvrId,isSend FROM message WHERE msgSvrId = ?",
                arrayOf(result[".sysmsg.revokemsg.newmsgid"] as? String? ?: return)
            )

            cursor.use { cursor ->
                if (cursor.moveToFirst()) {
                    val msgInfo = MessageInfo(WeMessageApi.convertMsgInfoInstanceFromCursor(cursor))

                    // 直接读数据库的 isSend 列判断, 不依赖 MessageInfo 的反射字段（反射取不到时会
                    // 默认成 0, 把「自己撤回」误判成「别人撤回」, 于是多插一条旧版提示）。
                    // 两路取「或」：任一来源表明是自己发的就按自己处理，宁可放过也不误插提示。
                    val isSelfSentRow = msgInfo.isSelfSender || runCatching {
                        cursor.getInt(cursor.getColumnIndexOrThrow("isSend")) == 1
                    }.getOrDefault(false)
                    if (isSelfSentRow && !recallOutgoing) {
                        WeLogger.i(TAG, "sender is self and not recall outgoing, skipping (xml path)")
                        return
                    }

                    // 此刻数据库还是撤回前的样子, 先把整行存下来, 后面即使微信改写了也能原样还原。
                    runCatching { cacheRawSnapshot(msgInfo.serverId) }

                    result[TYPE_KEY] = null

                    if (displayStyle == STYLE_LEGACY) {
                        // 旧版样式：插一条灰色系统提示，原消息保持不动（不加徽标、不变淡）。
                        val senderName = resolveSenderName(msgInfo, result)
                        val noticeText =
                            buildLegacyNotice(senderName, msgInfo.createTime, msgInfo.humanReadableRepr)
                        WeMessageApi.createSimpleMsgInfoAndInsert(
                            MessageType.SYSTEM.code,
                            msgInfo.talker,
                            noticeText,
                            msgInfo.createTime + 1
                        )
                        WeLogger.i(TAG, "blocked message revoke (legacy notice inserted)")
                    } else {
                        val key = encodeKey(msgInfo.talker, msgInfo.serverId)
                        recalledKeyCache = recalledKeyCache + key
                        recalledRecords = recalledKeyCache

                        WeLogger.i(TAG, "recorded message revoke: $key")
                        refreshBoundViews(msgInfo)
                    }
                }
            }
        }
    }

    /** 旧版灰色小字提示的最终文案（XML 路径与数据库兜底路径共用）。 */
    private fun buildLegacyNotice(senderName: String, createTime: Long, contentText: String): String {
        val timeFormat = legacyTimeFormat
        return legacyPattern
            .replace("\$sender", senderName)
            .replace("\$sendTime", formatTime(createTime, timeFormat))
            .replace("\$recallTime", formatTime(System.currentTimeMillis(), timeFormat))
            .replace("\$content", contentText)
    }

    /** 用户自定义的时间模板可能写错，格式化必须兜底，不能让整条提示插不进去。 */
    private fun formatTime(epochSeconds: Long, pattern: String): String =
        runCatching { formatEpoch(epochSeconds, pattern) }
            .getOrElse { formatEpoch(epochSeconds, LEGACY_TIME_FORMAT) }

    // 旧版提示里的发送者名字：优先用消息自己的发送者取真实显示名——revokemsg 里的名字
    // 在群里经常是「微信用户」这种占位，直接拿来会张冠李戴。
    private fun resolveSenderName(
        msgInfo: MessageInfo,
        result: Map<String, Any?>,
    ): String {
        val replaceMsg = result[".sysmsg.revokemsg.replacemsg"] as? String?
        val nameFromXml = NAME_REGEX.find(replaceMsg.orEmpty())?.groupValues?.get(2).orEmpty()
        val fallback = nameFromXml.ifBlank { if (msgInfo.isSelfSender) "自己" else "有人" }
        return runCatching {
            // 自己撤回时必须显示「自己」——私聊里 talker 就是对方，按 talker 取名会把
            // 「你自己撤回」写成「对方撤回」，这就是旧版提示里名字对不上的原因。
            if (msgInfo.isSend != 0) return@runCatching "自己"
            val senderWxId = msgInfo.sender
            when {
                senderWxId.isBlank() -> fallback
                msgInfo.talker.isGroupChatWxId ->
                    WeDatabaseApi.getGroupMemberDisplayName(msgInfo.talker, senderWxId)
                        .ifBlank { fallback }

                else -> WeDatabaseApi.getDisplayName(msgInfo.talker).ifBlank { fallback }
            }
        }.getOrElse { fallback }
    }

    // The recall usually arrives while the message row is on screen; restyle it immediately
    // instead of waiting for the next rebind.
    private fun refreshBoundViews(msgInfo: MessageInfo) {
        WeChatMessageViewApi.findBoundViews {
            it.talker == msgInfo.talker && it.serverId == msgInfo.serverId
        }.forEach { (view, boundMsgInfo) ->
            // onParse may run off the main thread; re-check the binding inside the posted block so
            // a view recycled in between is never styled for the wrong message.
            view.post {
                val current = WeChatMessageViewApi.getBoundMessage(view) ?: return@post
                if (current.instance === boundMsgInfo.instance) {
                    applyRecallStyle(view, current)
                }
            }
        }
    }

    private fun refreshBoundViews(talker: String, serverId: Long) {
        WeChatMessageViewApi.findBoundViews {
            it.talker == talker && it.serverId == serverId
        }.forEach { (view, _) ->
            view.post { applyRecallStyle(view, WeChatMessageViewApi.getBoundMessage(view) ?: return@post) }
        }
    }

    override fun onCreateView(param: XC_MethodHook.MethodHookParam, view: View) {
        if (displayStyle == STYLE_LEGACY) {
            // 旧版样式不画徽标；顺手清掉切换样式前残留的徽标。
            clearRecallStyle(view)
            return
        }
        val msgInfo = runCatching { WeChatMessageViewApi.getMsgInfoFromParam(param) }.getOrNull()
            ?: return
        clearRecallStyle(view)
        if (isRecalled(msgInfo)) {
            applyRecallStyle(view, msgInfo)
        }
    }

    private fun isRecalled(msgInfo: MessageInfo): Boolean =
        encodeKey(msgInfo.talker, msgInfo.serverId) in recalledKeyCache

    // ── recalled-message styling ──────────────────────────────────────────────

    private fun applyRecallStyle(view: View, msgInfo: MessageInfo) {
        runCatching {
            val contentView = findMessageContent(view) ?: return
            val previous = dimmedContentViews.put(view, contentView)
            if (previous != null && previous !== contentView) previous.alpha = 1f
            contentView.alpha = RECALL_ALPHA

            attachRecallBadge(view, msgInfo.isSelfSender, contentView)
        }.onFailure { WeLogger.w(TAG, "applyRecallStyle failed: ${it.message}") }
    }

    private fun clearRecallStyle(view: View) {
        runCatching {
            dimmedContentViews.remove(view)?.alpha = 1f
            removeRecallBadge(view)
        }.onFailure { WeLogger.w(TAG, "clearRecallStyle failed: ${it.message}") }
    }

    // Every classic chat holder in the supported host range inherits getMainContainerView(), and
    // most holders override it with the semantic bubble/card container. Some Mvvm holders leave
    // the inherited clickArea null and attach their ItemDataTag to the dynamically-created content
    // view instead, so fall back to the first visible tagged view across the whole row. Searching
    // the whole row avoids depending on message-specific LinearLayout ordering while visibility
    // excludes hidden send-status views that can carry the same tag.
    private fun findMessageContent(view: View): View? {
        val holder = view.tag
        // 上游依赖 holder 继承的 getMainContainerView()；微信改了 holder 结构时它可能不存在，
        // 这里必须容忍失败，否则整个上色/挂徽标流程会被打断（异常被 Xposed 吞掉，看起来就是没效果）。
        val mainContainer = holder?.let {
            runCatching {
                it.reflekt()
                    .firstMethodOrNull {
                        name = "getMainContainerView"
                        parameterCount = 0
                        superclass()
                    }
                    ?.invoke() as? View
            }.getOrNull()
        }
        return mainContainer ?: view.findViewWhich {
            it.isVisible && isTaggedBubbleView(it)
        }
    }

    private fun isTaggedBubbleView(view: View): Boolean =
        view.tag?.javaClass?.name?.startsWith("com.tencent.mm.ui.chatting.viewitems") == true

    private fun attachRecallBadge(row: View, isSelfSender: Boolean, anchor: View) {
        if (badges.containsKey(row)) return

        val rowGroup = row as? ViewGroup ?: return
        val context = row.context
        val dark = context.isDarkMode
        val iconColor = if (dark) badgeIconDark else badgeIconLight
        val circleColor = if (dark) badgeCircleDark else badgeCircleLight
        val badgeView: View = if (badgeType == BADGE_TYPE_TEXT) {
            TextView(context).apply {
                text = BADGE_TEXT
                textSize = 12f
                setTextColor(iconColor)
                includeFontPadding = false
                val hPad = BADGE_PADDING_DP.dpToPx(context)
                val vPad = (BADGE_PADDING_DP / 2).dpToPx(context)
                setPadding(hPad, vPad, hPad, vPad)
                background = GradientDrawable().apply {
                    cornerRadius = (BADGE_SIZE_DP / 2).dpToPx(context).toFloat()
                    setColor(circleColor)
                }
            }
        } else {
            RecallBadgeIconView(context, iconColor, circleColor)
        }
        // Not a child of the dimmed content row, so it must dim itself.
        badgeView.alpha = RECALL_ALPHA
        val size = BADGE_SIZE_DP.dpToPx(context)
        val badgeWidth =
            if (badgeType == BADGE_TYPE_TEXT) ViewGroup.LayoutParams.WRAP_CONTENT else size
        val badgeHeight =
            if (badgeType == BADGE_TYPE_TEXT) ViewGroup.LayoutParams.WRAP_CONTENT else size
        // The chat row root is a RelativeLayout on the supported host versions; fall back to the
        // parent's own LayoutParams type elsewhere, since a mismatched type crashes on measure.
        rowGroup.addView(badgeView, badgeLayoutParams(rowGroup, badgeWidth, badgeHeight))

        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            badges[row]?.let { positionRecallBadge(row, it) }
            true
        }
        badges[row] = RecallBadge(badgeView, isSelfSender, anchor, preDrawListener)
        row.viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    private fun badgeLayoutParams(parent: ViewGroup, width: Int, height: Int): ViewGroup.LayoutParams =
        when (parent) {
            is RelativeLayout -> RelativeLayout.LayoutParams(width, height)
            is LinearLayout -> LinearLayout.LayoutParams(width, height)
            is FrameLayout -> FrameLayout.LayoutParams(width, height)
            else -> ViewGroup.LayoutParams(width, height)
        }

    private fun removeRecallBadge(row: View) {
        val badge = badges.remove(row) ?: return
        row.viewTreeObserver.removeOnPreDrawListener(badge.preDrawListener)
        (row as? ViewGroup)?.removeView(badge.view)
    }

    // The badge sits fully outside the bubble, bottom-aligned with it, clamped inside the row:
    // on the bubble's right for received messages, mirrored to the bubble's left for self-sent
    // ones (bubble and avatar are right-aligned there). Reconciled before every draw from the
    // anchor's absolute position — never from cached field offsets, because other features move
    // the content column within the row (avatar/name show-hide) without touching the anchor's
    // own coordinates. The per-frame cost is two matrix walks over preallocated arrays plus a
    // couple of float compares; the write only happens on an actual change, and the DFS
    // re-anchor only runs when the current anchor view went away.
    private fun positionRecallBadge(row: View, badge: RecallBadge) {
        if (!row.isAttachedToWindow || row.width == 0) return
        var anchor = badge.anchor
        if (!anchor.isAttachedToWindow || anchor.width == 0) {
            anchor = findMessageContent(row) ?: return
            badge.anchor = anchor
        }
        val badgeView = badge.view
        val badgeWidth = badgeView.measuredWidth
        val badgeHeight = badgeView.measuredHeight
        if (badgeWidth == 0 || badgeHeight == 0) return

        row.getLocationOnScreen(badge.rowLocation)
        anchor.getLocationOnScreen(badge.anchorLocation)
        val anchorLeft = badge.anchorLocation[0] - badge.rowLocation[0]
        val anchorRight = anchorLeft + anchor.width
        val anchorBottom = badge.anchorLocation[1] - badge.rowLocation[1] + anchor.height
        val gap = BADGE_GAP_DP.dpToPx(row.context).toFloat()
        val rowRightLimit = (row.width - badgeWidth).toFloat()
        val targetX = if (badge.isSelfSender) {
            (anchorLeft - gap - badgeWidth).coerceIn(0f, rowRightLimit)
        } else {
            (anchorRight + gap).coerceIn(0f, rowRightLimit)
        }
        val targetY = (anchorBottom - badgeHeight)
            .toFloat()
            .coerceIn(0f, (row.height - badgeHeight).toFloat())
        if (badgeView.translationX != targetX || badgeView.translationY != targetY) {
            badgeView.translationX = targetX
            badgeView.translationY = targetY
        }
    }

    // Same icon style as SwipeMessageOperations' swipe action icons: a tinted vector glyph on a
    // circular backdrop, in red to mark the message as deleted.
    @SuppressLint("AppCompatCustomView")
    private class RecallBadgeIconView(
        context: Context,
        iconColor: Int,
        circleColor: Int,
    ) : ImageView(context) {
        init {
            scaleType = ScaleType.CENTER_INSIDE
            // A fresh drawable per badge, so the tint never leaks into another badge.
            val drawable = object : VectorPathDrawable(DELETE_ICON_PATH) {}
            drawable.colorFilter = PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
            setImageDrawable(drawable)
            imageTintList = null
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(circleColor)
            }
            val pad = BADGE_PADDING_DP.dpToPx(context)
            setPadding(pad, pad, pad, pad)
        }
    }

    // ── legacy notice migration ────────────────────────────────────────────────

    private sealed interface MigrationState {
        data object Idle : MigrationState
        data object Running : MigrationState
        data class Progress(val done: Int, val total: Int) : MigrationState
        data class Done(val migrated: Int, val unmatched: Int) : MigrationState
        data object Failed : MigrationState
    }

    private data class LegacyNotice(val msgId: Long, val talker: String, val createTime: Long)

    /**
     * Finds the system-message rows rendered from the old notice template, records their original
     * messages into the new persisted list, and deletes the notice rows.
     */
    private fun migrateLegacyNotices(onProgress: (Int, Int) -> Unit = { _, _ -> }): Pair<Int, Int> {
        // The 提示格式 option was removed; the last stored template survives in MMKV and is what
        // old notices were rendered with. The MMKV key only exists if the user ever saved a custom
        // value (defaults are applied at read time), so fall back to the historical default.
        val template = WePrefs.getString("recall_pattern")
            ?: "「\$sender」尝试撤回上一条消息 (已阻止)"
        val regex = buildLegacyNoticeRegex(template)
            ?: throw IllegalStateException("invalid stored recall pattern")

        val notices = mutableListOf<LegacyNotice>()
        // 只扫最近一批系统消息：message 表在长期使用的账号上非常大，全表扫描会让按钮
        // 长时间停在「正在迁移…」。撤回提示本来就是近期的东西，取最近的够用。
        WeDatabaseApi.rawQuery(
            "SELECT msgId, talker, content, createTime FROM message " +
                "WHERE type = ${MessageType.SYSTEM.code} " +
                "ORDER BY createTime DESC LIMIT $MIGRATION_SCAN_LIMIT",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val content = cursor.getString(2) ?: continue
                if (!regex.matches(content)) continue
                notices += LegacyNotice(
                    msgId = cursor.getLong(0),
                    talker = cursor.getString(1) ?: continue,
                    createTime = cursor.getLong(3),
                )
            }
        }

        var migrated = 0
        var unmatched = 0
        val newKeys = mutableSetOf<String>()
        notices.forEachIndexed { index, notice ->
            val originalServerId = findOriginalMessage(notice)
            if (originalServerId == null) {
                unmatched++
            } else {
                newKeys += encodeKey(notice.talker, originalServerId)
                WeDatabaseApi.delete("message", "msgId = ?", arrayOf(notice.msgId.toString()))
                migrated++
            }
            onProgress(index + 1, notices.size)
        }

        if (newKeys.isNotEmpty()) {
            recalledKeyCache = recalledKeyCache + newKeys
            recalledRecords = recalledKeyCache
        }
        return migrated to unmatched
    }

    // Legacy notices were injected at original.createTime + 1 (createTime is in seconds), so the
    // original lives one second before the notice in the same chat; fall back to the nearest
    // earlier regular message when that exact second has no surviving row.
    @Suppress("DEPRECATION")
    private fun findOriginalMessage(notice: LegacyNotice): Long? {
        val systemTypes = listOf(
            MessageType.SYSTEM.code,
            MessageType.SYSTEM_NOTICE.code,
            MessageType.RECALL.code,
            MessageType.SYSTEM_LOCATION.code,
        ).joinToString(",")
        val beforeNotice = (notice.createTime - 1).toString()

        WeDatabaseApi.rawQuery(
            "SELECT msgSvrId FROM message WHERE talker = ? AND createTime = ? AND type NOT IN ($systemTypes) ORDER BY msgId DESC LIMIT 1",
            arrayOf(notice.talker, beforeNotice)
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0).takeIf { it != 0L }
        }
        WeDatabaseApi.rawQuery(
            "SELECT msgSvrId FROM message WHERE talker = ? AND createTime < ? AND type NOT IN ($systemTypes) ORDER BY createTime DESC, msgId DESC LIMIT 1",
            arrayOf(notice.talker, beforeNotice)
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0).takeIf { it != 0L }
        }
        return null
    }

    private fun buildLegacyNoticeRegex(template: String): Regex? {
        if (template.isBlank()) return null

        val placeholders = listOf("\$sender", "\$sendTime", "\$recallTime", "\$content")
        val builder = StringBuilder("^")
        var rest = template
        while (rest.isNotEmpty()) {
            val match = placeholders
                .mapNotNull { placeholder ->
                    rest.indexOf(placeholder).takeIf { it >= 0 }?.let { placeholder to it }
                }
                .minByOrNull { it.second }
            if (match == null) {
                builder.append(Regex.escape(rest))
                break
            }
            val (placeholder, index) = match
            if (index > 0) builder.append(Regex.escape(rest.substring(0, index)))
            builder.append(if (placeholder == "\$content") """[\s\S]*""" else """[\s\S]+?""")
            rest = rest.substring(index + placeholder.length)
        }
        builder.append('$')

        return runCatching { Regex(builder.toString()) }.getOrNull()
    }

    // ── config dialog ──────────────────────────────────────────────────────────

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var recallOutgoingInput by remember { mutableStateOf(recallOutgoing) }
            var legacyInput by remember { mutableStateOf(recallLegacyNotice) }
            var badgeTypeInput by remember { mutableStateOf(badgeType) }
            var patternInput by remember { mutableStateOf(legacyPattern) }
            var timeFormatInput by remember { mutableStateOf(legacyTimeFormat) }
            var migrationState by remember { mutableStateOf<MigrationState>(MigrationState.Idle) }

            // 自己排版的弹窗：标题固定、正文可滚动、按钮固定。
            // 用 AlertDialogContent 时正文区高度写死且标题和正文挤在一起，
            // 设置项一多就既滑不动、又像是标题压住了内容。
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "防撤回",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    HorizontalDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = "防撤回自己的消息",
                                    description = "是否对自己发出的消息也生效",
                                    checked = recallOutgoingInput,
                                    onCheckedChange = {
                                        recallOutgoingInput = it
                                        recallOutgoing = it
                                    },
                                )
                            }
                            item {
                                DropDownMenuWidget(
                                    iconPlaceholder = false,
                                    title = "徽标类型",
                                    description = if (legacyInput) "当前用旧版样式，此项不生效" else null,
                                    value = badgeTypeInput,
                                    options = listOf(
                                        DropdownOption(BADGE_TYPE_ICON, "图标"),
                                        DropdownOption(BADGE_TYPE_TEXT, "文本"),
                                    ),
                                    enabled = !legacyInput,
                                    onValueChange = {
                                        badgeTypeInput = it
                                        badgeType = it
                                    },
                                )
                            }
                            item {
                                val migrating = migrationState is MigrationState.Running ||
                                    migrationState is MigrationState.Progress
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = "迁移旧版撤回提示",
                                    description = when (val state = migrationState) {
                                        MigrationState.Idle -> "把旧版插入的系统提示转成新样式并删除旧提示（单次最多处理最近 $MIGRATION_SCAN_LIMIT 条）；重新进入会话后生效"
                                        MigrationState.Running -> "正在迁移…"
                                        is MigrationState.Progress ->
                                            "正在迁移… ${state.done}/${state.total}"
                                        is MigrationState.Done ->
                                            "已迁移 ${state.migrated} 条提示，${state.unmatched} 条未能定位原消息"
                                        MigrationState.Failed -> "迁移失败"
                                    },
                                    isError = migrationState is MigrationState.Failed,
                                    enabled = !migrating,
                                    onClick = {
                                        migrationState = MigrationState.Running
                                        CoroutineScope(Dispatchers.IO).launch {
                                            val outcome = runCatching {
                                                migrateLegacyNotices { done, total ->
                                                    mainHandler.post {
                                                        migrationState =
                                                            MigrationState.Progress(done, total)
                                                    }
                                                }
                                            }
                                            withContext(Dispatchers.Main) {
                                                migrationState = outcome.fold(
                                                    onSuccess = { (migrated, unmatched) ->
                                                        MigrationState.Done(migrated, unmatched)
                                                    },
                                                    onFailure = {
                                                        WeLogger.e(
                                                            TAG,
                                                            "legacy notice migration failed",
                                                            it
                                                        )
                                                        MigrationState.Failed
                                                    },
                                                )
                                            }
                                        }
                                    },
                                    trailingContent = {
                                        if (migrating) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                        }
                                    },
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = "旧版样式：插入系统消息提示",
                                    description = "开启后改用最开始那版防撤回：在会话里插一条灰色系统提示（与上面的徽标样式互斥，同时只能用一种）",
                                    checked = legacyInput,
                                    onCheckedChange = {
                                        legacyInput = it
                                        recallLegacyNotice = it
                                        if (it) {
                                            // 切到旧版样式：把已经画上去的徽标清掉。
                                            WeChatMessageViewApi.findBoundViews { true }
                                                .forEach { (view, _) -> view.post { clearRecallStyle(view) } }
                                        }
                                    },
                                )
                            }
                            item(animatedVisibility = false) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        "旧版提示内容",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (legacyInput) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "占位符：\$sender 昵称、\$sendTime 发送时间、\$recallTime 撤回时间、\$content 消息内容",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    OutlinedTextField(
                                        value = patternInput,
                                        onValueChange = {
                                            patternInput = it
                                            legacyPattern = it
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp),
                                        enabled = legacyInput,
                                        textStyle = MaterialTheme.typography.bodyMedium,
                                        shape = MaterialTheme.shapes.small,
                                    )
                                    Text(
                                        "时间格式",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (legacyInput) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 12.dp),
                                    )
                                    Text(
                                        "例如 yyyy/MM/dd HH:mm:ss，留空则用默认格式",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    OutlinedTextField(
                                        value = timeFormatInput,
                                        onValueChange = {
                                            timeFormatInput = it
                                            legacyTimeFormat = it.ifBlank { LEGACY_TIME_FORMAT }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp),
                                        enabled = legacyInput,
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium,
                                        shape = MaterialTheme.shapes.small,
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onDismiss) { Text("关闭") }
                    }
                }
            }
        }
    }
}
