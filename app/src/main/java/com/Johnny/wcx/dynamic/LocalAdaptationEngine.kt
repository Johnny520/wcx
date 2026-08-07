package com.Johnny.wcx.dynamic

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.dexkit.cache.DexCacheManager
import com.Johnny.wcx.features.core.FeaturesLoader
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.DexKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.luckypray.dexkit.DexKitBridge
import java.io.File

/**
 * 纯本地自适应引擎 — 无云端依赖。
 *
 * 核心职责：
 * 1. 使用 DexKit 进行本地字节码扫描
 * 2. 自动检测微信版本变更
 * 3. 自动扫描和匹配 Hook，无需云端服务器
 * 4. 基于 SharedPreferences 的简单缓存机制
 * 5. 在模块启动时和微信 APK 变更时触发
 */
object LocalAdaptationEngine {

    private const val TAG = "LocalAdaptationEngine"
    private const val PREFS_ENGINE = "local_adaptation_engine"
    private const val KEY_LAST_VERSION = "last_wechat_version"
    private const val KEY_LAST_APK_SIZE = "last_apk_size"
    private const val KEY_LAST_APK_MTIME = "last_apk_mtime"
    private const val KEY_ADAPTATION_COUNT = "adaptation_count"
    private const val KEY_LAST_ADAPTATION_TIME = "last_adaptation_time"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isAdapting = false

    private var prefs: SharedPreferences? = null

    // 适配完成回调
    private val onCompleteCallbacks = mutableListOf<() -> Unit>()

    enum class EngineState {
        IDLE, ADAPTING, COMPLETED, FAILED
    }

    @Volatile
    var state: EngineState = EngineState.IDLE
        private set

    /**
     * 初始化引擎。
     * 在 WeLauncher 中调用，主进程启动时触发。
     */
    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        prefs = context.getSharedPreferences(PREFS_ENGINE, Context.MODE_PRIVATE)

        WeLogger.i(TAG, "initializing local adaptation engine")
        WeLogger.i(TAG, "wechat version: ${HostInfo.versionName}, code: ${HostInfo.versionCode}")

        checkAndAdapt()
    }

    /**
     * 注册适配完成回调。
     */
    fun onAdaptationComplete(callback: () -> Unit) {
        onCompleteCallbacks.add(callback)
        if (state == EngineState.COMPLETED) {
            callback()
        }
    }

    /**
     * 获取适配状态摘要。
     */
    fun getStatus(): Map<String, Any> {
        val p = prefs
        return mapOf(
            "state" to state.name,
            "initialized" to isInitialized,
            "isAdapting" to isAdapting,
            "lastVersion" to (p?.getString(KEY_LAST_VERSION, "") ?: ""),
            "currentVersion" to HostInfo.versionName,
            "adaptationCount" to (p?.getInt(KEY_ADAPTATION_COUNT, 0) ?: 0),
            "lastAdaptationTime" to (p?.getLong(KEY_LAST_ADAPTATION_TIME, 0L) ?: 0L)
        )
    }

    /**
     * 强制重新适配（调试用）。
     */
    fun forceReadapt() {
        WeLogger.i(TAG, "force re-adaptation requested")
        prefs?.edit()?.clear()?.apply()
        DexCacheManager.clearAllCache()
        state = EngineState.IDLE
        checkAndAdapt()
    }

    // ==================== 内部实现 ====================

    /**
     * 检查是否需要适配。
     * 适配条件：
     * 1. 首次运行（无缓存记录）
     * 2. 微信版本变更
     * 3. 微信 APK 文件变更（大小或修改时间变化）
     */
    private fun checkAndAdapt() {
        val p = prefs ?: return

        val currentVersion = "${HostInfo.versionName}${HostInfo.versionCode}"
        val lastVersion = p.getString(KEY_LAST_VERSION, "")

        val apkFile = File(HostInfo.appInfo.sourceDir)
        val currentApkSize = if (apkFile.exists()) apkFile.length() else 0L
        val currentApkMtime = if (apkFile.exists()) apkFile.lastModified() else 0L
        val lastApkSize = p.getLong(KEY_LAST_APK_SIZE, 0L)
        val lastApkMtime = p.getLong(KEY_LAST_APK_MTIME, 0L)

        val versionChanged = currentVersion != lastVersion
        val apkChanged = currentApkSize != lastApkSize || currentApkMtime != lastApkMtime

        if (!versionChanged && !apkChanged && lastVersion.isNotEmpty()) {
            WeLogger.i(TAG, "no changes detected, skipping adaptation")
            state = EngineState.COMPLETED
            return
        }

        WeLogger.i(TAG, "changes detected: versionChanged=$versionChanged, apkChanged=$apkChanged")
        startAdaptation()
    }

    private fun startAdaptation() {
        if (isAdapting) {
            WeLogger.w(TAG, "adaptation already in progress")
            return
        }

        isAdapting = true
        state = EngineState.ADAPTING

        scope.launch {
            try {
                performAdaptation()
                state = EngineState.COMPLETED
                WeLogger.i(TAG, "adaptation completed successfully")
                notifyComplete()
            } catch (e: Exception) {
                WeLogger.e(TAG, "adaptation failed", e)
                state = EngineState.FAILED
            } finally {
                isAdapting = false
            }
        }
    }

    /**
     * 执行适配流程：
     * 1. 获取 DexKitBridge
     * 2. 扫描所有 Hook 特征
     * 3. 保存缓存
     * 4. 更新版本记录
     */
    private suspend fun performAdaptation() {
        val p = prefs ?: return

        // 步骤 1: 获取 DexKitBridge
        val dexKit = withContext(Dispatchers.IO) {
            try {
                DexKit
            } catch (e: Exception) {
                WeLogger.e(TAG, "failed to acquire DexKitBridge", e)
                throw e
            }
        }

        // 步骤 2: 扫描所有 Hook 特征
        withContext(Dispatchers.IO) {
            scanAllFeatures(dexKit)
        }

        // 步骤 3: 保存版本信息
        val currentVersion = "${HostInfo.versionName}${HostInfo.versionCode}"
        val apkFile = File(HostInfo.appInfo.sourceDir)
        val apkSize = if (apkFile.exists()) apkFile.length() else 0L
        val apkMtime = if (apkFile.exists()) apkFile.lastModified() else 0L

        p.edit()
            .putString(KEY_LAST_VERSION, currentVersion)
            .putLong(KEY_LAST_APK_SIZE, apkSize)
            .putLong(KEY_LAST_APK_MTIME, apkMtime)
            .putInt(KEY_ADAPTATION_COUNT, p.getInt(KEY_ADAPTATION_COUNT, 0) + 1)
            .putLong(KEY_LAST_ADAPTATION_TIME, System.currentTimeMillis())
            .apply()

        WeLogger.i(TAG, "version info saved: $currentVersion")
    }

    /**
     * 扫描所有已注册的 Feature 的 Dex 特征。
     * 触发 DexKit 解析和缓存保存。
     */
    private fun scanAllFeatures(dexKit: DexKitBridge) {
        val features = FeaturesProvider.ALL_HOOK_ITEMS
        WeLogger.i(TAG, "scanning ${features.size} features...")

        var successCount = 0
        var failCount = 0

        for (feature in features) {
            try {
                // 触发 feature 的 Dex 解析（如果实现了 IResolveDex）
                if (feature is com.Johnny.wcx.dexkit.abc.IResolveDex) {
                    // 检查缓存是否有效
                    if (!DexCacheManager.isItemCacheValid(feature)) {
                        feature.resolveDex(dexKit)
                        DexCacheManager.saveItemCache(feature)
                        WeLogger.d(TAG, "resolved: ${feature.displayName}")
                    } else {
                        WeLogger.d(TAG, "cache hit: ${feature.displayName}")
                    }
                    successCount++
                }
            } catch (e: Exception) {
                WeLogger.w(TAG, "failed to resolve ${feature.displayName}: ${e.message}")
                failCount++
            }
        }

        WeLogger.i(TAG, "scan complete: $successCount success, $failCount failed")
    }

    private fun notifyComplete() {
        mainHandler.post {
            onCompleteCallbacks.forEach { callback ->
                try { callback() } catch (e: Exception) {
                    WeLogger.e(TAG, "adaptation callback failed", e)
                }
            }
        }
    }
}