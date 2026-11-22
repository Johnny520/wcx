package com.Johnny.wcx.features.items.system

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
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
import dev.ujhhgtg.reflekt.utils.createInstance
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.net.WeNetSceneApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import kotlin.random.Random

/**
 * 微信运动「一键点赞」。
 *
 * 定位思路（8.0.80 已验证，详见交付目录 ANCHORS.md）：
 * - 微信运动排行榜页面 = `com.tencent.mm.plugin.exdevice.ui.ExdeviceRankInfoUI`
 *   （自身日志 tag 是 `MicroMsg.Sport.ExdeviceRankInfoUI`；`onCreate` 里还会打
 *   `getWeRunTargetListFromServer`，两个串唯一命中该 Activity 的 onCreate）。
 * - 点赞请求 = `z92.r`，构造器 `(String username, String app_username, int state, String rank_id)`，
 *   URI `/cgi-bin/mmbiz-bin/rank/addlike`（全 dex 唯一）。`state` 语义来自
 *   `ExdeviceRankInfoUI.K7(String,int)` 的调用点：1 = 点赞（likecount+1，播放爱心动画），
 *   0 = 取消点赞。**本功能永远只传 1**，绝不传 0，避免误取消。
 * - 好友步数列表 = 主库表 `HardDeviceRankInfo`
 *   （列：rankID / appusername / username / ranknum / score / likecount / selfLikeState；
 *   `selfLikeState`：0 = 未点赞，1 = 已点赞，2 = 加载中，默认 3）。
 *
 * 安全设计：
 * - 串行发送：一条点赞请求收到 `onGYNetEnd` 之后才发下一条（微信 NetSceneQueue 对同类型
 *   任务有 forbid 机制，并发发会被静默丢弃），每条之间再插入 300~800ms 随机间隔。
 * - 只处理 `selfLikeState == 0` 的行，且只发「点赞」不发送「取消点赞」，即使语义判定失误
 *   也不会把已点赞的好友取消掉。
 * - 所有 Dex 锚点 `allowFailure = true`，定位失败时功能降级：设置页显示「当前版本不可用」
 *   并写日志，不会抛异常拖挂其他功能。
 */
@Feature(
    name = "微信运动一键点赞",
    categories = ["系统与隐私"],
    description = "在微信运动排行榜一键给全部好友点赞，或在设置页手动执行"
)
object SportLikeAll : ClickableFeature(), IResolveDex {

    private const val TAG = "SportLikeAll"

    /** 排行榜页面（微信运动）。 */
    private const val RANK_UI_TAG = "MicroMsg.Sport.ExdeviceRankInfoUI"
    private const val RANK_UI_ONCREATE_TAG = "getWeRunTargetListFromServer"

    /** 点赞 CGI（z92.r 构造器内唯一串）。 */
    private const val ADD_LIKE_URI = "/cgi-bin/mmbiz-bin/rank/addlike"

    private const val TABLE_RANK = "HardDeviceRankInfo"
    private const val STATE_NOT_LIKED = 0
    private const val STATE_LIKED = 1

    private const val SCENE_TIMEOUT_MS = 6_000L
    private const val MAX_SEND_RETRY = 2
    private const val FAB_TAG = "WCX_SPORT_LIKE_ALL_FAB"
    private const val DEFAULT_MIN_INTERVAL = "300"
    private const val DEFAULT_MAX_INTERVAL = "800"

    // ── prefs ────────────────────────────────────────────────────────────────
    private var injectFabValue by prefOption("sport_like_inject_fab", true)
    private var minIntervalValue by prefOption("sport_like_min_interval", DEFAULT_MIN_INTERVAL)
    private var maxIntervalValue by prefOption("sport_like_max_interval", DEFAULT_MAX_INTERVAL)
    private var lastRankIdValue by prefOption("sport_like_last_rank_id", "")
    private var lastAppUsernameValue by prefOption("sport_like_last_app_username", "")

    // ── Dex 锚点 ─────────────────────────────────────────────────────────────
    /** 微信运动排行榜 Activity（onCreate 内两个串唯一）。 */
    private val classRankUi by dexClass(allowFailure = true) {
        matcher {
            methods {
                add {
                    name = "onCreate"
                    paramCount = 1
                    usingEqStrings(RANK_UI_TAG, RANK_UI_ONCREATE_TAG)
                }
            }
        }
    }

    /** 排行榜 Activity 的 onCreate，用于注入悬浮按钮并抓取 rank_id / app_username。 */
    private val methodRankUiOnCreate by dexMethod(allowFailure = true) {
        matcher {
            name = "onCreate"
            paramCount = 1
            usingEqStrings(RANK_UI_TAG, RANK_UI_ONCREATE_TAG)
        }
    }

    /** 点赞 NetScene：构造器内含 `/cgi-bin/mmbiz-bin/rank/addlike`。 */
    private val classAddLikeScene by dexClass(allowFailure = true) {
        matcher {
            methods {
                add {
                    name = "<init>"
                    usingEqStrings(ADD_LIKE_URI)
                }
            }
        }
    }

    /** 点赞 NetScene 的回包回调，用于串行推进与成功计数。 */
    private val methodAddLikeOnGYNetEnd by dexMethod(allowFailure = true) {
        if (classAddLikeScene.isPlaceholder) {
            matcher {
                name = "onGYNetEnd"
                usingEqStrings(ADD_LIKE_URI)
            }
        } else {
            matcher {
                declaredClass(classAddLikeScene.clazz)
                name = "onGYNetEnd"
            }
        }
    }

    // ── 运行时状态 ────────────────────────────────────────────────────────────
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val likeLock = Any()
    private val queue = ArrayDeque<RankRow>()
    private val retryAttempts = HashMap<String, Int>()
    @Volatile
    private var sessionActive = false
    private var inFlight: RankRow? = null
    private var successCount = 0
    private var failCount = 0
    private var watchdog: Runnable? = null

    /** 本次会话最近一次在排行页抓到的 rank_id / app_username（内存优先，其次 prefs）。 */
    @Volatile
    private var sessionRankId: String = ""

    @Volatile
    private var sessionAppUsername: String = ""

    private data class RankRow(
        val username: String,
        val rankId: String,
        val appUsername: String,
        val selfLikeState: Int,
        val likeCount: Int,
        val score: Long,
    )

    // ── 生命周期 ─────────────────────────────────────────────────────────────

    override fun onEnable() {
        // 重新启用时重建协程作用域（onDisable 会 cancel 掉旧的）
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        if (classAddLikeScene.isPlaceholder) {
            WeLogger.e(TAG, "未定位到点赞 NetScene（$ADD_LIKE_URI），一键点赞在本版本不可用")
        }
        if (methodRankUiOnCreate.isPlaceholder) {
            WeLogger.e(TAG, "未定位到微信运动排行榜页面（$RANK_UI_TAG），无法注入悬浮按钮")
        }

        if (!methodRankUiOnCreate.isPlaceholder) {
            runCatching {
                methodRankUiOnCreate.hookAfter {
                    runCatching { onRankUiCreated(thisObject) }
                        .onFailure { WeLogger.e(TAG, "处理微信运动排行榜页面失败", it) }
                }
            }.onFailure { WeLogger.e(TAG, "hook 微信运动排行榜页面失败", it) }
        }

        if (!methodAddLikeOnGYNetEnd.isPlaceholder) {
            runCatching {
                methodAddLikeOnGYNetEnd.hookAfter {
                    runCatching { onLikeSceneEnded(args.getOrNull(1) as? Int ?: -1) }
                        .onFailure { WeLogger.e(TAG, "处理点赞回包失败", it) }
                }
            }.onFailure { WeLogger.e(TAG, "hook 点赞回包失败", it) }
        } else {
            WeLogger.w(TAG, "点赞回包回调未定位到，将退化为「按发送成功计数」")
        }
    }

    override fun onDisable() {
        stopSession()
        runCatching { scope.cancel() }
        synchronized(likeLock) {
            queue.clear()
            retryAttempts.clear()
        }
    }

    // ── 排行榜页面：抓取参数 + 注入悬浮按钮 ────────────────────────────────────

    private fun onRankUiCreated(instance: Any?) {
        val activity = instance as? Activity ?: return

        val rankId = runCatching { activity.intent?.getStringExtra("rank_id") }.getOrNull().orEmpty()
        val appUsername =
            runCatching { activity.intent?.getStringExtra("app_username") }.getOrNull().orEmpty()
        if (rankId.isNotBlank()) {
            sessionRankId = rankId
            lastRankIdValue = rankId
        }
        if (appUsername.isNotBlank()) {
            sessionAppUsername = appUsername
            lastAppUsernameValue = appUsername
        }
        WeLogger.i(TAG, "微信运动排行榜已打开 rankId=${rankId.ifBlank { "<空>" }} appUsername=${appUsername.ifBlank { "<空>" }}")

        injectFloatingButton(activity)
    }

    private fun injectFloatingButton(activity: Activity) {
        if (!injectFabValue) return
        val root = runCatching { activity.window?.decorView as? ViewGroup }.getOrNull() ?: run {
            WeLogger.w(TAG, "无法取得 decorView，跳过悬浮按钮注入")
            return
        }
        if (root.findViewWithTag<View>(FAB_TAG) != null) return

        val density = activity.resources.displayMetrics.density
        val button = TextView(activity).apply {
            tag = FAB_TAG
            text = "一键点赞"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val padH = (14 * density).toInt()
            val padV = (8 * density).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setCornerRadius(24 * density)
                setColor(Color.parseColor("#07C160"))
            }
            setOnClickListener {
                runCatching { startLikeAll() }
                    .onFailure { e -> WeLogger.e(TAG, "一键点赞启动失败", e) }
            }
        }

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = (16 * density).toInt()
            bottomMargin = (150 * density).toInt()
        }

        runCatching { root.addView(button, lp) }
            .onFailure { e -> WeLogger.e(TAG, "注入一键点赞悬浮按钮失败", e) }
    }

    // ── 数据读取 ─────────────────────────────────────────────────────────────

    private fun queryUnlikedRows(): List<RankRow> {
        if (classAddLikeScene.isPlaceholder) return emptyList()

        if (!WeDatabaseApi.isReady) {
            WeLogger.w(TAG, "微信数据库尚未就绪，无法读取 $TABLE_RANK，请稍后重试")
            return emptyList()
        }

        val sql = "SELECT rankID, appusername, username, selfLikeState, likecount, score FROM $TABLE_RANK"
        val records = WeDatabaseApi.executeQuery(sql)
        if (records.isEmpty()) {
            WeLogger.w(TAG, "$TABLE_RANK 表暂无数据（需要先打开一次微信运动排行榜同步数据）")
            return emptyList()
        }

        val self = runCatching { WeApi.selfWxId }.getOrDefault("")
        val preferRank = sessionRankId.ifBlank { lastRankIdValue }
        val preferApp = sessionAppUsername.ifBlank { lastAppUsernameValue }

        val seen = HashSet<String>()
        val rows = ArrayList<RankRow>()
        for (record in records) {
            val username = record["username"].asTrimmedString()
            if (username.isEmpty()) continue
            if (self.isNotEmpty() && username == self) continue
            val state = record["selfLikeState"].asIntOrNull() ?: continue
            if (state != STATE_NOT_LIKED) continue

            val rankId = record["rankID"].asTrimmedString()
            if (preferRank.isNotEmpty() && rankId.isNotEmpty() && rankId != preferRank) continue

            val appUsername = record["appusername"].asTrimmedString().ifBlank { preferApp }
            if (!seen.add(username)) continue

            rows.add(
                RankRow(
                    username = username,
                    rankId = rankId,
                    appUsername = appUsername,
                    selfLikeState = state,
                    likeCount = record["likecount"].asIntOrNull() ?: 0,
                    score = record["score"].asLongOrNull() ?: 0L,
                )
            )
        }

        WeLogger.i(
            TAG,
            "排行表共 ${records.size} 行，未点赞且可处理 ${rows.size} 人" +
                "（rankId=${preferRank.ifBlank { "<未捕获>" }}）"
        )
        return rows
    }

    private fun Any?.asTrimmedString(): String = when (this) {
        null -> ""
        is String -> trim()
        else -> toString().trim()
    }

    private fun Any?.asIntOrNull(): Int? = when (this) {
        is Number -> toInt()
        is String -> trim().toIntOrNull()
        else -> null
    }

    private fun Any?.asLongOrNull(): Long? = when (this) {
        is Number -> toLong()
        is String -> trim().toLongOrNull()
        else -> null
    }

    // ── 一键点赞会话 ──────────────────────────────────────────────────────────

    private fun startLikeAll() {
        if (sessionActive) {
            showToastSafe("正在点赞中，请稍候…")
            return
        }
        scope.launch {
            val rows = runCatching { queryUnlikedRows() }.getOrElse { e ->
                WeLogger.e(TAG, "查询微信运动排行数据失败", e)
                emptyList()
            }
            withContext(Dispatchers.Main) {
                if (rows.isEmpty()) {
                    showToastSafe("没有可点赞的好友（排行数据为空或已全部点赞）")
                    return@withContext
                }
                beginSession(rows)
            }
        }
    }

    private fun beginSession(rows: List<RankRow>) {
        synchronized(likeLock) {
            queue.clear()
            queue.addAll(rows)
            retryAttempts.clear()
            successCount = 0
            failCount = 0
            inFlight = null
            sessionActive = true
        }
        WeLogger.i(TAG, "开始一键点赞，共 ${rows.size} 人")
        showToastSafe("开始点赞，共 ${rows.size} 人")
        sendNext()
    }

    private fun sendNext() {
        synchronized(likeLock) {
            if (!sessionActive) return
            if (queue.isEmpty()) {
                finishSessionLocked()
                return
            }
            val next = queue.removeFirst()
            inFlight = next
            dispatch(next)
        }
    }

    /** 必须在 likeLock 内调用。 */
    private fun dispatch(row: RankRow) {
        val sent = runCatching { sendLikeScene(row) }.getOrElse { e ->
            WeLogger.e(TAG, "构造点赞请求失败 username=${row.username}", e)
            false
        }

        if (sent) {
            armWatchdog(row)
            return
        }

        inFlight = null
        val attempt = (retryAttempts[row.username] ?: 0) + 1
        if (attempt <= MAX_SEND_RETRY) {
            retryAttempts[row.username] = attempt
            queue.addFirst(row)
            WeLogger.w(TAG, "点赞请求入队被拒，${attempt}/${MAX_SEND_RETRY} 次重试 username=${row.username}")
            mainHandler.postDelayed({ runCatching { sendNext() } }, 600L)
            return
        }

        failCount++
        WeLogger.e(TAG, "点赞请求多次入队失败，放弃 username=${row.username}")
        scheduleNextLocked()
    }

    /** 必须在 likeLock 内调用。 */
    private fun armWatchdog(row: RankRow) {
        watchdog?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            var timedOut = false
            synchronized(likeLock) {
                if (inFlight === row) {
                    inFlight = null
                    failCount++
                    timedOut = true
                }
            }
            if (timedOut) {
                WeLogger.w(TAG, "点赞回包超时（${SCENE_TIMEOUT_MS}ms）username=${row.username}")
                synchronized(likeLock) { scheduleNextLocked() }
            }
        }
        watchdog = runnable
        mainHandler.postDelayed(runnable, SCENE_TIMEOUT_MS)
    }

    /** 必须在 likeLock 内调用。 */
    private fun scheduleNextLocked() {
        if (!sessionActive) return
        if (queue.isEmpty()) {
            finishSessionLocked()
            return
        }
        val min = minIntervalValue.toLongOrNull()?.coerceAtLeast(0L) ?: 300L
        val max = maxIntervalValue.toLongOrNull()?.coerceAtLeast(0L) ?: 800L
        val safeMin = min.coerceAtLeast(100L)
        val safeMax = maxOf(max, safeMin)
        val delayMs = if (safeMax > safeMin) Random.nextLong(safeMin, safeMax) else safeMin
        mainHandler.postDelayed({ runCatching { sendNext() } }, delayMs)
    }

    /** 必须在 likeLock 内调用。 */
    private fun finishSessionLocked() {
        if (!sessionActive) return
        sessionActive = false
        inFlight = null
        watchdog?.let { mainHandler.removeCallbacks(it) }
        watchdog = null
        val ok = successCount
        val bad = failCount
        retryAttempts.clear()
        WeLogger.i(TAG, "一键点赞结束：成功 $ok，失败 $bad")
        showToastSafe(if (bad > 0) "已点赞 $ok 人（失败 $bad）" else "已点赞 $ok 人")
    }

    private fun sendLikeScene(row: RankRow): Boolean {
        if (classAddLikeScene.isPlaceholder) {
            WeLogger.e(TAG, "点赞 NetScene 未定位到，跳过 username=${row.username}")
            return false
        }
        val appUsername = row.appUsername.ifBlank {
            sessionAppUsername.ifBlank { lastAppUsernameValue }
        }
        // 构造器：z92.r(username, app_username, state, rank_id)；state=1 表示点赞。
        val scene = classAddLikeScene.clazz.createInstance(
            row.username,
            appUsername,
            STATE_LIKED,
            row.rankId,
        )
        return WeNetSceneApi.sendNetScene(scene)
    }

    private fun onLikeSceneEnded(errCode: Int) {
        if (!sessionActive) return
        var finished = false
        val username = synchronized(likeLock) {
            val row = inFlight ?: return
            inFlight = null
            if (errCode == 0) successCount++ else failCount++
            watchdog?.let { mainHandler.removeCallbacks(it) }
            watchdog = null
            if (queue.isEmpty()) {
                finishSessionLocked()
                finished = true
            }
            row.username
        }
        WeLogger.i(TAG, "点赞回包 errCode=$errCode username=$username")
        if (!finished) {
            synchronized(likeLock) { scheduleNextLocked() }
        }
    }

    private fun stopSession() {
        synchronized(likeLock) {
            if (!sessionActive) return
            sessionActive = false
            queue.clear()
            inFlight = null
            watchdog?.let { mainHandler.removeCallbacks(it) }
            watchdog = null
        }
        WeLogger.i(TAG, "一键点赞已停止（关闭功能）")
    }

    private fun showToastSafe(text: String) {
        runCatching { mainHandler.post { runCatching { showToast(text) } } }
    }

    // ── 设置页手动入口 ────────────────────────────────────────────────────────

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var minInput by remember { mutableStateOf(minIntervalValue) }
            var maxInput by remember { mutableStateOf(maxIntervalValue) }
            var fabOn by remember { mutableStateOf(injectFabValue) }
            val sceneReady = !classAddLikeScene.isPlaceholder
            val pageReady = !classRankUi.isPlaceholder && !methodRankUiOnCreate.isPlaceholder

            AlertDialogContent(
                title = { Text("微信运动一键点赞") },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        ListItem(
                            headlineContent = {
                                Text(if (sceneReady) "点赞接口：已定位" else "点赞接口：当前版本不可用")
                            },
                            supportingContent = {
                                Text(
                                    if (sceneReady) "CGI $ADD_LIKE_URI"
                                    else "未在当前微信版本定位到点赞接口（已写入日志），可改用微信运动页面手动点赞"
                                )
                            },
                        )
                        ListItem(
                            headlineContent = {
                                Text(if (pageReady) "排行页入口：已就绪" else "排行页入口：当前版本不可用")
                            },
                            supportingContent = {
                                Text(
                                    if (pageReady) "打开「微信运动 → 排行榜」后右下角会出现「一键点赞」按钮"
                                    else "未在当前微信版本定位到排行榜页面（已写入日志），只能使用下方手动入口"
                                )
                            },
                        )
                        ListItem(
                            headlineContent = { Text("最近同步的排行") },
                            supportingContent = {
                                Text(
                                    "rank_id = ${lastRankIdValue.ifBlank { "<未捕获>" }}\n" +
                                        "app_username = ${lastAppUsernameValue.ifBlank { "<未捕获>" }}"
                                )
                            },
                        )
                        TextField(
                            value = minInput,
                            onValueChange = { minInput = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("点赞间隔下限 (毫秒)") },
                            supportingText = { Text("默认 300，低于 100 会被抬到 100") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        TextField(
                            value = maxInput,
                            onValueChange = { maxInput = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("点赞间隔上限 (毫秒)") },
                            supportingText = { Text("默认 800，实际在 [下限, 上限) 随机") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        ListItem(
                            trailingContent = {
                                Switch(checked = fabOn, onCheckedChange = { fabOn = it })
                            },
                            headlineContent = { Text("注入排行榜悬浮按钮") },
                            supportingContent = { Text("关闭后仅在设置页手动执行") },
                        )
                        Button(
                            enabled = sceneReady && !sessionActive,
                            onClick = {
                                minIntervalValue = minInput.ifBlank { DEFAULT_MIN_INTERVAL }
                                maxIntervalValue = maxInput.ifBlank { DEFAULT_MAX_INTERVAL }
                                injectFabValue = fabOn
                                startLikeAll()
                                onDismiss()
                            },
                        ) {
                            Text(if (sessionActive) "点赞进行中…" else "立即为全部好友点赞")
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        minIntervalValue = minInput.ifBlank { DEFAULT_MIN_INTERVAL }
                        maxIntervalValue = maxInput.ifBlank { DEFAULT_MAX_INTERVAL }
                        injectFabValue = fabOn
                        onDismiss()
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
            )
        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text("提示") },
                    text = { Text("一键点赞会给排行榜里的所有好友点赞，属于自动化操作，存在账号异常风险，确定启用吗?") },
                    confirmButton = {
                        Button(onClick = {
                            applyToggle(true)
                            onDismiss()
                        }) { Text("确定") }
                    },
                    dismissButton = { TextButton(onDismiss) { Text("取消") } },
                )
            }
            return false
        }
        return true
    }
}
