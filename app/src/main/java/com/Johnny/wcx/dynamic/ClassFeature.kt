package com.Johnny.wcx.dynamic

/**
 * 动态字节码特征描述符 — 不依赖固定版本号，基于多维度特征匹配目标类/方法/字段。
 *
 * 微信更新后混淆名全部更换、新增/删除局部变量、修改方法逻辑时，
 * 引擎通过以下特征自动定位原需要 Hook 的目标：
 * - 类继承关系 (superClass)
 * - 接口实现 (interfaces)
 * - 方法入参/返回值签名 (methodSignatures)
 * - 字符串常量 (stringConstants)
 * - 字段变量特征 (fieldSignatures)
 * - 布局 ID 特征 (layoutIds)
 * - 类访问修饰符 (classModifiers)
 * - 注解 (annotations)
 */
data class ClassFeature(
    /** 唯一标识符，用于缓存和日志 */
    val id: String,

    /** 特征匹配优先级 (0=最高)，数字越大越靠后尝试 */
    val priority: Int = 0,

    // --- 类级别特征 ---
    /** 父类全限定名 (如 "android.app.Activity")，null 表示不限制 */
    val superClass: String? = null,

    /** 必须实现的接口全限定名列表 */
    val interfaces: List<String> = emptyList(),

    /** 类访问修饰符掩码 */
    val classModifiers: Int? = null,

    /** 类注解全限定名列表 */
    val annotations: List<String> = emptyList(),

    /** 类中必须包含的字符串常量 (至少匹配一个即通过) */
    val stringConstants: List<String> = emptyList(),

    /** 类中必须包含的字符串常量 (全部匹配才通过) */
    val stringConstantsAll: List<String> = emptyList(),

    // --- 布局特征 ---
    /** 布局文件中的 ID 关键词 (用于辅助匹配) */
    val layoutIds: List<String> = emptyList(),

    // --- 方法级别特征 ---
    /** 目标方法特征列表 */
    val methodFeatures: List<MethodFeature> = emptyList(),

    // --- 字段级别特征 ---
    /** 目标字段特征列表 */
    val fieldFeatures: List<FieldFeature> = emptyList(),

    // --- 备用匹配策略 ---
    /** 如果精确匹配失败，尝试的模糊匹配关键词列表 */
    val fallbackKeywords: List<String> = emptyList(),

    /** 该类在微信中的功能描述 */
    val description: String = "",

    /** 是否允许匹配多个类（取第一个匹配） */
    val allowMultiple: Boolean = false,

    /** 匹配多个类时的索引 */
    val multipleIndex: Int = 0,

    /** 是否允许匹配失败（不设置 placeholder） */
    val allowFailure: Boolean = false
)

/**
 * 方法特征 — 通过方法签名特征定位目标方法，不依赖固定方法名。
 */
data class MethodFeature(
    /** 方法唯一标识 */
    val id: String,

    /** 方法名关键词 (用于模糊匹配混淆后的方法名) */
    val nameKeywords: List<String> = emptyList(),

    /** 返回类型全限定名 */
    val returnType: String? = null,

    /** 参数类型列表 (全限定名) */
    val paramTypes: List<String> = emptyList(),

    /** 参数数量 (负数表示不限制) */
    val paramCount: Int = -1,

    /** 方法访问修饰符掩码 */
    val modifiers: Int? = null,

    /** 方法注解 */
    val annotations: List<String> = emptyList(),

    /** 方法内必须包含的字符串常量 */
    val stringConstants: List<String> = emptyList(),

    /** 是否静态方法 */
    val isStatic: Boolean? = null,

    /** 是否构造函数 */
    val isConstructor: Boolean = false,

    /** 匹配优先级 (0=最高) */
    val priority: Int = 0
)

/**
 * 字段特征 — 通过字段特征定位目标字段。
 */
data class FieldFeature(
    /** 字段唯一标识 */
    val id: String,

    /** 字段类型全限定名 */
    val type: String? = null,

    /** 字段名关键词 */
    val nameKeywords: List<String> = emptyList(),

    /** 字段访问修饰符掩码 */
    val modifiers: Int? = null,

    /** 是否静态字段 */
    val isStatic: Boolean? = null,

    /** 字段注解 */
    val annotations: List<String> = emptyList()
)

/**
 * 动态扫描结果 — 每个匹配到的目标。
 */
data class ScanResult(
    /** 扫描到的类名 */
    val className: String,

    /** 匹配到的方法列表 (methodFeatureId -> method descriptor) */
    val methods: Map<String, DynamicMethodDesc> = emptyMap(),

    /** 匹配到的字段列表 (fieldFeatureId -> field descriptor) */
    val fields: Map<String, DynamicFieldDesc> = emptyMap(),

    /** 匹配置信度 0.0 ~ 1.0 */
    val confidence: Float = 1.0f,

    /** 使用的匹配策略 */
    val strategy: MatchStrategy = MatchStrategy.EXACT
)

/**
 * 动态方法描述符 (用于缓存和反序列化)
 */
data class DynamicMethodDesc(
    val className: String,
    val methodName: String,
    val methodSign: String,
    val descriptor: String = "$className;->$methodName$methodSign"
)

/**
 * 动态字段描述符
 */
data class DynamicFieldDesc(
    val className: String,
    val fieldName: String,
    val typeName: String,
    val descriptor: String = "$className;->$fieldName:$typeName"
)

/**
 * 匹配策略枚举
 */
enum class MatchStrategy {
    /** 精确匹配 — 类特征完全匹配 */
    EXACT,
    /** 模糊匹配 — 通过关键词匹配 */
    FUZZY,
    /** 继承链匹配 — 通过父类/接口匹配 */
    INHERITANCE,
    /** 签名匹配 — 仅通过方法签名匹配 */
    SIGNATURE,
    /** 字符串常量匹配 */
    STRING_CONSTANT,
    /** 云端特征库匹配 */
    CLOUD_FEATURE,
    /** 备用兼容链路 */
    FALLBACK
}