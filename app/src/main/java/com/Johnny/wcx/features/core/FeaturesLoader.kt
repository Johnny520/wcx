package com.Johnny.wcx.features.core

import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.cache.DexCacheManager
import com.Johnny.wcx.dynamic.AutoAdaptationManager
import com.Johnny.wcx.dynamic.DynamicFallbackChain
import com.Johnny.wcx.features.api.ui.WeSettingsInjector
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.measureTime

/**
 * 功能加载器 — 集成动态适配引擎，取消手动"完美适配"按钮。
 *
 * 加载流程：
 * 1. 尝试从缓存加载 Dex 委托（快速路径）
 * 2. 缓存完整 → 直接启动所有功能
 * 3. 缓存不完整/过期 → 触发后台静默全自动适配
 * 4. 适配完成后自动注入 Hook 并启动功能
 *
 * 不再需要用户手动操作，全程全自动。
 */
object FeaturesLoader {

    private const val TAG = "FeaturesLoader"

    fun loadFeatures() {
        // 初始化全自动适配管理器
        AutoAdaptationManager.init()

        val allFeatures = FeaturesProvider.ALL_HOOK_ITEMS
        val allDexItems = allFeatures.filterIsInstance<IResolveDex>()

        // 检查缓存是否有效
        val outdatedItems = DexCacheManager.getOutdatedItems(allDexItems)
        val validItems = allDexItems - outdatedItems.toSet()

        if (outdatedItems.isNotEmpty())
            WeLogger.i(TAG, "found ${validItems.size} valid items, ${outdatedItems.size} outdated items")

        // 从缓存加载（快速路径）
        val cacheFailedItems = loadDescriptorsFromCache(validItems)
        val allBrokenItems = (outdatedItems + cacheFailedItems).distinct()

        if (allBrokenItems.isNotEmpty()) {
            // 缓存不完整，触发后台自动适配（不再显示弹窗）
            WeLogger.i(TAG, "${allBrokenItems.size} items need re-adaptation, triggering auto-adapt in background")
            scheduleAutoAdaptation(allBrokenItems)
        }

        // 启动所有功能（缓存完整的直接启动，不完整的跳过）
        val elapsed = measureTime {
            allFeatures.forEach { feature ->
                val isBroken = feature is IResolveDex && allBrokenItems.contains(feature)

                if (isBroken && feature !is WeSettingsInjector) {
                    // 检查是否已有备用链路处于激活状态
                    if (DynamicFallbackChain.isFallbackActive(feature.name)) {
                        WeLogger.i(TAG, "starting ${feature.name} via fallback chain")
                        feature.startup()
                        return@forEach
                    }
                    WeLogger.w(TAG, "skipping ${feature.name} — awaiting auto-adaptation")
                    return@forEach
                }

                feature.startup()
            }
        }
        WeLogger.i(TAG, "enabling all hook items took $elapsed")

        if (TargetProcesses.isInMain && Preferences.showStartupToast) {
            showToast("WCX 加载成功!")
        }
    }

    // ---------------------------------------------------------------------------

    /**
     * 逐委托从缓存恢复状态。
     */
    private fun loadDescriptorsFromCache(items: List<IResolveDex>): List<IResolveDex> {
        val failedItems = mutableListOf<IResolveDex>()

        for (item in items) {
            val path = (item as BaseFeature).displayName
            try {
                val cache = DexCacheManager.loadItemCache(item)
                if (cache == null) {
                    WeLogger.w(TAG, "cache missing for $path")
                    failedItems += item
                    continue
                }

                val missingKeys = item.loadFromCache(cache)
                if (missingKeys.isNotEmpty()) {
                    val total = item.dexDelegates.size
                    val loaded = total - missingKeys.size
                    WeLogger.w(TAG, "$path: loaded $loaded/$total delegates from cache, missing: $missingKeys")
                    failedItems += item
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "cache load failed for $path", e)
                runCatching { DexCacheManager.deleteCache(path) }
                failedItems += item
            }
        }

        return failedItems
    }

    /**
     * 后台静默自动适配 — 替代原来的手动 DexResolver 弹窗。
     *
     * 注册适配完成回调，AutoAdaptationManager 适配完成后自动重新加载功能。
     */
    private fun scheduleAutoAdaptation(brokenItems: List<IResolveDex>) {
        if (Preferences.noDexResolve) {
            WeLogger.w(TAG, "noDexResolve is set, skipping auto-adaptation")
            return
        }

        WeLogger.i(TAG, "scheduling background auto-adaptation for ${brokenItems.size} items")

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            // 等待 AutoAdaptationManager 完成适配
            AutoAdaptationManager.onAdaptationComplete {
                WeLogger.i(TAG, "auto-adaptation complete, re-loading features")

                // 重新加载功能（适配完成后注入的 Hook 已就绪）
                val allFeatures = FeaturesProvider.ALL_HOOK_ITEMS
                allFeatures.forEach { feature ->
                    if (feature.isActive) return@forEach

                    val isBroken = feature is IResolveDex &&
                        brokenItems.any { (it as BaseFeature).name == feature.name }

                    if (isBroken && feature !is WeSettingsInjector) {
                        if (DynamicFallbackChain.isFailed(feature.name)) {
                            WeLogger.w(TAG, "feature ${feature.name} still failed after adaptation")
                            return@forEach
                        }
                    }

                    try {
                        feature.startup()
                    } catch (e: Exception) {
                        WeLogger.e(TAG, "failed to start ${feature.name} after adaptation", e)
                        DynamicFallbackChain.handleFailure(feature, e)
                    }
                }
            }
        }
    }
}