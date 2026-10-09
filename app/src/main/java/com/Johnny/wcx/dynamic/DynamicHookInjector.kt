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

            val total = feature.dexDelegates.size
            WeLogger.i(TAG, "injected $injectedCount/$total delegates for ${feature.displayName}")
            // Partial descriptor injection is not a successful adaptation. Reporting true here
            // caused the manager to treat a feature with unresolved delegates as healthy.
            total > 0 && injectedCount == total
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
            // Only accept an exact stable-key match. ClassFeature IDs are not generally the
            // same as localized display names; guessing by substring could inject wrong hooks.
            val result = scanResults[feature.name]
                ?: feature.technicalId.takeIf { it.isNotBlank() }?.let(scanResults::get)
            if (result == null) {
                // Keep absence distinct from an attempted injection failure. The manager uses
                // this diagnostic to avoid claiming COMPLETED when keys do not line up.
                if (feature.dexDelegates.isNotEmpty()) {
                    WeLogger.d(
                        TAG,
                        "no exact scan result key for feature name='${feature.name}', " +
                            "technicalId='${feature.technicalId}' (${feature.displayName}); skipping dynamic injection"
                    )
                }
                continue
            }
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
            if (!isValidClassName(methodDesc.className) ||
                !isValidMethodMapping(methodDesc.methodName, methodDesc.methodSign) ||
                methodDesc.methodName == "<init>" || methodDesc.methodName == "<clinit>") {
                WeLogger.w(TAG, "rejected malformed scanned method descriptor for ${delegate.key}")
                return false
            }
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

        // Do not infer semantic equivalence from a unique substring match. In an obfuscated
        // host, a single candidate can still be an unrelated method; only an exact feature ID
        // or an explicitly provisioned cloud mapping is acceptable here.

        // 尝试云端特征库
        val cloudMapping = CloudFeatureDB.getMethodMapping(
            scanResult.className.substringAfterLast("."),
            propertyName
        )
        if (cloudMapping != null && isValidMethodMapping(cloudMapping.methodName, cloudMapping.methodSign)) {
            delegate.setDescriptor(
                com.Johnny.wcx.dexkit.DexMethodDescriptor(
                    scanResult.className,
                    cloudMapping.methodName,
                    cloudMapping.methodSign
                )
            )
            WeLogger.d(TAG, "cloud injected method: ${delegate.key} -> ${cloudMapping.methodName}")
            return true
        } else if (cloudMapping != null) {
            WeLogger.w(TAG, "rejected malformed cloud method mapping for ${delegate.key}")
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
            if (!isValidClassName(ctorDesc.className) ||
                !isValidMethodMapping("<init>", ctorDesc.methodSign) ||
                !ctorDesc.methodSign.endsWith(")V")) {
                WeLogger.w(TAG, "rejected malformed scanned constructor descriptor for ${delegate.key}")
                return false
            }
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


    /** Validate a fully-qualified Java class name supplied by a scan/cloud result. */
    private fun isValidClassName(className: String): Boolean {
        if (className.isBlank() || className.startsWith(".") || className.endsWith(".")) return false
        if (className.contains('/') || className.contains(';') || className.contains('[') ||
            className.contains(' ') || className.contains("->")) return false
        return className.split('.').all { part ->
            part.isNotEmpty() && part.none { it == '(' || it == ')' || it == ':' }
        }
    }

    /** Validate DEX method descriptors before accepting cached/cloud data. */
    private fun isValidMethodMapping(methodName: String, methodSign: String): Boolean {
        if (methodName.isBlank() || methodName.contains('/') || methodName.contains(';') || methodName.contains("->")) {
            return false
        }
        if (!methodSign.startsWith("(")) return false
        val close = methodSign.indexOf(')')
        if (close < 0) return false
        var cursor = 1
        while (cursor < close) {
            val next = typeDescriptorEnd(methodSign, cursor, allowVoid = false) ?: return false
            if (next > close) return false
            cursor = next
        }
        if (cursor != close) return false
        val returnEnd = typeDescriptorEnd(methodSign, close + 1, allowVoid = true) ?: return false
        return returnEnd == methodSign.length
    }

    /** Validate a field name and exactly one DEX field type descriptor. */
    private fun isValidFieldMapping(fieldName: String, typeName: String): Boolean {
        if (fieldName.isBlank() || fieldName.contains('/') || fieldName.contains(';') || fieldName.contains("->")) {
            return false
        }
        return typeDescriptorEnd(typeName, 0, allowVoid = false) == typeName.length
    }

    /** Returns the first index after a valid DEX type descriptor, or null when malformed. */
    private fun typeDescriptorEnd(value: String, start: Int, allowVoid: Boolean): Int? {
        if (start >= value.length) return null
        var cursor = start
        while (cursor < value.length && value[cursor] == '[') cursor++
        if (cursor >= value.length) return null
        return when (value[cursor]) {
            'V' -> if (allowVoid && cursor == start) cursor + 1 else null
            'Z', 'B', 'S', 'C', 'I', 'J', 'F', 'D' -> cursor + 1
            'L' -> {
                val semicolon = value.indexOf(';', cursor + 1)
                if (semicolon <= cursor + 1) return null
                val className = value.substring(cursor + 1, semicolon)
                if (className.startsWith('/') || className.endsWith('/') ||
                    className.contains('.') || className.contains(' ') ||
                    className.contains('[') || className.contains('(') || className.contains(')')) {
                    null
                } else semicolon + 1
            }
            else -> null
        }
    }

    private fun injectField(delegate: DexFieldDelegate, scanResult: ScanResult): Boolean {
        val propertyName = delegate.key.substringAfterLast(":")

        val fieldDesc = scanResult.fields.entries.firstOrNull { (id, _) ->
            id == propertyName || delegate.key.endsWith(":$id")
        }?.value

        if (fieldDesc != null) {
            if (!isValidClassName(fieldDesc.className) ||
                !isValidFieldMapping(fieldDesc.fieldName, fieldDesc.typeName)) {
                WeLogger.w(TAG, "rejected malformed scanned field descriptor for ${delegate.key}")
                return false
            }
            delegate.setDescriptor("${fieldDesc.className}->${fieldDesc.fieldName}:${fieldDesc.typeName}")
            WeLogger.d(TAG, "injected field: ${delegate.key} -> ${fieldDesc.descriptor}")
            return true
        }

        // 尝试云端特征库
        val cloudMapping = CloudFeatureDB.getFieldMapping(
            scanResult.className.substringAfterLast("."),
            propertyName
        )
        if (cloudMapping != null && isValidFieldMapping(cloudMapping.fieldName, cloudMapping.typeName)) {
            delegate.setDescriptor(
                "${scanResult.className}->${cloudMapping.fieldName}:${cloudMapping.typeName}"
            )
            WeLogger.d(TAG, "cloud injected field: ${delegate.key} -> ${cloudMapping.fieldName}")
            return true
        } else if (cloudMapping != null) {
            WeLogger.w(TAG, "rejected malformed cloud field mapping for ${delegate.key}")
        }

        return false
    }
}