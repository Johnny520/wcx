package com.Johnny.wcx.ai

import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 重要信息提醒：自动回复处理消息时，把「对方发来的重要内容」单独记下来。
 *
 * 为什么要单独记：自动回复会把注意力都花在「怎么答」上，对方给的截止时间、金额、
 * 地址这类硬信息很容易连同回复一起被忽略，第二天就忘。这里把它们抽出来放到 AI 首页顶部。
 *
 * 判定用规则（正则），不做额外模型请求：自动回复本身已经要发一次请求，
 * 再加一次判断会让电量和额度翻倍，而「时间 / 钱 / 截止 / 地点」用词高度固定，规则足够。
 */
@Serializable
data class AiImportantAlert(
    val id: String,
    /** 会话 id（单聊即好友 wxid）。 */
    val talker: String,
    val name: String,
    val text: String,
    val at: Long,
    val read: Boolean = false,
)

object AiImportantAlerts {

    private const val TAG = "AiImportantAlerts"

    private const val KEY = "ai_important_alerts_v1"

    /** 只留最近这么多条，避免偏好文件无限膨胀。 */
    private const val MAX_KEEP = 60

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val TIME_WORDS = Regex(
        "明天|后天|今天|大后天|下周[一二三四五六日天]?|周[一二三四五六日天]|礼拜[一二三四五六日天]" +
                "|\\d{1,2}\\s*[:：]\\s*\\d{2}|\\d{1,2}\\s*[月日号]",
    )

    private val MONEY_WORDS = Regex("\\d+(\\.\\d+)?\\s*(元|块|万|w)|转账|红包|付款|报销|发票")

    /** 命中任意一条就视为重要（这些词本身就意味着「别漏」）。 */
    private val URGENT_WORDS = Regex(
        "截止|务必|一定要|尽快|马上|最后期限|deadline|别忘|记得|过期|逾期|仅此一次|就差你",
    )

    private val PLACE_WORDS = Regex("地址|集合|地点|会议室|开会|签到|楼下|门口|定位")

    @Volatile
    private var cached: List<AiImportantAlert>? = null

    private fun readAll(): List<AiImportantAlert> {
        cached?.let { return it }
        val text = runCatching { WePrefs.getStringOrDef(KEY, "") }.getOrDefault("")
        val list = if (text.isBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<AiImportantAlert>>(text) }
                .getOrElse {
                    WeLogger.w(TAG, "重要信息解析失败，重置", it)
                    emptyList()
                }
        }
        cached = list
        return list
    }

    private fun writeAll(list: List<AiImportantAlert>) {
        cached = list
        runCatching { WePrefs.putString(KEY, json.encodeToString(list)) }
            .onFailure { WeLogger.e(TAG, "保存重要信息失败", it) }
    }

    /** 未读的重要信息，最新的在前。 */
    fun pending(): List<AiImportantAlert> = readAll().filter { !it.read }.sortedByDescending { it.at }

    fun unreadCount(): Int = readAll().count { !it.read }

    fun markAllRead() {
        val all = readAll()
        if (all.none { !it.read }) return
        writeAll(all.map { if (it.read) it else it.copy(read = true) })
    }

    /**
     * 收到对方消息时调用：命中规则就记一条。
     *
     * 同一个人同一段话只记一次：文本相同且 10 分钟内已有记录则跳过，
     * 避免对方连发几条同样的内容刷出一串提醒。
     */
    fun maybeRecord(talker: String, name: String, text: String, at: Long = System.currentTimeMillis()) {
        if (!AiConfig.isMasterEnabled) return
        val body = text.trim()
        if (body.isEmpty() || body.length < 4) return
        if (!isImportant(body)) return

        val all = readAll()
        val duplicated = all.any {
            it.talker == talker && it.text == body && at - it.at < 10 * 60 * 1000L
        }
        if (duplicated) return

        val record = AiImportantAlert(
            id = UUID.randomUUID().toString(),
            talker = talker,
            name = name.ifBlank { talker },
            text = body.take(200),
            at = at,
        )
        val next = (all + record).sortedByDescending { it.at }.take(MAX_KEEP)
        writeAll(next)
        WeLogger.i(TAG, "记录重要信息：${record.name} -> ${record.text.take(30)}")
    }

    /** 规则判定：命中「紧急词」直接算；否则要在「时间/金额/地点」里命中至少两类。 */
    fun isImportant(text: String): Boolean {
        if (URGENT_WORDS.containsMatchIn(text)) return true
        var hits = 0
        if (TIME_WORDS.containsMatchIn(text)) hits++
        if (MONEY_WORDS.containsMatchIn(text)) hits++
        if (PLACE_WORDS.containsMatchIn(text)) hits++
        return hits >= 2
    }
}
