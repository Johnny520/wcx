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
        ::scanByExactClassName,
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
            // A malformed matcher or DexKit edge case must not abort the whole adaptation run.
            val result = try {
                strategy(dexKit, feature)
            } catch (e: Exception) {
                WeLogger.w(TAG, "${feature.id}: strategy ${strategy.javaClass.name} failed: ${e.message}")
                null
            } catch (e: LinkageError) {
                // Host APK class metadata can be inconsistent across Android/WeChat versions;
                // isolate a linkage failure to this strategy rather than aborting adaptation.
                WeLogger.w(TAG, "${feature.id}: strategy ${strategy.javaClass.name} linkage failure: ${e.message}")
                null
            }
            if (result != null) {
                // DexKit structural matches are hypotheses, not proof that the target can be
                // resolved by the host class loader. Validate the concrete members before
                // publishing a result to the injector; if validation fails, continue with the
                // next strategy instead of caching a stale or malformed descriptor.
                if (!verify(result)) {
                    WeLogger.w(TAG, "${feature.id}: ${result.strategy} candidate ${result.className} failed runtime descriptor verification; trying next strategy")
                    continue
                }
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
            // Isolate each feature: one unexpected scan exception must not discard all prior
            // successes or prevent later features from being checked.
            val result = try {
                scan(dexKit, feature)
            } catch (e: Exception) {
                WeLogger.e(TAG, "${feature.id}: scan aborted unexpectedly; continuing with remaining features", e)
                null
            } catch (e: LinkageError) {
                WeLogger.e(TAG, "${feature.id}: scan linkage failure; continuing with remaining features: ${e.message}")
                null
            }
            if (result != null) {
                success[feature.id] = result
            } else {
                failed.add(feature)
            }
        }

        return BatchScanResult(success, failed)
    }

    // -----------------------------------------------------------------------
    // Strategy 0: verified class-name hint, followed by member-signature validation.
    // -----------------------------------------------------------------------

    private fun scanByExactClassName(dexKit: DexKitBridge, feature: ClassFeature): ScanResult? {
        val hint = feature.classNameHint?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val classes = dexKit.findClass { matcher { className = hint } }
            if (classes.size != 1) {
                if (classes.isNotEmpty()) {
                    WeLogger.w(TAG, "${feature.id}: class-name hint '$hint' resolved to ${classes.size} candidates")
                }
                return null
            }
            val target = classes.single()
            val expectedSuper = feature.superClass
            if (expectedSuper != null && target.superClass?.name != expectedSuper) {
                WeLogger.w(
                    TAG,
                    "${feature.id}: class-name hint '$hint' has superclass '${target.superClass?.name}', " +
                        "expected '$expectedSuper'; falling through to structural strategies"
                )
                return null
            }
            buildResult(dexKit, feature, target, MatchStrategy.EXACT, 1.0f)
        } catch (e: Exception) {
            WeLogger.d(TAG, "${feature.id}: exact class-name hint failed: ${e.message}")
            null
        }
    }

    // -----------------------------------------------------------------------
    // 策略 1: 精确匹配 — 类特征完全匹配
    // -----------------------------------------------------------------------

    private fun scanByExactMatch(dexKit: DexKitBridge, feature: ClassFeature): ScanResult? {
        // An empty DexKit matcher may match every class. Features with only soft
        // stringConstants/method hints must go through a strategy that actually uses them.
        val hasExactConstraint = feature.superClass != null || feature.interfaces.isNotEmpty() ||
            feature.classModifiers != null || feature.annotations.isNotEmpty() ||
            feature.stringConstantsAll.isNotEmpty()
        if (!hasExactConstraint) return null

        return try {
            val classes = dexKit.findClass {
                // Keep all mandatory constraints in one matcher; repeated matcher {} calls
                // may replace the previous matcher and silently discard earlier constraints.
                matcher {
                    feature.superClass?.let { superClass = it }
                    feature.interfaces.forEach { iface -> addInterface(iface) }
                    feature.classModifiers?.let { modifiers = it }
                    feature.annotations.forEach { annotation -> addAnnotation { type = annotation } }
                    feature.stringConstantsAll.forEach { str -> addUsingString(str, StringMatchType.Equals) }
                }
            }

            if (classes.isEmpty()) return null

            val targetClass = if (feature.allowMultiple) {
                classes.getOrNull(feature.multipleIndex)
            } else {
                if (classes.size > 1) {
                    WeLogger.w(TAG, "${feature.id}: exact match is ambiguous (${classes.size} candidates); refusing to select an arbitrary class")
                    return null
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
            fun structuralQuery(stringConstraint: String? = null): List<ClassData> = dexKit.findClass {
                matcher {
                    feature.superClass?.let { superClass = it }
                    feature.interfaces.forEach { iface -> addInterface(iface) }
                    stringConstraint?.let { addUsingString(it, StringMatchType.Contains) }
                }
            }

            val candidates = when {
                feature.stringConstantsAll.isNotEmpty() -> dexKit.findClass {
                    matcher {
                        feature.superClass?.let { superClass = it }
                        feature.interfaces.forEach { iface -> addInterface(iface) }
                        feature.stringConstantsAll.forEach { addUsingString(it, StringMatchType.Equals) }
                    }
                }
                feature.stringConstants.isNotEmpty() -> feature.stringConstants
                    .flatMap { structuralQuery(it) }.distinctBy { it.name }
                else -> structuralQuery()
            }

            if (candidates.isEmpty()) return null
            if (!feature.allowMultiple && candidates.size != 1) {
                WeLogger.w(TAG, "${feature.id}: inheritance match is ambiguous (${candidates.size} candidates); refusing arbitrary selection")
                return null
            }
            val targetClass = (if (feature.allowMultiple) {
                candidates.getOrNull(feature.multipleIndex)
            } else {
                candidates.singleOrNull()
            }) ?: run {
                WeLogger.w(TAG, "${feature.id}: inheritance candidate index ${feature.multipleIndex} is unavailable")
                return null
            }
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
                // Compose the signature as one matcher so every declared constraint applies.
                matcher {
                    primaryMethod.returnType?.let { returnType = it.toDexKitTypeName() }
                    primaryMethod.paramTypes.forEach { pt -> addParamType(pt.toDexKitTypeName()) }
                    if (primaryMethod.paramCount >= 0) paramCount = primaryMethod.paramCount
                    if (primaryMethod.isConstructor) name = "<init>"
                    primaryMethod.modifiers?.let { modifiers = it }
                }
            }

            if (methods.isEmpty()) return null

            // A broad signature can match many methods/classes. Do not silently pick the first
            // result, which could bind a feature to an unrelated class.
            val matchingClasses = methods.map { it.className }.distinct()
            if (matchingClasses.size != 1) {
                WeLogger.w(TAG, "${feature.id}: signature match is ambiguous (${matchingClasses.size} classes)")
                return null
            }
            val className = matchingClasses.first()
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
            fun queryForString(string: String?, exact: Boolean): List<ClassData> = dexKit.findClass {
                matcher {
                    feature.superClass?.let { superClass = it }
                    feature.interfaces.forEach { iface -> addInterface(iface) }
                    if (string != null) addUsingString(string, if (exact) StringMatchType.Equals else StringMatchType.Contains)
                }
            }
            val candidates: List<ClassData> = when {
                feature.stringConstantsAll.isNotEmpty() -> dexKit.findClass {
                    matcher {
                        feature.superClass?.let { superClass = it }
                        feature.interfaces.forEach { iface -> addInterface(iface) }
                        feature.stringConstantsAll.forEach { addUsingString(it, StringMatchType.Equals) }
                    }
                }
                else -> feature.stringConstants.flatMap { queryForString(it, exact = false) }
                    .distinctBy { it.name }
            }
            if (candidates.isEmpty()) return null

            if (!feature.allowMultiple && candidates.size != 1) {
                WeLogger.w(TAG, "${feature.id}: string-constant match is ambiguous (${candidates.size} candidates); refusing arbitrary selection")
                return null
            }
            val targetClass = (if (feature.allowMultiple) {
                candidates.getOrNull(feature.multipleIndex)
            } else {
                candidates.singleOrNull()
            }) ?: run {
                WeLogger.w(TAG, "${feature.id}: string-constant candidate index ${feature.multipleIndex} is unavailable")
                return null
            }
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
            // Fallback keywords have OR semantics. Keep the per-keyword hit set so candidates
            // found by string constants are not discarded merely because their class names are
            // obfuscated and contain none of the human-readable keywords.
            val hitsByKeyword = keywords.associateWith { keyword ->
                dexKit.findClass {
                    matcher {
                        feature.superClass?.let { superClass = it }
                        feature.interfaces.forEach { iface -> addInterface(iface) }
                        addUsingString(keyword, StringMatchType.Contains)
                    }
                }.map { it.name }.toSet()
            }
            val allClasses = keywords.flatMap { keyword ->
                hitsByKeyword[keyword].orEmpty().mapNotNull { className -> dexKit.findClassData(className) }
            }.distinctBy { it.name }

            if (allClasses.isEmpty()) return null

            // Score by the number of keyword constants that actually hit, with class-name and
            // superclass matches as additional evidence rather than the only evidence.
            val scored = allClasses.map { cls ->
                var score = hitsByKeyword.count { (_, classNames) -> cls.name in classNames } * 3
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
            val tiedBest = scored.count { it.second == best.second }
            if (!feature.allowMultiple && tiedBest > 1) {
                WeLogger.w(TAG, "${feature.id}: fuzzy match has $tiedBest equally scored candidates (${best.second}); refusing arbitrary selection")
                return null
            }

            val selected = (if (feature.allowMultiple) {
                scored.getOrNull(feature.multipleIndex)
            } else {
                best
            }) ?: run {
                WeLogger.w(TAG, "${feature.id}: fuzzy candidate index ${feature.multipleIndex} is unavailable")
                return null
            }
            val confidence = (selected.second.toFloat() / (keywords.size * 3)).coerceIn(0.1f, 0.5f)
            buildResult(dexKit, feature, selected.first, MatchStrategy.FUZZY, confidence)
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
        // A cloud record without a class name would create an empty matcher, which can
        // match the entire DEX. Never turn incomplete remote metadata into a random target.
        val targetClassName = cloudFeature.className?.takeIf { it.isNotBlank() } ?: run {
            WeLogger.w(TAG, "${feature.id}: cloud feature has no className; ignoring incomplete record")
            return null
        }

        return try {
            val classes = dexKit.findClass {
                matcher { className = targetClassName }
            }

            if (classes.size != 1) {
                WeLogger.w(TAG, "${feature.id}: cloud class '$targetClassName' resolved to ${classes.size} candidates; refusing selection")
                return null
            }

            buildResult(dexKit, feature, classes.single(), MatchStrategy.CLOUD_FEATURE, 0.9f)
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
    ): ScanResult? {
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

        val expectedMembers = feature.methodFeatures.size + feature.fieldFeatures.size
        val matchedMembers = methods.size + fields.size

        // A class-level match alone is not evidence that the feature's actual Hook targets
        // survived the WeChat update. Reject candidates where every declared member signature
        // failed; otherwise the scanner reports success with an empty, unusable descriptor set.
        if (expectedMembers > 0 && matchedMembers == 0) {
            WeLogger.w(
                TAG,
                "${feature.id}: class ${classData.name} matched via $strategy, but none of " +
                    "$expectedMembers declared method/field features matched; rejecting candidate"
            )
            return null
        }

        val memberCoverage = if (expectedMembers == 0) 1.0f else matchedMembers.toFloat() / expectedMembers
        if (matchedMembers < expectedMembers) {
            WeLogger.w(
                TAG,
                "${feature.id}: partial member coverage $matchedMembers/$expectedMembers in ${classData.name}"
            )
        }

        return ScanResult(
            className = classData.name,
            methods = methods,
            fields = fields,
            confidence = (baseConfidence * memberCoverage).coerceIn(0.0f, 1.0f),
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
                    feature.returnType?.let { returnType = it.toDexKitTypeName() }
                    feature.paramTypes.forEach { addParamType(it.toDexKitTypeName()) }
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
                            feature.returnType?.let { returnType = it.toDexKitTypeName() }
                            feature.paramTypes.forEach { addParamType(it.toDexKitTypeName()) }
                        }
                    }
                    val matches = fuzzyResults.filter { m ->
                        (feature.isStatic == null || Modifier.isStatic(m.modifiers) == feature.isStatic) &&
                            feature.nameKeywords.any { kw -> m.name.contains(kw, ignoreCase = true) }
                    }
                    if (matches.size == 1) {
                        val match = matches.single()
                        return DynamicMethodDesc(match.className, match.methodName, match.methodSign)
                    }
                    if (matches.size > 1) {
                        WeLogger.w(TAG, "${feature.id}: keyword fallback is ambiguous (${matches.size} methods) in ${classData.name}")
                    }
                }
                return null
            }

            val staticFiltered = feature.isStatic?.let { expected ->
                results.filter { Modifier.isStatic(it.modifiers) == expected }
            } ?: results
            if (staticFiltered.isEmpty()) return null
            val candidates = if (feature.nameKeywords.isEmpty()) staticFiltered else {
                val keywordMatches = staticFiltered.filter { m ->
                    feature.nameKeywords.any { kw -> m.name.contains(kw, ignoreCase = true) }
                }
                // Names are commonly obfuscated in WeChat; keywords are only a tie-breaker,
                // never a reason to discard an otherwise unique signature match.
                if (keywordMatches.isNotEmpty()) keywordMatches else staticFiltered
            }
            if (candidates.size != 1) {
                WeLogger.w(TAG, "${feature.id}: method descriptor is ambiguous (${candidates.size} candidates) in ${classData.name}")
                return null
            }
            val m = candidates.single()
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
                    feature.type?.let { type = it.toDexKitTypeName() }
                    feature.modifiers?.let { modifiers = it }
                }
            }

            if (results.isEmpty()) {
                if (feature.nameKeywords.isNotEmpty()) {
                    val fuzzyResults = dexKit.findField {
                        matcher {
                            declaredClass = classData.name
                            feature.type?.let { type = it.toDexKitTypeName() }
                        }
                    }
                    val matches = fuzzyResults.filter { f ->
                        (feature.isStatic == null || Modifier.isStatic(f.modifiers) == feature.isStatic) &&
                            feature.nameKeywords.any { kw -> f.name.contains(kw, ignoreCase = true) }
                    }
                    if (matches.size == 1) {
                        val match = matches.single()
                        val typeDescriptor = match.typeName.toDexFieldDescriptor() ?: return null
                        return DynamicFieldDesc(match.className, match.fieldName, typeDescriptor)
                    }
                    if (matches.size > 1) {
                        WeLogger.w(TAG, "${feature.id}: field keyword fallback is ambiguous (${matches.size} fields) in ${classData.name}")
                    }
                }
                return null
            }

            val staticFiltered = feature.isStatic?.let { expected ->
                results.filter { Modifier.isStatic(it.modifiers) == expected }
            } ?: results
            // Like method matching, field-name keywords are tie-breakers rather than hard
            // requirements (obfuscation may remove the original name). But when a keyword
            // does identify candidates, prefer that smaller set instead of rejecting a valid
            // field merely because unrelated fields share the same type.
            val keywordFiltered = if (feature.nameKeywords.isEmpty()) staticFiltered else {
                val keywordMatches = staticFiltered.filter { field ->
                    feature.nameKeywords.any { keyword -> field.name.contains(keyword, ignoreCase = true) }
                }
                if (keywordMatches.isNotEmpty()) keywordMatches else staticFiltered
            }
            if (keywordFiltered.size != 1) {
                WeLogger.w(TAG, "${feature.id}: field descriptor is ambiguous (${keywordFiltered.size} candidates) in ${classData.name}")
                return null
            }
            val f = keywordFiltered.single()
            val typeDescriptor = f.typeName.toDexFieldDescriptor() ?: return null
            DynamicFieldDesc(f.className, f.fieldName, typeDescriptor)
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
            // Validate the root target even when a feature currently has no member descriptors.
            // Without this, an empty result could be accepted despite naming a nonexistent class.
            ClassLoaders.HOST.loadClass(result.className)
            result.methods.forEach { (_, desc) ->
                try {
                    // A scan result can contain methods declared on a related class, so verify
                    // against the descriptor's owner rather than assuming the root class owns it.
                    val owner = ClassLoaders.HOST.loadClass(desc.className)
                    val paramTypes = parseParamTypes(desc.methodSign)
                    val close = desc.methodSign.indexOf(')')
                    require(close >= 0 && close + 1 < desc.methodSign.length) {
                        "Malformed method descriptor: ${desc.methodSign}"
                    }
                    val returnType = parseTypeDescriptor(desc.methodSign.substring(close + 1), allowVoid = true)
                    if (desc.methodName == "<init>") {
                        require(returnType == Void.TYPE) { "Constructor descriptor must return V: ${desc.methodSign}" }
                        owner.getDeclaredConstructor(*paramTypes)
                    } else {
                        val method = owner.getDeclaredMethod(desc.methodName, *paramTypes)
                        require(method.returnType == returnType) {
                            "Return type mismatch for ${desc.className}->${desc.methodName}: " +
                                "descriptor=$returnType, runtime=${method.returnType}"
                        }
                    }
                } catch (e: ReflectiveOperationException) {
                    WeLogger.w(TAG, "verify failed: method ${desc.className}->${desc.methodName}${desc.methodSign}: ${e.message}")
                    return false
                } catch (e: IllegalArgumentException) {
                    WeLogger.w(TAG, "verify failed: invalid method descriptor for ${desc.methodName}: ${e.message}")
                    return false
                } catch (e: SecurityException) {
                    WeLogger.w(TAG, "verify failed: access to method ${desc.className}->${desc.methodName} denied: ${e.message}")
                    return false
                } catch (e: LinkageError) {
                    WeLogger.w(TAG, "verify failed: method ${desc.className}->${desc.methodName} linkage error: ${e.message}")
                    return false
                }
            }
            result.fields.forEach { (_, desc) ->
                try {
                    val owner = ClassLoaders.HOST.loadClass(desc.className)
                    val field = owner.getDeclaredField(desc.fieldName)
                    val descriptorType = parseTypeDescriptor(desc.typeName, allowVoid = false)
                    require(field.type == descriptorType) {
                        "Field type mismatch for ${desc.className}->${desc.fieldName}: " +
                            "descriptor=$descriptorType, runtime=${field.type}"
                    }
                } catch (e: ReflectiveOperationException) {
                    WeLogger.w(TAG, "verify failed: field ${desc.className}->${desc.fieldName}: ${e.message}")
                    return false
                } catch (e: SecurityException) {
                    WeLogger.w(TAG, "verify failed: access to field ${desc.className}->${desc.fieldName} denied: ${e.message}")
                    return false
                } catch (e: LinkageError) {
                    WeLogger.w(TAG, "verify failed: field ${desc.className}->${desc.fieldName} linkage error: ${e.message}")
                    return false
                }
            }
            true
        } catch (e: ClassNotFoundException) {
            WeLogger.w(TAG, "verify failed: class ${result.className} not found: ${e.message}")
            false
        } catch (e: IllegalArgumentException) {
            WeLogger.w(TAG, "verify failed: malformed scan result: ${e.message}")
            false
        } catch (e: SecurityException) {
            WeLogger.w(TAG, "verify failed: reflective verification denied: ${e.message}")
            false
        } catch (e: LinkageError) {
            WeLogger.w(TAG, "verify failed: class/linkage error for ${result.className}: ${e.message}")
            false
        }
    }

    /** Resolve one complete JVM/Dex type descriptor using the host class loader. */
    private fun parseTypeDescriptor(descriptor: String, allowVoid: Boolean): Class<*> {
        require(descriptor.isNotEmpty()) { "Empty type descriptor" }
        return when (descriptor) {
            "V" -> {
                require(allowVoid) { "Void is not valid for this type" }
                Void.TYPE
            }
            "Z" -> Boolean::class.javaPrimitiveType!!
            "B" -> Byte::class.javaPrimitiveType!!
            "C" -> Char::class.javaPrimitiveType!!
            "S" -> Short::class.javaPrimitiveType!!
            "I" -> Int::class.javaPrimitiveType!!
            "J" -> Long::class.javaPrimitiveType!!
            "F" -> Float::class.javaPrimitiveType!!
            "D" -> Double::class.javaPrimitiveType!!
            else -> when {
                descriptor.startsWith("[") -> {
                    // Do not reject arrays merely because an object component contains the
                    // letter 'V' (for example [Lcom/example/Video;). Parse the component type
                    // according to the descriptor grammar and prohibit only the exact V type.
                    val component = descriptor.dropWhile { it == '[' }
                    require(component.isNotEmpty() && component != "V") {
                        "Array descriptor cannot contain void: $descriptor"
                    }
                    require(isValidReferenceOrPrimitiveDescriptor(component, allowVoid = false)) {
                        "Invalid array descriptor: $descriptor"
                    }
                    Class.forName(descriptor.replace('/', '.'), false, ClassLoaders.HOST)
                }
                descriptor.startsWith("L") && descriptor.endsWith(";") && descriptor.length > 2 -> {
                    val internalName = descriptor.substring(1, descriptor.length - 1)
                    require(isValidInternalClassName(internalName)) { "Invalid object descriptor: $descriptor" }
                    ClassLoaders.HOST.loadClass(internalName.replace('/', '.'))
                }
                else -> throw IllegalArgumentException("Invalid type descriptor: $descriptor")
            }
        }
    }

    /** DexKit field type names are commonly Java names (for example `java.lang.String`),
     * while a Dex field descriptor requires `Ljava/lang/String;`. Normalize both forms.
     */
    private fun String.toDexFieldDescriptor(): String? {
        val value = trim()
        if (value.isEmpty()) return null
        val primitive = when (value) {
            "boolean" -> "Z"
            "byte" -> "B"
            "char" -> "C"
            "short" -> "S"
            "int" -> "I"
            "long" -> "J"
            "float" -> "F"
            "double" -> "D"
            "void" -> return null
            else -> null
        }
        if (primitive != null) return primitive
        if (value.startsWith("[")) {
            val dimensions = value.takeWhile { it == '[' }
            val componentDescriptor = value.dropWhile { it == '[' }.toDexFieldDescriptor() ?: return null
            if (componentDescriptor == "V") return null
            return dimensions + componentDescriptor
        }
        if (value.endsWith("[]")) {
            val component = value.dropLast(2).toDexFieldDescriptor() ?: return null
            return "[$component"
        }
        if (value.startsWith("L") && value.endsWith(";") && isValidReferenceOrPrimitiveDescriptor(value, false)) {
            return value
        }
        // Already a primitive Dex descriptor.
        if (value in setOf("Z", "B", "C", "S", "I", "J", "F", "D")) return value
        val internalName = value.replace('.', '/')
        if (!isValidInternalClassName(internalName)) return null
        return "L$internalName;"
    }

    private fun isValidInternalClassName(name: String): Boolean =
        name.isNotBlank() && !name.startsWith('/') && !name.endsWith('/') &&
            !name.contains("//") && name.none { it == '.' || it == ';' || it == '[' || it == '(' || it == ')' || it.isWhitespace() }

    private fun isValidReferenceOrPrimitiveDescriptor(descriptor: String, allowVoid: Boolean): Boolean {
        if (descriptor in setOf("Z", "B", "C", "S", "I", "J", "F", "D")) return true
        if (descriptor == "V") return allowVoid
        if (descriptor.startsWith("L") && descriptor.endsWith(";") && descriptor.length > 2) {
            return isValidInternalClassName(descriptor.substring(1, descriptor.length - 1))
        }
        if (descriptor.startsWith("[")) {
            val component = descriptor.dropWhile { it == '[' }
            return component.isNotEmpty() && component != "V" && isValidReferenceOrPrimitiveDescriptor(component, allowVoid = false)
        }
        return false
    }

    /** Parse a JVM/Dex method descriptor without splitting away object-type delimiters. */
    private fun parseParamTypes(methodSign: String): Array<Class<*>> {
        val open = methodSign.indexOf('(')
        val close = methodSign.indexOf(')', startIndex = open + 1)
        require(open >= 0 && close > open) { "Malformed method descriptor: $methodSign" }

        val params = methodSign.substring(open + 1, close)
        if (params.isEmpty()) return emptyArray()

        val loader = ClassLoaders.HOST
        val result = ArrayList<Class<*>>()
        var index = 0
        while (index < params.length) {
            val start = index
            while (index < params.length && params[index] == '[') index++
            require(index < params.length) { "Incomplete parameter descriptor: $methodSign" }

            when (params[index]) {
                'L' -> {
                    val end = params.indexOf(';', index)
                    require(end >= 0) { "Unterminated object descriptor: $methodSign" }
                    index = end + 1
                }
                'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> index++
                else -> throw IllegalArgumentException("Invalid parameter descriptor in $methodSign at $index")
            }

            val typeDescriptor = params.substring(start, index)
            val type = when (typeDescriptor) {
                "Z" -> Boolean::class.javaPrimitiveType!!
                "B" -> Byte::class.javaPrimitiveType!!
                "C" -> Char::class.javaPrimitiveType!!
                "S" -> Short::class.javaPrimitiveType!!
                "I" -> Int::class.javaPrimitiveType!!
                "J" -> Long::class.javaPrimitiveType!!
                "F" -> Float::class.javaPrimitiveType!!
                "D" -> Double::class.javaPrimitiveType!!
                else -> when {
                    typeDescriptor.startsWith("[") ->
                        Class.forName(typeDescriptor.replace('/', '.'), false, loader)
                    typeDescriptor.startsWith("L") && typeDescriptor.endsWith(";") ->
                        loader.loadClass(typeDescriptor.substring(1, typeDescriptor.length - 1).replace('/', '.'))
                    else -> throw IllegalArgumentException("Invalid parameter descriptor: $typeDescriptor")
                }
            }
            result += type
        }
        return result.toTypedArray()
    }

    /**
     * DexKit matcher APIs expect Java type names, while the feature registry stores JVM
     * descriptors (for example `Ljava/lang/String;`, `I`, `[I`). Normalize both forms.
     */
    private fun String.toDexKitTypeName(): String {
        val value = trim()
        if (value.isEmpty()) return value
        when (value) {
            "V" -> return "void"
            "Z" -> return "boolean"
            "B" -> return "byte"
            "C" -> return "char"
            "S" -> return "short"
            "I" -> return "int"
            "J" -> return "long"
            "F" -> return "float"
            "D" -> return "double"
        }
        if (value.startsWith("[")) return value.drop(1).toDexKitTypeName() + "[]"
        if (value.startsWith("L") && value.endsWith(";")) {
            return value.substring(1, value.length - 1).replace('/', '.')
        }
        return value.replace('/', '.')
    }

    /**
     * 批量扫描结果
     */
    data class BatchScanResult(
        val success: Map<String, ScanResult>,
        val failed: List<ClassFeature>
    )
}