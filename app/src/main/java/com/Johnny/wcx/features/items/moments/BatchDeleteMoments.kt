package com.Johnny.wcx.features.items.moments

import android.annotation.SuppressLint
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.net.WeNetSceneApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.style.DangerBanner
import com.Johnny.wcx.ui.style.SectionLabel
import com.Johnny.wcx.ui.style.SoftCard
import com.Johnny.wcx.ui.style.StatusPill
import com.Johnny.wcx.ui.style.StyleRow
import com.Johnny.wcx.ui.style.StyleTitle
import com.Johnny.wcx.ui.style.WxPalette
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.hookAfterDirectly
import org.json.JSONObject
import java.lang.reflect.Constructor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 批量删除自己的朋友圈。
 *
 * ## 原理
 * 微信删除朋友圈走网络接口 `/cgi-bin/micromsg-bin/mmsnsobjectop`，
 * 对应客户端类 `com.tencent.mm.plugin.sns.model.NetSceneSnsObjectOp`（8.0.80 混淆名 `r2`），
 * 构造器 `(long snsId, int opType)`。微信 UI 只提供逐条删除，但协议层面可以连续发。
 *
 * 本功能做的事：
 * 1. 从本地 `SnsInfo` 表枚举自己的朋友圈（按年份 / 数量筛选）
 * 2. 逐条构造 NetScene 并投递到微信网络队列
 * 3. 按可配置的间隔与批量暂停节流，降低风控概率
 *
 * ## opType 自动学习
 * 微信 8.0.80 中没有可查的 opType 常量类，因此本功能采用**观察学习**：
 * hook `NetSceneSnsObjectOp` 的 `(J,I)` 构造器，把你手动删除一条朋友圈时微信自己传入的
 * opType 记录下来，批量删除时复用同一个值。首次使用前需要手动删一条做校准。
 *
 * ## 风险
 * 高频删除朋友圈会触发微信风控：删除被拒、朋友圈发布/查看受限。
 * 默认节流为 5 秒/条、每 20 条暂停 60 秒、每日上限 200 条，建议不要放大。
 */
@SuppressLint("DiscouragedApi")
@Feature(
    name = "批量删除朋友圈",
    categories = ["朋友圈"],
    description = "按年份或数量批量删除自己发布的朋友圈\n自动学习删除参数 · 节流执行 · 支持中止"
)
object BatchDeleteMoments : ClickableFeature(), IResolveDex {

    private const val TAG = "BatchDeleteMoments"

    // ── 配置键 ────────────────────────────────────────────────────────────────
    private const val PREF_OP_TYPE = "sns_batch_delete_op_type"
    private const val PREF_INTERVAL = "sns_batch_delete_interval"
    private const val PREF_BATCH_SIZE = "sns_batch_delete_batch_size"
    private const val PREF_BATCH_PAUSE = "sns_batch_delete_batch_pause"
    private const val PREF_DAILY_LIMIT = "sns_batch_delete_daily_limit"
    private const val PREF_DAILY_DATE = "sns_batch_delete_daily_date"
    private const val PREF_DAILY_COUNT = "sns_batch_delete_daily_count"

    // ── DexKit 定位 ───────────────────────────────────────────────────────────
    /** NetSceneSnsObjectOp：cgi 特征串 + 包过滤，单串避免「同串不同方法」不命中。 */
    private val classSnsObjectOp by dexClass {
        searchPackages("com.tencent.mm.plugin.sns.model")
        matcher {
            usingEqStrings("/cgi-bin/micromsg-bin/mmsnsobjectop")
        }
    }

    /** onGYNetEnd：用于读取每条删除请求的服务端返回码。 */
    private val methodOpOnGYNetEnd by dexMethod {
        matcher {
            declaredClass(classSnsObjectOp.clazz)
            name = "onGYNetEnd"
        }
    }

    // ── 配置（持久化） ────────────────────────────────────────────────────────
    /** 学习到的 opType，-1 表示尚未学到。 */
    private var learnedOpType by WePrefs.prefOption(PREF_OP_TYPE, -1)
    private var intervalInput by WePrefs.prefOption(PREF_INTERVAL, "5000")
    private var batchSizeInput by WePrefs.prefOption(PREF_BATCH_SIZE, "20")
    private var batchPauseInput by WePrefs.prefOption(PREF_BATCH_PAUSE, "60000")
    private var dailyLimitInput by WePrefs.prefOption(PREF_DAILY_LIMIT, "200")

    // ── 运行状态 ──────────────────────────────────────────────────────────────
    private val running = AtomicBoolean(false)
    @Volatile private var cancelRequested = false

    /** 正在删除的 snsId：用于区分「模块触发」与「用户手动触发」，避免污染学习样本。 */
    private val deletingSnsIds = ConcurrentHashMap.newKeySet<Long>()

    /** 删除结果：snsId -> retcode（0 成功）。 */
    private val deleteResults = ConcurrentHashMap<Long, Int>()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // ── 数据结构 ──────────────────────────────────────────────────────────────
    data class MomentItem(
        val snsId: Long,
        val createTime: Long,
        val createTimeText: String,
        val type: Int,
        val preview: String,
    )

    enum class FilterMode { BY_YEAR, BY_COUNT }

    data class DeleteOptions(
        val mode: FilterMode = FilterMode.BY_YEAR,
        val yearFrom: Int = 2014,
        val yearTo: Int = Calendar.getInstance().get(Calendar.YEAR),
        val limit: Int = 100,
        val intervalMs: Long = 5000L,
        val batchSize: Int = 20,
        val batchPauseMs: Long = 60000L,
        val dailyLimit: Int = 200,
    )

    data class DeleteReport(
        val total: Int,
        val success: Int,
        val failed: Int,
        val skipped: Int,
        val cancelled: Boolean,
    )

    // ══════════════════════════════════════════════════════════════════════════
    // 生命周期 / 自动学习
    // ══════════════════════════════════════════════════════════════════════════

    override fun onEnable() {
        hookOpResult()
        hookOpTypeLearning()
    }

    /** 监听删除结果：从 onGYNetEnd 的 JSON 里取 retcode。 */
    private fun hookOpResult() {
        runCatching {
            methodOpOnGYNetEnd.hookAfter {
                val json = args.getOrNull(2) as? JSONObject ?: return@hookAfter
                val retCode = json.optInt("retcode", -1)
                // snsId 可能以 snsId / objectId 字段返回，两种都尝试
                val snsId = json.optLong("snsId", 0L).takeIf { it != 0L }
                    ?: json.optLong("objectId", 0L).takeIf { it != 0L }
                WeLogger.i(TAG, "NetSceneSnsObjectOp result: retcode=$retCode snsId=$snsId")

                if (snsId != null && deletingSnsIds.contains(snsId)) {
                    deleteResults[snsId] = retCode
                }
            }
        }.onFailure {
            WeLogger.e(TAG, "hook NetSceneSnsObjectOp.onGYNetEnd failed", it)
        }
    }

    /**
     * 学习 opType：hook `NetSceneSnsObjectOp(long, int)` 构造器。
     *
     * 只有非模块触发（用户手动删除）才更新样本，避免把历史值覆盖成错误值。
     */
    private fun hookOpTypeLearning() {
        runCatching {
            val cls = classSnsObjectOp.clazz
            val ctor = findSnsOpConstructor(cls) ?: run {
                WeLogger.w(TAG, "未找到 NetSceneSnsObjectOp(long,int) 构造器，opType 学习不可用")
                return
            }
            ctor.hookAfterDirectly {
                val snsId = (args.getOrNull(0) as? Long) ?: return@hookAfterDirectly
                val opType = (args.getOrNull(1) as? Int) ?: return@hookAfterDirectly
                if (deletingSnsIds.contains(snsId)) return@hookAfterDirectly

                if (learnedOpType != opType) {
                    learnedOpType = opType
                    WeLogger.i(TAG, "已学习 opType=$opType（来源：手动删除 snsId=$snsId）")
                }
            }
            WeLogger.i(TAG, "opType 学习 hook 已安装")
        }.onFailure {
            WeLogger.e(TAG, "hook NetSceneSnsObjectOp 构造器失败", it)
        }
    }

    private fun findSnsOpConstructor(cls: Class<*>): Constructor<*>? {
        return cls.declaredConstructors.firstOrNull { ctor ->
            val types = ctor.parameterTypes
            types.size == 2 &&
                (types[0] == Long::class.javaPrimitiveType || types[0] == java.lang.Long::class.java) &&
                (types[1] == Int::class.javaPrimitiveType || types[1] == java.lang.Integer::class.java)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 枚举自己的朋友圈
    // ══════════════════════════════════════════════════════════════════════════

    /** 读取 SnsInfo 表的列名，避免硬编码列名在版本间失效。 */
    private fun snsInfoColumns(): Set<String> = runCatching {
        val cols = mutableSetOf<String>()
        WeDatabaseApi.rawQuery("PRAGMA table_info(SnsInfo)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIdx >= 0) cols.add(c.getString(nameIdx))
            }
        }
        cols
    }.getOrElse {
        WeLogger.e(TAG, "读取 SnsInfo 表结构失败", it)
        emptySet()
    }

    /**
     * 枚举自己的朋友圈。
     *
     * 依赖列：snsId / userName / createTime / type / content（缺列时降级跳过）。
     */
    fun loadMyMoments(options: DeleteOptions): List<MomentItem> {
        val self = WeApi.selfWxId
        if (self.isNullOrEmpty()) {
            WeLogger.w(TAG, "selfWxId 为空，无法枚举朋友圈")
            return emptyList()
        }

        val cols = snsInfoColumns()
        if (cols.isEmpty()) return emptyList()

        val hasUser = "userName" in cols
        val hasTime = "createTime" in cols
        if (!hasTime) {
            WeLogger.w(TAG, "SnsInfo 缺少 createTime 列，无法按时间筛选")
            return emptyList()
        }

        val where = buildString {
            append(" WHERE ")
            if (hasUser) append("userName = ?")
            else append("1=1")
        }

        val sql = "SELECT snsId, createTime, type, content FROM SnsInfo$where ORDER BY createTime DESC"
        val args = if (hasUser) arrayOf<Any>(self) else null

        val result = mutableListOf<MomentItem>()
        runCatching {
            WeDatabaseApi.rawQuery(sql, args).use { c ->
                val iSnsId = c.getColumnIndex("snsId")
                val iTime = c.getColumnIndex("createTime")
                val iType = c.getColumnIndex("type")
                val iContent = c.getColumnIndex("content")
                if (iSnsId < 0 || iTime < 0) return@use

                while (c.moveToNext()) {
                    val snsId = c.getLong(iSnsId)
                    if (snsId == 0L) continue
                    val createTime = c.getLong(iTime)
                    val type = if (iType >= 0) c.getInt(iType) else 0
                    val preview = if (iContent >= 0) {
                        runCatching { c.getBlob(iContent)?.let { String(it, Charsets.UTF_8) } }.getOrNull()
                    } else null

                    if (!passesFilter(createTime, type, preview, options)) continue

                    result.add(
                        MomentItem(
                            snsId = snsId,
                            createTime = createTime,
                            createTimeText = formatTime(createTime),
                            type = type,
                            preview = summarize(preview),
                        )
                    )
                    if (result.size >= options.limit) break
                }
            }
        }.onFailure { WeLogger.e(TAG, "枚举朋友圈失败", it) }

        WeLogger.i(TAG, "枚举到 ${result.size} 条待删朋友圈（self=$self）")
        return result
    }

    private fun passesFilter(createTime: Long, type: Int, content: String?, options: DeleteOptions): Boolean {
        if (createTime <= 0L) return false
        val cal = Calendar.getInstance().apply { timeInMillis = createTime * 1000 }
        val year = cal.get(Calendar.YEAR)
        if (options.mode == FilterMode.BY_YEAR) {
            if (year < options.yearFrom || year > options.yearTo) return false
        }
        return true
    }

    private fun formatTime(seconds: Long): String =
        runCatching { dateFormat.format(Date(seconds * 1000)) }.getOrDefault("")

    private fun summarize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val text = raw
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (text.length > 40) text.take(40) + "…" else text
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 批量删除
    // ══════════════════════════════════════════════════════════════════════════

    /** 今日已删除条数（用于每日上限保护）。 */
    private fun todayCount(): Int {
        val today = dateFormat.format(Date())
        val storedDate = WePrefs.getStringOrDef(PREF_DAILY_DATE, "")
        if (storedDate != today) {
            WePrefs.putString(PREF_DAILY_DATE, today)
            WePrefs.putString(PREF_DAILY_COUNT, "0")
            return 0
        }
        return WePrefs.getStringOrDef(PREF_DAILY_COUNT, "0").toIntOrNull() ?: 0
    }

    private fun bumpTodayCount(delta: Int) {
        val current = todayCount()
        WePrefs.putString(PREF_DAILY_COUNT, (current + delta).toString())
    }

    fun isRunning(): Boolean = running.get()

    fun requestCancel() {
        cancelRequested = true
    }

    /**
     * 执行批量删除（后台线程，不阻塞 UI）。
     *
     * @return false 表示前置条件不满足（未学习 opType / 正在运行 / 超过每日上限）。
     */
    fun startDelete(
        items: List<MomentItem>,
        options: DeleteOptions,
        onProgress: (done: Int, total: Int, message: String) -> Unit,
        onFinished: (DeleteReport) -> Unit,
    ): Boolean {
        if (items.isEmpty()) {
            onProgress(0, 0, "没有符合条件的朋友圈")
            return false
        }
        if (learnedOpType < 0) {
            onProgress(0, items.size, "尚未学习删除参数，请先手动删除一条朋友圈")
            return false
        }
        if (!running.compareAndSet(false, true)) {
            onProgress(0, items.size, "已有删除任务在执行")
            return false
        }

        val alreadyToday = todayCount()
        val allow = (options.dailyLimit - alreadyToday).coerceAtLeast(0)
        if (allow <= 0) {
            running.set(false)
            onProgress(0, items.size, "已达到今日上限（${options.dailyLimit} 条）")
            return false
        }

        val queue = items.take(allow)
        cancelRequested = false
        deleteResults.clear()

        thread(name = "BatchDeleteMoments", isDaemon = true) {
            var success = 0
            var failed = 0
            var cancelled = false
            try {
                val ctor = findSnsOpConstructor(classSnsObjectOp.clazz)
                if (ctor == null) {
                    onFinished(DeleteReport(queue.size, 0, 0, queue.size, false))
                    return@thread
                }

                queue.forEachIndexed { index, item ->
                    if (cancelRequested) {
                        cancelled = true
                        return@forEachIndexed
                    }
                    // 每批之间长暂停，降低风控特征
                    if (index > 0 && index % options.batchSize == 0) {
                        onProgress(index, queue.size, "第 ${index / options.batchSize} 批完成，暂停 ${options.batchPauseMs / 1000}s")
                        sleepSafe(options.batchPauseMs)
                    }
                    if (index > 0) sleepSafe(options.intervalMs)

                    if (cancelRequested) {
                        cancelled = true
                        return@forEachIndexed
                    }

                    val ok = deleteOne(ctor, item.snsId)
                    if (ok) {
                        success++
                        bumpTodayCount(1)
                    } else {
                        failed++
                    }
                    onProgress(index + 1, queue.size, "${item.createTimeText} ${if (ok) "已删除" else "失败"}")
                }
            } catch (t: Throwable) {
                WeLogger.e(TAG, "批量删除异常中止", t)
            } finally {
                deletingSnsIds.clear()
                running.set(false)
                onFinished(
                    DeleteReport(
                        total = queue.size,
                        success = success,
                        failed = failed,
                        skipped = items.size - queue.size,
                        cancelled = cancelled,
                    )
                )
            }
        }
        return true
    }

    private fun sleepSafe(ms: Long) {
        if (ms <= 0) return
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cancelRequested) return
            Thread.sleep(minOf(500L, (end - System.currentTimeMillis()).coerceAtLeast(1L)))
        }
    }

    /** 删除一条，阻塞等待结果（最多 15 秒）。 */
    private fun deleteOne(ctor: Constructor<*>, snsId: Long): Boolean {
        val opType = learnedOpType
        if (opType < 0) return false
        return try {
            deletingSnsIds.add(snsId)
            deleteResults.remove(snsId)
            val netScene = ctor.newInstance(snsId, opType)
            WeNetSceneApi.sendNetScene(netScene)

            // 等待 onGYNetEnd 回填结果
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                val ret = deleteResults.remove(snsId)
                if (ret != null) {
                    deletingSnsIds.remove(snsId)
                    WeLogger.i(TAG, "删除 snsId=$snsId retcode=$ret")
                    return ret == 0
                }
                Thread.sleep(120)
            }
            WeLogger.w(TAG, "删除 snsId=$snsId 超时未收到结果")
            deletingSnsIds.remove(snsId)
            false
        } catch (t: Throwable) {
            WeLogger.e(TAG, "删除 snsId=$snsId 抛异常", t)
            deletingSnsIds.remove(snsId)
            false
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 设置 UI
    // ══════════════════════════════════════════════════════════════════════════

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var mode by remember { mutableStateOf(FilterMode.BY_YEAR) }
            var yearFrom by remember { mutableStateOf((Calendar.getInstance().get(Calendar.YEAR) - 3).toString()) }
            var yearTo by remember { mutableStateOf(Calendar.getInstance().get(Calendar.YEAR).toString()) }
            var limit by remember { mutableStateOf("100") }
            var interval by remember { mutableStateOf(intervalInput) }
            var batchSize by remember { mutableStateOf(batchSizeInput) }
            var batchPause by remember { mutableStateOf(batchPauseInput) }
            var dailyLimit by remember { mutableStateOf(dailyLimitInput) }

            AlertDialogContent(
                title = { StyleTitle("批量删除朋友圈") },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {

                        DangerBanner(
                            title = "⚠ 使用前必读",
                            message = "· 微信对「短时间内大量删除朋友圈」有风控：会出现删除失败、" +
                                "朋友圈发布/查看受限等限制。\n" +
                                "· 默认 5 秒/条、每 20 条停 60 秒、每日上限 200 条，请勿把间隔调到 1 秒以下。\n" +
                                "· 删除不可恢复，不会进入回收站。\n" +
                                "· 首次使用需先手动删除一条朋友圈，用于学习删除参数。"
                        )

                        SoftCard(gradient = WxPalette.GradMint) {
                            SectionLabel("删除参数")
                            StyleRow(
                                title = "opType（自动学习）",
                                subtitle = if (learnedOpType >= 0) "已学习：opType=$learnedOpType"
                                else "尚未学习：请先手动删除一条自己的朋友圈",
                                trailing = {
                                    StatusPill(
                                        text = if (learnedOpType >= 0) "已就绪" else "待学习",
                                        color = if (learnedOpType >= 0) WxPalette.Primary else WxPalette.Warning,
                                        background = if (learnedOpType >= 0) WxPalette.PrimarySoft else WxPalette.WarningSoft,
                                    )
                                },
                            )
                        }

                        SoftCard(gradient = WxPalette.GradBlue) {
                            SectionLabel("筛选范围")
                            StyleRow(
                                title = "按年份筛选",
                                subtitle = "只删除指定年份区间内的朋友圈",
                                trailing = {
                                    Switch(
                                        checked = mode == FilterMode.BY_YEAR,
                                        onCheckedChange = { mode = FilterMode.BY_YEAR },
                                    )
                                },
                            )
                            if (mode == FilterMode.BY_YEAR) {
                                TextField(
                                    value = yearFrom,
                                    onValueChange = { yearFrom = it.filter { c -> c.isDigit() }.take(4) },
                                    label = { Text("起始年份（含）") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                )
                                TextField(
                                    value = yearTo,
                                    onValueChange = { yearTo = it.filter { c -> c.isDigit() }.take(4) },
                                    label = { Text("结束年份（含）") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                )
                            }
                            StyleRow(
                                title = "按数量筛选",
                                subtitle = "从最新的开始，删除指定条数",
                                trailing = {
                                    Switch(
                                        checked = mode == FilterMode.BY_COUNT,
                                        onCheckedChange = { mode = FilterMode.BY_COUNT },
                                    )
                                },
                            )
                            TextField(
                                value = limit,
                                onValueChange = { limit = it.filter { c -> c.isDigit() }.take(5) },
                                label = { Text("最多删除条数") },
                                supportingText = { Text("本次任务上限，实际还受每日上限约束") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                        }

                        SoftCard(gradient = WxPalette.GradPurple) {
                            SectionLabel("节流参数（建议保持默认）")
                            TextField(
                                value = interval,
                                onValueChange = { interval = it.filter { c -> c.isDigit() }.take(6) },
                                label = { Text("每条间隔（毫秒）") },
                                supportingText = { Text("建议 ≥ 3000，过低容易触发风控") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                            TextField(
                                value = batchSize,
                                onValueChange = { batchSize = it.filter { c -> c.isDigit() }.take(3) },
                                label = { Text("每批条数") },
                                supportingText = { Text("删除多少条后暂停一次") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                            TextField(
                                value = batchPause,
                                onValueChange = { batchPause = it.filter { c -> c.isDigit() }.take(7) },
                                label = { Text("批次暂停（毫秒）") },
                                supportingText = { Text("建议 ≥ 60000") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                            TextField(
                                value = dailyLimit,
                                onValueChange = { dailyLimit = it.filter { c -> c.isDigit() }.take(4) },
                                label = { Text("每日上限（条）") },
                                supportingText = { Text("今日已删 ${todayCount()} 条") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        intervalInput = interval.ifBlank { "5000" }
                        batchSizeInput = batchSize.ifBlank { "20" }
                        batchPauseInput = batchPause.ifBlank { "60000" }
                        dailyLimitInput = dailyLimit.ifBlank { "200" }

                        val options = DeleteOptions(
                            mode = mode,
                            yearFrom = yearFrom.toIntOrNull() ?: 2014,
                            yearTo = yearTo.toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR),
                            limit = limit.toIntOrNull()?.coerceAtLeast(1) ?: 100,
                            intervalMs = intervalInput.toLongOrNull()?.coerceAtLeast(1000L) ?: 5000L,
                            batchSize = batchSizeInput.toIntOrNull()?.coerceAtLeast(1) ?: 20,
                            batchPauseMs = batchPauseInput.toLongOrNull()?.coerceAtLeast(1000L) ?: 60000L,
                            dailyLimit = dailyLimitInput.toIntOrNull()?.coerceAtLeast(1) ?: 200,
                        )
                        onDismiss()
                        confirmAndStart(context, options)
                    }) { Text("扫描并删除") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }

    /** 扫描 → 二次确认 → 启动。 */
    private fun confirmAndStart(context: Context, options: DeleteOptions) {
        if (learnedOpType < 0) {
            showToast("请先手动删除一条朋友圈以学习删除参数")
            return
        }

        val items = loadMyMoments(options)
        if (items.isEmpty()) {
            showToast("没有符合条件的朋友圈")
            return
        }

        val sample = items.take(3).joinToString("\n") { "· ${it.createTimeText}  ${it.preview}" }
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("确认删除") },
                text = {
                    Text(
                        "将删除 ${items.size} 条朋友圈，此操作不可恢复。\n\n" +
                            "最近 3 条：\n$sample\n\n" +
                            "间隔 ${options.intervalMs / 1000}s / 每 ${options.batchSize} 条暂停 " +
                            "${options.batchPauseMs / 1000}s / 今日已删 ${todayCount()} 条。\n\n" +
                            "删除过程中可以随时在通知/日志中查看进度，任务不会因为关闭本对话框而中断。"
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        val started = startDelete(
                            items = items,
                            options = options,
                            onProgress = { done, total, message ->
                                if (done % 10 == 0 || done == total) {
                                    WeLogger.i(TAG, "进度 $done/$total：$message")
                                }
                            },
                            onFinished = { report ->
                                WeLogger.i(
                                    TAG,
                                    "批量删除完成：成功 ${report.success} / 失败 ${report.failed} / " +
                                        "跳过 ${report.skipped} / 中止=${report.cancelled}"
                                )
                                runCatching {
                                    showToast(
                                        "批量删除结束：成功 ${report.success}，失败 ${report.failed}" +
                                            if (report.cancelled) "（已中止）" else ""
                                    )
                                }
                            }
                        )
                        if (started) showToast("已开始删除 ${items.size} 条，可在日志查看进度")
                    }) { Text("确认删除") }
                },
                dismissButton = { TextButton(onDismiss) { Text("再想想") } }
            )
        }
    }
}
