package com.Johnny.wcx.dynamic

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.Johnny.wcx.utils.WeLogger
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自修复监控器 — 监控四大 Tab 加载状态，自动修复空白页或无限加载。
 *
 * 策略：
 * - 不 Hook Activity 生命周期
 * - 使用轮询方式检测页面状态
 * - 检测到空白页/无限加载时自动修复
 * - 通过调整视图层级或触发布局刷新来修复
 *
 * 监控指标：
 * - 四大 Tab（微信/通讯录/发现/我）是否正常加载
 * - 页面是否出现空白（无子 View）
 * - 页面是否出现无限加载（长时间无变化）
 */
object SelfHealingMonitor {

    private const val TAG = "SelfHealingMonitor"

    // 监控配置
    // 3 秒一次会在主线程持续遍历 View 树（hasVisibleChild 递归 + Tab 检查），
    // 是微信卡顿的常驻开销来源；15 秒足够发现「页面空白」这类问题。
    /**
     * 轮询间隔。原先 15 秒就会在主线程跑一遍「找 Activity → 判空白 → 找底部栏」，
     * 其中找底部栏是无上限 BFS 全树遍历（见下方注释），在会话列表这种大 View 树上
     * 每 15 秒出现一次几十毫秒的卡顿尖峰。空白页自愈本身就是兜底逻辑，30 秒足够。
     */
    private const val POLL_INTERVAL_MS = 30_000L
    private const val MAX_SCAN_DEPTH = 10
    private const val MAX_SCAN_NODES = 400
    private const val BLANK_PAGE_THRESHOLD_MS = 10_000L
    private const val MAX_RETRY_COUNT = 3

    // 状态
    private val mainHandler = Handler(Looper.getMainLooper())
    private val monitorRunning = AtomicBoolean(false)
    private var lastActivityRef = WeakReference<Activity>(null)

    // 页面加载时间戳
    private val pageLoadTimes = mutableMapOf<String, Long>()
    private val retryCounts = mutableMapOf<String, Int>()

    @Volatile
    private var isInitialized = false

    /**
     * 初始化自修复监控器。
     */
    fun init() {
        if (isInitialized) return
        isInitialized = true

        WeLogger.i(TAG, "self-healing monitor initialized")
        startPolling()
    }

    /**
     * 获取监控状态。
     */
    fun getStatus(): Map<String, Any> {
        return mapOf(
            "monitorRunning" to monitorRunning.get(),
            "pageLoadTimes" to pageLoadTimes.toMap(),
            "retryCounts" to retryCounts.toMap()
        )
    }

    /**
     * 重置所有状态。
     */
    fun reset() {
        pageLoadTimes.clear()
        retryCounts.clear()
    }

    // ==================== 轮询 ====================

    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                checkAndHeal()
            } catch (e: Throwable) {
                WeLogger.e(TAG, "polling error", e)
            } finally {
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    private fun startPolling() {
        if (monitorRunning.getAndSet(true)) return
        mainHandler.post(pollRunnable)
        WeLogger.d(TAG, "polling started")
    }

    /**
     * 核心检测与修复逻辑。
     */
    private fun checkAndHeal() {
        val launcher = findLauncherUI() ?: return

        // 记录当前页面
        lastActivityRef = WeakReference(launcher)

        val pageKey = "LauncherUI_${launcher.hashCode()}"
        val now = System.currentTimeMillis()

        // 检查页面是否空白
        if (isPageBlank(launcher)) {
            val loadTime = pageLoadTimes.getOrPut(pageKey) { now }
            val elapsed = now - loadTime

            if (elapsed > BLANK_PAGE_THRESHOLD_MS) {
                WeLogger.w(TAG, "blank page detected for ${elapsed}ms, attempting heal")
                healBlankPage(launcher, pageKey)
            }
        } else {
            // 页面正常，记录时间
            if (!pageLoadTimes.containsKey(pageKey)) {
                pageLoadTimes[pageKey] = now
                WeLogger.d(TAG, "page loaded normally")
            }
        }

        // 原来这里还有 checkTabs()：它依赖 findBottomBar() 的返回值判断「底部 Tab 少于 4 个」，
        // 而 findBottomBar() 的筛选条件本身就要求 childCount in 4..5 → 判定恒为 false，
        // 属于每轮白跑一次「无上限 BFS 整个 decorView + 每个节点做一次 javaClass.name.lowercase()」
        // 的死逻辑，已整段移除。
    }

    /**
     * 判断页面是否空白（无有效子 View）。
     */
    private fun isPageBlank(activity: Activity): Boolean {
        try {
            val contentView = activity.findViewById<View>(android.R.id.content)
            if (contentView == null) return true
            if (contentView !is ViewGroup) return false

            // 递归检查是否有可见的有效子 View
            return !hasVisibleChild(contentView)
        } catch (e: Throwable) {
            return false
        }
    }

    /**
     * 递归检查 ViewGroup 是否有可见子 View。
     */
    /**
     * 递归检查是否有可见子 View。
     *
     * 限制深度与节点总数：微信首页之外可能存在极深的 View 树，无上限递归会在主线程
     * 占用大量时间（每次轮询都跑一遍）。超出预算时按「未找到」返回，下一次轮询会再试。
     */
    private fun hasVisibleChild(
        parent: ViewGroup,
        depth: Int = 0,
        budget: IntArray = intArrayOf(MAX_SCAN_NODES),
    ): Boolean {
        if (depth > MAX_SCAN_DEPTH || budget[0] <= 0) return false
        for (i in 0 until parent.childCount) {
            if (budget[0]-- <= 0) return false
            val child = parent.getChildAt(i) ?: continue
            if (child.visibility != View.VISIBLE) continue
            if (child.width > 0 && child.height > 0) return true
            if (child is ViewGroup && hasVisibleChild(child, depth + 1, budget)) return true
        }
        return false
    }

    /**
     * 修复空白页面。
     */
    private fun healBlankPage(activity: Activity, pageKey: String) {
        val retries = retryCounts.getOrDefault(pageKey, 0)
        if (retries >= MAX_RETRY_COUNT) {
            WeLogger.w(TAG, "max retries reached for blank page healing")
            return
        }

        retryCounts[pageKey] = retries + 1
        WeLogger.i(TAG, "blank page healing attempt ${retries + 1}/$MAX_RETRY_COUNT")

        try {
            val contentView = activity.findViewById<View>(android.R.id.content)

            // 策略 1: 强制请求布局
            contentView?.requestLayout()
            contentView?.invalidate()

            // 策略 3: 触发 Activity 重新测量
            activity.window?.decorView?.requestLayout()
            activity.window?.decorView?.invalidate()

            // 重置加载时间，给下次检测机会
            pageLoadTimes[pageKey] = System.currentTimeMillis()

            WeLogger.d(TAG, "blank page healing applied")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "blank page healing failed", e)
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 查找当前可见的 LauncherUI Activity。
     */
    /**
     * 反射句柄缓存。
     *
     * 原实现每次轮询都重新 Class.forName + getDeclaredMethod + getDeclaredField（外加每个
     * ActivityRecord 一次 getDeclaredField("activity")），全部在主线程。反射查找本身不便宜，
     * 30 秒一次的常驻开销没必要每次都付，这里把句柄缓存下来。
     */
    private var atClass: Class<*>? = null
    private var atCurrentMethod: java.lang.reflect.Method? = null
    private var atActivitiesField: java.lang.reflect.Field? = null
    private val activityFieldCache = HashMap<Class<*>, java.lang.reflect.Field?>()

    private fun findLauncherUI(): Activity? {
        try {
            val cls = atClass ?: Class.forName("android.app.ActivityThread").also { atClass = it }
            val current = atCurrentMethod ?: cls.getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .also { atCurrentMethod = it }
            val at = current.invoke(null)
            val field = atActivitiesField ?: cls.getDeclaredField("mActivities")
                .apply { isAccessible = true }
                .also { atActivitiesField = it }
            val activities = field.get(at) as? Map<*, *> ?: return null

            atClass = cls
            for (record in activities.values) {
                if (record == null) continue
                val recordClass = record.javaClass
                val f = if (activityFieldCache.containsKey(recordClass)) {
                    activityFieldCache[recordClass]
                } else {
                    runCatching { recordClass.getDeclaredField("activity") }
                        .getOrNull()?.apply { isAccessible = true }
                        .also { activityFieldCache[recordClass] = it }
                } ?: continue
                val activity = f.get(record) as? Activity ?: continue
                if (activity.javaClass.name == "com.tencent.mm.ui.LauncherUI" && !activity.isFinishing) {
                    return activity
                }
            }
        } catch (e: Throwable) {
            WeLogger.d(TAG, "findLauncherUI error: ${e.message}")
        }
        return null
    }
}
