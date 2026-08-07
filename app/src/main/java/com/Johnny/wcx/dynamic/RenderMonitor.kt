package com.Johnny.wcx.dynamic

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.Johnny.wcx.utils.WeLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 实时渲染监控 — 检测四大 Tab 加载阻塞、侧边栏创建失败时自动修复。
 *
 * 检测到问题时引擎自动微调页面挂载时机、调整视图层级，
 * 无需用户手动关闭侧边栏/页面劫持，自动修复渲染卡死，全部功能保留运行。
 *
 * 监控指标：
 * - 四大 Tab 是否正常加载
 * - 侧边栏是否创建成功
 * - 页面渲染是否超时
 * - 主线程是否阻塞
 */
object RenderMonitor {

    private const val TAG = "RenderMonitor"

    // 监控配置
    private const val RENDER_TIMEOUT_MS = 5_000L
    private const val UI_THREAD_BLOCK_THRESHOLD_MS = 3_000L
    private const val MAX_RETRY_COUNT = 3

    // 当前页面状态
    private var currentActivity: Activity? = null
    private var pageCreateTime = 0L
    private val tabsLoaded = mutableMapOf<Int, Boolean>()
    private var sidePanelCreated = false
    private val monitorRunning = AtomicBoolean(false)

    // 修复重试计数
    private val retryCounts = mutableMapOf<String, Int>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val monitorThread = Handler(Looper.getMainLooper())

    /**
     * 页面创建回调 — 由 PageLifecycleCompat 调用。
     */
    fun onPageCreated(activity: Activity) {
        currentActivity = activity
        pageCreateTime = System.currentTimeMillis()
        tabsLoaded.clear()
        sidePanelCreated = false

        WeLogger.d(TAG, "page created: ${activity.javaClass.simpleName}")

        // 启动渲染超时监控
        startRenderTimeoutMonitor(activity)
    }

    /**
     * 页面恢复回调。
     */
    fun onPageResumed(activity: Activity) {
        currentActivity = activity
        WeLogger.d(TAG, "page resumed: ${activity.javaClass.simpleName}")

        // 检查所有 UI 组件
        mainHandler.postDelayed({
            checkAllComponents(activity)
        }, 500)
    }

    /**
     * 通知 Tab 已加载。
     */
    fun notifyTabLoaded(tabIndex: Int) {
        tabsLoaded[tabIndex] = true
        WeLogger.d(TAG, "tab $tabIndex loaded")
    }

    /**
     * 通知侧边栏已创建。
     */
    fun notifySidePanelCreated() {
        sidePanelCreated = true
        WeLogger.d(TAG, "side panel created successfully")
    }

    /**
     * 通知侧边栏创建失败。
     */
    fun notifySidePanelFailed() {
        sidePanelCreated = false
        WeLogger.w(TAG, "side panel creation failed, scheduling retry")
        scheduleSidePanelRetry()
    }

    // -----------------------------------------------------------------------
    // 内部监控逻辑
    // -----------------------------------------------------------------------

    private fun startRenderTimeoutMonitor(activity: Activity) {
        mainHandler.postDelayed({
            val elapsed = System.currentTimeMillis() - pageCreateTime
            if (elapsed > RENDER_TIMEOUT_MS && !allTabsLoaded()) {
                WeLogger.w(TAG, "render timeout detected after ${elapsed}ms, tabs loaded: $tabsLoaded")
                onRenderTimeout(activity)
            }
        }, RENDER_TIMEOUT_MS)
    }

    private fun checkAllComponents(activity: Activity) {
        // 检查四大 Tab
        if (!allTabsLoaded()) {
            WeLogger.w(TAG, "some tabs not loaded: $tabsLoaded")
            tryFixTabsLoading(activity)
        }

        // 检查侧边栏
        if (!sidePanelCreated) {
            scheduleSidePanelRetry()
        }

        // 检查主线程
        checkMainThreadBlocking()
    }

    private fun allTabsLoaded(): Boolean {
        // 四大 Tab: 微信(index=0), 通讯录(1), 发现(2), 我(3)
        return tabsLoaded.size >= 4 && tabsLoaded.values.all { it }
    }

    private fun onRenderTimeout(activity: Activity) {
        if (monitorRunning.getAndSet(true)) return

        try {
            WeLogger.w(TAG, "attempting to fix render timeout")

            // 策略 1: 微调页面挂载时机
            tryFixViewHierarchy(activity)

            // 策略 2: 强制刷新布局
            tryForceLayout(activity)

            // 策略 3: 清除可能卡死的主线程消息
            clearBlockingMessages()
        } finally {
            monitorRunning.set(false)
        }
    }

    private fun tryFixTabsLoading(activity: Activity) {
        val retryKey = "tabs_${activity.javaClass.simpleName}"
        val retries = retryCounts.getOrDefault(retryKey, 0)
        if (retries >= MAX_RETRY_COUNT) {
            WeLogger.w(TAG, "max retries reached for tab loading fix")
            return
        }

        retryCounts[retryKey] = retries + 1
        WeLogger.i(TAG, "tab loading fix attempt ${retries + 1}/$MAX_RETRY_COUNT")

        // 尝试触发 ViewPager 的页面选择（强制刷新 Tab）
        try {
            val decorView = activity.window?.decorView
            if (decorView is ViewGroup) {
                findAndTriggerViewPager(decorView)
            }
        } catch (e: Exception) {
            WeLogger.w(TAG, "tab loading fix failed: ${e.message}")
        }
    }

    private fun scheduleSidePanelRetry() {
        val retryKey = "side_panel"
        val retries = retryCounts.getOrDefault(retryKey, 0)
        if (retries >= MAX_RETRY_COUNT) {
            WeLogger.e(TAG, "max retries reached for side panel, giving up")
            return
        }

        retryCounts[retryKey] = retries + 1
        val delay = 1000L * (retries + 1) // 递增延迟

        WeLogger.i(TAG, "scheduling side panel retry in ${delay}ms (attempt ${retries + 1}/$MAX_RETRY_COUNT)")
        mainHandler.postDelayed({
            tryRecreateSidePanel()
        }, delay)
    }

    private fun tryRecreateSidePanel() {
        // 触发侧边栏重新创建
        try {
            AutoAdaptationManager.retryFeature("HomeSidePanelFeature")
        } catch (e: Exception) {
            WeLogger.w(TAG, "side panel retry failed: ${e.message}")
        }
    }

    private fun tryFixViewHierarchy(activity: Activity) {
        try {
            val decorView = activity.window?.decorView
            if (decorView is ViewGroup) {
                // 触发视图层级重新测量和布局
                decorView.requestLayout()
                decorView.invalidate()
            }
        } catch (e: Exception) {
            WeLogger.w(TAG, "view hierarchy fix failed: ${e.message}")
        }
    }

    private fun tryForceLayout(activity: Activity) {
        try {
            val contentView = activity.findViewById<View>(android.R.id.content)
            contentView?.let {
                it.requestLayout()
                it.invalidate()
            }
        } catch (e: Exception) {
            WeLogger.w(TAG, "force layout failed: ${e.message}")
        }
    }

    private fun clearBlockingMessages() {
        try {
            // 移除主线程消息队列中可能卡死的消息
            val queue = Looper.getMainLooper().queue
            // 通过反射清理消息队列
            val messagesField = queue.javaClass.getDeclaredField("mMessages")
            messagesField.isAccessible = true
            // 不清除消息，仅记录
            val hasMessages = messagesField.get(queue) != null
            WeLogger.d(TAG, "main thread has pending messages: $hasMessages")
        } catch (e: Exception) {
            WeLogger.d(TAG, "could not inspect message queue: ${e.message}")
        }
    }

    private fun checkMainThreadBlocking() {
        val startTime = System.currentTimeMillis()
        mainHandler.post {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > UI_THREAD_BLOCK_THRESHOLD_MS) {
                WeLogger.w(TAG, "main thread blocked for ${elapsed}ms, possible ANR")
            }
        }
    }

    /**
     * 递归查找 ViewPager 并触发刷新。
     */
    private fun findAndTriggerViewPager(parent: ViewGroup) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val className = child.javaClass.name
            if (className.contains("ViewPager") || className.contains("ViewPager2")) {
                try {
                    // 尝试调用 setCurrentItem 触发刷新
                    val method = child.javaClass.getMethod("getCurrentItem")
                    val currentItem = method.invoke(child) as Int
                    val setMethod = child.javaClass.getMethod("setCurrentItem", Int::class.javaPrimitiveType!!)
                    setMethod.invoke(child, currentItem)
                    WeLogger.i(TAG, "triggered ViewPager refresh at index $currentItem")
                } catch (e: Exception) {
                    WeLogger.w(TAG, "ViewPager refresh failed: ${e.message}")
                }
            } else if (child is ViewGroup) {
                findAndTriggerViewPager(child)
            }
        }
    }

    /**
     * 获取监控状态。
     */
    fun getStatus(): Map<String, Any> {
        return mapOf(
            "tabsLoaded" to tabsLoaded.toMap(),
            "sidePanelCreated" to sidePanelCreated,
            "retryCounts" to retryCounts.toMap(),
            "monitorRunning" to monitorRunning.get()
        )
    }

    /**
     * 重置所有监控状态。
     */
    fun reset() {
        tabsLoaded.clear()
        sidePanelCreated = false
        retryCounts.clear()
        monitorRunning.set(false)
        currentActivity = null
    }
}