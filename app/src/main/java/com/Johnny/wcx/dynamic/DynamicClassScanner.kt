package com.Johnny.wcx.dynamic

import com.Johnny.wcx.dexkit.dsl.findClassData
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.ClassLoaders
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindField
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.FieldMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import java.lang.reflect.Modifier

/**
 * 动态字节码特征扫描引擎 — 核心扫描器。
 *
 * 不依赖固定版本号匹配，依靠：
 * - 类继承关系
 * - 方法入参/返回值签名
 * - 字符串常量
 * - 字段变量特征
 * - 布局 ID 特征
 *
 * 自动检索微信目标对象。微信更新后哪怕混淆名全部更换、新增/删除局部变量、
 * 修改方法逻辑，引擎自动定位原需要 Hook 的目标类、方法、成员变量。
 *
 * 使用方式：
 * ```
 * val scanner = DynamicClassScanner(dexKit)
 * val result = scanner.scan(launcherUiFeature)
 * if (result != null) {
 *     // 使用 result.className, result.methods, result.fields
 * }
 * ```
 */
object DynamicClassScanner {

    private const val TAG = "DynamicClassScanner"

    // 扫描策略：按优先级从高到低尝试
    private val STRATEGIES = listOf(
        ::scanByExactMatch,
        ::scanByInheritance,
        ::scanBySignature,
        ::scanByStringConstant,
        ::scanByFuzzyKeyword,
        ::scanByCloudFeature
    )

    /**
     * 执行完整扫描流程，按优先级依次尝试各策略，返回第一个有效结果。
     */
    fun scan(dexKit: DexKitBridge, feature: ClassFeature): ScanResult? {
        WeLogger.i(TAG, "scanning ${feature.id}: ${feature.description}")

        for (strategy in STRATEGIES) {
            val result = strategy(dexKit, feature)
            if (result != null) {
                WeLogger.i(TAG, "${feature.id} matched via ${result.strategy} -> ${result.className} (confidence=${result.confidence})")
                return result
            }
        }

        WeLogger.w(TAG, "${feature.id}: all strategies failed")
        return null
    }

    /**
     * 批量扫描，返回成功和失败的结果。
     */
    fun scanBatch(dexKit: DexKitBridge, features: List<ClassFeature>): BatchScanResult {
        val success = mutableMapOf<String, ScanResult>()
        val failed = mutableListOf<ClassFeature>()

        for (feature in features.sortedBy { it.priority }) {
            val result = scan(dexKit, feature)
            if (result != null) {
                success[feature.id] = result
            } else {
                failed.add(feature)
            }
        }

        return BatchScanResult(success, failed)
    }

    // -----------------------------------------------------------------------
    // 策略 1: 精确匹配 — 类特征完全匹配
    // -----------------------------------------------------------------------

    private fun scanByExactMatch(dexKit: DexKitBridge, feature: ClassFeature): ScanResult? {
        return try {
            val classes = dexKit.findClass {
                feature.superClass?.let { matcher { superClass = it } }
                feature.interfaces.forEach { iface ->
                    matcher { addInterface(iface) }
                }
                feature.classModifiers?.let { matcher { modifiers = it } }
                feature.annotations.forEach { annotation ->
                    matcher { addAnnotation { type = annotation } }
                }
                feature.stringConstantsAll.forEach { str ->
                    matcher { addUsingString(str, StringMatchType.Equals) }
                }
            }

            if (classes.isEmpty()) return null

            val targetClass = if (feature.allowMultiple) {
                classes.getOrNull(feature.multipleIndex)
            } else {
                if (classes.size > 1) {
                    WeLogger.w(TAG, "${feature.id}: multiple exact matches (${classes.size}), using first: ${classes[0].name}")
                }
                classes.first()
            } ?: return null

            buildResult(dexKit, feature, targetClass, MatchStrategy.EXACT, 1.0f)
        } catch (e: Exception) {
            WeLogger.d(TAG, "${feature.id}: exact match failed: ${e.message}")
            null
        }
    }

    // -----------------------------------------------------------------------
    // 策略 2: 继承链匹配 — 仅通过父类/接口
    // -----------------------------------------------------------------------

    private fun scanByInheritance(dexKit: DexKitBridge, feature: ClassFeature): ScanResult? {
        if (feature.superClass == null && feature.interfaces.isEmpty()) return null

        return try {
            val classes = dexKit.findClass {
                feature.superClass?.let { matcher { superClass = it } }
                feature.interfaces.forEach { iface ->
                    matcher { addInterface(iface) }
                }
            }

            if (classes.isEmpty()) return null

            // 继承链匹配优先使用字符串常量进一步过滤
            var candidates: List<ClassData> = classes
            if (feature.stringConstants.isNotEmpty()) {
                candidates = classes.filter { cls ->
                    feature.stringConstants.any { str ->
                        cls.methods.any { m ->
                            m.descriptor.contains(str) || m.name.contains(str, ignoreCase = true)
                        } || cls.fields.any { f ->
                            f.descriptor.contains(str) || f.name.contains(str, ignoreCase = true)
                        }
                    }
                }
            }

            if (candidates.isEmpty()) {
                candidates = classes
            }

            val targetClass = candidates.first()
            buildResult(dexKit, feature, targetClass, MatchStrategy.INHERITANCE, 0.7f)
        } catch (e: Exception) {
            WeLogger.d(TAG, "${feature.id}: inheritance match failed: ${e.message}")
            null
        }
    }

    // -----------------------------------------------------------------------
    // 策略 3: 签名匹配 — 仅通过方法签名
    // -----------------------------------------------------------------------

    private fun scanBySignature(dexKit: DexKitBridge, feature: ClassFeature): ScanResult? {
        if (feature.methodFeatures.isEmpty()) return null

        return try {
            // 取第一个方法特征来定位类
            val primaryMethod = feature.methodFeatures.first()
            val methods = dexKit.findMethod {
                primaryMethod.returnType?.let { matcher { returnType = it } }
                primaryMethod.paramTypes.forEach { pt ->
                    matcher { addParamType(pt) }
                }
                if (primaryMethod.paramCount >= 0) {
                    matcher { paramCount = primaryMethod.paramCount }
                }
                if (primaryMethod.isConstructor) {
                    matcher { name = "<init>" }
                }
                primaryMethod.modifiers?.let { matcher { modifiers = it } }
            }

            if (methods.isEmpty()) return null

            // 从方法反向定位类
            val className = methods.first().className
            val classData = dexKit.findClassData(className) ?: return null

            buildResult(dexKit, feature, classData, MatchStrategy.SIGNATURE, 0.6f)
        } catch (e: Exception) {
            WeLogger.d(TAG, "${feature.id}: signature match failed: ${e.message}")
            null
        }
    }

    // -----------------------------------------------------------------------
    // 策略 4: 字符串常量匹配
    // -----------------------------------------------------------------------

    private fun scanByStringConstant(dexKit: DexKitBridge, feature: ClassFeature): ScanResult? {
        val allStrings = feature.stringConstantsAll + feature.stringConstants
        if (allStrings.isEmpty()) return null

        return try {
            val classes = dexKit.findClass {
                feature.stringConstantsAll.forEach { str ->
                    matcher { addUsingString(str, StringMatchType.Equals) }
                }
                if (feature.stringConstantsAll.isEmpty()) {
                    feature.stringConstants.forEach { str ->
                        matcher { addUsingString(str, StringMatchType.Contains) }
                    }
                }
            }

            if (classes.isEmpty()) return null

            // 通过父类进一步过滤
            var candidates: List<ClassData> = classes
            if (feature.superClass != null) {
                candidates = classes.filter { it.superClass?.name == feature.superClass }
                if (candidates.isEmpty()) candidates = classes
            }

            val targetClass = candidates.first()
            buildResult(dexKit, feature, targetClass, MatchStrategy.STRING_CONSTANT, 0.5f)
        } catch (e: Exception) {
            WeLogger.d(TAG, "${feature.id}: string constant match failed: ${e.message}")
            null
        }
    }

    // -----------------------------------------------------------------------
    // 策略 5: 模糊关键词匹配
    // -----------------------------------------------------------------------

    private fun scanByFuzzyKeyword(dexKit: DexKitBridge, feature: ClassFeature): ScanResult? {
        val keywords = feature.fallbackKeywords
        if (keywords.isEmpty()) return null

        return try {
            // 通过类名模糊搜索
            val allClasses = dexKit.findClass {
                keywords.forEach { kw ->
                    matcher {
                        addUsingString(kw, StringMatchType.Contains)
                    }
                }
            }

            if (allClasses.isEmpty()) return null

            // 评分: 匹配越多关键词的类得分越高
            val scored = allClasses.map { cls ->
                var score = 0
                val name = cls.name.lowercase()
                val superName = cls.superClass?.name?.lowercase() ?: ""
                for (kw in keywords) {
                    val lowerKw = kw.lowercase()
                    if (name.contains(lowerKw)) score += 3
                    if (superName.contains(lowerKw)) score += 2
                }
                cls to score
            }.sortedByDescending { it.second }

            val best = scored.first()
            if (best.second == 0) return null

            val confidence = (best.second.toFloat() / (keywords.size * 3)).coerceIn(0.1f, 0.5f)
            buildResult(dexKit, feature, best.first, MatchStrategy.FUZZY, confidence)
        } catch (e: Exception) {
            WeLogger.d(TAG, "${feature.id}: fuzzy match failed: ${e.message}")
            null
        }
    }

    // -----------------------------------------------------------------------
    // 策略 6: 云端特征库匹配
    // -----------------------------------------------------------------------

    private fun scanByCloudFeature(dexKit: DexKitBridge, feature: ClassFeature): ScanResult? {
        // 从云端特征库获取当前版本的精确匹配规则
        val cloudFeature = CloudFeatureDB.getFeature(feature.id) ?: return null

        return try {
            val classes = dexKit.findClass {
                cloudFeature.className?.let { matcher { className = it } }
            }

            if (classes.isEmpty()) return null

            buildResult(dexKit, feature, classes.first(), MatchStrategy.CLOUD_FEATURE, 0.9f)
        } catch (e: Exception) {
            WeLogger.d(TAG, "${feature.id}: cloud feature match failed: ${e.message}")
            null
        }
    }

    // -----------------------------------------------------------------------
    // 构建结果：扫描类中的方法和字段
    // -----------------------------------------------------------------------

    private fun buildResult(
        dexKit: DexKitBridge,
        feature: ClassFeature,
        classData: ClassData,
        strategy: MatchStrategy,
        baseConfidence: Float
    ): ScanResult {
        val methods = mutableMapOf<String, DynamicMethodDesc>()
        val fields = mutableMapOf<String, DynamicFieldDesc>()

        // 扫描方法
        for (methodFeature in feature.methodFeatures) {
            val methodResult = findMethodByFeature(dexKit, classData, methodFeature)
            if (methodResult != null) {
                methods[methodFeature.id] = methodResult
            }
        }

        // 扫描字段
        for (fieldFeature in feature.fieldFeatures) {
            val fieldResult = findFieldByFeature(dexKit, classData, fieldFeature)
            if (fieldResult != null) {
                fields[fieldFeature.id] = fieldResult
            }
        }

        return ScanResult(
            className = classData.name,
            methods = methods,
            fields = fields,
            confidence = baseConfidence,
            strategy = strategy
        )
    }

    /**
     * 通过方法特征在指定类中查找方法。
     */
    private fun findMethodByFeature(
        dexKit: DexKitBridge,
        classData: ClassData,
        feature: MethodFeature
    ): DynamicMethodDesc? {
        return try {
            val results = dexKit.findMethod {
                matcher {
                    declaredClass = classData.name
                    if (feature.isConstructor) {
                        name = "<init>"
                    }
                    feature.returnType?.let { returnType = it }
                    feature.paramTypes.forEach { addParamType(it) }
                    if (feature.paramCount >= 0) {
                        paramCount = feature.paramCount
                    }
                    feature.modifiers?.let { modifiers = it }
                }
            }

            if (results.isEmpty()) {
                // 模糊匹配: 通过关键词
                if (feature.nameKeywords.isNotEmpty()) {
                    val fuzzyResults = dexKit.findMethod {
                        matcher {
                            declaredClass = classData.name
                            feature.returnType?.let { returnType = it }
                            feature.paramTypes.forEach { addParamType(it) }
                        }
                    }
                    val best = fuzzyResults.firstOrNull { m ->
                        feature.nameKeywords.any { kw ->
                            m.name.contains(kw, ignoreCase = true)
                        }
                    }
                    if (best != null) {
                        return DynamicMethodDesc(best.className, best.methodName, best.methodSign)
                    }
                }
                return null
            }

            val m = results.first()
            DynamicMethodDesc(m.className, m.methodName, m.methodSign)
        } catch (e: Exception) {
            WeLogger.d(TAG, "findMethodByFeature ${feature.id} failed: ${e.message}")
            null
        }
    }

    /**
     * 通过字段特征在指定类中查找字段。
     */
    private fun findFieldByFeature(
        dexKit: DexKitBridge,
        classData: ClassData,
        feature: FieldFeature
    ): DynamicFieldDesc? {
        return try {
            val results = dexKit.findField {
                matcher {
                    declaredClass = classData.name
                    feature.type?.let { type = it }
                    feature.modifiers?.let { modifiers = it }
                }
            }

            if (results.isEmpty()) {
                if (feature.nameKeywords.isNotEmpty()) {
                    val fuzzyResults = dexKit.findField {
                        matcher {
                            declaredClass = classData.name
                            feature.type?.let { type = it }
                        }
                    }
                    val best = fuzzyResults.firstOrNull { f ->
                        feature.nameKeywords.any { kw ->
                            f.name.contains(kw, ignoreCase = true)
                        }
                    }
                    if (best != null) {
                        return DynamicFieldDesc(best.className, best.fieldName, best.typeName)
                    }
                }
                return null
            }

            val f = results.first()
            DynamicFieldDesc(f.className, f.fieldName, f.typeName)
        } catch (e: Exception) {
            WeLogger.d(TAG, "findFieldByFeature ${feature.id} failed: ${e.message}")
            null
        }
    }

    // -----------------------------------------------------------------------
    // 反射验证：确保扫描到的类/方法/字段在运行时确实存在
    // -----------------------------------------------------------------------

    /**
     * 运行时验证 ScanResult 是否可用。
     */
    fun verify(result: ScanResult): Boolean {
        return try {
            val clazz = ClassLoaders.HOST.loadClass(result.className)
            result.methods.forEach { (_, desc) ->
                try {
                    val paramTypes = parseParamTypes(desc.methodSign)
                    clazz.getDeclaredMethod(desc.methodName, *paramTypes)
                } catch (_: NoSuchMethodException) {
                    WeLogger.w(TAG, "verify failed: method ${desc.methodName} not found in ${result.className}")
                    return false
                }
            }
            result.fields.forEach { (_, desc) ->
                try {
                    clazz.getDeclaredField(desc.fieldName)
                } catch (_: NoSuchFieldException) {
                    WeLogger.w(TAG, "verify failed: field ${desc.fieldName} not found in ${result.className}")
                    return false
                }
            }
            true
        } catch (e: ClassNotFoundException) {
            WeLogger.w(TAG, "verify failed: class ${result.className} not found")
            false
        }
    }

    private fun parseParamTypes(methodSign: String): Array<Class<*>> {
        val paramsStr = methodSign.substring(
            methodSign.indexOf('(') + 1,
            methodSign.indexOf(')')
        )
        if (paramsStr.isEmpty()) return emptyArray()

        return paramsStr.split(";")
            .filter { it.isNotEmpty() }
            .map { it.replace('/', '.') + ";" }
            .map { typeStr ->
                when {
                    typeStr.startsWith("L") -> ClassLoaders.HOST.loadClass(
                        typeStr.removePrefix("L").removeSuffix(";").replace('/', '.')
                    )
                    typeStr == "Z" -> Boolean::class.javaPrimitiveType!!
                    typeStr == "B" -> Byte::class.javaPrimitiveType!!
                    typeStr == "C" -> Char::class.javaPrimitiveType!!
                    typeStr == "S" -> Short::class.javaPrimitiveType!!
                    typeStr == "I" -> Int::class.javaPrimitiveType!!
                    typeStr == "J" -> Long::class.javaPrimitiveType!!
                    typeStr == "F" -> Float::class.javaPrimitiveType!!
                    typeStr == "D" -> Double::class.javaPrimitiveType!!
                    typeStr == "V" -> Void::class.javaPrimitiveType!!
                    else -> Any::class.java
                }
            }.toTypedArray()
    }

    /**
     * 批量扫描结果
     */
    data class BatchScanResult(
        val success: Map<String, ScanResult>,
        val failed: List<ClassFeature>
    )
}