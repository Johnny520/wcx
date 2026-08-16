package com.Johnny.wcx.dynamic

import com.Johnny.wcx.dexkit.dsl.BaseDexDelegate
import com.Johnny.wcx.dexkit.dsl.DexClassDelegate
import com.Johnny.wcx.dexkit.dsl.DexConstructorDelegate
import com.Johnny.wcx.dexkit.dsl.DexFieldDelegate
import com.Johnny.wcx.dexkit.dsl.DexMethodDelegate
import com.Johnny.wcx.features.core.BaseFeature
import com.Johnny.wcx.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge

/**
 * 动态 Hook 注入器 — 运行时动态按需注入 Hook。
 *
 * 不再一次性预编译固定全套 Dex，改为运行时动态按需注入 Hook。
 * 用户开启的全部功能实时动态绑定扫描到的微信目标，
 * 关闭功能才不注入；不存在提前打包全部冲突字节码的问题。
 *
 * 工作原理：
 * 1. 扫描结果 (ScanResult) 映射到 Dex 委托
 * 2. 运行时动态挂载 Hook
 * 3. 支持单个功能的热插拔（启用/禁用）
 */
object DynamicHookInjector {

    private const val TAG = "DynamicHookInjector"

    /**
     * 将扫描结果注入到 BaseFeature 的 Dex 委托中。
     * 注入后功能即可正常执行 Hook 操作，无需手动适配。
     *
     * @param feature 目标功能
     * @param scanResult 扫描结果
     * @return 是否注入成功
     */
    fun inject(feature: BaseFeature, scanResult: ScanResult): Boolean {
        return try {
            var injectedCount = 0

            for (delegate in feature.dexDelegates) {
                when (delegate) {
                    is DexClassDelegate -> {
                        if (injectClass(delegate, scanResult)) injectedCount++
                    }
                    is DexMethodDelegate -> {
                        if (injectMethod(delegate, scanResult)) injectedCount++
                    }
                    is DexConstructorDelegate -> {
                        if (injectConstructor(delegate, scanResult)) injectedCount++
                    }
                    is DexFieldDelegate -> {
                        if (injectField(delegate, scanResult)) injectedCount++
                    }
                }
            }

            WeLogger.i(TAG, "injected $injectedCount/${feature.dexDelegates.size} delegates for ${feature.displayName}")
            injectedCount > 0
        } catch (e: Exception) {
            WeLogger.e(TAG, "inject failed for ${feature.displayName}", e)
            false
        }
    }

    /**
     * 批量注入多个功能的扫描结果。
     */
    fun injectBatch(
        features: List<BaseFeature>,
        scanResults: Map<String, ScanResult>
    ): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        for (feature in features) {
            val result = scanResults[feature.name] ?: continue
            results[feature.displayName] = inject(feature, result)
        }
        return results
    }

    // -----------------------------------------------------------------------
    // 各类委托注入
    // -----------------------------------------------------------------------

    private fun injectClass(delegate: DexClassDelegate, scanResult: ScanResult): Boolean {
        // 类委托直接设置类名
        delegate.setDescriptor(scanResult.className)
        WeLogger.d(TAG, "injected class: ${delegate.key} -> ${scanResult.className}")
        return true
    }

    private fun injectMethod(delegate: DexMethodDelegate, scanResult: ScanResult): Boolean {
        // 尝试从 scanResult.methods 中匹配对应的方法
        // key 格式: "FeatureName:propertyName"
        val propertyName = delegate.key.substringAfterLast(":")

        // 先尝试精确匹配
        val methodDesc = scanResult.methods.entries.firstOrNull { (id, _) ->
            id == propertyName || delegate.key.endsWith(":$id")
        }?.value

        if (methodDesc != null) {
            delegate.setDescriptor(
                com.Johnny.wcx.dexkit.DexMethodDescriptor(
                    methodDesc.className,
                    methodDesc.methodName,
                    methodDesc.methodSign
                )
            )
            WeLogger.d(TAG, "injected method: ${delegate.key} -> ${methodDesc.descriptor}")
            return true
        }

        // 尝试模糊匹配 (通过关键词)
        val fuzzyMatch = scanResult.methods.values.firstOrNull { desc ->
            desc.methodName.contains(propertyName, ignoreCase = true) ||
            propertyName.contains(desc.methodName, ignoreCase = true)
        }

        if (fuzzyMatch != null) {
            delegate.setDescriptor(
                com.Johnny.wcx.dexkit.DexMethodDescriptor(
                    fuzzyMatch.className,
                    fuzzyMatch.methodName,
                    fuzzyMatch.methodSign
                )
            )
            WeLogger.d(TAG, "fuzzy injected method: ${delegate.key} -> ${fuzzyMatch.descriptor}")
            return true
        }

        // 尝试云端特征库
        val cloudMapping = CloudFeatureDB.getMethodMapping(
            scanResult.className.substringAfterLast("."),
            propertyName
        )
        if (cloudMapping != null) {
            delegate.setDescriptor(
                com.Johnny.wcx.dexkit.DexMethodDescriptor(
                    scanResult.className,
                    cloudMapping.methodName,
                    cloudMapping.methodSign
                )
            )
            WeLogger.d(TAG, "cloud injected method: ${delegate.key} -> ${cloudMapping.methodName}")
            return true
        }

        WeLogger.w(TAG, "no method match for ${delegate.key}")
        return false
    }

    private fun injectConstructor(delegate: DexConstructorDelegate, scanResult: ScanResult): Boolean {
        val propertyName = delegate.key.substringAfterLast(":")

        val ctorDesc = scanResult.methods.entries.firstOrNull { (id, _) ->
            id == propertyName || delegate.key.endsWith(":$id")
        }?.value

        if (ctorDesc != null) {
            delegate.setDescriptor(
                com.Johnny.wcx.dexkit.DexMethodDescriptor(
                    ctorDesc.className,
                    "<init>",
                    ctorDesc.methodSign
                )
            )
            WeLogger.d(TAG, "injected constructor: ${delegate.key} -> ${ctorDesc.descriptor}")
            return true
        }

        return false
    }

    private fun injectField(delegate: DexFieldDelegate, scanResult: ScanResult): Boolean {
        val propertyName = delegate.key.substringAfterLast(":")

        val fieldDesc = scanResult.fields.entries.firstOrNull { (id, _) ->
            id == propertyName || delegate.key.endsWith(":$id")
        }?.value

        if (fieldDesc != null) {
            delegate.setDescriptor("${fieldDesc.className}->${fieldDesc.fieldName}:${fieldDesc.typeName}")
            WeLogger.d(TAG, "injected field: ${delegate.key} -> ${fieldDesc.descriptor}")
            return true
        }

        // 尝试云端特征库
        val cloudMapping = CloudFeatureDB.getFieldMapping(
            scanResult.className.substringAfterLast("."),
            propertyName
        )
        if (cloudMapping != null) {
            delegate.setDescriptor(
                "${scanResult.className}->${cloudMapping.fieldName}:${cloudMapping.typeName}"
            )
            WeLogger.d(TAG, "cloud injected field: ${delegate.key} -> ${cloudMapping.fieldName}")
            return true
        }

        return false
    }
}