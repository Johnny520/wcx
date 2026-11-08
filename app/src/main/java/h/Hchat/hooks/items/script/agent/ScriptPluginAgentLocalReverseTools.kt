package h.Hchat.hooks.items.script.agent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.model.ResourceEntry
import h.Hchat.dexkit.DexBridgeHolder
import h.Hchat.preferences.HchatStorage
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.AnnotationData
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/**
 * Agent 的内置逆向工具。当前微信复用共享 DexKitBridge；外部 APK 按目标会话串行打开，切换时释放。
 */
object ScriptPluginAgentLocalReverseTools {
    private const val PREFIX = "hchat.reverse."
    private const val MAX_RESULTS = 100
    private const val MIN_SMALI_CHARS = 1_000
    private const val DEFAULT_SMALI_CHARS = 24_000
    private const val MAX_SMALI_CONTEXT_CHARS = 48_000
    private val toolNames = setOf(
        "open_target_session",
        "list_target_sessions",
        "get_target_session",
        "close_target_session",
        "compare_methods_using_strings",
        "find_classes_using_strings",
        "find_methods_using_strings",
        "find_methods_using_resource",
        "find_methods",
        "inspect_method",
        "inspect_class",
        "export_method_java",
        "export_class_java",
        "export_method_smali",
        "export_class_smali",
        "read_tool_result",
        "find_resource_values",
        "get_resource_value",
        "list_res",
        "decode_xml",
        "manifest"
    )
    private val binding = AtomicReference<Binding?>(null)
    private val targetLock = Any()
    private val targetSessions = LinkedHashMap<String, TargetSession>()
    private var activeExternalBinding: Binding? = null

    @JvmStatic
    fun install(holder: DexBridgeHolder, context: Context) {
        synchronized(targetLock) {
            activeExternalBinding?.close()
            activeExternalBinding = null
            val host = Binding.host(holder, context.applicationContext)
            binding.getAndSet(host)?.close()
            targetSessions.clear()
            targetSessions[host.sessionId] = TargetSession.fromBinding(host, System.currentTimeMillis())
            loadPersistedTargets(host)
        }
    }

    @JvmStatic
    fun toolCatalog(): String {
        val tools = JSONArray()
        tool(
            tools,
            "${PREFIX}open_target_session",
            "注册并打开指定微信 APK，或在省略参数时返回当前运行微信目标；返回的 session_id 仅用于后续查询工具",
            linkedMapOf(
                "input" to stringValue("可选的微信 APK 绝对路径；只传此参数，省略时使用当前运行微信")
            )
        )
        tool(
            tools,
            "${PREFIX}list_target_sessions",
            "列出当前微信和已注册的外部微信 APK 目标"
        )
        tool(
            tools,
            "${PREFIX}get_target_session",
            "读取一个目标会话的路径、微信版本和当前加载状态",
            linkedMapOf("session_id" to stringValue("list_target_sessions 返回的 session_id")),
            required = listOf("session_id")
        )
        tool(
            tools,
            "${PREFIX}close_target_session",
            "关闭并取消注册一个外部 APK 目标；不会删除原 APK 文件",
            linkedMapOf("session_id" to stringValue("要关闭的外部目标 session_id")),
            required = listOf("session_id")
        )
        tool(
            tools,
            "${PREFIX}compare_methods_using_strings",
            "在多个微信 APK 中使用同一组字符串锚点定位方法，返回各版本候选以便横向比较",
            linkedMapOf(
                "session_ids" to stringArray("至少两个目标 session_id"),
                "contains_all_strings" to stringArray("候选方法必须同时使用的全部字符串"),
                "contains_any_strings" to stringArray("候选方法使用任意一个即可命中的字符串"),
                "limit" to resultLimit("每个目标最多返回的候选数量")
            ),
            required = listOf("session_ids"),
            anyOf = listOf(listOf("contains_all_strings"), listOf("contains_any_strings"))
        )
        tool(
            tools,
            "${PREFIX}find_classes_using_strings",
            "按字符串常量锚点定位类候选，先用独特字符串缩小范围",
            linkedMapOf(
                "contains_all_strings" to stringArray("候选类必须同时使用的全部字符串"),
                "contains_any_strings" to stringArray("候选类使用任意一个即可命中的字符串"),
                "brief" to booleanValue("只返回继续定位所需的紧凑字段", false),
                "fields" to fieldProjection("需要返回的字段", CLASS_SEARCH_FIELDS),
                "limit" to resultLimit(),
                "offset" to resultOffset()
            ),
            anyOf = listOf(listOf("contains_all_strings"), listOf("contains_any_strings"))
        )
        tool(
            tools,
            "${PREFIX}find_methods_using_strings",
            "按字符串常量锚点定位方法候选，返回可继续检查的完整 descriptor",
            linkedMapOf(
                "contains_all_strings" to stringArray("候选方法必须同时使用的全部字符串"),
                "contains_any_strings" to stringArray("候选方法使用任意一个即可命中的字符串"),
                "brief" to booleanValue("只返回 descriptor 和 Dex 来源", false),
                "fields" to fieldProjection("需要返回的字段", METHOD_SEARCH_FIELDS),
                "limit" to resultLimit(),
                "offset" to resultOffset()
            ),
            anyOf = listOf(listOf("contains_all_strings"), listOf("contains_any_strings"))
        )
        tool(
            tools,
            "${PREFIX}find_resource_values",
            "按 APK resources.arsc 中的资源值定位字符串、整数、布尔值或颜色；界面文本必须优先使用此工具，不要拿资源文本做 DEX 字符串检索",
            linkedMapOf(
                "type" to enumValue("资源类型", listOf("string", "integer", "bool", "color")),
                "value" to stringValue("要查找的资源值"),
                "contains" to booleanValue("字符串是否使用包含匹配；默认 true", true),
                "ignore_case" to booleanValue("字符串匹配是否忽略大小写", false),
                "brief" to booleanValue("只返回资源 ID、类型和名称", false),
                "fields" to fieldProjection("需要返回的字段", RESOURCE_VALUE_SEARCH_FIELDS),
                "limit" to resultLimit(),
                "offset" to resultOffset()
            ),
            required = listOf("type", "value")
        )
        tool(
            tools,
            "${PREFIX}get_resource_value",
            "按资源 ID 或资源类型/名称读取 resources.arsc 中的全部配置值",
            linkedMapOf(
                "resource_id" to stringValue("资源 ID，例如 0x7f111663"),
                "type" to stringValue("资源类型，例如 string"),
                "name" to stringValue("资源名称，例如 cfs")
            ),
            anyOf = listOf(listOf("resource_id"), listOf("type", "name"))
        )
        tool(
            tools,
            "${PREFIX}find_methods_using_resource",
            "按资源 ID 定位直接使用该常量的方法；先用资源值检索取得 resource_id",
            linkedMapOf(
                "resource_id" to stringValue("资源 ID，例如 0x7f111663"),
                "brief" to booleanValue("只返回 descriptor 和 Dex 来源", false),
                "fields" to fieldProjection("需要返回的字段", METHOD_SEARCH_FIELDS),
                "limit" to resultLimit(),
                "offset" to resultOffset()
            ),
            required = listOf("resource_id")
        )
        tool(
            tools,
            "${PREFIX}list_res",
            "列出当前 APK 的资源表条目；可按资源类型过滤",
            linkedMapOf(
                "type" to stringValue("可选资源类型，例如 string、layout、drawable"),
                "brief" to booleanValue("只返回资源 ID、类型和名称", false),
                "fields" to fieldProjection("需要返回的字段", RESOURCE_SEARCH_FIELDS),
                "limit" to resultLimit(),
                "offset" to resultOffset()
            )
        )
        tool(
            tools,
            "${PREFIX}decode_xml",
            "解码 APK 内的二进制 XML，例如 AndroidManifest.xml 或 res/layout/*.xml；truncated=true 时按 nextOffset 续读",
            linkedMapOf(
                "path" to stringValue("APK 内路径，例如 AndroidManifest.xml 或 res/layout/main.xml"),
                "offset" to resultOffset("XML 字符偏移，续读时使用上次返回的 nextOffset"),
                "max_chars" to integerValue(
                    "本次最多返回字符数",
                    DEFAULT_SMALI_CHARS,
                    MIN_SMALI_CHARS,
                    MAX_SMALI_CONTEXT_CHARS
                )
            ),
            required = listOf("path")
        )
        tool(
            tools,
            "${PREFIX}find_methods",
            "按完整 descriptor 精确定位，或按类名/方法名组合筛选方法",
            linkedMapOf(
                "descriptor" to stringValue("完整方法 descriptor，例如 Lpkg/Class;->name(I)Z；填写时精确查询"),
                "class_name_contains" to stringValue("声明类名包含的文本，可用点分类名或 descriptor 片段"),
                "method_name_contains" to stringValue("方法名包含的文本，区分大小写"),
                "descriptor_contains" to stringValue("在类名/方法名筛选结果上继续过滤 descriptor 的文本"),
                "brief" to booleanValue("只返回 descriptor 和 Dex 来源", false),
                "fields" to fieldProjection("需要返回的字段", METHOD_SEARCH_FIELDS),
                "limit" to resultLimit(),
                "offset" to resultOffset()
            ),
            anyOf = listOf(listOf("descriptor"), listOf("class_name_contains"), listOf("method_name_contains"))
        )
        tool(
            tools,
            "${PREFIX}inspect_method",
            "检查一个方法的字符串、字段、opcode，并按需返回一层调用目标或调用者",
            linkedMapOf(
                "descriptor" to stringValue("find_methods 返回的完整方法 descriptor"),
                "include" to stringArray(
                    "可选证据；省略或传空数组时返回全部证据",
                    enumValues = listOf("strings", "using-fields", "invokes", "callers", "annotations", "opcodes"),
                    requireItem = false
                ),
                "brief" to booleanValue("只返回各类证据数量", false)
            ),
            required = listOf("descriptor")
        )
        tool(
            tools,
            "${PREFIX}inspect_class",
            "列出一个类的父类、接口、字段和方法 descriptor",
            linkedMapOf(
                "descriptor" to stringValue("完整类 descriptor，例如 Lpkg/Class;"),
                "class_name" to stringValue("完整点分类名或类 descriptor"),
                "include" to stringArray(
                    "可选内容；省略时返回字段和方法，annotations 需显式请求",
                    enumValues = listOf("fields", "methods", "annotations"),
                    requireItem = false
                ),
                "brief" to booleanValue("只返回类摘要和内容数量", false),
                "limit" to resultLimit("返回的字段和方法各自最大数量")
            ),
            anyOf = listOf(listOf("descriptor"), listOf("class_name"))
        )
        tool(
            tools,
            "${PREFIX}export_method_java",
            "按完整方法 descriptor 导出 Java 语义代码；truncated=true 时用 nextOffset 继续读取",
            linkedMapOf(
                "descriptor" to stringValue("要导出的完整方法 descriptor"),
                "offset" to resultOffset("Java 字符偏移，续读时使用上次返回的 nextOffset"),
                "max_chars" to integerValue(
                    "本次最多返回字符数；大方法应分页读取",
                    DEFAULT_SMALI_CHARS,
                    MIN_SMALI_CHARS,
                    MAX_SMALI_CONTEXT_CHARS
                )
            ),
            required = listOf("descriptor")
        )
        tool(
            tools,
            "${PREFIX}export_class_java",
            "按类 descriptor 或类名导出整类 Java 语义代码；truncated=true 时用 nextOffset 继续读取",
            linkedMapOf(
                "descriptor" to stringValue("要导出的完整类 descriptor"),
                "class_name" to stringValue("要导出的完整点分类名"),
                "offset" to resultOffset("Java 字符偏移，续读时使用上次返回的 nextOffset"),
                "max_chars" to integerValue(
                    "本次最多返回字符数；整类 Java 应分页读取",
                    DEFAULT_SMALI_CHARS,
                    MIN_SMALI_CHARS,
                    MAX_SMALI_CONTEXT_CHARS
                )
            ),
            anyOf = listOf(listOf("descriptor"), listOf("class_name"))
        )
        tool(
            tools,
            "${PREFIX}export_method_smali",
            "按完整方法 descriptor 导出 Smali；truncated=true 时用 nextOffset 继续读取",
            linkedMapOf(
                "descriptor" to stringValue("要导出的完整方法 descriptor"),
                "offset" to resultOffset("Smali 字符偏移，续读时使用上次返回的 nextOffset"),
                "max_chars" to integerValue(
                    "本次最多返回字符数；大方法应分页读取",
                    DEFAULT_SMALI_CHARS,
                    MIN_SMALI_CHARS,
                    MAX_SMALI_CONTEXT_CHARS
                )
            ),
            required = listOf("descriptor")
        )
        tool(
            tools,
            "${PREFIX}export_class_smali",
            "按类 descriptor 或类名导出整类 Smali；truncated=true 时用 nextOffset 继续读取",
            linkedMapOf(
                "descriptor" to stringValue("要导出的完整类 descriptor"),
                "class_name" to stringValue("要导出的完整点分类名"),
                "offset" to resultOffset("Smali 字符偏移，续读时使用上次返回的 nextOffset"),
                "max_chars" to integerValue(
                    "本次最多返回字符数；整类 Smali 应分页读取",
                    DEFAULT_SMALI_CHARS,
                    MIN_SMALI_CHARS,
                    MAX_SMALI_CONTEXT_CHARS
                )
            ),
            anyOf = listOf(listOf("descriptor"), listOf("class_name"))
        )
        tool(
            tools,
            "${PREFIX}read_tool_result",
            "按 handle 和字符偏移继续读取被分页保存的任意工具结果",
            linkedMapOf(
                "handle" to stringValue("长工具结果返回的 handle"),
                "offset" to resultOffset("从 nextOffset 指定的位置继续读取"),
                "max_chars" to integerValue(
                    "本次最多返回字符数",
                    ScriptPluginAgentToolResultStore.DEFAULT_PAGE_CHARS,
                    1_000,
                    ScriptPluginAgentToolResultStore.MAX_PAGE_CHARS
                )
            ),
            required = listOf("handle")
        )
        tool(
            tools,
            "${PREFIX}manifest",
            "读取目标 APK 的结构化 Manifest；include 省略时保持基础摘要",
            linkedMapOf(
                "include" to stringArray(
                    "需要展开的 Manifest 分区",
                    enumValues = MANIFEST_SECTIONS,
                    requireItem = false
                )
            )
        )
        return JSONObject().apply {
            put("source", "Hchat 内置逆向工具")
            put(
                "instructions",
                "默认目标是当前微信 APK。分析其它版本时先用 open_target_session(input) 注册 APK，并在后续每次查询中传返回的 session_id；多版本初筛优先使用 compare_methods_using_strings。find/list 首次查询优先 brief=true，确有需要再选择 fields。Java/Smali/XML 按 nextOffset 分页读取。"
            )
            put("tools", tools)
            put("target", "当前微信 APK，可按 session_id 查询已注册的其它微信 APK")
        }.toString()
    }

    @JvmStatic
    fun call(
        name: String,
        arguments: JSONObject,
        cancellation: ScriptPluginAgentCancellation? = null,
        onProgress: ((String) -> Unit)? = null,
        context: Context? = null,
        allowedExternalApkRoots: List<File> = emptyList()
    ): String {
        val normalizedName = normalize(name)
        if (normalizedName == "read_tool_result") {
            val readContext = context ?: binding.get()?.context
                ?: return error("当前无法读取工具结果")
            return runCatching {
                cancellation?.throwIfCancelled()
                onProgress?.invoke(progressLabel(normalizedName))
                ScriptPluginAgentToolResultStore.readPage(readContext, arguments).also {
                    cancellation?.throwIfCancelled()
                    onProgress?.invoke("整理逆向结果")
                }
            }.getOrElse {
                if (cancellation?.isCancellation(it) == true) throw it
                error(it.message ?: it.javaClass.simpleName)
            }
        }
        val current = binding.get()
            ?: return error("内置逆向工具尚未绑定到微信运行时")
        if (normalizedName !in toolNames) return error("未知的内置逆向工具: $name")
        return runCatching {
            cancellation?.throwIfCancelled()
            synchronized(targetLock) {
                onProgress?.invoke(progressLabel(normalizedName))
                val result = when (normalizedName) {
                    "open_target_session" -> openTarget(current, arguments, allowedExternalApkRoots)
                    "list_target_sessions" -> listTargets(current)
                    "get_target_session" -> getTarget(current, arguments)
                    "close_target_session" -> closeTarget(current, arguments)
                    "compare_methods_using_strings" -> compareMethodsUsingStrings(current, arguments, cancellation)
                    else -> {
                        val target = resolveTarget(current, arguments.optString("session_id", ""))
                        val targetResult = when (normalizedName) {
                            "find_classes_using_strings" -> findClasses(target, arguments, cancellation)
                            "find_methods_using_strings" -> findMethodsUsingStrings(target, arguments, cancellation)
                            "find_methods_using_resource" -> findMethodsUsingResource(target, arguments, cancellation)
                            "find_methods" -> findMethods(target, arguments, cancellation)
                            "inspect_method" -> inspectMethod(target, arguments, cancellation)
                            "inspect_class" -> inspectClass(target, arguments, cancellation)
                            "export_method_java" -> exportMethodJava(target, arguments, cancellation)
                            "export_class_java" -> exportClassJava(target, arguments, cancellation)
                            "export_method_smali" -> exportMethodSmali(target, arguments, cancellation)
                            "export_class_smali" -> exportClassSmali(target, arguments, cancellation)
                            "find_resource_values" -> findResourceValues(target, arguments)
                            "get_resource_value" -> getResourceValue(target, arguments)
                            "list_res" -> listResources(target, arguments)
                            "decode_xml" -> decodeXml(target, arguments)
                            "manifest" -> manifest(target, arguments)
                            else -> error("未知的内置逆向工具: $name")
                        }
                        attachTarget(targetResult, target)
                    }
                }
                cancellation?.throwIfCancelled()
                onProgress?.invoke("整理逆向结果")
                result
            }
        }.getOrElse {
            if (cancellation?.isCancellation(it) == true) throw it
            error("内置逆向工具执行失败: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    @JvmStatic
    fun isKnownToolName(name: String): Boolean = normalize(name) in toolNames

    fun isToolResultReader(name: String): Boolean = normalize(name) == "read_tool_result"

    private fun progressLabel(name: String): String {
        return when (name) {
            "open_target_session" -> "打开 APK 逆向目标"
            "compare_methods_using_strings" -> "横向查询多个微信版本"
            "export_method_java", "export_class_java" -> "反编译 Java"
            "export_method_smali", "export_class_smali" -> "导出 Smali"
            "find_resource_values", "get_resource_value", "list_res", "decode_xml" -> "解析 APK 资源"
            "manifest" -> "解析 Manifest"
            "read_tool_result" -> "读取结果分页"
            "inspect_method", "inspect_class" -> "检查 Dex 结构"
            else -> "查询 Dex 索引"
        }
    }

    private fun normalize(name: String): String {
        return name.trim().removePrefix(PREFIX).removePrefix("local.")
    }

    private fun tool(
        tools: JSONArray,
        name: String,
        description: String,
        properties: Map<String, JSONObject> = emptyMap(),
        required: List<String> = emptyList(),
        anyOf: List<List<String>> = emptyList()
    ) {
        val normalizedName = normalize(name)
        val scopedProperties = LinkedHashMap(properties)
        if (normalizedName !in SESSION_MANAGEMENT_TOOLS && normalizedName != "read_tool_result") {
            scopedProperties.putIfAbsent(
                "session_id",
                stringValue("可选目标 session_id；省略时查询当前运行微信 APK")
            )
        }
        tools.put(JSONObject().apply {
            put("name", name)
            put("description", description)
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    scopedProperties.forEach { (property, schema) -> put(property, schema) }
                })
                if (required.isNotEmpty()) put("required", JSONArray(required))
                if (anyOf.isNotEmpty()) {
                    put("anyOf", JSONArray(anyOf.map { JSONObject().put("required", JSONArray(it)) }))
                }
                put("additionalProperties", false)
            })
        })
    }

    private fun stringValue(description: String): JSONObject = JSONObject().apply {
        put("type", "string")
        put("description", description)
        put("minLength", 1)
    }

    private fun enumValue(description: String, values: List<String>): JSONObject = JSONObject().apply {
        put("type", "string")
        put("description", description)
        put("enum", JSONArray(values))
    }

    private fun booleanValue(description: String, defaultValue: Boolean): JSONObject = JSONObject().apply {
        put("type", "boolean")
        put("description", description)
        put("default", defaultValue)
    }

    private fun stringArray(
        description: String,
        enumValues: List<String> = emptyList(),
        requireItem: Boolean = true
    ): JSONObject = JSONObject().apply {
        put("type", "array")
        put("description", description)
        if (requireItem) put("minItems", 1)
        put("items", JSONObject().apply {
            put("type", "string")
            if (enumValues.isNotEmpty()) put("enum", JSONArray(enumValues))
        })
    }

    private fun fieldProjection(description: String, values: List<String>): JSONObject {
        return stringArray(description, enumValues = values, requireItem = false)
    }

    private fun integerValue(
        description: String,
        defaultValue: Int,
        minimum: Int,
        maximum: Int? = null
    ): JSONObject = JSONObject().apply {
        put("type", "integer")
        put("description", description)
        put("default", defaultValue)
        put("minimum", minimum)
        maximum?.let { put("maximum", it) }
    }

    private fun resultLimit(description: String = "本次最多返回的候选数量"): JSONObject {
        return integerValue(description, 30, 1, MAX_RESULTS)
    }

    private fun resultOffset(description: String = "候选或文本的起始偏移"): JSONObject {
        return integerValue(description, 0, 0)
    }

    private fun openTarget(host: Binding, args: JSONObject, allowedExternalApkRoots: List<File>): String {
        val input = args.optString("input", "").trim()
        var requestedSessionId = args.optString("session_id", "").trim()
        if (input.isNotBlank() && requestedSessionId.isNotBlank()) {
            if (requestedSessionId == input || File(requestedSessionId).isAbsolute) {
                requestedSessionId = ""
            } else {
                return error("open_target_session 不能同时传 input 和 session_id")
            }
        }
        val target = when {
            input.isNotBlank() -> openExternalTarget(host, input, allowedExternalApkRoots)
            requestedSessionId.isNotBlank() -> resolveTarget(host, requestedSessionId)
            else -> host
        }
        val session = targetSessions[target.sessionId] ?: TargetSession.fromBinding(target, System.currentTimeMillis())
        return targetView(host, session).apply {
            put("dexCount", target.dex.getDexNum())
            put("message", if (target.kind == TARGET_KIND_HOST) {
                "当前运行微信已绑定；省略 session_id 时默认查询此目标"
            } else {
                "外部微信 APK 已打开；后续每次查询必须传此 session_id"
            })
        }.toString()
    }

    private fun listTargets(host: Binding): String {
        removeMissingTargets(host)
        val items = targetSessions.values.map { targetView(host, it) }
        return JSONObject().apply {
            put("total", items.size)
            put("default_session_id", host.sessionId)
            put("items", JSONArray(items))
        }.toString()
    }

    private fun getTarget(host: Binding, args: JSONObject): String {
        val sessionId = args.optString("session_id", "").trim()
        val session = targetSessions[sessionId] ?: return error("没有找到目标会话: $sessionId")
        return targetView(host, session).toString()
    }

    private fun closeTarget(host: Binding, args: JSONObject): String {
        val sessionId = args.optString("session_id", "").trim()
        if (sessionId.isBlank()) return error("close_target_session 需要 session_id")
        if (sessionId == host.sessionId) return error("当前运行微信目标不能关闭")
        val removed = targetSessions.remove(sessionId) ?: return error("没有找到目标会话: $sessionId")
        if (activeExternalBinding?.sessionId == sessionId) {
            activeExternalBinding?.close()
            activeExternalBinding = null
        }
        persistTargets(host)
        return JSONObject().apply {
            put("ok", true)
            put("session_id", removed.sessionId)
            put("sourcePath", removed.apkPath)
            put("message", "外部目标已关闭，原 APK 文件未删除")
        }.toString()
    }

    private fun compareMethodsUsingStrings(
        host: Binding,
        args: JSONObject,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val sessionIds = strings(args, "session_ids").distinct()
        if (sessionIds.size < 2) return error("compare_methods_using_strings 至少需要两个 session_id")
        if (sessionIds.size > MAX_COMPARE_TARGETS) {
            return error("compare_methods_using_strings 一次最多比较 $MAX_COMPARE_TARGETS 个目标")
        }
        val all = strings(args, "contains_all_strings")
        val any = strings(args, "contains_any_strings")
        if (all.isEmpty() && any.isEmpty()) return error("至少提供 contains_all_strings 或 contains_any_strings")
        val items = JSONArray()
        var failed = 0
        sessionIds.forEach { sessionId ->
            cancellation?.throwIfCancelled()
            val session = targetSessions[sessionId]
            if (session == null) {
                failed++
                items.put(JSONObject().apply {
                    put("session_id", sessionId)
                    put("ok", false)
                    put("error", "没有找到目标会话")
                })
                return@forEach
            }
            val item = targetView(host, session)
            try {
                val target = resolveTarget(host, sessionId)
                val matches = JSONObject(findMethodsUsingStrings(target, args, cancellation))
                val matchError = matches.optString("error", "")
                val matchOk = matches.optBoolean("ok", matchError.isBlank())
                if (!matchOk) failed++
                item.put("ok", matchOk)
                item.put("total", matches.optInt("total", 0))
                item.put("offset", matches.optInt("offset", 0))
                item.put("limit", matches.optInt("limit", limit(args)))
                item.put("hasMore", matches.optBoolean("hasMore", false))
                item.put("items", matches.optJSONArray("items") ?: JSONArray())
                matchError.takeIf { it.isNotBlank() }?.let { item.put("error", it) }
            } catch (error: Throwable) {
                if (cancellation?.isCancellation(error) == true) throw error
                failed++
                item.put("ok", false)
                item.put("error", error.message ?: error.javaClass.simpleName)
            }
            items.put(item)
        }
        return JSONObject().apply {
            put("ok", failed == 0)
            put("requested", sessionIds.size)
            put("compared", items.length())
            put("failed", failed)
            put("items", items)
        }.toString()
    }

    private fun openExternalTarget(host: Binding, input: String, allowedRoots: List<File>): Binding {
        val file = runCatching { File(input).canonicalFile }
            .getOrElse { throw IllegalArgumentException("APK 路径无效: $input", it) }
        val allowed = allowedRoots.any { root ->
            val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
            file.path == canonicalRoot.path ||
                file.path.startsWith(canonicalRoot.path.trimEnd(File.separatorChar) + File.separator)
        }
        require(allowed) { "只能打开用户消息中明确提供的 APK 路径或其子项" }
        validateApk(file)
        if (sameFile(file, File(host.apkPath))) return host
        val sessionId = targetId(file)
        targetSessions[sessionId]?.let { return resolveTarget(host, sessionId) }
        val supersededSessionIds = targetSessions.values
            .filter { it.kind == TARGET_KIND_EXTERNAL && it.apkPath == file.path }
            .map { it.sessionId }
        val session = TargetSession(
            sessionId = sessionId,
            apkPath = file.path,
            kind = TARGET_KIND_EXTERNAL,
            fileLength = file.length(),
            lastModified = file.lastModified(),
            addedAt = System.currentTimeMillis()
        )
        targetSessions[session.sessionId] = session
        val target = try {
            resolveTarget(host, session.sessionId)
        } catch (error: Throwable) {
            targetSessions.remove(session.sessionId)
            throw error
        }
        supersededSessionIds.forEach { targetSessions.remove(it) }
        pruneTargets(host)
        persistTargets(host)
        return target
    }

    private fun resolveTarget(host: Binding, requestedSessionId: String): Binding {
        val sessionId = requestedSessionId.trim().ifBlank { host.sessionId }
        if (sessionId == host.sessionId) return host
        val session = targetSessions[sessionId]
            ?: throw IllegalArgumentException("没有找到目标会话: $sessionId")
        val file = File(session.apkPath)
        if (!file.isFile || file.length() != session.fileLength || file.lastModified() != session.lastModified) {
            throw IllegalStateException("目标 APK 已移动或发生变化，请使用 open_target_session(input) 重新打开")
        }
        activeExternalBinding?.takeIf { it.sessionId == sessionId && it.dex.isValid }?.let { return it }
        activeExternalBinding?.close()
        activeExternalBinding = null
        val dex = DexKitBridge.create(file.path)
        if (!dex.isValid) {
            dex.close()
            throw IllegalStateException("DexKit 无法打开目标 APK: ${file.name}")
        }
        return Binding.external(host.context, dex, session).also { activeExternalBinding = it }
    }

    private fun attachTarget(rawResult: String, target: Binding): String {
        return runCatching {
            JSONObject(rawResult).apply {
                put("session_id", target.sessionId)
                put("sourcePath", target.apkPath)
                put("targetKind", target.kind)
            }.toString()
        }.getOrDefault(rawResult)
    }

    private fun targetView(host: Binding, session: TargetSession): JSONObject {
        val packageInfo = archivePackageInfo(host.context, session.apkPath)
        return JSONObject().apply {
            put("session_id", session.sessionId)
            put("input", session.apkPath)
            put("sourcePath", session.apkPath)
            put("kind", session.kind)
            put("default", session.sessionId == host.sessionId)
            put("loaded", session.sessionId == host.sessionId || activeExternalBinding?.sessionId == session.sessionId)
            put("fileName", File(session.apkPath).name)
            put("fileSize", session.fileLength)
            put("lastModified", session.lastModified)
            put("packageName", packageInfo?.packageName.orEmpty())
            put("versionName", packageInfo?.versionName.orEmpty())
            put("versionCode", packageInfo?.longVersionCode ?: 0L)
            if (session.kind == TARGET_KIND_HOST) put("classLoader", host.classLoaderDescription)
        }
    }

    private fun validateApk(file: File) {
        require(file.isFile) { "APK 文件不存在或不可读取: ${file.path}" }
        require(file.extension.equals("apk", ignoreCase = true)) { "目标必须是 APK 文件: ${file.name}" }
        require(file.length() > 0L) { "APK 文件为空: ${file.name}" }
        ZipFile(file).use { zip ->
            require(zip.getEntry("AndroidManifest.xml") != null) { "APK 缺少 AndroidManifest.xml" }
            require(zip.getEntry("classes.dex") != null) { "APK 缺少 classes.dex" }
        }
    }

    private fun sameFile(first: File, second: File): Boolean {
        return runCatching { first.canonicalPath == second.canonicalPath }.getOrDefault(first.path == second.path)
    }

    private fun findClasses(
        binding: Binding,
        args: JSONObject,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val all = strings(args, "contains_all_strings")
        val any = strings(args, "contains_any_strings")
        if (all.isEmpty() && any.isEmpty()) return error("至少提供 contains_all_strings 或 contains_any_strings")
        val results = linkedMapOf<String, ClassData>()
        if (all.isNotEmpty()) {
            val matcher = ClassMatcher().usingStrings(all)
            binding.dex.findClass(FindClass().matcher(matcher)).forEach { results[it.name] = it }
        }
        any.forEach { value ->
            val matcher = ClassMatcher().usingStrings(value)
            binding.dex.findClass(FindClass().matcher(matcher)).forEach { results[it.name] = it }
        }
        return classSearchResult(binding, results.values.toList(), args, cancellation)
    }

    private fun findMethodsUsingStrings(
        binding: Binding,
        args: JSONObject,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val all = strings(args, "contains_all_strings")
        val any = strings(args, "contains_any_strings")
        if (all.isEmpty() && any.isEmpty()) return error("至少提供 contains_all_strings 或 contains_any_strings")
        val results = linkedMapOf<String, MethodData>()
        if (all.isNotEmpty()) {
            val matcher = MethodMatcher().usingStrings(all)
            binding.dex.findMethod(FindMethod().matcher(matcher)).forEach { results[it.descriptor] = it }
        }
        any.forEach { value ->
            val matcher = MethodMatcher().usingStrings(value)
            binding.dex.findMethod(FindMethod().matcher(matcher)).forEach { results[it.descriptor] = it }
        }
        return methodSearchResult(binding, results.values.toList(), args, cancellation)
    }

    private fun findMethodsUsingResource(
        binding: Binding,
        args: JSONObject,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val resourceId = parseResourceId(args.optString("resource_id", ""))
            ?: return error("find_methods_using_resource 需要有效的 resource_id")
        val candidates = binding.dex.findMethod(
            FindMethod().matcher(MethodMatcher().usingNumbers(resourceId))
        )
        return methodSearchResult(binding, candidates.toList(), args, cancellation)
    }

    private fun findMethods(
        binding: Binding,
        args: JSONObject,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val descriptor = args.optString("descriptor", "").trim()
        if (descriptor.isNotBlank() && descriptor.contains("->") && !args.has("descriptor_contains")) {
            val direct = runCatching { binding.dex.getMethodData(descriptor) }.getOrNull()
            return methodSearchResult(binding, listOfNotNull(direct), args, cancellation)
        }
        val className = args.optString("class_name_contains", "").trim()
        val methodName = args.optString("method_name_contains", "").trim()
        val descriptorPart = args.optString("descriptor_contains", descriptor).trim()
        if (className.isBlank() && methodName.isBlank()) {
            if (descriptorPart.contains("->")) {
                val direct = runCatching { binding.dex.getMethodData(descriptorPart) }.getOrNull()
                return methodSearchResult(binding, listOfNotNull(direct), args, cancellation)
            }
            return error("至少提供 class_name_contains、method_name_contains 或完整 descriptor")
        }
        val matcher = MethodMatcher()
        if (className.isNotBlank()) matcher.declaredClass(className, StringMatchType.Contains)
        if (methodName.isNotBlank()) matcher.name(methodName, StringMatchType.Contains, false)
        val candidates = binding.dex.findMethod(FindMethod().matcher(matcher))
            .filter { descriptorPart.isBlank() || it.descriptor.contains(descriptorPart) }
        return methodSearchResult(binding, candidates, args, cancellation)
    }

    private fun inspectMethod(
        binding: Binding,
        args: JSONObject,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val descriptor = args.optString("descriptor", "").trim()
        if (descriptor.isBlank()) return error("inspect_method 需要完整 descriptor")
        val method = runCatching { binding.dex.getMethodData(descriptor) }.getOrNull()
            ?: return error("没有找到方法: $descriptor")
        val include = strings(args, "include").toSet()
        require(include.all { it in METHOD_INSPECT_SECTIONS }) { "inspect_method include 包含不支持的值" }
        val sections = include.ifEmpty { METHOD_INSPECT_SECTIONS.toSet() }
        val brief = args.optBoolean("brief", false)
        val classDescriptor = method.descriptor.substringBefore("->")
        val sourceEntry = binding.smaliExporter.locateClassEntries(listOf(classDescriptor), cancellation)[classDescriptor]
        return methodView(method).apply {
            put("sourcePath", binding.apkPath)
            put("sourceEntry", sourceEntry ?: JSONObject.NULL)
            put("include", JSONArray(include))
            put("brief", brief)
            if ("strings" in sections) {
                if (brief) put("usingStringsCount", method.usingStrings.size)
                else put("usingStrings", JSONArray(method.usingStrings.take(MAX_RESULTS)))
            }
            if ("using-fields" in sections) {
                if (brief) put("usingFieldsCount", method.usingFields.size)
                else put("usingFields", JSONArray(method.usingFields.take(MAX_RESULTS).map { fieldView(it.field) }))
            }
            if ("invokes" in sections) {
                if (brief) put("invokesCount", method.invokes.size)
                else put("invokes", JSONArray(method.invokes.take(MAX_RESULTS).map(::methodView)))
            }
            if ("callers" in sections) {
                if (brief) put("callersCount", method.callers.size)
                else put("callers", JSONArray(method.callers.take(MAX_RESULTS).map(::methodView)))
            }
            if ("annotations" in sections) {
                if (brief) put("annotationsCount", method.annotations.size)
                else put("annotations", JSONArray(method.annotations.map(::annotationView)))
            }
            if ("opcodes" in sections) {
                if (brief) put("opCodesCount", method.opNames.size)
                else put("opNames", JSONArray(method.opNames.take(400)))
            }
        }.toString()
    }

    private fun inspectClass(
        binding: Binding,
        args: JSONObject,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val value = args.optString("descriptor", args.optString("class_name", "")).trim()
        if (value.isBlank()) return error("inspect_class 需要 descriptor 或 class_name")
        val clazz = runCatching { binding.dex.getClassData(value) }.getOrNull()
            ?: return error("没有找到类: $value")
        val itemLimit = limit(args)
        val include = strings(args, "include").toSet()
        require(include.all { it in CLASS_INSPECT_SECTIONS }) { "inspect_class include 包含不支持的值" }
        val sections = include.ifEmpty { setOf("fields", "methods") }
        val brief = args.optBoolean("brief", false)
        val sourceEntry = binding.smaliExporter.locateClassEntries(listOf(clazz.descriptor), cancellation)[clazz.descriptor]
        return classView(clazz).apply {
            put("sourcePath", binding.apkPath)
            put("sourceEntry", sourceEntry ?: JSONObject.NULL)
            put("include", JSONArray(include))
            put("brief", brief)
            put("superClass", runCatching { clazz.superClass?.name.orEmpty() }.getOrDefault(""))
            put("interfaces", JSONArray(runCatching { clazz.interfaces.map { it.name } }.getOrDefault(emptyList())))
            if ("fields" in sections) {
                if (brief) put("fieldsCount", clazz.fieldCount)
                else put("fields", JSONArray(clazz.fields.take(itemLimit).map(::fieldView)))
            }
            if ("methods" in sections) {
                if (brief) put("methodsCount", clazz.methodCount)
                else put("methods", JSONArray(clazz.methods.take(itemLimit).map(::methodView)))
            }
            if ("annotations" in sections) {
                if (brief) put("annotationsCount", clazz.annotations.size)
                else put("annotations", JSONArray(clazz.annotations.map(::annotationView)))
            }
        }.toString()
    }

    private fun exportMethodSmali(
        binding: Binding,
        args: JSONObject,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val descriptor = args.optString("descriptor", "").trim()
        if (descriptor.isBlank()) return error("export_method_smali 需要完整 descriptor")
        return binding.smaliExporter.exportMethod(descriptor, smaliArguments(args), cancellation)
    }

    private fun exportMethodJava(
        binding: Binding,
        args: JSONObject,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val descriptor = args.optString("descriptor", "").trim()
        if (descriptor.isBlank()) return error("export_method_java 需要完整 descriptor")
        return binding.javaExporter.exportMethod(descriptor, args, cancellation)
    }

    private fun exportClassJava(
        binding: Binding,
        args: JSONObject,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val descriptor = args.optString("descriptor", args.optString("class_name", "")).trim()
        if (descriptor.isBlank()) return error("export_class_java 需要 descriptor 或 class_name")
        return binding.javaExporter.exportClass(descriptor, args, cancellation)
    }

    private fun exportClassSmali(
        binding: Binding,
        args: JSONObject,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val descriptor = args.optString("descriptor", args.optString("class_name", "")).trim()
        if (descriptor.isBlank()) return error("export_class_smali 需要 descriptor 或 class_name")
        return binding.smaliExporter.exportClass(descriptor, smaliArguments(args), cancellation)
    }

    private fun smaliArguments(args: JSONObject): JSONObject {
        return JSONObject(args.toString()).apply {
            put("offset", optInt("offset", 0).coerceAtLeast(0))
            put(
                "max_chars",
                optInt("max_chars", DEFAULT_SMALI_CHARS)
                    .coerceIn(MIN_SMALI_CHARS, MAX_SMALI_CONTEXT_CHARS)
            )
        }
    }

    private fun findResourceValues(binding: Binding, args: JSONObject): String {
        val type = args.optString("type", "").trim()
        val query = args.optString("value", "").trim()
        if (type.isBlank() || query.isBlank()) return error("find_resource_values 需要 type 和 value")
        val contains = if (args.has("contains")) args.optBoolean("contains", true) else true
        val ignoreCase = args.optBoolean("ignore_case", false)
        val expected = if (ignoreCase) query.lowercase() else query
        val hits = resourceEntries(binding)
            .filter { it.getType().equals(type, ignoreCase = true) }
            .flatMap { resource ->
                resourceValues(resource).asSequence().filter { value ->
                    val actual = if (ignoreCase) value.value.lowercase() else value.value
                    if (contains) actual.contains(expected) else actual == expected
                }.map { value -> ResourceHit(resource, value) }
            }
            .toList()
        val fields = projectionFields(
            args,
            RESOURCE_VALUE_SEARCH_FIELDS,
            RESOURCE_VALUE_BRIEF_FIELDS,
            RESOURCE_VALUE_DEFAULT_FIELDS
        )
        val items = hits.drop(offset(args)).take(limit(args)).map { hit ->
            resourceView(binding, hit.resource, hit.value, fields)
        }
        return result(items, hits.size, args)
    }

    private fun getResourceValue(binding: Binding, args: JSONObject): String {
        val resourceId = parseResourceId(args.optString("resource_id", ""))
        val type = args.optString("type", "").trim()
        val name = args.optString("name", "").trim()
        val resource = when {
            resourceId != null -> runCatching { binding.resourceTable.getResource(resourceId) }.getOrNull()
            type.isNotBlank() && name.isNotBlank() -> resourceEntries(binding).firstOrNull {
                it.getType().equals(type, ignoreCase = true) && it.getName() == name
            }
            else -> null
        } ?: return error("没有找到资源；请提供 resource_id，或同时提供 type 和 name")
        return resourceView(binding, resource).apply {
            put("values", JSONArray(resourceValues(resource).map { value ->
                JSONObject().apply {
                    put("value", value.value)
                    put("qualifiers", value.qualifiers)
                    put("default", value.defaultValue)
                }
            }))
        }.toString()
    }

    private fun listResources(binding: Binding, args: JSONObject): String {
        val type = args.optString("type", "").trim()
        val resources = resourceEntries(binding)
            .filter { type.isBlank() || it.getType().equals(type, ignoreCase = true) }
            .toList()
        val fields = projectionFields(
            args,
            RESOURCE_SEARCH_FIELDS,
            RESOURCE_BRIEF_FIELDS,
            RESOURCE_DEFAULT_FIELDS
        )
        val items = resources.drop(offset(args)).take(limit(args)).map { resource ->
            resourceView(binding, resource, fields = fields)
        }
        return result(items, resources.size, args)
    }

    private fun decodeXml(binding: Binding, args: JSONObject): String {
        val path = args.optString("path", "").trim().removePrefix("/")
        if (path.isBlank()) return error("decode_xml 需要 APK 内路径")
        return runCatching {
            val xml = binding.resourceModule.loadResXmlDocument(path).toString()
            val offset = args.optInt("offset", 0).coerceIn(0, xml.length)
            val maxChars = args.optInt("max_chars", DEFAULT_SMALI_CHARS)
                .coerceIn(MIN_SMALI_CHARS, MAX_SMALI_CONTEXT_CHARS)
            val end = (offset + maxChars).coerceAtMost(xml.length)
            JSONObject().apply {
                put("path", path)
                put("sourcePath", binding.apkPath)
                put("sourceEntry", path)
                put("offset", offset)
                put("returnedLength", end - offset)
                put("totalLength", xml.length)
                put("truncated", end < xml.length)
                if (end < xml.length) put("nextOffset", end)
                put("xml", xml.substring(offset, end))
            }.toString()
        }.getOrElse { error("无法解码 XML $path: ${it.message ?: it.javaClass.simpleName}") }
    }

    private fun resourceEntries(binding: Binding): Sequence<ResourceEntry> {
        return binding.resourceTable.getResources().asSequence()
    }

    private data class ResourceValue(
        val value: String,
        val qualifiers: String,
        val defaultValue: Boolean
    )

    private data class ResourceHit(
        val resource: ResourceEntry,
        val value: ResourceValue
    )

    private fun resourceValues(resource: ResourceEntry): List<ResourceValue> {
        return resource.iterator().asSequence().mapNotNull { entry ->
            runCatching {
                ResourceValue(
                    value = entry.getValueAsString(),
                    qualifiers = entry.getResConfig()?.getQualifiers().orEmpty(),
                    defaultValue = entry.isDefault
                )
            }.getOrNull()
        }.filter { it.value.isNotBlank() }.distinct().toList()
    }

    private fun resourceView(
        binding: Binding,
        resource: ResourceEntry,
        value: ResourceValue? = null,
        fields: Set<String> = RESOURCE_VALUE_DEFAULT_FIELDS.toSet()
    ): JSONObject {
        val needsFileSource = fields.any { it == "filePath" || it == "sourceEntry" || it == "resolution" }
        val filePath = if (needsFileSource) binding.resourceFilePath(resource.getResourceId()) else null
        return JSONObject().apply {
            putProjected(fields, "resourceId", resource.getHexId())
            putProjected(fields, "type", resource.getType())
            putProjected(fields, "name", resource.getName())
            value?.let {
                putProjected(fields, "value", it.value)
                putProjected(fields, "qualifiers", it.qualifiers)
                putProjected(fields, "default", it.defaultValue)
            }
            putProjected(fields, "filePath", filePath ?: JSONObject.NULL)
            putProjected(fields, "sourcePath", binding.apkPath)
            putProjected(fields, "sourceEntry", filePath ?: JSONObject.NULL)
            putProjected(fields, "resolution", when {
                filePath != null -> "table-backed"
                resource.isDefined -> "table-value"
                resource.isDeclared -> "table-hole"
                else -> "unresolved"
            })
        }
    }

    private fun parseResourceId(value: String): Int? {
        val text = value.trim()
        if (text.isBlank()) return null
        return runCatching {
            if (text.startsWith("0x", ignoreCase = true)) {
                text.substring(2).toLong(16).toInt()
            } else {
                text.toLong().toInt()
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun manifest(binding: Binding, args: JSONObject): String {
        val include = strings(args, "include").toSet()
        require(include.all { it in MANIFEST_SECTIONS }) { "manifest include 包含不支持的值" }
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_PERMISSIONS or
            PackageManager.GET_CONFIGURATIONS or
            PackageManager.GET_META_DATA
        val info = archivePackageInfo(binding.context, binding.apkPath, flags)
            ?: return error("无法解析 APK Manifest: ${binding.apkPath}")
        return JSONObject().apply {
            put("packageName", info.packageName)
            put("versionName", info.versionName ?: "")
            put("versionCode", info.longVersionCode)
            put("sourcePath", binding.apkPath)
            put("sourceEntry", "AndroidManifest.xml")
            put("applicationClass", info.applicationInfo?.className ?: "")
            put("minSdk", info.applicationInfo?.minSdkVersion ?: 0)
            put("targetSdk", info.applicationInfo?.targetSdkVersion ?: 0)
            put("permissions", JSONArray(info.requestedPermissions?.toList().orEmpty()))
            put("activities", JSONArray(info.activities?.map { it.name }.orEmpty()))
            put("services", JSONArray(info.services?.map { it.name }.orEmpty()))
            put("receivers", JSONArray(info.receivers?.map { it.name }.orEmpty()))
            put("providers", JSONArray(info.providers?.map { it.name }.orEmpty()))
            if ("uses-sdk" in include) {
                put("usesSdk", JSONObject().apply {
                    put("minSdkVersion", info.applicationInfo?.minSdkVersion ?: 0)
                    put("targetSdkVersion", info.applicationInfo?.targetSdkVersion ?: 0)
                })
            }
            if ("application" in include) {
                put("application", info.applicationInfo?.let(::applicationView) ?: JSONObject.NULL)
            }
            if ("uses-permissions" in include) {
                put("usesPermissions", JSONArray(info.requestedPermissions?.toList().orEmpty()))
            }
            if ("defined-permissions" in include) {
                put("definedPermissions", JSONArray(info.permissions?.map { permission ->
                    JSONObject().apply {
                        put("name", permission.name)
                        put("protectionLevel", permission.protectionLevel)
                    }
                }.orEmpty()))
            }
            if ("uses-features" in include) {
                put("usesFeatures", JSONArray(info.reqFeatures?.map { feature ->
                    JSONObject().apply {
                        put("name", feature.name ?: JSONObject.NULL)
                        put("required", feature.flags and android.content.pm.FeatureInfo.FLAG_REQUIRED != 0)
                        put("glEsVersion", "0x${feature.reqGlEsVersion.toUInt().toString(16).padStart(8, '0')}")
                    }
                }.orEmpty()))
            }
            if ("activities" in include) {
                put("activities", JSONArray(info.activities?.filter { it.targetActivity.isNullOrBlank() }
                    ?.map(::activityView).orEmpty()))
            }
            if ("activity-aliases" in include) {
                put("activityAliases", JSONArray(info.activities?.filter { !it.targetActivity.isNullOrBlank() }
                    ?.map(::activityView).orEmpty()))
            }
            if ("services" in include) {
                put("services", JSONArray(info.services?.map(::serviceView).orEmpty()))
            }
            if ("receivers" in include) {
                put("receivers", JSONArray(info.receivers?.map(::activityView).orEmpty()))
            }
            if ("providers" in include) {
                put("providers", JSONArray(info.providers?.map(::providerView).orEmpty()))
            }
        }.toString()
    }

    private fun applicationView(info: android.content.pm.ApplicationInfo): JSONObject {
        return JSONObject().apply {
            put("name", info.className ?: "")
            put("process", info.processName ?: "")
            put("enabled", info.enabled)
            put("labelRes", info.labelRes)
            put("icon", info.icon)
            put("metaData", bundleView(info.metaData))
        }
    }

    private fun activityView(info: android.content.pm.ActivityInfo): JSONObject {
        return componentView(info).apply {
            put("permission", info.permission ?: JSONObject.NULL)
            if (!info.targetActivity.isNullOrBlank()) put("targetActivity", info.targetActivity)
            put("metaData", bundleView(info.metaData))
        }
    }

    private fun serviceView(info: android.content.pm.ServiceInfo): JSONObject {
        return componentView(info).apply {
            put("permission", info.permission ?: JSONObject.NULL)
            put("metaData", bundleView(info.metaData))
        }
    }

    private fun providerView(info: android.content.pm.ProviderInfo): JSONObject {
        return componentView(info).apply {
            put("authorities", info.authority ?: JSONObject.NULL)
            put("readPermission", info.readPermission ?: JSONObject.NULL)
            put("writePermission", info.writePermission ?: JSONObject.NULL)
            put("metaData", bundleView(info.metaData))
        }
    }

    private fun componentView(info: android.content.pm.ComponentInfo): JSONObject {
        return JSONObject().apply {
            put("name", info.name ?: "")
            put("process", info.processName ?: "")
            put("exported", info.exported)
            put("enabled", info.enabled)
        }
    }

    private fun bundleView(bundle: android.os.Bundle?): JSONObject {
        return JSONObject().apply {
            bundle?.keySet()?.sorted()?.forEach { key ->
                put(key, bundle.get(key)?.toString() ?: JSONObject.NULL)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun archivePackageInfo(
        context: Context,
        apkPath: String,
        flags: Int = PackageManager.GET_META_DATA
    ): android.content.pm.PackageInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageArchiveInfo(
                    apkPath,
                    PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                context.packageManager.getPackageArchiveInfo(apkPath, flags)
            }
        }.getOrNull()
    }

    private fun loadPersistedTargets(host: Binding) {
        val raw = HchatStorage.preferences(host.context, TARGET_PREFS_NAME)
            .getString(TARGET_PREFS_KEY, "")
            .orEmpty()
        if (raw.isBlank()) return
        runCatching {
            val array = JSONArray(raw)
            var changed = false
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                if (item == null) {
                    changed = true
                    continue
                }
                val file = runCatching { File(item.optString("path", "")).canonicalFile }.getOrNull()
                if (file == null) {
                    changed = true
                    continue
                }
                if (!file.isFile || !file.extension.equals("apk", ignoreCase = true) || sameFile(file, File(host.apkPath))) {
                    changed = true
                    continue
                }
                val session = TargetSession(
                    sessionId = targetId(file),
                    apkPath = file.path,
                    kind = TARGET_KIND_EXTERNAL,
                    fileLength = file.length(),
                    lastModified = file.lastModified(),
                    addedAt = item.optLong("addedAt", file.lastModified()).coerceAtLeast(0L)
                )
                targetSessions[session.sessionId] = session
            }
            val beforePrune = targetSessions.size
            pruneTargets(host)
            if (changed || targetSessions.size != beforePrune) persistTargets(host)
        }.onFailure {
            HchatStorage.preferences(host.context, TARGET_PREFS_NAME)
                .edit()
                .remove(TARGET_PREFS_KEY)
                .apply()
        }
    }

    private fun persistTargets(host: Binding) {
        val array = JSONArray()
        targetSessions.values
            .filter { it.kind == TARGET_KIND_EXTERNAL }
            .sortedBy { it.addedAt }
            .forEach { session ->
                array.put(JSONObject().apply {
                    put("path", session.apkPath)
                    put("addedAt", session.addedAt)
                })
            }
        HchatStorage.preferences(host.context, TARGET_PREFS_NAME)
            .edit()
            .putString(TARGET_PREFS_KEY, array.toString())
            .apply()
    }

    private fun removeMissingTargets(host: Binding) {
        val missing = targetSessions.values
            .filter { it.kind == TARGET_KIND_EXTERNAL && !File(it.apkPath).isFile }
            .map { it.sessionId }
        if (missing.isEmpty()) return
        missing.forEach { sessionId ->
            targetSessions.remove(sessionId)
            if (activeExternalBinding?.sessionId == sessionId) {
                activeExternalBinding?.close()
                activeExternalBinding = null
            }
        }
        persistTargets(host)
    }

    private fun pruneTargets(host: Binding) {
        val external = targetSessions.values
            .filter { it.kind == TARGET_KIND_EXTERNAL }
            .sortedBy { it.addedAt }
        external.dropLast(MAX_REGISTERED_TARGETS).forEach { session ->
            targetSessions.remove(session.sessionId)
            if (activeExternalBinding?.sessionId == session.sessionId) {
                activeExternalBinding?.close()
                activeExternalBinding = null
            }
        }
        if (external.size > MAX_REGISTERED_TARGETS) persistTargets(host)
    }

    private fun targetId(file: File): String {
        val identity = "${file.path}\u0000${file.length()}\u0000${file.lastModified()}"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            .take(24)
        return "hchat-local-$hash"
    }

    private fun classSearchResult(
        binding: Binding,
        candidates: List<ClassData>,
        args: JSONObject,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val fields = projectionFields(
            args,
            CLASS_SEARCH_FIELDS,
            CLASS_BRIEF_FIELDS,
            CLASS_SEARCH_DEFAULT_FIELDS
        )
        val page = candidates.drop(offset(args)).take(limit(args))
        val sourceEntries = if ("sourceEntry" in fields) {
            binding.smaliExporter.locateClassEntries(page.map { it.descriptor }, cancellation)
        } else {
            emptyMap()
        }
        val items = page.map { value ->
            JSONObject().apply {
                putProjected(fields, "className", value.name)
                putProjected(fields, "descriptor", value.descriptor)
                putProjected(fields, "sourceFile", value.sourceFile ?: "")
                putProjected(fields, "methodCount", value.methodCount)
                putProjected(fields, "fieldCount", value.fieldCount)
                putProjected(fields, "modifiers", value.modifiers)
                putProjected(fields, "sourcePath", binding.apkPath)
                putProjected(fields, "sourceEntry", sourceEntries[value.descriptor] ?: JSONObject.NULL)
            }
        }
        return result(items, candidates.size, args)
    }

    private fun methodSearchResult(
        binding: Binding,
        candidates: List<MethodData>,
        args: JSONObject,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val fields = projectionFields(
            args,
            METHOD_SEARCH_FIELDS,
            METHOD_BRIEF_FIELDS,
            METHOD_SEARCH_DEFAULT_FIELDS
        )
        val page = candidates.drop(offset(args)).take(limit(args))
        val sourceEntries = if ("sourceEntry" in fields) {
            binding.smaliExporter.locateClassEntries(
                page.map { it.descriptor.substringBefore("->") },
                cancellation
            )
        } else {
            emptyMap()
        }
        val items = page.map { value ->
            JSONObject().apply {
                putProjected(fields, "className", value.className)
                putProjected(fields, "methodName", value.methodName)
                putProjected(fields, "descriptor", value.descriptor)
                putProjected(fields, "methodSign", value.methodSign)
                putProjected(fields, "returnType", value.returnTypeName)
                putProjected(fields, "paramTypes", JSONArray(value.paramTypeNames))
                putProjected(fields, "paramCount", value.paramCount)
                putProjected(fields, "modifiers", value.modifiers)
                putProjected(fields, "sourcePath", binding.apkPath)
                putProjected(
                    fields,
                    "sourceEntry",
                    sourceEntries[value.descriptor.substringBefore("->")] ?: JSONObject.NULL
                )
            }
        }
        return result(items, candidates.size, args)
    }

    private fun projectionFields(
        args: JSONObject,
        allowed: List<String>,
        briefFields: List<String>,
        defaultFields: List<String> = allowed
    ): Set<String> {
        val requested = strings(args, "fields").distinct()
        val unknown = requested.filter { it !in allowed }
        require(unknown.isEmpty()) { "fields 包含不支持的值: ${unknown.joinToString()}" }
        return when {
            requested.isNotEmpty() -> requested.toCollection(LinkedHashSet())
            args.optBoolean("brief", false) -> briefFields.toCollection(LinkedHashSet())
            else -> defaultFields.toCollection(LinkedHashSet())
        }
    }

    private fun JSONObject.putProjected(fields: Set<String>, key: String, value: Any?): JSONObject {
        if (key in fields) put(key, value ?: JSONObject.NULL)
        return this
    }

    private fun classView(value: ClassData): JSONObject {
        return JSONObject().apply {
            put("className", value.name)
            put("descriptor", value.descriptor)
            put("sourceFile", value.sourceFile ?: "")
            put("methodCount", value.methodCount)
            put("fieldCount", value.fieldCount)
        }
    }

    private fun methodView(value: MethodData): JSONObject {
        return JSONObject().apply {
            put("className", value.className)
            put("methodName", value.methodName)
            put("descriptor", value.descriptor)
            put("methodSign", value.methodSign)
            put("returnType", value.returnTypeName)
            put("paramTypes", JSONArray(value.paramTypeNames))
            put("paramCount", value.paramCount)
            put("modifiers", value.modifiers)
        }
    }

    private fun fieldView(value: FieldData?): JSONObject {
        return JSONObject().apply {
            if (value == null) return@apply
            put("className", value.className)
            put("fieldName", value.fieldName)
            put("descriptor", value.descriptor)
            put("type", value.typeName)
        }
    }

    private fun annotationView(value: AnnotationData): JSONObject {
        return JSONObject().apply {
            put("typeName", value.typeName)
            put("typeDescriptor", value.typeDescriptor)
            put("visibility", value.visibility?.name ?: "UNKNOWN")
            put("elements", JSONArray(value.elements.map { element ->
                JSONObject().apply {
                    put("name", element.name)
                    put("type", element.value.type.name)
                    put("value", element.value.value?.toString() ?: JSONObject.NULL)
                }
            }))
        }
    }

    private fun strings(args: JSONObject, key: String): List<String> {
        val value = args.opt(key) ?: return emptyList()
        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).trim().takeIf { text -> text.isNotBlank() } }
            else -> value.toString().split(',', '\n').map { it.trim() }.filter { it.isNotBlank() }
        }
    }

    private fun limit(args: JSONObject): Int = args.optInt("limit", 30).coerceIn(1, MAX_RESULTS)

    private fun offset(args: JSONObject): Int = args.optInt("offset", 0).coerceAtLeast(0)

    private fun result(items: List<JSONObject>, total: Int, args: JSONObject): String {
        val offset = offset(args)
        val limit = limit(args)
        return JSONObject().apply {
            put("total", total)
            put("offset", offset)
            put("limit", limit)
            put("hasMore", offset + items.size < total)
            put("items", JSONArray(items))
        }.toString()
    }

    private fun error(message: String): String {
        return JSONObject().apply {
            put("error", message)
            put("ok", false)
        }.toString()
    }

    private data class TargetSession(
        val sessionId: String,
        val apkPath: String,
        val kind: String,
        val fileLength: Long,
        val lastModified: Long,
        val addedAt: Long
    ) {
        companion object {
            fun fromBinding(binding: Binding, addedAt: Long): TargetSession {
                val file = File(binding.apkPath)
                return TargetSession(
                    sessionId = binding.sessionId,
                    apkPath = binding.apkPath,
                    kind = binding.kind,
                    fileLength = file.length(),
                    lastModified = file.lastModified(),
                    addedAt = addedAt
                )
            }
        }
    }

    private class Binding private constructor(
        val context: Context,
        val dex: DexKitBridge,
        val apkPath: String,
        val sessionId: String,
        val kind: String,
        val classLoaderDescription: String,
        private val ownsDex: Boolean
    ) : AutoCloseable {
        val smaliExporter = ScriptPluginAgentSmaliExporter(
            apkPath = apkPath,
            cacheRoot = File(context.cacheDir, "Hchat_agent_reverse")
        )
        val javaExporter = ScriptPluginAgentJavaExporter(apkPath, smaliExporter)
        private val resourceModuleLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            ApkModule.loadApkFile(File(apkPath)).apply {
                setLoadDefaultFramework(false)
            }
        }
        val resourceModule: ApkModule
            get() = resourceModuleLazy.value
        val resourceTable: TableBlock by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            resourceModule.getTableBlock()
        }
        private val resourceFilePaths by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            HashMap<Int, String>().apply {
                resourceModule.listResFiles().forEach { resFile ->
                    resFile.forEach { entry -> putIfAbsent(entry.resourceId, resFile.filePath) }
                }
            }
        }

        fun resourceFilePath(resourceId: Int): String? = resourceFilePaths[resourceId]

        override fun close() {
            if (resourceModuleLazy.isInitialized()) {
                runCatching { resourceModuleLazy.value.close() }
            }
            if (ownsDex) runCatching { dex.close() }
        }

        companion object {
            fun host(holder: DexBridgeHolder, context: Context): Binding {
                val file = runCatching { File(holder.apkPath).canonicalFile }.getOrDefault(File(holder.apkPath))
                return Binding(
                    context = context,
                    dex = holder.dexKitBridge,
                    apkPath = file.path,
                    sessionId = targetId(file),
                    kind = TARGET_KIND_HOST,
                    classLoaderDescription = holder.hostClassLoader.toString(),
                    ownsDex = false
                )
            }

            fun external(context: Context, dex: DexKitBridge, session: TargetSession): Binding {
                return Binding(
                    context = context,
                    dex = dex,
                    apkPath = session.apkPath,
                    sessionId = session.sessionId,
                    kind = session.kind,
                    classLoaderDescription = "",
                    ownsDex = true
                )
            }
        }
    }

    private const val TARGET_KIND_HOST = "current_wechat_apk"
    private const val TARGET_KIND_EXTERNAL = "external_wechat_apk"
    private const val TARGET_PREFS_NAME = "Hchat_agent_reverse_targets"
    private const val TARGET_PREFS_KEY = "targets_v1"
    private const val MAX_REGISTERED_TARGETS = 16
    private const val MAX_COMPARE_TARGETS = 12
    private val CLASS_SEARCH_FIELDS = listOf(
        "className", "descriptor", "sourceFile", "methodCount", "fieldCount", "modifiers",
        "sourcePath", "sourceEntry"
    )
    private val CLASS_SEARCH_DEFAULT_FIELDS = listOf(
        "className", "descriptor", "sourceFile", "methodCount", "fieldCount"
    )
    private val CLASS_BRIEF_FIELDS = listOf("className", "descriptor")
    private val METHOD_SEARCH_FIELDS = listOf(
        "className", "methodName", "descriptor", "methodSign", "returnType", "paramTypes",
        "paramCount", "modifiers", "sourcePath", "sourceEntry"
    )
    private val METHOD_SEARCH_DEFAULT_FIELDS = listOf(
        "className", "methodName", "descriptor", "methodSign", "returnType", "paramTypes",
        "paramCount", "modifiers"
    )
    private val METHOD_BRIEF_FIELDS = listOf("descriptor")
    private val RESOURCE_SEARCH_FIELDS = listOf(
        "resourceId", "type", "name", "filePath", "sourcePath", "sourceEntry", "resolution"
    )
    private val RESOURCE_VALUE_SEARCH_FIELDS = listOf(
        "resourceId", "type", "name", "value", "qualifiers", "default", "filePath",
        "sourcePath", "sourceEntry", "resolution"
    )
    private val RESOURCE_DEFAULT_FIELDS = listOf("resourceId", "type", "name", "sourcePath")
    private val RESOURCE_VALUE_DEFAULT_FIELDS = listOf(
        "resourceId", "type", "name", "value", "qualifiers", "default", "sourcePath"
    )
    private val RESOURCE_BRIEF_FIELDS = listOf("resourceId", "type", "name")
    private val RESOURCE_VALUE_BRIEF_FIELDS = RESOURCE_BRIEF_FIELDS
    private val METHOD_INSPECT_SECTIONS = listOf(
        "strings", "using-fields", "invokes", "callers", "annotations", "opcodes"
    )
    private val CLASS_INSPECT_SECTIONS = listOf("fields", "methods", "annotations")
    private val MANIFEST_SECTIONS = listOf(
        "uses-sdk", "application", "uses-permissions", "defined-permissions", "uses-features",
        "activities", "activity-aliases", "services", "receivers", "providers"
    )
    private val SESSION_MANAGEMENT_TOOLS = setOf(
        "open_target_session",
        "list_target_sessions",
        "get_target_session",
        "close_target_session",
        "compare_methods_using_strings"
    )
}
