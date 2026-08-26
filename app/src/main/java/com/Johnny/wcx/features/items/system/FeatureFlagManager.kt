package com.Johnny.wcx.features.items.system

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.IconButton
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.copyToClipboard
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.reflection.DexKit
import com.Johnny.wcx.utils.serialization.DefaultJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.luckypray.dexkit.query.matchers.ClassMatcher
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import java.lang.reflect.Modifier as JavaModifier

/**
 * WeChat feature flag type names, matching [ly4.e.h()] return values.
 *
 * WeChat's obfuscated class hierarchy for feature flags (verified at 8.0.69):
 * - [ly4.h] root abstract class: [b]()=name, [c]()=desc, [e]()=group
 * - [ly4.e] extends [h]: [h]()=typeName("Int" default), [i]()=defaultValue, [l]()=b() + '_' + h()
 * - [ly4.d] extends [e]: [n]()=labels, [o]()=values
 * - [ly4.i] extends [d]: concrete boolean-like (Int, labels=["关闭","打开"], values=["0","1"])
 * - [ly4.f] extends [e]: h()="String"
 * - [ly4.g] extends [h]: empty
 *
 * API entry: [fd5.d1].[b](String key, Object defaultValue) — central get method.
 * Key format: fullKey = b() + '_' + h()   (via [ly4.e.l])
 */
@Feature(name = "灰度测试管理器", categories = ["系统与隐私"], description = "覆盖微信灰度测试 (Feature Flag) 的值")
object FeatureFlagManager : ClickableFeature(), IResolveDex {

    private val overridesFile by lazy { KnownPaths.moduleData / "feature_flag_overrides.json" }

    /**
     * Base class for all feature flags: [ly4.e] (verified from WeChat 8.0.69).
     * Identified by its [i]() method containing "Int", "Float", "String", "Long", "".
     * Hierarchy: [ly4.h]->[ly4.e]->[ly4.d]->[ly4.i] (Int) or [ly4.e]->[ly4.f] (String).
     */
    private val classFeatureFlagBase by dexClass {
        matcher {
            addMethod {
                usingEqStrings("Int")
            }
            addMethod {
                usingEqStrings("Int", "Float", "String", "Long", "")
            }
            addMethod {
                usingEqStrings("")
            }
        }
    }

    /**
     * Central API method: [fd5.d1].[b](String, Object) -> Object.
     * Key format: splits input key by '_', uses last segment for type dispatch.
     * Type names: "Int", "Float", "Long", "String" — matched by last _-segment.
     */
    private val methodRepairerConfigApiGet by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("RepairerConfigThread", "ValueStrategy_")
            }
            usingEqStrings("String", "Int", "Long", "Float", "key", "defaultValue")
            paramTypes(String::class.java, Any::class.java)
            returnType(Any::class.java)
        }
    }

    // ---------------------------------------------------------------------------
    // Override data model
    // ---------------------------------------------------------------------------

    @Serializable
    private data class FeatureFlagOverride(
        val runtimeKey: String,
        val internalType: String,  // "i"|"f"|"l"|"s"
        val rawValue: String
    ) {
        /** The runtime value to set as hook result. */
        val value: Any
            get() = when (internalType) {
                "i" -> rawValue.toInt()
                "f" -> rawValue.toFloat()
                "l" -> rawValue.toLong()
                "s" -> rawValue
                else -> error("Unknown override type: $internalType")
            }
    }

    // ---------------------------------------------------------------------------
    // Override persistence
    // ---------------------------------------------------------------------------

    /**
     * Load overrides from JSON file.
     */
    private fun loadOverrides(): Map<String, FeatureFlagOverride> {
        val file = overridesFile
        if (!file.exists()) return emptyMap()
        return runCatching {
            val list = DefaultJson.decodeFromString<List<FeatureFlagOverride>>(file.readText())
            list.associateBy { it.runtimeKey }
        }.getOrElse { e ->
            WeLogger.e(TAG, "failed to load $overridesFile", e)
            emptyMap()
        }
    }

    /**
     * Persist overrides, then mark cache as dirty.
     */
    private fun saveOverrides(overrides: List<FeatureFlagOverride>) {
        saveOverridesRaw(overrides)
        markCacheDirty()
    }

    private fun saveOverridesRaw(overrides: List<FeatureFlagOverride>) {
        runCatching {
            overridesFile.writeText(DefaultJson.encodeToString(overrides))
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to save $overridesFile", e)
        }
    }

    // ---------------------------------------------------------------------------
    // Live-reloadable override cache
    // ---------------------------------------------------------------------------

    @Volatile
    private var overridesCache: Map<String, FeatureFlagOverride>? = null
    private val cacheDirty = AtomicBoolean(true)

    private fun getOverrides(): Map<String, FeatureFlagOverride> {
        if (cacheDirty.compareAndSet(true, false)) {
            overridesCache = loadOverrides()
        }
        return overridesCache!!
    }

    private fun markCacheDirty() {
        cacheDirty.set(true)
    }

    // ---------------------------------------------------------------------------
    // Hook
    // ---------------------------------------------------------------------------

    override fun onEnable() {
        methodRepairerConfigApiGet.hookBefore {
            try {
                val key = args[0] as? String ?: return@hookBefore
                val override = getOverrides()[key] ?: return@hookBefore
                // 仅当 override.value 类型与原方法返回类型一致时才设置 result
                if (method is java.lang.reflect.Method) {
                    val returnType = (method as java.lang.reflect.Method).returnType
                    val overrideValue = override.value
                    if (returnType.isInstance(overrideValue) ||
                        (returnType.isPrimitive && overrideValue?.javaClass?.let { it == returnType } == true)
                    ) {
                        result = overrideValue
                    }
                }
            } catch (e: Throwable) {
                // 兜底异常捕获
            }
        }
    }

    // ---------------------------------------------------------------------------
    // UI — Dialog
    // ---------------------------------------------------------------------------

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            FeatureFlagManagerDialog(onDismiss = onDismiss)
        }
    }
    // =====================================================================
    // Composable UI
    // =====================================================================

    @Composable
    private fun FeatureFlagManagerDialog(onDismiss: () -> Unit) {
        var isLoading by remember { mutableStateOf(true) }
        var featureFlagClasses by remember { mutableStateOf<List<String>>(emptyList()) }
        var searchQuery by remember { mutableStateOf("") }

        val filteredClasses = remember(searchQuery, featureFlagClasses) {
            if (searchQuery.isEmpty()) featureFlagClasses
            else featureFlagClasses.filter { it.contains(searchQuery, ignoreCase = true) }
        }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                val superClassName = classFeatureFlagBase.clazz.name

                val results = DexKit.findClass {
                    matcher {
                        modifiers(JavaModifier.FINAL)
                        anyOf(
                            ClassMatcher().apply {
                                // ly4.f subclasses: Concrete → f → e (2 levels)
                                superClass { superClass = superClassName }
                            },
                            ClassMatcher().apply {
                                // ly4.i subclasses: Concrete → i → d → e (3 levels)
                                superClass { superClass { superClass = superClassName } }
                            }
                        )
                    }
                }
                featureFlagClasses = results.map { it.name }.sorted()
                isLoading = false
            }
        }

        AlertDialogContent(
            title = { Text("灰度测试管理器") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 500.dp)
                ) {
                    if (isLoading) {
                        LoadingView()
                    } else {
                        SearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onClear = { searchQuery = "" }
                        )
                        when {
                            filteredClasses.isEmpty() && featureFlagClasses.isEmpty() -> {
                                EmptyState()
                            }

                            filteredClasses.isEmpty() -> {
                                NoMatchState()
                            }

                            else -> {
                                FlagList(classNames = filteredClasses)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        )
    }

    @Composable
    private fun ColumnScope.LoadingView() {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f), contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularWavyProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("正在扫描灰度测试类, 请稍等...")
            }
        }
    }

    @Composable
    private fun ColumnScope.EmptyState() {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f), contentAlignment = Alignment.Center
        ) {
            Text("未找到灰度测试类", style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun ColumnScope.NoMatchState() {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f), contentAlignment = Alignment.Center
        ) {
            Text("未找到匹配的类", style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun SearchBar(
        query: String,
        onQueryChange: (String) -> Unit,
        onClear: () -> Unit
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            placeholder = { Text("搜索类名...") },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Text("×")
                    }
                }
            }
        )
    }

    @Composable
    private fun ColumnScope.FlagList(classNames: List<String>) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(classNames) { className ->
                FlagListItem(className = className)
            }
        }
    }

    @Composable
    private fun FlagListItem(className: String) {
        var showActionDialog by remember { mutableStateOf(false) }

        if (showActionDialog) {
            FlagActionDialog(
                className = className,
                onDismiss = { showActionDialog = false }
            )
        }

        Text(
            text = className.substringAfterLast('.'),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showActionDialog = true }
                .padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium
        )
        HorizontalDivider(Modifier.alpha(0.3f))
    }

    @Composable
    private fun FlagActionDialog(
        className: String,
        onDismiss: () -> Unit
    ) {
        var internalName by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        var typeName by remember { mutableStateOf("") }
        var configKey by remember { mutableStateOf("") }
        var showOverrideDialog by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            val flagInstance = className.toClass().createInstance()
            flagInstance
                .reflekt()
                .methods { returnType = String::class }
                .apply {
                    // this[0] = b() (name), this[1] = c() (desc), this[2] = h() (type),
                    // this[3] = j(), this[4] = k(), this[5] = l() (fullKey = b() + '_' + h())
                    if (isNotEmpty()) internalName = this[0].invoke()!! as String
                    if (size > 1) description = this[1].invoke()!! as String
                    if (size > 2) typeName = this[2].invoke()!! as String
                    for (i in indices) {
                        val str = this[i].invoke()!! as String
                        if (str.startsWith("clicfg")) {
                            configKey = str
                        }
                    }
                }
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = className.substringAfterLast('.'),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                val runtimeKey = if (internalName.isNotEmpty() && typeName.isNotEmpty()) {
                    "${internalName}_${typeName}"
                } else null

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                ) {
                    CopyInfoItem("复制完整类名", className)
                    CopyInfoItem("复制功能内部名称", internalName)
                    CopyInfoItem("复制功能简介", description)
                    CopyInfoItem("复制配置键名", configKey)

                    ListItem(
                        modifier = Modifier.clickable { showOverrideDialog = true },
                        supportingContent = { Text("为该灰度测试项覆盖其当前取值") },
                        headlineContent = {
                            Text("覆盖功能取值", style = MaterialTheme.typography.bodyLarge)
                        },
                    )

                    // Override value sub-dialog
                    if (showOverrideDialog && runtimeKey != null) {
                        OverrideValueDialog(
                            runtimeKey = runtimeKey,
                            typeName = typeName,
                            onDismiss = { showOverrideDialog = false }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        )
    }

    @Composable
    private fun CopyInfoItem(label: String, value: String) {
        if (value.isEmpty()) return
        val context = LocalContext.current
        ListItem(
            modifier = Modifier.clickable { copyToClipboard(context, value) },
            supportingContent = { Text(value) },
            headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        )
    }

    private const val TAG = "FeatureFlagManager"

    @Composable
    private fun OverrideValueDialog(
        runtimeKey: String,
        typeName: String,
        onDismiss: () -> Unit
    ) {
        // Map WeChat type name → internal type char
        val defaultTypeChar = when (typeName) {
            "Int" -> "i"
            "Float" -> "f"
            "Long" -> "l"
            "String" -> "s"
            else -> "i"  // fallback
        }

        val existingOverride = remember {
            getOverrides()[runtimeKey]
        }

        var type by remember { mutableStateOf(existingOverride?.internalType ?: defaultTypeChar) }
        var rawValue by remember { mutableStateOf(existingOverride?.rawValue ?: "") }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("设置覆盖值") },
            text = {
                Column {
                    TextField(
                        value = type,
                        onValueChange = { type = it },
                        singleLine = true,
                        label = { Text("类型 ([s]tring/[f]loat/[i]nt/[l]ong)") }
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = rawValue,
                        onValueChange = { rawValue = it },
                        singleLine = true,
                        label = { Text("值") }
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = {
                    val overrides = loadOverrides().values.toMutableList()
                    val existingIndex = overrides.indexOfFirst { it.runtimeKey == runtimeKey }
                    if (existingIndex == -1) {
                        WeLogger.i(TAG, "override not found for $runtimeKey, nothing to clear")
                        showToast("未找到该灰度测试的覆盖值!")
                        return@TextButton
                    }
                    WeLogger.i(TAG, "removing override for $runtimeKey")
                    overrides.removeAt(existingIndex)
                    saveOverrides(overrides)
                    onDismiss()
                }) { Text("清除") }
            },
            confirmButton = {
                Button(onClick = {
                    val rawValueStr = rawValue
                    // Validate value based on type
                    val validated = when (type) {
                        "s", "string" -> FeatureFlagOverride(runtimeKey, "s", rawValueStr)
                        "i", "int" -> {
                            val v = rawValueStr.toIntOrNull()
                            if (v == null) {
                                showToast("值格式不正确, 请重新输入")
                                return@Button
                            }
                            FeatureFlagOverride(runtimeKey, "i", rawValueStr)
                        }

                        "l", "long" -> {
                            val v = rawValueStr.toLongOrNull()
                            if (v == null) {
                                showToast("值格式不正确, 请重新输入")
                                return@Button
                            }
                            FeatureFlagOverride(runtimeKey, "l", rawValueStr)
                        }

                        "f", "float" -> {
                            val v = rawValueStr.toFloatOrNull()
                            if (v == null) {
                                showToast("值格式不正确, 请重新输入")
                                return@Button
                            }
                            FeatureFlagOverride(runtimeKey, "f", rawValueStr)
                        }

                        else -> {
                            showToast("类型格式不正确, 请重新输入")
                            return@Button
                        }
                    }

                    val overrides = loadOverrides().values.toMutableList()
                    val existingIndex = overrides.indexOfFirst { it.runtimeKey == runtimeKey }
                    if (existingIndex == -1) {
                        WeLogger.i(TAG, "adding new override for $runtimeKey")
                        overrides.add(validated)
                    } else {
                        WeLogger.i(TAG, "updating override for $runtimeKey")
                        overrides[existingIndex] = validated
                    }
                    saveOverrides(overrides)
                    onDismiss()
                }) { Text("确定") }
            }
        )
    }
}

