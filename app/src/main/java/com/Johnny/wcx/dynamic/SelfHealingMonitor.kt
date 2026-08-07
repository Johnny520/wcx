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
    private const val POLL_INTERVAL_MS = 3000L
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

        // 检查四大 Tab
        checkTabs(launcher, pageKey)
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
    private fun hasVisibleChild(parent: ViewGroup): Boolean {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) ?: continue
            if (child.visibility != View.VISIBLE) continue
            if (child.width > 0 && child.height > 0) return true
            if (child is ViewGroup && hasVisibleChild(child)) return true
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

            // 策略 2: 触发 ViewPager 刷新
            if (contentView is ViewGroup) {
                triggerViewPagerRefresh(contentView)
            }

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

    /**
     * 检查四大 Tab 是否正常加载。
     * 微信底部导航栏应有 4 个 Tab：微信、通讯录、发现、我。
     */
    private fun checkTabs(activity: Activity, pageKey: String) {
        try {
            val decorView = activity.window?.decorView as? ViewGroup ?: return

            // 查找底部导航栏
            val bottomBar = findBottomBar(decorView) ?: return

            // 检查 Tab 数量
            if (bottomBar.childCount < 4) {
                val retries = retryCounts.getOrDefault("tabs_$pageKey", 0)
                if (retries < MAX_RETRY_COUNT) {
                    retryCounts["tabs_$pageKey"] = retries + 1
                    WeLogger.w(TAG, "tabs count abnormal: ${bottomBar.childCount}, healing")

                    // 触发 ViewPager 刷新以重新加载 Tab
                    triggerViewPagerRefresh(decorView)
                }
            }
        } catch (e: Throwable) {
            WeLogger.d(TAG, "tab check error: ${e.message}")
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 查找当前可见的 LauncherUI Activity。
     */
    private fun findLauncherUI(): Activity? {
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            val activitiesField = atClass.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            val activities = activitiesField.get(at) as? Map<*, *> ?: return null

            for (record in activities.values) {
                val activity = record?.javaClass?.getDeclaredField("activity")
                    ?.apply { isAccessible = true }
                    ?.get(record) as? Activity ?: continue
                if (activity.javaClass.name == "com.tencent.mm.ui.LauncherUI" && !activity.isFinishing) {
                    return activity
                }
            }
        } catch (e: Throwable) {
            WeLogger.d(TAG, "findLauncherUI error: ${e.message}")
        }
        return null
    }

    /**
     * 查找底部导航栏 ViewGroup。
     */
    private fun findBottomBar(parent: ViewGroup): ViewGroup? {
        val queue = ArrayDeque<View>()
        queue.add(parent)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            val className = v.javaClass.name.lowercase()
            if ((className.contains("bottomnavigation") || className.contains("tablayout")) &&
                v is ViewGroup && v.childCount in 4..5) {
                return v
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    v.getChildAt(i)?.let { queue.add(it) }
                }
            }
        }
        return null
    }

    /**
     * 触发 ViewPager 刷新。
     */
    private fun triggerViewPagerRefresh(parent: ViewGroup) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) ?: continue
            val className = child.javaClass.name
            if (className.contains("ViewPager") || className.contains("ViewPager2")) {
                try {
                    val method = child.javaClass.getMethod("getCurrentItem")
                    val currentItem = method.invoke(child) as Int
                    val setMethod = child.javaClass.getMethod("setCurrentItem", Int::class.javaPrimitiveType!!)
                    setMethod.invoke(child, currentItem)
                    WeLogger.i(TAG, "triggered ViewPager refresh at index $currentItem")
                } catch (e: Throwable) {
                    WeLogger.w(TAG, "ViewPager refresh failed: ${e.message}")
                }
            } else if (child is ViewGroup) {
                triggerViewPagerRefresh(child)
            }
        }
    }
}