package com.Johnny.wcx.features.items.moments

import android.content.ContentValues
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.ui.WeMomentsApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

@Feature(
    name = "自动点赞",
    categories = ["朋友圈"],
    description = "浏览朋友圈时自动点赞"
)
object AutoLikeMoments : AutoMomentsBase(),
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener,
    AutoRefresh.IRefreshListener {

    override val TAG = "AutoLikeMoments"

    private const val MODE_WHEN_SEEN = 0
    private const val MODE_ALL_LOADED = 1
    private const val ACTION_LIKE = 0
    private const val ACTION_UNLIKE = 1
    private const val RETRY_INTERVAL_MS = 30_000L
    private const val MAX_ACTION_DELAY_MS = 300_000L

    private val handledSnsIds = ConcurrentHashMap.newKeySet<String>()
    private val lastAttemptAt = ConcurrentHashMap<String, Long>()
    private val actionLock = Any()

    @Volatile
    private var lastActionSentAt = 0L

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        AutoRefresh.addListener(this)

        installTimelineHooks()

        if (currentMode == MODE_ALL_LOADED) {
            scanCachedTargetMoments()
        }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        AutoRefresh.removeListener(this)
    }

    /** Called by [AutoRefresh] on every scheduled refresh cycle. */
    override fun onRefresh() {
        if (currentMode == MODE_ALL_LOADED) {
            scanCachedTargetMoments()
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var mode by remember { mutableIntStateOf(currentMode) }
            var action by remember { mutableIntStateOf(currentAction) }
            var delayInput by remember { mutableStateOf(actionDelayMs.toString()) }
            var useWhitelist by remember { mutableStateOf(momentsUseWhitelist) }

            AlertDialogContent(
                title = { Text("自动点赞") },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        ListItem(
                            modifier = Modifier.clickable { useWhitelist = !useWhitelist },
                            trailingContent = { Switch(checked = useWhitelist, onCheckedChange = null) },
                            supportingContent = { Text(if (useWhitelist) "仅对选中联系人点赞" else "对选中联系人跳过点赞") },
                            headlineContent = { Text(if (useWhitelist) "黑名单 [> 白名单 <]" else "[> 黑名单 <] 白名单") },
                        )

                        ListItem(
                            modifier = Modifier.clickable {
                                val regularContacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
                                val currentList = if (useWhitelist) momentsWhitelist else momentsBlacklist

                                showComposeDialog(context) {
                                    ContactsSelector(
                                        title = if (useWhitelist) "选择白名单" else "选择黑名单",
                                        contacts = regularContacts,
                                        initialSelectedWxIds = currentList,
                                        onDismiss = onDismiss
                                    ) { selectedIds ->
                                        if (useWhitelist) {
                                            momentsWhitelist = selectedIds
                                        } else {
                                            momentsBlacklist = selectedIds
                                        }
                                        showToast("已保存 ${selectedIds.size} 个联系人, 重启微信以使更改生效")
                                        onDismiss()
                                    }
                                }
                            },
                            supportingContent = { Text("点击选择联系人") },
                            headlineContent = { Text(if (useWhitelist) "配置白名单" else "配置黑名单") },
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Text(
                            text = "操作类型",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        Column(Modifier.selectableGroup()) {
                            ModeRow(
                                title = "自动点赞模式",
                                summary = "浏览或同步朋友圈时, 自动给指定目标点赞",
                                checked = action == ACTION_LIKE,
                                onClick = { action = ACTION_LIKE }
                            )
                            ModeRow(
                                title = "取消点赞模式",
                                summary = "浏览或同步朋友圈时, 自动取消你已点过的赞",
                                checked = action == ACTION_UNLIKE,
                                onClick = { action = ACTION_UNLIKE }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Text(
                            text = "运行机制",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        Column(Modifier.selectableGroup()) {
                            ModeRow(
                                title = "刷到时即时处理",
                                summary = "仅在滚动浏览朋友圈、视图可见时触发对应操作",
                                checked = mode == MODE_WHEN_SEEN,
                                onClick = { mode = MODE_WHEN_SEEN }
                            )
                            ModeRow(
                                title = "本地缓存全量处理",
                                summary = "自动扫描本地已缓存和后续收到的所有目标朋友圈\n需启用「朋友圈/自动刷新」",
                                checked = mode == MODE_ALL_LOADED,
                                onClick = { mode = MODE_ALL_LOADED }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        TextField(
                            value = delayInput,
                            onValueChange = { delayInput = it.filter { c -> c.isDigit() }.take(6) },
                            label = { Text("操作间隔 (毫秒)") },
                            supportingText = { Text("在实际发送点赞/取消点赞请求之间等待") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            momentsUseWhitelist = useWhitelist
                            currentMode = mode
                            currentAction = action
                            actionDelayMs = (delayInput.toLongOrNull() ?: 0L).coerceIn(0L, MAX_ACTION_DELAY_MS)
                            handledSnsIds.clear()
                            lastAttemptAt.clear()
                            showToast("已保存")
                            if (mode == MODE_ALL_LOADED) {
                                scanCachedTargetMoments()
                            }
                            onDismiss()
                        }
                    ) {
                        Text("保存")
                    }
                }
            )
        }
    }

    override fun onInsert(table: String, values: ContentValues) {
        processSnsInfoValues(table, values)
    }

    override fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int) {
        processSnsInfoValues(table, values)
    }

    override fun processVisibleItems(list: ViewGroup) {
        // In whitelist mode, bail if the list is empty. In blacklist mode, an empty blacklist means
        // "process everyone" so we must not bail.
        if (momentsUseWhitelist && momentsWhitelist.isEmpty()) return
        for (i in 0 until list.childCount) {
            runCatching {
                locateSnsInfo(list.getChildAt(i))?.let { processSnsInfoAsync(it, "visible") }
            }.onFailure {
                WeLogger.w(TAG, "failed to process visible Moments item", it)
            }
        }
    }

    private fun processSnsInfoValues(table: String, values: ContentValues) {
        if (table != "SnsInfo") return
        if (currentMode != MODE_ALL_LOADED) return

        val owner = values.getAsString("userName")?.trim().orEmpty()
        if (!isTarget(owner)) return

        // Skip deleted/recalled moments (sourceType != 0)
        val sourceType = values.getAsInteger("sourceType") ?: 0
        if (sourceType != 0) return

        val action = currentAction
        val likeFlag = values.getAsInteger("likeFlag") ?: 0
        if (action == ACTION_LIKE && likeFlag != 0) return
        if (action == ACTION_UNLIKE && likeFlag == 0) return

        val snsId = values.getAsLong("snsId") ?: return
        val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: return
        processSnsInfoAsync(snsInfo, "database")
    }

    private fun scanCachedTargetMoments() {
        if (momentsUseWhitelist && momentsWhitelist.isEmpty()) return
        thread(name = "ScanMomentsToAutoLikeThread") {
            WeLogger.d(TAG, "scanCachedTargetMoments: scanning (useWhitelist=$momentsUseWhitelist)")
            val snsIds = runCatching {
                queryCachedTargetSnsIds()
            }.onFailure {
                WeLogger.w(TAG, "failed to query cached target moments", it)
            }.getOrDefault(emptyList())

            WeLogger.d(TAG, "scanCachedTargetMoments: found ${snsIds.size} cached moments")
            for (snsId in snsIds) {
                val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: run {
                    WeLogger.w(TAG, "scanCachedTargetMoments: failed to get snsInfo for snsId=$snsId")
                    continue
                }
                WeLogger.d(TAG, "scanCachedTargetMoments: processing snsId=$snsId")
                processSnsInfo(snsInfo, "cached")
            }
        }
    }

    private fun queryCachedTargetSnsIds(): List<Long> {
        if (!WeDatabaseApi.isReady) return emptyList()

        val likePredicate = if (currentAction == ACTION_UNLIKE) {
            "IFNULL(likeFlag, 0) != 0"
        } else {
            "IFNULL(likeFlag, 0) = 0"
        }

        val (userFilter, args) = if (momentsUseWhitelist) {
            val whitelist = momentsWhitelist
            if (whitelist.isEmpty()) return emptyList()
            val placeholders = whitelist.joinToString(",") { "?" }
            "AND userName IN ($placeholders)" to whitelist.map { it as Any }.toTypedArray()
        } else {
            val blacklist = momentsBlacklist
            if (blacklist.isEmpty()) {
                "" to emptyArray()
            } else {
                val placeholders = blacklist.joinToString(",") { "?" }
                "AND userName NOT IN ($placeholders)" to blacklist.map { it as Any }.toTypedArray()
            }
        }

        val sql = """
            SELECT snsId
            FROM SnsInfo
            WHERE $likePredicate
              AND snsId != 0
              AND (sourceType = 0)
              $userFilter
            ORDER BY createTime DESC
        """.trimIndent()

        WeLogger.d(TAG, "queryCachedTargetSnsIds: sql=$sql, args=${args.joinToString(",")}")

        val result = mutableListOf<Long>()
        WeDatabaseApi.rawQuery(sql, args).use { cursor ->
            WeLogger.d(TAG, "queryCachedTargetSnsIds: cursor count=${cursor.count}")
            while (cursor.moveToNext()) {
                val snsId = cursor.getLong(0)
                WeLogger.d(TAG, "queryCachedTargetSnsIds: found snsId=$snsId")
                result += snsId
            }
        }
        WeLogger.d(TAG, "queryCachedTargetSnsIds: returning ${result.size} results")
        return result
    }

    private fun processSnsInfo(snsInfo: Any, source: String) {
        val owner = WeMomentsApi.getOwnerWxId(snsInfo)?.trim().orEmpty()
        WeLogger.d(TAG, "processSnsInfo: source=$source, owner=$owner, isTarget=${isTarget(owner)}")
        if (!isTarget(owner)) return
        if (owner == WeApi.selfWxId) return

        if (WeMomentsApi.isDeleted(snsInfo)) {
            WeLogger.d(TAG, "processSnsInfo: skipping deleted moments for owner=$owner")
            return
        }

        val snsTableId = WeMomentsApi.getSnsTableId(snsInfo) ?: run {
            WeLogger.w(TAG, "processSnsInfo: failed to get snsTableId for owner=$owner")
            return
        }

        if (isIntercepted(snsInfo)) {
            WeLogger.d(TAG, "processSnsInfo: skipping intercepted moments for owner=$owner")
            return
        }

        WeLogger.d(TAG, "processSnsInfo: processing snsTableId=$snsTableId, owner=$owner, source=$source")

        if (snsTableId in handledSnsIds) return
        val action = currentAction
        val liked = WeMomentsApi.isLiked(snsInfo)
        if (action == ACTION_LIKE && liked) {
            handledSnsIds.add(snsTableId)
            return
        }
        if (action == ACTION_UNLIKE && !liked) {
            handledSnsIds.add(snsTableId)
            return
        }
        if (!canAttempt(snsTableId)) return

        val result = sendWithDelay {
            val latestOwner = WeMomentsApi.getOwnerWxId(snsInfo)?.trim().orEmpty()
            if (!isTarget(latestOwner) || latestOwner == WeApi.selfWxId) {
                WeMomentsApi.ActionResult(success = true, sent = false, message = "target skipped")
            } else if (WeMomentsApi.isDeleted(snsInfo)) {
                WeMomentsApi.ActionResult(success = true, sent = false, message = "deleted/recalled")
            } else {
                val latestLiked = WeMomentsApi.isLiked(snsInfo)
                when (action) {
                    ACTION_LIKE if latestLiked ->
                        WeMomentsApi.ActionResult(success = true, sent = false, message = "already liked")

                    ACTION_UNLIKE if !latestLiked ->
                        WeMomentsApi.ActionResult(success = true, sent = false, message = "already unliked")

                    ACTION_UNLIKE ->
                        WeMomentsApi.unlike(snsInfo)

                    else -> WeMomentsApi.like(snsInfo)
                }
            }
        }
        if (result.success) {
            handledSnsIds.add(snsTableId)
            WeLogger.i(TAG, "auto-${actionLabel(action)} $source sent=${result.sent}, owner=$owner, sns=$snsTableId")
        } else {
            val message = "auto-${actionLabel(action)} $source failed, owner=$owner, sns=$snsTableId, message=${result.message}"
            result.error?.let { WeLogger.w(TAG, message, it) } ?: WeLogger.w(TAG, message)
        }
    }

    private fun canAttempt(snsTableId: String): Boolean {
        synchronized(lastAttemptAt) {
            val now = System.currentTimeMillis()
            val last = lastAttemptAt[snsTableId] ?: 0L
            if (now - last < RETRY_INTERVAL_MS) return false
            lastAttemptAt[snsTableId] = now
            return true
        }
    }

    private fun processSnsInfoAsync(snsInfo: Any, source: String) {
        thread(name = "AutoLikeMomentThread") {
            processSnsInfo(snsInfo, source)
        }
    }

    private fun sendWithDelay(block: () -> WeMomentsApi.ActionResult): WeMomentsApi.ActionResult =
        synchronized(actionLock) {
            val delay = actionDelayMs
            if (delay > 0) {
                val wait = delay - (System.currentTimeMillis() - lastActionSentAt)
                if (wait > 0) Thread.sleep(wait)
            }

            val result = block()
            if (result.sent) {
                lastActionSentAt = System.currentTimeMillis()
            }
            result
        }

    private fun actionLabel(action: Int): String =
        if (action == ACTION_UNLIKE) "unlike" else "like"

    private fun isTarget(wxId: String): Boolean {
        if (wxId.isBlank()) return false
        return if (momentsUseWhitelist) wxId in momentsWhitelist else wxId !in momentsBlacklist
    }

    private var currentMode by WePrefs.prefOption("moments_auto_like_mode", MODE_WHEN_SEEN)
    private var currentAction by WePrefs.prefOption("moments_auto_like_action", ACTION_LIKE)
    private var actionDelayMs by WePrefs.prefOption("moments_auto_like_action_delay_ms", 0L)

    private var momentsUseWhitelist by WePrefs.prefOption("moments_use_whitelist", true)
    private var momentsWhitelist by WePrefs.prefOption("moments_whitelist", emptySet())
    private var momentsBlacklist by WePrefs.prefOption("moments_blacklist", emptySet())
}

@Composable
private fun ModeRow(
    title: String,
    summary: String,
    checked: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .clickable(onClick = onClick)
            .fillMaxWidth(),
        leadingContent = {
            RadioButton(
                selected = checked,
                onClick = null
            )
        },
        supportingContent = { Text(summary) },
        headlineContent = { Text(title) },
    )
}
