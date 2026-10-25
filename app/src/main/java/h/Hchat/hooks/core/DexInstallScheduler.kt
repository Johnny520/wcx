package h.Hchat.hooks.core

import android.os.Handler
import android.os.Looper
import h.Hchat.utils.HLog
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 统一串行安装、有限重试；卸载后旧任务不能重新安装或保留等待中的闭包。 */
object DexInstallScheduler {
    enum class Stage(internal val level: Int) { EARLY(0), BRIDGE(1), WARMUP(2) }

    private const val TAG = "[WCX:DexInstall]"
    private const val MAX_RETRY_ATTEMPTS = 6
    private const val MAX_RETRY_DELAY_MS = 60_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val states = ConcurrentHashMap<String, State>()
    private val readyLevel = AtomicInteger(0)
    private val stateLock = Any()
    private val dexKitExecutionLock = Any()
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>(),
        { runnable -> Thread(runnable, "Hchat-DexInstall").apply { isDaemon = true } }
    )

    fun schedule(
        key: String,
        label: String = key,
        stage: Stage = Stage.BRIDGE,
        priority: Int = 0,
        installer: () -> Boolean
    ) = synchronized(stateLock) {
        if (key.isBlank()) return@synchronized
        val registry = HookRegistry.get()
        val generation = registry.installationGeneration()
        if (!registry.isInstallationGenerationActive(generation)) return@synchronized
        val previous = states[key]
        val state = if (previous == null || previous.generation != generation) {
            previous?.let(::cancelState)
            State(label, generation).also { states[key] = it }
        } else previous
        if (state.installed.get()) return@synchronized
        state.label = label
        state.stage = stage
        state.priority = priority
        state.installer = installer
        if (!isReady(stage)) return@synchronized
        if (!state.running.get() && !state.retryScheduled.get() &&
            state.retryIndex.get() >= MAX_RETRY_ATTEMPTS
        ) state.retryIndex.set(0)
        runNow(key, state)
    }

    @JvmStatic
    fun scheduleTask(key: String, label: String, stage: Stage, priority: Int, installer: Callable<Boolean>) {
        schedule(key, label, stage, priority) { installer.call() == true }
    }

    @JvmStatic fun markDexBridgeReady() { advanceReady(Stage.BRIDGE) }
    @JvmStatic fun markDexReady() { markDexWarmupReady() }
    @JvmStatic fun markDexWarmupReady() { advanceReady(Stage.WARMUP) }

    @JvmStatic
    fun runDexKitTask(task: Runnable) {
        val registry = HookRegistry.get()
        val generation = registry.installationGeneration()
        synchronized(dexKitExecutionLock) {
            registry.withInstallationGeneration(generation) { task.run() }
        }
    }

    private fun advanceReady(stage: Stage) {
        while (true) {
            val current = readyLevel.get()
            if (current >= stage.level) break
            if (readyLevel.compareAndSet(current, stage.level)) break
        }
        resetExhaustedRetries()
        runReadyStates()
    }

    /**
     * 把此前「重试耗尽、尚未安装」的任务计数归零并重新排队，让失败任务有机会恢复。
     *
     * 供「脚本一键修复 / 重新初始化」等入口调用；阶段推进（[advanceReady]）内部也会自动调用一次。
     */
    @JvmStatic
    fun retryPending() = synchronized(stateLock) {
        resetExhaustedRetries()
        runReadyStates()
    }

    /**
     * 一键修复：把**全部未安装**任务（含仍在重试与已暂停的）失败计数清零并立即重排。
     * 比 [retryPending]（只处理已暂停的）更彻底，供「一键修复 / 重新初始化脚本子系统」调用。
     */
    @JvmStatic
    fun retryAllPending() = synchronized(stateLock) {
        states.values.forEach { s ->
            if (!s.installed.get() && s.installer != null && !s.running.get()) {
                s.retryIndex.set(0)
                s.retryScheduled.set(false)
                s.retryTask?.let(mainHandler::removeCallbacks)
                s.retryTask = null
            }
        }
        runReadyStates()
    }

    /**
     * 强制把就绪阶段推进到 WARMUP。
     * 供一键修复：即便 `wechat_api:warmup` 任务没跑成、阶段卡在 BRIDGE，也让脚本的
     * 菜单 / 消息 / 发送按钮等 WARMUP 级 Hook 有机会被安装（否则它们永远不会被尝试）。
     */
    @JvmStatic
    fun ensureWarmupReady() {
        advanceReady(Stage.BRIDGE)
        advanceReady(Stage.WARMUP)
    }

    /** 任务快照（诊断页用）：逐个 Hook 安装任务当前的真实状态。 */
    data class TaskStatus(
        val key: String,
        val label: String,
        val stage: String,
        val installed: Boolean,
        val running: Boolean,
        val retryIndex: Int,
        val paused: Boolean,
        /** 最近一次安装失败的原因（未失败为 null）。自检 / 诊断页直接展示，开发者不必再翻日志。 */
        val lastError: String? = null,
        /** 最近一次安装失败的时间戳（毫秒），0 表示从未失败。 */
        val lastErrorAt: Long = 0L,
    ) {
        val summary: String
            get() {
                val base = when {
                    installed -> "✅ 已安装"
                    running -> "⏳ 安装中"
                    paused -> "❌ 失败·已暂停（可一键修复）"
                    else -> "⚠ 待重试（第 " + retryIndex + " 次）"
                }
                return if (!installed && !lastError.isNullOrBlank()) "$base｜上次失败：$lastError" else base
            }
    }

    /** 当前就绪阶段名。 */
    @JvmStatic
    fun readyLevelName(): String = when (readyLevel.get()) {
        0 -> "EARLY（早期）"
        1 -> "BRIDGE（桥接就绪）"
        2 -> "WARMUP（预热就绪）"
        else -> "未知"
    }

    /** 所有任务的状态快照（未安装的排前面）。 */
    @JvmStatic
    fun snapshot(): List<TaskStatus> = synchronized(stateLock) {
        states.entries
            .map { (key, s) ->
                TaskStatus(
                    key = key,
                    label = s.label,
                    stage = s.stage.name,
                    installed = s.installed.get(),
                    running = s.running.get(),
                    retryIndex = s.retryIndex.get(),
                    paused = !s.installed.get() && s.installer != null &&
                            s.retryIndex.get() >= MAX_RETRY_ATTEMPTS,
                    lastError = s.lastError,
                    lastErrorAt = s.lastErrorAt,
                )
            }
            .sortedWith(compareBy({ it.installed }, { it.stage }, { it.key }))
    }

    /** 尚未安装（含待重试与已暂停）的任务数。 */
    @JvmStatic
    fun pendingCount(): Int = synchronized(stateLock) {
        states.values.count { !it.installed.get() }
    }

    /** 已暂停（重试耗尽仍未装成、可一键修复恢复）的任务数。 */
    @JvmStatic
    fun failedCount(): Int = synchronized(stateLock) {
        states.values.count {
            !it.installed.get() && it.installer != null && it.retryIndex.get() >= MAX_RETRY_ATTEMPTS
        }
    }

    /** 阶段推进与一键修复共用的恢复点：重试次数已达上限、但仍未安装且保留 installer 的任务，计数归零。 */
    private fun resetExhaustedRetries() = synchronized(stateLock) {
        states.values.forEach { state ->
            if (!state.installed.get() && state.installer != null &&
                state.retryIndex.get() >= MAX_RETRY_ATTEMPTS
            ) {
                state.retryIndex.set(0)
            }
        }
    }

    fun markInstalled(key: String) = synchronized(stateLock) {
        val registry = HookRegistry.get()
        val generation = registry.installationGeneration()
        if (!registry.isInstallationGenerationActive(generation)) return@synchronized
        val previous = states[key]
        val state = if (previous == null || previous.generation != generation) {
            previous?.let(::cancelState)
            State(key, generation).also { states[key] = it }
        } else previous
        cancelState(state)
        state.installed.set(true)
    }

    /** 不等待无法中断的 DexKit 调用；它返回后由旧代次租约拒绝后续挂钩。 */
    fun cancelAll() = synchronized(stateLock) {
        val previous = states.values.toList()
        states.clear()
        previous.forEach(::cancelState)
        // 只取消请求，现有 DexKit 的就绪阶段不随请求清理而倒退。
    }

    private fun cancelState(state: State) {
        state.installer = null
        state.retryTask?.let(mainHandler::removeCallbacks)
        state.retryTask = null
        state.retryScheduled.set(false)
        state.future?.let {
            it.cancel(false)
            executor.remove(it)
        }
        state.future = null
    }

    private fun isCurrent(key: String, state: State): Boolean =
        states[key] === state && HookRegistry.get().isInstallationGenerationActive(state.generation)

    private fun runNow(key: String, state: State) = synchronized(stateLock) {
        if (!isCurrent(key, state) || !isReady(state.stage) || state.installed.get() || state.installer == null) {
            return@synchronized
        }
        if (!state.running.compareAndSet(false, true)) return@synchronized
        val future = FutureTask<Unit> { runInstaller(key, state) }
        state.future = future
        try {
            executor.execute(future)
        } catch (error: Throwable) {
            state.future = null
            state.running.set(false)
            throw error
        }
    }

    private fun runInstaller(key: String, state: State) {
        var ok = false
        val attemptStartedAt = System.currentTimeMillis()
        try {
            synchronized(dexKitExecutionLock) {
                val installer = synchronized(stateLock) {
                    if (isCurrent(key, state) && !state.installed.get()) state.installer else null
                } ?: return
                ok = HookRegistry.get().withInstallationGeneration(state.generation) { installer() }
            }
        } catch (_: CancellationException) {
            // 卸载与正在执行的定位竞争时，旧租约失效属于正常取消。
        } catch (error: Throwable) {
            recordFailure(state, "异常：" + (error.message ?: error.javaClass.simpleName))
            HLog.e("$TAG ${state.label} 安装异常: ${error.message}", error)
        } finally {
            synchronized(stateLock) {
                state.future = null
                state.running.set(false)
                if (states[key] === state) {
                    if (!isCurrent(key, state)) {
                        states.remove(key, state)
                        cancelState(state)
                    } else if (ok || state.installed.get()) {
                        state.installed.set(true)
                        state.installer = null
                        state.lastError = null
                        state.retryTask?.let(mainHandler::removeCallbacks)
                        state.retryTask = null
                        state.retryScheduled.set(false)
                    } else {
                        // installer 返回 false（未抛异常）是最常见、也最缺信息的一类失败：
                        // 以前这里完全静默，日志里只留一句「重试 N 次仍未成功」。
                        if (state.lastErrorAt < attemptStartedAt) {
                            recordFailure(state, "返回 false（目标未命中或依赖未就绪）")
                        }
                        scheduleRetry(key, state)
                    }
                }
            }
        }
    }

    private fun runReadyStates() = synchronized(stateLock) {
        states.entries.sortedWith(
            compareBy<Map.Entry<String, State>> { it.value.stage.level }
                .thenBy { it.value.priority }.thenBy { it.key }
        ).forEach { (key, state) -> runNow(key, state) }
    }

    /** 记录一次失败：既写运行日志（日志页可见），也留在 TaskStatus 里给自检页展示。 */
    private fun recordFailure(state: State, reason: String) {
        state.lastError = reason
        state.lastErrorAt = System.currentTimeMillis()
        HLog.i("$TAG ${state.label} 安装未成功（第 ${state.retryIndex.get()} 次）：$reason")
    }

    private fun scheduleRetry(key: String, state: State) {
        if (!isCurrent(key, state) || state.installed.get()) return
        if (!state.retryScheduled.compareAndSet(false, true)) return
        val index = state.retryIndex.getAndIncrement()
        if (index >= MAX_RETRY_ATTEMPTS) {
            // 不永久放弃：保留 installer，仅暂停本轮；阶段推进 / retryPending() 会重置计数后再试，
            // 避免「一次性失败后永久卡死」（微信启动早期 DexKit 尚未就绪时尤其明显）。
            state.retryScheduled.set(false)
            HLog.e(
                "$TAG ${state.label} 重试 $index 次仍未成功，暂停本轮重试" +
                    "（阶段推进 / 一键修复可再恢复）；最近失败：${state.lastError ?: "未知"}"
            )
            return
        }
        val retry = Runnable {
            synchronized(stateLock) {
                if (!isCurrent(key, state)) return@synchronized
                state.retryTask = null
                state.retryScheduled.set(false)
                runNow(key, state)
            }
        }
        state.retryTask = retry
        mainHandler.postDelayed(retry, retryDelayMs(index))
    }

    private fun retryDelayMs(attempt: Int): Long {
        if (attempt <= 0) return 1_000L
        if (attempt == 1) return 3_000L
        return minOf(MAX_RETRY_DELAY_MS, 3_000L * (1L shl (attempt - 1)))
    }

    private fun isReady(stage: Stage): Boolean = readyLevel.get() >= stage.level

    private class State(@Volatile var label: String, val generation: Long) {
        @Volatile var stage: Stage = Stage.BRIDGE
        @Volatile var priority: Int = 0
        @Volatile var installer: (() -> Boolean)? = null
        var future: FutureTask<Unit>? = null
        var retryTask: Runnable? = null
        val installed = AtomicBoolean(false)
        val running = AtomicBoolean(false)
        val retryScheduled = AtomicBoolean(false)
        val retryIndex = AtomicInteger(0)
        @Volatile var lastError: String? = null
        @Volatile var lastErrorAt: Long = 0L
    }
}
