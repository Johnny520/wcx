package com.Johnny.wcx.dynamic

import com.Johnny.wcx.features.core.BaseFeature
import com.Johnny.wcx.utils.WeLogger

/**
 * 动态备用兼容链路 — 自动冲突隔离 + 动态降级备用链路。
 *
 * 动态匹配失败的单一组件自动启用备用兼容实现，
 * 其余全部功能不受影响，不会整体空白、不会强制关闭整套 UI 功能。
 *
 * 工作原理：
 * 1. 每个功能可注册多个备用实现
 * 2. 主实现失败时自动切换到备用实现
 * 3. 备用实现也失败时，启用最小化兜底实现
 * 4. 隔离故障，不影响其他功能
 */
object DynamicFallbackChain {

    private const val TAG = "DynamicFallbackChain"

    /**
     * 备用实现描述符。
     */
    data class FallbackEntry(
        /** 备用实现标识 */
        val id: String,
        /** 优先级 (数字越小越优先) */
        val priority: Int = 0,
        /** 备用实现启动回调 */
        val startup: (() -> Boolean)? = null,
        /** 备用实现关闭回调 */
        val shutdown: (() -> Unit)? = null,
        /** 描述 */
        val description: String = ""
    )

    /**
     * 功能的备用链路注册表。
     * featureId -> List<FallbackEntry>
     */
    private val fallbackRegistry = mutableMapOf<String, MutableList<FallbackEntry>>()
    private val activeFallbacks = mutableMapOf<String, String>() // featureId -> activeFallbackId
    private val failedFeatures = mutableSetOf<String>()

    /**
     * 为功能注册备用实现。
     */
    fun register(featureId: String, fallback: FallbackEntry) {
        fallbackRegistry.getOrPut(featureId) { mutableListOf() }.add(fallback)
        fallbackRegistry[featureId]?.sortBy { it.priority }
        WeLogger.d(TAG, "registered fallback for $featureId: ${fallback.id} (priority=${fallback.priority})")
    }

    /**
     * 为功能注册多个备用实现。
     */
    fun registerAll(featureId: String, fallbacks: List<FallbackEntry>) {
        fallbacks.forEach { register(featureId, it) }
    }

    /**
     * 处理功能启动失败，尝试备用链路。
     *
     * @param feature 失败的功能
     * @param error 失败原因
     * @return 是否通过备用链路成功启动
     */
    fun handleFailure(feature: BaseFeature, error: Throwable? = null): Boolean {
        val featureId = feature.name
        if (featureId in failedFeatures) {
            WeLogger.w(TAG, "$featureId already marked as failed, skipping fallback")
            return false
        }

        WeLogger.w(TAG, "handling failure for $featureId: ${error?.message ?: "unknown error"}")

        val fallbacks = fallbackRegistry[featureId]
        if (fallbacks.isNullOrEmpty()) {
            WeLogger.w(TAG, "no fallback registered for $featureId")
            failedFeatures.add(featureId)
            return false
        }

        // 按优先级依次尝试备用实现
        for (fallback in fallbacks) {
            if (fallback.id in activeFallbacks.values) {
                WeLogger.d(TAG, "fallback ${fallback.id} already active for $featureId")
                continue
            }

            WeLogger.i(TAG, "trying fallback ${fallback.id} for $featureId: ${fallback.description}")

            try {
                val success = fallback.startup?.invoke() ?: false
                if (success) {
                    activeFallbacks[featureId] = fallback.id
                    WeLogger.i(TAG, "fallback ${fallback.id} activated for $featureId")
                    return true
                }
            } catch (e: Exception) {
                WeLogger.w(TAG, "fallback ${fallback.id} failed for $featureId: ${e.message}")
            }
        }

        // 所有备用实现都失败
        failedFeatures.add(featureId)
        WeLogger.e(TAG, "all fallbacks failed for $featureId, feature marked as failed")
        return false
    }

    /**
     * 检查功能是否处于故障状态。
     */
    fun isFailed(featureId: String): Boolean = featureId in failedFeatures

    /**
     * 检查功能是否通过备用链路运行。
     */
    fun isFallbackActive(featureId: String): Boolean = featureId in activeFallbacks

    /**
     * 获取当前激活的备用实现 ID。
     */
    fun getActiveFallback(featureId: String): String? = activeFallbacks[featureId]

    /**
     * 重置功能的故障状态（用于重新适配后重试）。
     */
    fun reset(featureId: String) {
        failedFeatures.remove(featureId)
        val fallbackId = activeFallbacks.remove(featureId)
        if (fallbackId != null) {
            // 关闭备用实现
            fallbackRegistry[featureId]?.find { it.id == fallbackId }?.shutdown?.invoke()
        }
        WeLogger.i(TAG, "reset feature $featureId")
    }

    /**
     * 重置所有功能的故障状态。
     */
    fun resetAll() {
        activeFallbacks.forEach { (featureId, fallbackId) ->
            fallbackRegistry[featureId]?.find { it.id == fallbackId }?.shutdown?.invoke()
        }
        activeFallbacks.clear()
        failedFeatures.clear()
        WeLogger.i(TAG, "reset all features")
    }

    /**
     * 获取故障统计信息。
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "totalFeatures" to fallbackRegistry.size,
            "failedFeatures" to failedFeatures.size,
            "activeFallbacks" to activeFallbacks.size,
            "failedFeatureIds" to failedFeatures.toList(),
            "activeFallbackIds" to activeFallbacks.toMap()
        )
    }
}