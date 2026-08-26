@file:Suppress("unused")

package com.Johnny.wcx.dexkit.dsl

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import com.Johnny.wcx.dexkit.DexMethodDescriptor
import com.Johnny.wcx.features.core.BaseFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.ClassLoaders
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindField
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * 所有 Dex 委托的公共接口，用于统一缓存读写。
 * 每个委托负责自己的序列化/反序列化。
 */
sealed interface BaseDexDelegate {
    val key: String
    fun getDescriptorString(): String?

    /** 从缓存字符串恢复状态 */
    fun loadDescriptor(value: String)

    /** 执行内联查找（如果是内联声明的话） */
    fun findInline(dexKit: DexKitBridge): Boolean = true
}

// ---------------------------------------------------------------------------
// DexClassDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 类委托 — 自动生成 Key，自动反射获取 Class。
 */
class DexClassDelegate internal constructor(
    override val key: String,
    private val inlineBlock: ((DexClassDelegate, DexKitBridge) -> Boolean)? = null
) : ReadOnlyProperty<BaseFeature, DexClassDelegate>, BaseDexDelegate {

    private var descriptorString: String? = null
    private var cachedClass: Class<*>? = null
    internal var cachedData: ClassData? = null

    val clazz: Class<*>
        get() {
            if (descriptorString == "com.tencent.mm.ui.LauncherUI")
                error("Class resolution has failed: $key")
            if (cachedClass == null && descriptorString != null)
                cachedClass = descriptorString!!.toClassOrNull()
            return cachedClass ?: error("Class not found for key: $key")
        }

    @Suppress("NOTHING_TO_INLINE")
    inline fun reflekt() = clazz.reflekt()

    fun setDescriptor(className: String) {
        descriptorString = className
        cachedClass = null
        cachedData = null
    }

    @Suppress("unused")
    fun setDescriptor(c: ClassData) {
        setDescriptor(c.name)
    }

    fun setPlaceholderDescriptor(placeholder: Boolean = true, reason: String? = null) {
        WeLogger.w("DexClassDelegate", "setting placeholder for $key")
        setDescriptor("com.tencent.mm.ui.LauncherUI")
    }

    val isPlaceholder
        get() = descriptorString == "com.tencent.mm.ui.LauncherUI"

    override fun getDescriptorString(): String? = descriptorString
    override fun loadDescriptor(value: String) = setDescriptor(value)

    /**
     * 查找 Dex 类。结果直接写入委托自身。
     */
    fun find(
        dexKit: DexKitBridge,
        allowMultiple: Boolean = false,
        allowFailure: Boolean = false,
        multipleIndex: Int = 0,
        block: FindClass.() -> Unit
    ): Boolean {
        val results = try {
            dexKit.findClass(block)
        } catch (e: Exception) {
            WeLogger.w("DexClassDelegate", "DexKit findClass failed for key: $key, error: ${e.message}")
            if (!allowFailure) setPlaceholderDescriptor()
            return false
        }

        if (results.isEmpty()) {
            if (!allowFailure) {
                WeLogger.w("DexClassDelegate", "DexKit: No class found for key: $key")
                setPlaceholderDescriptor()
            } else {
                setPlaceholderDescriptor()
            }
            return false
        }
        if (results.size > 1 && !allowMultiple) {
            if (allowFailure) {
                WeLogger.w("DexClassDelegate", "DexKit: Multiple classes found for key: $key, count: ${results.size}, using first match")
                setDescriptor(results[0].name)
                return true
            }
            error(
                "DexKit: Multiple classes found for key: $key, count: ${results.size}, classes: ${
                results.joinToString(",") { it.name }
            }")
        }

        setDescriptor(results[safeIndex(results, multipleIndex, key, "class")].name)
        return true
    }

    fun getClassData(dexKit: DexKitBridge): ClassData =
        dexKit.findClassData(getDescriptorString()!!)!!

    override fun findInline(dexKit: DexKitBridge): Boolean {
        return inlineBlock?.invoke(this, dexKit) ?: true
    }

    override fun getValue(thisRef: BaseFeature, property: KProperty<*>): DexClassDelegate = this
}

// ---------------------------------------------------------------------------
// DexFieldDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 字段委托 — 自动生成 Key，自动反射获取 Field。
 */
class DexFieldDelegate internal constructor(
    override val key: String,
    private val inlineBlock: ((DexFieldDelegate, DexKitBridge) -> Boolean)? = null
) : ReadOnlyProperty<BaseFeature, DexFieldDelegate>, BaseDexDelegate {

    private var descriptorString: String? = null
    private var cachedField: Field? = null
    internal var cachedData: FieldData? = null

    val field: Field
        get() {
            if (descriptorString == PLACEHOLDER_DESCRIPTOR)
                error("Field resolution has failed: $key")
            if (cachedField == null && descriptorString != null)
                cachedField = getFieldInstance(descriptorString!!)
            return cachedField ?: error("Field not found for key: $key")
        }

    fun setDescriptor(desc: String) {
        descriptorString = desc
        cachedField = null
        cachedData = null
    }

    @Suppress("unused")
    fun setDescriptor(f: FieldData) {
        setDescriptor(f.descriptor)
    }

    fun setPlaceholderDescriptor(placeholder: Boolean = true, reason: String? = null) {
        WeLogger.w("DexFieldDelegate", "setting placeholder for $key")
        setDescriptor(PLACEHOLDER_DESCRIPTOR)
    }

    val isPlaceholder
        get() = descriptorString == PLACEHOLDER_DESCRIPTOR

    override fun getDescriptorString(): String? = descriptorString
    override fun loadDescriptor(value: String) = setDescriptor(value)

    fun find(
        dexKit: DexKitBridge,
        allowMultiple: Boolean = false,
        allowFailure: Boolean = false,
        resultIndex: Int = 0,
        block: FindField.() -> Unit
    ): Boolean {
        val results = try {
            dexKit.findField(block)
        } catch (e: Exception) {
            WeLogger.w("DexFieldDelegate", "DexKit findField failed for key: $key, error: ${e.message}")
            setPlaceholderDescriptor()
            return false
        }

        if (results.isEmpty()) {
            if (!allowFailure) {
                WeLogger.w("DexFieldDelegate", "DexKit: No field found for key: $key")
            }
            setPlaceholderDescriptor()
            return false
        }
        if (results.size > 1 && !allowMultiple) {
            if (allowFailure) {
                WeLogger.w("DexFieldDelegate", "DexKit: Multiple fields found for key: $key, count: ${results.size}, using first match")
                setDescriptor(results[0].descriptor)
                return true
            }
            error(
                "DexKit: Multiple fields found for key: $key, count: ${results.size}, fields:${
                    results.map { "${it.className}::${it.fieldName}" }
                }"
            )
        }

        setDescriptor(results[safeIndex(results, resultIndex, key, "field")].descriptor)
        return true
    }

    override fun findInline(dexKit: DexKitBridge): Boolean {
        return inlineBlock?.invoke(this, dexKit) ?: true
    }

    override fun getValue(thisRef: BaseFeature, property: KProperty<*>): DexFieldDelegate = this

    private fun getFieldInstance(descriptor: String): Field {
        val arrow = descriptor.indexOf("->")
        val colon = descriptor.indexOf(':', arrow)
        require(arrow >= 0 && colon >= 0) { descriptor }
        val className = descriptor.substring(1, arrow - 1).replace('/', '.')
        val fieldName = descriptor.substring(arrow + 2, colon)
        var current: Class<*>? = ClassLoaders.HOST.loadClass(className)
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException(descriptor)
    }

    companion object {
        private const val PLACEHOLDER_DESCRIPTOR =
            "Lcom/tencent/mm/ui/LauncherUI;->INSTANCE:Lcom/tencent/mm/ui/LauncherUI;"
    }
}

// ---------------------------------------------------------------------------
// DexMethodDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 方法委托 — 自动生成 Key，自动反射获取 Method。
 */
class DexMethodDelegate internal constructor(
    override val key: String,
    private val inlineBlock: ((DexMethodDelegate, DexKitBridge) -> Boolean)? = null
) : ReadOnlyProperty<BaseFeature, DexMethodDelegate>, BaseDexDelegate {

    private var descriptor: DexMethodDescriptor? = null
    private var cachedMethod: Method? = null
    internal var cachedData: MethodData? = null

    val method: Method
        get() {
            if (descriptor != null && descriptor!!.name == "Lcom/tencent/mm/ui/LauncherUI;->()Lcom/tencent/mm/ui/LauncherUI;")
                error("Method resolution has failed: $key")
            if (cachedMethod == null && descriptor != null)
                cachedMethod = descriptor!!.getMethodInstance(ClassLoaders.HOST)
            return cachedMethod ?: error("Method not found for key: $key")
        }

    @Deprecated("You shouldn't call .reflekt() on a Method", level = DeprecationLevel.ERROR)
    fun reflekt(): Nothing = error("You shouldn't call .reflekt() on a Method")

    fun setDescriptor(desc: DexMethodDescriptor) {
        descriptor = desc
        cachedMethod = null
        cachedData = null
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun setDescriptor(m: MethodData) = setDescriptor(DexMethodDescriptor(m.className, m.methodName, m.methodSign))

    val isPlaceholder
        get() = descriptor != null &&
                descriptor!!.name == "Lcom/tencent/mm/ui/LauncherUI;->getInstance()Lcom/tencent/mm/ui/LauncherUI;"

    fun setDescriptor(className: String, methodName: String, methodSign: String) =
        setDescriptor(DexMethodDescriptor(className, methodName, methodSign))

    fun setPlaceholderDescriptor(placeholder: Boolean = true, reason: String? = null) {
        WeLogger.w("DexMethodDelegate", "setting placeholder for $key")
        setDescriptor(DexMethodDescriptor("Lcom/tencent/mm/ui/LauncherUI;->getInstance()Lcom/tencent/mm/ui/LauncherUI;"))
    }

    override fun getDescriptorString(): String? = descriptor?.descriptor

    override fun loadDescriptor(value: String) {
        descriptor = DexMethodDescriptor(value)
        cachedMethod = null
        cachedData = null
    }

    /**
     * 查找 Dex 方法。结果直接写入委托自身。
     */
    fun find(
        dexKit: DexKitBridge,
        allowMultiple: Boolean = false,
        allowFailure: Boolean = false,
        resultIndex: Int = 0,
        block: FindMethod.() -> Unit
    ): Boolean {
        val results = try {
            dexKit.findMethod(block)
        } catch (e: Exception) {
            WeLogger.w("DexMethodDelegate", "DexKit findMethod failed for key: $key, error: ${e.message}")
            setPlaceholderDescriptor()
            return false
        }

        if (results.isEmpty()) {
            if (!allowFailure) {
                WeLogger.w("DexMethodDelegate", "DexKit: No method found for key: $key")
            }
            setPlaceholderDescriptor()
            return false
        }
        if (results.size > 1 && !allowMultiple) {
            if (allowFailure) {
                WeLogger.w("DexMethodDelegate", "DexKit: Multiple methods found for key: $key, count: ${results.size}, using first match: ${results[0].className}::${results[0].methodName}")
                val m = results[0]
                setDescriptor(DexMethodDescriptor(m.className, m.methodName, m.methodSign))
                return true
            }
            error(
                "DexKit: Multiple methods found for key: $key, count: ${results.size}, methods:${
                    results.map {
                        "${it.className}::${it.methodName}"
                    }
                }"
            )
        }

        val m = results[safeIndex(results, resultIndex, key, "method")]
        setDescriptor(DexMethodDescriptor(m.className, m.methodName, m.methodSign))
        return true
    }

    override fun findInline(dexKit: DexKitBridge): Boolean {
        return inlineBlock?.invoke(this, dexKit) ?: true
    }

    override fun getValue(thisRef: BaseFeature, property: KProperty<*>): DexMethodDelegate = this
}

// ---------------------------------------------------------------------------
// DexConstructorDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 构造函数委托 — 自动生成 Key，自动反射获取 Constructor。
 */
class DexConstructorDelegate internal constructor(
    override val key: String,
    private val inlineBlock: ((DexConstructorDelegate, DexKitBridge) -> Boolean)? = null
) : ReadOnlyProperty<BaseFeature, DexConstructorDelegate>, BaseDexDelegate {

    private var descriptor: DexMethodDescriptor? = null
    private var cachedConstructor: Constructor<*>? = null
    internal var cachedData: MethodData? = null

    val constructor: Constructor<*>
        get() {
            if (cachedConstructor == null && descriptor != null)
                cachedConstructor = descriptor!!.getConstructorInstance(ClassLoaders.HOST)
            return cachedConstructor ?: error("Constructor not found for key: $key")
        }

    @Deprecated("You shouldn't call .reflekt() on a Constructor", level = DeprecationLevel.ERROR)
    fun reflekt(): Nothing = error("You shouldn't call .reflekt() on a Constructor")

    fun newInstance(vararg initArgs: Any?): Any = constructor.newInstance(*initArgs)

    fun setDescriptor(desc: DexMethodDescriptor) {
        descriptor = desc
        cachedConstructor = null
        cachedData = null
    }

    fun setPlaceholderDescriptor(placeholder: Boolean = true, reason: String? = null) {
        WeLogger.w("DexMethodDelegate", "setting placeholder for $key")
        setDescriptor(DexMethodDescriptor("Lcom/tencent/mm/ui/LauncherUI;->getInstance()Lcom/tencent/mm/ui/LauncherUI;"))
    }

    @Suppress("unused")
    fun setDescriptor(className: String, methodSign: String) =
        setDescriptor(DexMethodDescriptor(className, "<init>", methodSign))

    override fun getDescriptorString(): String? = descriptor?.descriptor

    override fun loadDescriptor(value: String) {
        descriptor = DexMethodDescriptor(value)
        cachedConstructor = null
        cachedData = null
    }

    /**
     * 查找 Dex 构造函数。结果直接写入委托自身。
     */
    fun find(
        dexKit: DexKitBridge,
        allowMultiple: Boolean = false,
        throwOnFailure: Boolean = true,
        resultIndex: Int = 0,
        block: FindMethod.() -> Unit
    ): Boolean {
        val results = try {
            dexKit.findMethod {
                block()
                if (matcher == null) matcher { name = "<init>" }
                else matcher!!.name = "<init>"
            }
        } catch (e: Exception) {
            WeLogger.w("DexConstructorDelegate", "DexKit findConstructor failed for key: $key, error: ${e.message}")
            setPlaceholderDescriptor()
            return false
        }

        if (results.isEmpty()) {
            if (!throwOnFailure) {
                WeLogger.w("DexConstructorDelegate", "DexKit: No constructor found for key: $key")
                setPlaceholderDescriptor()
            }
            return false
        }
        if (results.size > 1 && !allowMultiple) {
            if (!throwOnFailure) {
                WeLogger.w("DexConstructorDelegate", "DexKit: Multiple constructors found for key: $key, count: ${results.size}, using first match")
                val m = results[0]
                setDescriptor(DexMethodDescriptor(m.className, "<init>", m.methodSign))
                return true
            }
            error("DexKit: Multiple constructors found for key: $key, count: ${results.size}")
        }

        val m = results[safeIndex(results, resultIndex, key, "constructor")]
        setDescriptor(DexMethodDescriptor(m.className, "<init>", m.methodSign))
        return true
    }

    override fun findInline(dexKit: DexKitBridge): Boolean {
        return inlineBlock?.invoke(this, dexKit) ?: true
    }

    override fun getValue(thisRef: BaseFeature, property: KProperty<*>): DexConstructorDelegate = this
}

// ---------------------------------------------------------------------------
// 委托工厂函数 — 自动注册到父 Feature
// ---------------------------------------------------------------------------

/**
 * 创建 dexConstructor 委托，并将其注册到所属 Feature 的委托列表中。
 */
fun dexConstructor(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexConstructorDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexConstructorDelegate(key).also { item.registerDexDelegate(it) }
    }

/**
 * 创建 dexClass 委托，并将其注册到所属 Feature 的委托列表中。
 */
fun dexClass(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexClassDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexClassDelegate(key).also { item.registerDexDelegate(it) }
    }

/**
 * 创建 dexField 委托，并将其注册到所属 Feature 的委托列表中。
 */
fun dexField(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexFieldDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexFieldDelegate(key).also { item.registerDexDelegate(it) }
    }

/**
 * 创建 dexMethod 委托，并将其注册到所属 Feature 的委托列表中。
 */
fun dexMethod(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexMethodDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexMethodDelegate(key).also { item.registerDexDelegate(it) }
    }

@Suppress("NOTHING_TO_INLINE")
inline fun DexKitBridge.findClassData(clazz: String): ClassData? =
    findClass { matcher { className = clazz } }.singleOrNull()

// ---------------------------------------------------------------------------
// 内联查找委托工厂函数
// ---------------------------------------------------------------------------

/**
 * 创建带有内联查找逻辑的 dexConstructor 委托
 */
fun dexConstructor(
    allowMultiple: Boolean = false,
    throwOnFailure: Boolean = true,
    resultIndex: Int = 0,
    block: FindMethod.() -> Unit
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexConstructorDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexConstructorDelegate(key) { delegate, dexKit ->
            delegate.find(dexKit, allowMultiple, throwOnFailure, resultIndex, block)
        }.also { item.registerDexDelegate(it) }
    }

/**
 * 创建带有内联查找逻辑的 dexClass 委托
 */
fun dexClass(
    allowMultiple: Boolean = false,
    allowFailure: Boolean = false,
    multipleIndex: Int = 0,
    block: FindClass.() -> Unit
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexClassDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexClassDelegate(key) { delegate, dexKit ->
            delegate.find(dexKit, allowMultiple, allowFailure, multipleIndex, block)
        }.also { item.registerDexDelegate(it) }
    }

/**
 * 创建带有内联查找逻辑的 dexField 委托
 */
fun dexField(
    allowMultiple: Boolean = false,
    allowFailure: Boolean = false,
    resultIndex: Int = 0,
    block: FindField.() -> Unit
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexFieldDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexFieldDelegate(key) { delegate, dexKit ->
            delegate.find(dexKit, allowMultiple, allowFailure, resultIndex, block)
        }.also { item.registerDexDelegate(it) }
    }

/**
 * 创建带有内联查找逻辑的 dexMethod 委托
 */
fun dexMethod(
    allowMultiple: Boolean = false,
    allowFailure: Boolean = false,
    resultIndex: Int = 0,
    block: FindMethod.() -> Unit
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexMethodDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexMethodDelegate(key) { delegate, dexKit ->
            delegate.find(dexKit, allowMultiple, allowFailure, resultIndex, block)
        }.also { item.registerDexDelegate(it) }
    }

/**
 * 防止 resultIndex / multipleIndex 越界导致整个 Dex 扫描崩溃。
 * 越界时记录日志并回退到最后一个有效结果。
 */
private fun <T> safeIndex(results: List<T>, index: Int, key: String, kind: String): Int {
    if (index < results.size) return index
    WeLogger.w(
        "DexDelegate",
        "resultIndex $index out of bounds (size=${results.size}) for $kind key=$key, clamping to last"
    )
    return results.lastIndex.coerceAtLeast(0)
}

// ---- WeKit-compat: `.data` DexKit metadata accessors (merged official features use these) ----

val DexClassDelegate.data: org.luckypray.dexkit.result.ClassData
    get() = cachedData ?: com.Johnny.wcx.dexkit.resolution.DexResolutionContext.dexKit
        .getClassData(getDescriptorString()!!)!!.also { cachedData = it }

val DexMethodDelegate.data: org.luckypray.dexkit.result.MethodData
    get() = cachedData ?: com.Johnny.wcx.dexkit.resolution.DexResolutionContext.dexKit
        .getMethodData(getDescriptorString()!!)!!.also { cachedData = it }

val DexConstructorDelegate.data: org.luckypray.dexkit.result.MethodData
    get() = cachedData ?: com.Johnny.wcx.dexkit.resolution.DexResolutionContext.dexKit
        .getMethodData(getDescriptorString()!!)!!.also { cachedData = it }

val DexFieldDelegate.data: org.luckypray.dexkit.result.FieldData
    get() = cachedData ?: com.Johnny.wcx.dexkit.resolution.DexResolutionContext.dexKit
        .getFieldData(getDescriptorString()!!)!!.also { cachedData = it }
