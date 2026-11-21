package com.Johnny.wcx.features.items.chat

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import com.Johnny.wcx.utils.hookBeforeDirectly
import com.Johnny.wcx.utils.reflection.ClassLoaders
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
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

@Feature(name = "群成员行为监控", categories = ["聊天"], description = "监控群成员的退群与修改群昵称行为")
object MonitorGroupMemberOperations : SwitchFeature(), IResolveDex, WeDatabaseListenerApi.IUpdateListener {

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)

        methodHandleSpanClick.hookBefore {
            val url = args[1]!!.reflekt().firstField {
                type = BString
                modifiers(Modifiers.FINAL)
            }.get()!! as String
            if (!url.startsWith("weixin://weixinhongbao/wekit/chatroom_userinfo/")) return@hookBefore

            val wxId = url.substringAfterLast('/')
            val context = (args[0] as View).context

            WeApi.openContact(context, wxId, WeApi.OpenContactDestination.HOMEPAGE)
        }

        installLinkRenderingFallback()
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
            val content = """<_wc_custom_link_ color="#28C445" href="$href">${escapeXml(displayString)}</_wc_custom_link_> 退出了群组"""

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
            val content = """<_wc_custom_link_ color="#28C445" href="$href">${escapeXml(displayString)}</_wc_custom_link_> 修改群昵称：${escapeXml(oldShow)} → ${escapeXml(newShow)}"""

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

    // ==================== 自定义链接渲染兜底 ====================
    // 微信聊天系统消息渲染会解析 content 里的 <_wc_custom_link_> 标签并渲染成绿色可点击链接；
    // 解析失败（例如昵称含未转义的 & < > 等字符）时整条消息会原样显示出 XML 源码。
    // 这里双端保障：插入消息时对昵称文本做 XML 转义（保证生成的 XML 恒合法）；
    // 渲染时兜底解析「漏到 TextView 的原始标签」为同色可点击 span，保证界面永远不出现源码。

    @Volatile
    private var linkRenderingInstalled = false

    private const val LINK_TAG = "<_wc_custom_link_"

    private const val PROFILE_LINK_PREFIX = "weixin://weixinhongbao/wekit/chatroom_userinfo/"

    private val LINK_REGEX = Regex("""<_wc_custom_link_[^>]*>([\s\S]*?)</_wc_custom_link_>""")

    private val ATTR_REGEX = Regex("""(\w+)="([^"]*)"""")

    private val DEFAULT_LINK_COLOR = 0xFF28C445.toInt()

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun installLinkRenderingFallback() {
        if (linkRenderingInstalled) return
        runCatching {
            val bufType = Class.forName("android.widget.TextView\$BufferType", false, ClassLoaders.HOST)
            TextView::class.java.getDeclaredMethod("setText", CharSequence::class.java, bufType)
                .hookBeforeDirectly {
                    val original = args.getOrNull(0) as? CharSequence ?: return@hookBeforeDirectly
                    val raw = original.toString()
                    if (!raw.contains(LINK_TAG)) return@hookBeforeDirectly
                    val rendered = renderCustomLinks(raw) ?: return@hookBeforeDirectly
                    args[0] = rendered
                    (thisObject as? TextView)?.let { tv ->
                        if (tv.movementMethod == null) {
                            tv.movementMethod = LinkMovementMethod.getInstance()
                        }
                    }
                }
            linkRenderingInstalled = true
            WeLogger.i(TAG, "自定义链接渲染兜底已安装")
        }.onFailure { WeLogger.e(TAG, "自定义链接渲染兜底安装失败", it) }
    }

    private fun renderCustomLinks(raw: String): CharSequence? {
        return runCatching {
            val matches = LINK_REGEX.findAll(raw).toList()
            if (matches.isEmpty()) return@runCatching null
            val builder = SpannableStringBuilder()
            var cursor = 0
            for (match in matches) {
                val range = match.range
                if (range.first > cursor) builder.append(raw, cursor, range.first)
                val attrs = ATTR_REGEX.findAll(match.value)
                    .associate { it.groupValues[1] to it.groupValues[2] }
                val color = attrs["color"]
                    ?.let { runCatching { Color.parseColor(it) }.getOrNull() }
                    ?: DEFAULT_LINK_COLOR
                val href = attrs["href"].orEmpty()
                val start = builder.length
                builder.append(match.groupValues[1])
                val end = builder.length
                builder.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val wxId = href.takeIf { it.startsWith(PROFILE_LINK_PREFIX) }?.substringAfterLast('/')
                if (!wxId.isNullOrEmpty()) {
                    builder.setSpan(
                        object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                runCatching {
                                    WeApi.openContact(widget.context, wxId, WeApi.OpenContactDestination.HOMEPAGE)
                                }
                            }

                            override fun updateDrawState(ds: TextPaint) {
                                ds.color = color
                                ds.isUnderlineText = false
                            }
                        },
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                cursor = range.last + 1
            }
            if (cursor < raw.length) builder.append(raw, cursor, raw.length)
            builder
        }.getOrNull()
    }

    private const val TAG = "MonitorGroupMemberOperations"
}
