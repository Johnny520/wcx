package com.Johnny.wcx.ai.group

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 群总结的触发层：闹钟 + 进程内定时器 + 启动补跑。
 *
 * **计时依据是落盘的 `anchorAt` / `lastSlotEnd`，不是这里的定时器。** 微信关掉不会让计时
 * 错乱，只会让「到点那一刻没跑」；下次 `onEnable` 会按 `isDue` 补一次。
 *
 * 闹钟的能力边界（与 `ScheduledMessage` 一致，接收器是动态注册的）：
 * - 进程活着：闹钟与 `delay` 双源，靠 `lastSlotEnd` 去重；
 * - 进程被杀 / force-stop：闹钟投递不到，**拉不起已死的微信**，只能等下次启动补跑。
 */
object GroupDigestScheduler {

    private const val TAG = "GroupDigestScheduler"
    private const val ACTION = "com.Johnny.wcx.GROUP_DIGEST_ALARM"
    private const val EXTRA_GROUP_ID = "group_id"
    private const val INTERVAL_BETWEEN_GROUPS_MS = 5000L
    private const val DUE_TRIGGER_DELAY_MS = 2000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timers = ConcurrentHashMap<String, Job>()

    @Volatile
    private var receiver: BroadcastReceiver? = null

    fun onEnable() {
        registerReceiver()
        scope.launch { catchUp() }
    }

    fun onDisable() {
        unregisterReceiver()
        timers.values.forEach { it.cancel() }
        timers.clear()
        GroupDigestStore.loadIndex().groups.forEach { cancelAlarm(it.groupId) }
    }

    /**
     * 启动补跑：已经过了点的群各跑一次，串行、群间 5s。
     *
     * 不按错过的槽位连发：进程死一晚只补一条覆盖整段的摘要。
     */
    private suspend fun catchUp() {
        val now = System.currentTimeMillis()
        val due = GroupDigestStore.loadIndex().groups
            .asSequence()
            .filter { it.enabled }
            .mapNotNull { GroupDigestStore.readConfig(it.groupId) }
            .filter { it.isDue(now) }
            .toList()
        if (due.isEmpty()) return
        WeLogger.i(TAG, "启动补跑 ${due.size} 个群")
        for (config in due) {
            GroupDigestRunner.run(config.groupId)
            delay(INTERVAL_BETWEEN_GROUPS_MS)
        }
        rescheduleAll()
    }

    fun rescheduleAll() {
        GroupDigestStore.loadIndex().groups.forEach { schedule(it.groupId) }
    }

    /** 按最新配置重排一个群：改频率、开关、换群名之后都要调一次。 */
    fun schedule(groupId: String) {
        val config = GroupDigestStore.readConfig(groupId) ?: return
        cancel(groupId)
        if (!config.enabled) return
        val now = System.currentTimeMillis()
        val at = if (config.isDue(now)) now + DUE_TRIGGER_DELAY_MS else config.nextRunAt(now)
        if (at <= 0L) return
        setAlarm(groupId, at)
        scheduleTimer(groupId, at)
    }

    fun cancel(groupId: String) {
        timers.remove(groupId)?.cancel()
        cancelAlarm(groupId)
    }

    private fun scheduleTimer(groupId: String, atMs: Long) {
        val delayMs = atMs - System.currentTimeMillis()
        if (delayMs <= 0L) return
        timers[groupId] = scope.launch {
            try {
                delay(delayMs)
                handleTrigger(groupId)
            } catch (_: CancellationException) {
                // 群被删除 / 关闭 / 改频率
            } catch (e: Throwable) {
                WeLogger.e(TAG, "进程内定时器失败：$groupId", e)
            }
        }
    }

    /**
     * 闹钟与定时器都会进来，可能同时触发两次：
     * `run` 的每群单飞锁会挡住第二个，这里据此决定要不要重排 ——
     * 「正在总结」时重排会在 2 秒后再触发一次，形成空转。
     */
    private fun handleTrigger(groupId: String) {
        val config = GroupDigestStore.readConfig(groupId) ?: return
        if (!config.enabled) return
        scope.launch {
            val result = GroupDigestRunner.run(groupId)
            if (!result.busy) schedule(groupId)
        }
    }

    // ── 闹钟 ────────────────────────────────────────────────────────────────

    private fun setAlarm(groupId: String, atMs: Long) {
        val manager = HostInfo.application.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return
        val pending = alarmIntent(groupId)
        val canExact = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.canScheduleExactAlarms() else true
        }.getOrDefault(false)
        runCatching {
            if (canExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            }
        }.onFailure {
            // 部分 ROM 不认 canScheduleExactAlarms 的结果，当场降级。
            WeLogger.w(TAG, "精确闹钟失败，降级：${it.javaClass.simpleName}")
            runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending) }
        }
    }

    private fun cancelAlarm(groupId: String) {
        val manager = HostInfo.application.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return
        runCatching { manager.cancel(alarmIntent(groupId)) }
    }

    /** 同一群只保留一个闹钟：requestCode 由 groupId 稳定派生。 */
    private fun alarmIntent(groupId: String): PendingIntent {
        val intent = Intent(ACTION)
            .setPackage(HostInfo.application.packageName)
            .putExtra(EXTRA_GROUP_ID, groupId)
        return PendingIntent.getBroadcast(
            HostInfo.application,
            groupId.hashCode() and 0x7FFFFFFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ── 接收器 ──────────────────────────────────────────────────────────────

    private fun registerReceiver() {
        if (receiver != null) return
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val groupId = intent?.getStringExtra(EXTRA_GROUP_ID) ?: return
                handleTrigger(groupId)
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                HostInfo.application,
                created,
                IntentFilter(ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiver = created
        }.onFailure {
            WeLogger.e(TAG, "注册闹钟接收器失败", it)
        }
    }

    private fun unregisterReceiver() {
        receiver?.let { runCatching { HostInfo.application.unregisterReceiver(it) } }
        receiver = null
    }
}
