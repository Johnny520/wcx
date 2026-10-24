package com.Johnny.wcx.features.items.contacts

import android.app.Activity
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.ui.WeContactPrefsScreenApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Feature(
    name = "显示添加时间",
    categories = ["联系人与群组"],
    description = "在联系人详情页显示添加好友的日期与已添加天数；微信没记录时按最早聊天记录估算",
)
object ShowFriendAddedTime : SwitchFeature(), WeContactPrefsScreenApi.IContactInfoProvider {

    private const val TAG = "ShowFriendAddedTime"
    private const val KEY = "wcx_friend_added_time"
    /** 资料页把「当前查看谁」放在 intent 里，不同微信版本/入口的 key 不完全一致，全部试一遍。 */
    private val USERNAME_EXTRAS = listOf("Contact_User", "Chat_User", "Contact_User_Name", "Contact_UUID")

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** rcontact.type 的最低位表示「这是一个好友」。 */
    private const val CONTACT_FLAG_FRIEND = 0x1

    /** rcontact 里存放「添加时间」的列名；第一次查表后缓存，避免每次进资料页都 PRAGMA。 */
    @Volatile
    private var timeColumn: String? = null

    @Volatile
    private var columnsProbed = false

    /**
     * 添加时间的来源，决定摘要怎么写：
     * - EXACT：微信记录了添加时间
     * - ESTIMATED：没记录，用最早聊天记录估算
     * - NOT_RECORDED：当前微信有该字段，但这条好友没值（添加于微信开始记录之前）
     * - UNSUPPORTED：当前微信版本压根没有这个字段
     */
    private enum class Source { EXACT, ESTIMATED, NOT_RECORDED, NOT_FRIEND, UNSUPPORTED }

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }

    override fun getContactInfoItem(activity: Activity): List<WeContactPrefsScreenApi.PreferenceItem> {
        val username = resolveUsername(activity) ?: run {
            val keys = runCatching { activity.intent?.extras?.keySet()?.toList() }.getOrNull()
            WeLogger.w(TAG, "no contact username in intent extras: $keys")
            return emptyList()
        }

        val (addedAt, source) = resolveAddedTime(username)
        val summary = when (source) {
            Source.NOT_FRIEND -> "该好友未添加，无法查看添加时间"
            Source.UNSUPPORTED -> "当前微信版本不支持查询"
            Source.NOT_RECORDED -> "该好友添加于微信记录此时间之前，无法查询"
            else -> {
                val date = runCatching {
                    Instant.ofEpochSecond(addedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                }.getOrNull() ?: return emptyList()
                val days = ChronoUnit.DAYS.between(date, LocalDate.now()).coerceAtLeast(0)
                val text = "${date.format(dateFormatter)}（${days}天）"
                if (source == Source.ESTIMATED) "$text · 按最早聊天记录" else text
            }
        }

        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(
                key = KEY,
                title = "添加时间",
                summary = summary,
                // 期望位置：紧跟「地区」那一行（截图里的第 4 行）。
                // 微信这行的 key 各版本不同，多给几个候选；全都找不到就插到列表第一条。
                position = 0,
                afterKeyParts = listOf("area", "region", "diqu", "location"),
            )
        )
    }

    private fun resolveUsername(activity: Activity): String? {
        val intent = runCatching { activity.intent }.getOrNull() ?: return null
        for (key in USERNAME_EXTRAS) {
            val value = runCatching { intent.getStringExtra(key) }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    /** 这一行只是展示，点击不做任何事。 */
    override fun onItemClick(activity: Activity, key: String): Boolean = false

    /**
     * 老版本微信没有记录添加时间（该字段是后来才加的），这种情况用「与该好友最早一条聊天记录」
     * 的时间做近似，文案上标明是估算，避免被当成准确值。
     */
    private fun resolveAddedTime(username: String): Pair<Long, Source> {
        val row = loadContactRow(username)
        // rcontact 里根本没有这个人（陌生人 / 群成员资料页）→ 未添加
        if (row == null) return 0L to Source.NOT_FRIEND

        // type 的最低位是「好友」标志；没有这一位说明对方不是好友（群成员、陌生人等）。
        val type = (row["type"] as? Number)?.toInt() ?: 0
        val isFriend = (type and CONTACT_FLAG_FRIEND) != 0
        if (!isFriend) return 0L to Source.NOT_FRIEND

        val column = resolveTimeColumn()
        val epoch = column?.let { normalizeEpoch(row[it]) }
        if (epoch != null) return epoch to Source.EXACT

        // 是好友但没有记录：能按最早聊天记录估算就估算
        queryFirstMessageTime(username)?.let { return it to Source.ESTIMATED }
        return 0L to if (column == null) Source.UNSUPPORTED else Source.NOT_RECORDED
    }

    private fun loadContactRow(username: String): Map<String, Any?>? = runCatching {
        WeDatabaseApi.executeQuery(
            "SELECT * FROM rcontact WHERE username = ? LIMIT 1",
            arrayOf<Any>(username)
        ).firstOrNull()
    }.onFailure { WeLogger.w(TAG, "load rcontact failed: ${it.message}") }
        .getOrNull()

    /** 兜底：最早一条聊天记录的时间（老版本微信没有记录好友天数所需字段时用）。 */
    private fun queryFirstMessageTime(username: String): Long? = runCatching {
        WeDatabaseApi.executeQuery(
            "SELECT createTime FROM message WHERE talker = ? ORDER BY createTime ASC LIMIT 1",
            arrayOf<Any>(username)
        ).firstOrNull()?.get("createTime")?.let { normalizeEpoch(it) }
    }.onFailure { WeLogger.w(TAG, "query first message time failed: ${it.message}") }
        .getOrNull()

    /**
     * 微信各版本对「添加时间」的列名不完全一致，这里按常见命名依次探测，并把 rcontact 的
     * 实际列名写进日志 —— 万一都没命中，看日志就能补一个候选。
     */
    private fun resolveTimeColumn(): String? {
        if (columnsProbed) return timeColumn
        synchronized(this) {
            if (columnsProbed) return timeColumn
            columnsProbed = true

            val columns = runCatching {
                WeDatabaseApi.executeQuery("PRAGMA table_info(rcontact)")
                    .mapNotNull { it["name"] as? String }
            }.getOrNull().orEmpty()

            if (columns.isEmpty()) {
                WeLogger.w(TAG, "rcontact schema unavailable")
                return null
            }
            WeLogger.i(TAG, "rcontact columns: $columns")

            val normalized = columns.associateBy { it.replace("_", "").lowercase() }
            timeColumn = normalized["createtime"]
                ?: normalized["addtime"]
                ?: normalized["friendtime"]
                ?: normalized["addfriendtime"]
                ?: columns.firstOrNull {
                    it.replace("_", "").contains("add", true) && it.contains("time", true)
                }

            WeLogger.i(TAG, "resolved added-time column: $timeColumn")
            return timeColumn
        }
    }

    /** 微信不同版本存的是秒或毫秒，统一成秒；不像时间戳的值直接丢弃。 */
    private fun normalizeEpoch(value: Any?): Long? {
        val raw = when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        } ?: return null

        return when {
            raw in 1_000_000_000L..9_999_999_999L -> raw
            raw in 1_000_000_000_000L..9_999_999_999_999L -> raw / 1000
            else -> null
        }
    }
}
