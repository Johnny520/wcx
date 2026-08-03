package com.Johnny.wcx.features.items.contacts.hidecontacts

import com.Johnny.wcx.utils.WeLogger
import java.util.Calendar
import kotlin.concurrent.thread

private const val TAG = "HideContacts.Schedule"

/**
 * Manages timed hide/show schedules for HideContacts.
 * Integrates with the WCX HideContacts feature to provide scheduled visibility control.
 */
object HideContactsSchedule {

    private val lock = Any()
    private var running = false
    private var watchThread: Thread? = null

    // Callback references - set by HideContacts during install
    var onHide: (() -> Unit)? = null
    var onShow: (() -> Unit)? = null
    var getSchedules: (() -> List<HideSchedule>)? = null

    fun install() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        WeLogger.d(TAG, "schedule watcher starting")
        watchThread = thread(name = "HideScheduleWatcher", isDaemon = true) {
            try {
                watchLoop()
            } catch (e: InterruptedException) {
                // expected on uninstall
            } catch (e: Exception) {
                WeLogger.w(TAG, "schedule watcher error", e)
            }
        }
    }

    fun uninstall() {
        synchronized(lock) {
            running = false
        }
        watchThread?.interrupt()
        watchThread = null
        WeLogger.d(TAG, "schedule watcher stopped")
    }

    private fun watchLoop() {
        while (synchronized(lock) { running }) {
            try {
                val schedules = getSchedules?.invoke() ?: emptyList()
                val now = Calendar.getInstance()
                val currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                val currentDayOfWeek = now.get(Calendar.DAY_OF_WEEK) // 1=Sunday, ..., 7=Saturday

                for (schedule in schedules) {
                    if (!schedule.enabled) continue
                    if (!schedule.daysOfWeek.contains(currentDayOfWeek)) continue

                    val shouldTrigger = when (schedule.kind) {
                        HideScheduleKind.REPEATING -> schedule.minuteOfDay == currentMinute
                        HideScheduleKind.ONCE -> {
                            schedule.atEpochMillis > 0L &&
                                    System.currentTimeMillis() >= schedule.atEpochMillis
                        }
                    }

                    if (shouldTrigger) {
                        when (schedule.action) {
                            HideScheduleAction.HIDE -> {
                                WeLogger.i(TAG, "triggering scheduled HIDE: ${schedule.id}")
                                onHide?.invoke()
                            }
                            HideScheduleAction.SHOW -> {
                                WeLogger.i(TAG, "triggering scheduled SHOW: ${schedule.id}")
                                onShow?.invoke()
                            }
                        }
                    }
                }

                // Check every 30 seconds
                Thread.sleep(30_000L)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                WeLogger.w(TAG, "schedule check error", e)
                try {
                    Thread.sleep(30_000L)
                } catch (_: InterruptedException) {
                    throw InterruptedException()
                }
            }
        }
    }
}

fun newHideScheduleId(): String = "hsched_${System.currentTimeMillis()}"