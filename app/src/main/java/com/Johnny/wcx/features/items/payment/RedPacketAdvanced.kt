package com.Johnny.wcx.features.items.payment

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import kotlin.random.Random

/**
 * 红包精细化增强（供 [AutoOpenRedPackets] 调用）。
 *
 * 包含三块能力，**全部默认关闭/保持原行为**，开关不打开时代码路径与改造前一致：
 *  1. 祝福语关键词过滤：只抢 / 排除包含指定关键词的红包（读 appmsg XML 的 `<wishing>`，
 *     拿不到 `<wishing>` 时退化为 `<title>`）。
 *  2. 延时随机化：沿用 [AutoOpenRedPackets] 已有的「私聊/群聊 min~max 随机」，
 *     并新增「按秒填写」开关（默认关闭，开启后输入 1/3 表示 1000~3000ms）。
 *  3. 白/黑名单手动管理：可手工增删 wxid 与群 ID（含 `@im.chatroom` 企业微信群），
 *     与 `ContactsSelector` 选择的结果写在同一份 prefs 里。
 *
 * 说明：白/黑名单的**生效逻辑**本来就在 [AutoOpenRedPackets.handleRedPacket] 里，
 * 用 talker（会话 ID）比对，群聊（`xxx@chatroom` / `xxx@im.chatroom`）与个人（wxid）
 * 都走同一条判断，本次只补 UI 与管理入口，不改判断顺序。
 */
object RedPacketAdvanced {

    private const val TAG = "RedPacketAdvanced"

    // ── prefs key（与 AutoOpenRedPackets 共用同一份存储） ─────────────────────────
    const val KEY_KW_MODE = "red_packet_kw_mode"
    const val KEY_KW_INCLUDE = "red_packet_kw_include"
    const val KEY_KW_EXCLUDE = "red_packet_kw_exclude"
    const val KEY_DELAY_SECOND_MODE = "red_packet_delay_second_mode"
    const val KEY_WHITELIST = "red_packet_whitelist"
    const val KEY_BLACKLIST = "red_packet_blacklist"
    private const val KEY_DELAY_MIN_PRIVATE = "red_packet_delay_min_private"
    private const val KEY_DELAY_MAX_PRIVATE = "red_packet_delay_max_private"
    private const val KEY_DELAY_MIN_GROUP = "red_packet_delay_min_group"
    private const val KEY_DELAY_MAX_GROUP = "red_packet_delay_max_group"

    /** 关键词匹配模式。 */
    enum class KeywordMode(val label: String, val hint: String) {
        OFF("关闭", "不过滤，抢所有红包"),
        INCLUDE("仅抢含关键词", "祝福语包含任一关键词才抢"),
        EXCLUDE("排除含关键词", "祝福语包含任一关键词时跳过"),
    }

    private var kwModeValue by WePrefs.prefOption(KEY_KW_MODE, KeywordMode.OFF.name)
    private var kwIncludeValue by WePrefs.prefOption(KEY_KW_INCLUDE, "")
    private var kwExcludeValue by WePrefs.prefOption(KEY_KW_EXCLUDE, "")
    private var delaySecondModeValue by WePrefs.prefOption(KEY_DELAY_SECOND_MODE, false)

    private var delayMinPrivateValue by WePrefs.prefOption(KEY_DELAY_MIN_PRIVATE, "200")
    private var delayMaxPrivateValue by WePrefs.prefOption(KEY_DELAY_MAX_PRIVATE, "500")
    private var delayMinGroupValue by WePrefs.prefOption(KEY_DELAY_MIN_GROUP, "300")
    private var delayMaxGroupValue by WePrefs.prefOption(KEY_DELAY_MAX_GROUP, "800")

    private var whitelistValue by WePrefs.prefOption(KEY_WHITELIST, emptySet<String>())
    private var blacklistValue by WePrefs.prefOption(KEY_BLACKLIST, emptySet<String>())

    // ── 关键词过滤 ───────────────────────────────────────────────────────────────

    val keywordMode: KeywordMode
        get() = runCatching { KeywordMode.valueOf(kwModeValue) }.getOrDefault(KeywordMode.OFF)

    var includeKeywordsRaw: String
        get() = kwIncludeValue
        set(value) {
            kwIncludeValue = value
        }

    var excludeKeywordsRaw: String
        get() = kwExcludeValue
        set(value) {
            kwExcludeValue = value
        }

    /** 关键词分隔符：换行 / 中英文逗号 / 中英文分号 / 顿号 / 竖线 / 制表符。空格不作分隔（祝福语常含空格）。 */
    private val keywordSeparators = charArrayOf(
        '\n', '\r', ',', '，', ';', '；', '、', '|', '\t'
    )

    private fun splitKeywords(raw: String): List<String> =
        raw.split(*keywordSeparators)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }
            .distinct()
            .toList()

    fun setKeywordMode(mode: KeywordMode) {
        kwModeValue = mode.name
    }

    /**
     * 是否按关键词跳过这个红包。
     *
     * 任何配置异常（模式非法、关键词为空）都返回 false —— **宁可多抢，绝不能因为配置问题漏抢**。
     *
     * @param wishing appmsg XML 里的 `<wishing>`（祝福语）
     * @param title   appmsg XML 里的 `<title>`（没有 wishing 时的退化字段）
     */
    fun shouldSkipByKeyword(wishing: String, title: String): Boolean {
        val mode = keywordMode
        if (mode == KeywordMode.OFF) return false

        val text = (if (wishing.isNotBlank()) wishing else title).lowercase()
        if (text.isBlank()) return false

        val keywords = if (mode == KeywordMode.INCLUDE) {
            splitKeywords(kwIncludeValue)
        } else {
            splitKeywords(kwExcludeValue)
        }
        if (keywords.isEmpty()) return false

        val hit = keywords.any { text.contains(it) }
        return if (mode == KeywordMode.INCLUDE) !hit else hit
    }

    // ── 延时随机化 ───────────────────────────────────────────────────────────────

    var delaySecondMode: Boolean
        get() = delaySecondModeValue
        set(value) {
            delaySecondModeValue = value
        }

    /**
     * 计算本次抢红包前的等待毫秒数。
     *
     * 与改造前完全一致的部分：极速模式直接用 [floorMs]；否则在 [min, max) 区间随机，
     * 并把 min 抬到不低于 [floorMs]（内置最小延迟保护），max 不小于 min。
     *
     * 新增：`delaySecondMode` 打开时，上面填的 min/max 按「秒」解释（×1000）。
     */
    fun resolveDelayMs(isGroup: Boolean, speedMode: Boolean, floorMs: Long): Long {
        if (speedMode) return floorMs

        val rawMin: Long
        val rawMax: Long
        if (isGroup) {
            rawMin = delayMinGroupValue.toLongOrNull() ?: 300L
            rawMax = delayMaxGroupValue.toLongOrNull() ?: 800L
        } else {
            rawMin = delayMinPrivateValue.toLongOrNull() ?: 200L
            rawMax = delayMaxPrivateValue.toLongOrNull() ?: 500L
        }

        val scale = if (delaySecondModeValue) 1000L else 1L
        val minDelay = rawMin.coerceAtLeast(0L) * scale
        val maxDelay = rawMax.coerceAtLeast(0L) * scale

        val safeMin = maxOf(minDelay, floorMs)
        val safeMax = maxOf(maxDelay, safeMin)
        return if (safeMax > safeMin) Random.nextLong(safeMin, safeMax) else safeMin
    }

    // ── 白/黑名单存取（与 AutoOpenRedPackets 的 prefs 同源） ──────────────────────

    fun currentList(useWhitelist: Boolean): Set<String> =
        if (useWhitelist) whitelistValue else blacklistValue

    fun saveList(useWhitelist: Boolean, ids: Set<String>) {
        val cleaned = ids.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (useWhitelist) whitelistValue = cleaned else blacklistValue = cleaned
        WeLogger.i(
            TAG,
            "saved ${if (useWhitelist) "whitelist" else "blacklist"} size=${cleaned.size}"
        )
    }

    fun addToList(useWhitelist: Boolean, id: String): Boolean {
        val trimmed = id.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
        val current = currentList(useWhitelist)
        if (current.contains(trimmed)) return false
        saveList(useWhitelist, current + trimmed)
        return true
    }

    fun removeFromList(useWhitelist: Boolean, id: String) {
        val current = currentList(useWhitelist)
        if (!current.contains(id)) return
        saveList(useWhitelist, current - id)
    }

    // ── 设置界面：关键词过滤 + 延时单位 ───────────────────────────────────────────

    fun showKeywordFilterDialog(context: Context) {
        showComposeDialog(context) {
            var mode by remember { mutableStateOf(keywordMode) }
            var includeText by remember { mutableStateOf(kwIncludeValue) }
            var excludeText by remember { mutableStateOf(kwExcludeValue) }
            var secondMode by remember { mutableStateOf(delaySecondModeValue) }

            AlertDialogContent(
                title = { Text("关键词过滤与延时") },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        KeywordMode.entries.forEach { item ->
                            ListItem(
                                modifier = Modifier.clickable { mode = item },
                                headlineContent = {
                                    Text((if (mode == item) "● " else "○ ") + item.label)
                                },
                                supportingContent = { Text(item.hint) },
                            )
                        }
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = includeText,
                            onValueChange = { includeText = it },
                            label = { Text("仅抢包含的关键词") },
                            supportingText = { Text("多个关键词用换行/逗号/顿号分隔；留空表示不限制") },
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = excludeText,
                            onValueChange = { excludeText = it },
                            label = { Text("排除的关键词") },
                            supportingText = { Text("祝福语命中任一关键词时跳过该红包") },
                        )
                        ListItem(
                            modifier = Modifier.clickable { secondMode = !secondMode },
                            trailingContent = {
                                Switch(checked = secondMode, onCheckedChange = { secondMode = it })
                            },
                            headlineContent = { Text("延时按「秒」填写") },
                            supportingContent = {
                                Text(
                                    if (secondMode) "当前：延迟输入框按秒解释（1 表示 1000 毫秒）"
                                    else "当前：延迟输入框按毫秒解释（与旧版一致）"
                                )
                            },
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        setKeywordMode(mode)
                        kwIncludeValue = includeText
                        kwExcludeValue = excludeText
                        delaySecondModeValue = secondMode
                        showToast("已保存关键词过滤设置")
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
            )
        }
    }

    // ── 设置界面：白/黑名单手动管理（增删） ────────────────────────────────────────

    fun showListManagerDialog(context: Context, useWhitelist: Boolean) {
        showComposeDialog(context) {
            var ids by remember { mutableStateOf(currentList(useWhitelist)) }
            var input by remember { mutableStateOf("") }
            var selecting by remember { mutableStateOf(false) }
            val titleText = if (useWhitelist) "白名单管理" else "黑名单管理"

            if (selecting) {
                ContactsSelector(
                    title = if (useWhitelist) "选择白名单" else "选择黑名单",
                    contacts = remember { runCatching { WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups() }.getOrDefault(emptyList()) },
                    initialSelectedWxIds = ids,
                    onDismiss = { selecting = false },
                ) { selected ->
                    ids = selected
                    saveList(useWhitelist, selected)
                    selecting = false
                }
            } else {
                AlertDialogContent(
                    title = { Text(titleText) },
                    text = {
                        DefaultColumn {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    modifier = Modifier.fillMaxWidth(0.62f),
                                    value = input,
                                    onValueChange = { input = it },
                                    label = { Text("手动添加 wxid / 群 ID") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                )
                                Button(
                                    onClick = {
                                        if (addToList(useWhitelist, input)) {
                                            ids = currentList(useWhitelist)
                                            input = ""
                                            showToast("已添加，重启微信后生效")
                                        } else {
                                            showToast("ID 无效或已存在")
                                        }
                                    },
                                    enabled = input.isNotBlank(),
                                ) { Text("添加") }
                            }
                            Text("当前 ${ids.size} 项。群聊填群 ID（形如 xxxx@chatroom / xxxx@im.chatroom），个人填 wxid。重启微信后生效。")
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (ids.isEmpty()) {
                                    item { Text("暂无条目") }
                                }
                                items(ids.toList()) { id ->
                                    ListItem(
                                        headlineContent = {
                                            Text(remember(id) { runCatching { WeDatabaseApi.getDisplayName(id) }.getOrDefault(id) })
                                        },
                                        supportingContent = { Text(id) },
                                        trailingContent = {
                                            TextButton(onClick = {
                                                removeFromList(useWhitelist, id)
                                                ids = currentList(useWhitelist)
                                            }) { Text("删除") }
                                        },
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = onDismiss) { Text("完成") }
                    },
                    dismissButton = {
                        TextButton(onClick = { selecting = true }) { Text("从联系人选择") }
                    },
                )
            }
        }
    }
}
