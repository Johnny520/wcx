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
    description = "在联系人详情页显示成为好友的日期与天数；微信没记录时按最早聊天记录估算",
)
object ShowFriendAddedTime : SwitchFeature(), WeContactPrefsScreenApi.IContactInfoProvider {

    private const val TAG = "ShowFriendAddedTime"
    private const val KEY = "wcx_friend_added_time"
    private const val EXTRA_CONTACT_USER = "Contact_User"

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** rcontact 里存放「添加时间」的列名；第一次查表后缓存，避免每次进资料页都 PRAGMA。 */
    @Volatile
    private var timeColumn: String? = null

    @Volatile
    private var columnsProbed = false

    /** 添加时间的来源：精确 / 估算 / 未知，决定摘要怎么写。 */
    private enum class Source { EXACT, ESTIMATED, UNKNOWN }

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }

    override fun getContactInfoItem(activity: Activity): List<WeContactPrefsScreenApi.PreferenceItem> {
        val username = runCatching { activity.intent?.getStringExtra(EXTRA_CONTACT_USER) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        val (addedAt, source) = resolveAddedTime(username)
        val summary = when (source) {
            Source.UNKNOWN -> "未知（微信未记录）"
            else -> {
                val date = runCatching {
                    Instant.ofEpochSecond(addedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                }.getOrNull() ?: return emptyList()
                val days = ChronoUnit.DAYS.between(date, LocalDate.now()).coerceAtLeast(0)
                val text = "${date.format(dateFormatter)}（${days}天）"
                if (source == Source.ESTIMATED) "$text 按最早聊天记录估算" else text
            }
        }

        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(
                key = KEY,
                title = "添加时间",
                summary = summary,
            )
        )
    }

    /** 这一行只是展示，点击不做任何事。 */
    override fun onItemClick(activity: Activity, key: String): Boolean = false

    /**
     * 老版本微信没有记录添加时间（该字段是后来才加的），这种情况用「与该好友最早一条聊天记录」
     * 的时间做近似，文案上标明是估算，避免被当成准确值。
     */
    private fun resolveAddedTime(username: String): Pair<Long, Source> {
        queryAddedTime(username)?.let { return it to Source.EXACT }
        queryFirstMessageTime(username)?.let { return it to Source.ESTIMATED }
        return 0L to Source.UNKNOWN
    }

    private fun queryAddedTime(username: String): Long? {
        val column = resolveTimeColumn() ?: return null
        return runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT $column FROM rcontact WHERE username = ? LIMIT 1",
                arrayOf<Any>(username)
            ).firstOrNull()?.get(column)?.let { normalizeEpoch(it) }
        }.onFailure { WeLogger.w(TAG, "query added time failed: ${it.message}") }
            .getOrNull()
    }

    /** 兜底：最早一条聊天记录的时间（老版本微信没有添加时间字段时用）。 */
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
