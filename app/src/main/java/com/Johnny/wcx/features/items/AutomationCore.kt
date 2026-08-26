package com.Johnny.wcx.features.items

import com.Johnny.wcx.R

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

import com.Johnny.wcx.features.api.core.models.IWeContact



import com.Johnny.wcx.ui.content.BaseContactSelector
import com.Johnny.wcx.ui.content.MINUTES_PER_DAY
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.content.formatMinuteOfDay
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.serialization.DefaultJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Collator
import java.util.Calendar
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
internal data class AutomationToggleRule(val enabled: Boolean = false)

@Serializable
internal data class AutomationTimeRangeRule(
    val enabled: Boolean = false,
    val startMinute: Int = 0,
    val endMinute: Int = 0
) {
    fun matches(now: Calendar = Calendar.getInstance()): Boolean {
        if (!enabled) return true
        val current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val start = startMinute.coerceIn(0, MINUTES_PER_DAY - 1)
        val end = endMinute.coerceIn(0, MINUTES_PER_DAY - 1)
        if (start == end) return true
        return if (start < end) current in start until end else current !in end..<start
    }
}

@Serializable
internal enum class AutomationKeywordMode {
    STRING_LIST,
    EXACT,
    REGEX
}

@Serializable
internal data class AutomationKeywordRule(
    val enabled: Boolean = false,
    val mode: AutomationKeywordMode = AutomationKeywordMode.STRING_LIST,
    val strings: List<String> = emptyList(),
    val regex: String = "",
    val ignoreCase: Boolean = false,
) {
    fun matches(text: String): Boolean {
        if (!enabled) return true
        val keywords = strings
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        return when (mode) {
            AutomationKeywordMode.STRING_LIST -> keywords.any { text.contains(it, ignoreCase) }
            AutomationKeywordMode.EXACT -> keywords.any { text.equals(it, ignoreCase) }

            AutomationKeywordMode.REGEX -> runCatching {
                Regex(regex, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
                    .containsMatchIn(text)
            }.getOrDefault(false)
        }
    }

    fun validationError(label: String): String? {
        if (!enabled) return null
        return when (mode) {
            AutomationKeywordMode.STRING_LIST, AutomationKeywordMode.EXACT ->
                if (strings.none(String::isNotBlank)) {
                    "%1\$s字符串列表不能为空".format(label)
                } else null

            AutomationKeywordMode.REGEX -> when {
                regex.isBlank() -> "%1\$s正则表达式不能为空".format(label)
                runCatching { Regex(regex) }.isFailure ->
                    "%1\$s正则表达式格式不正确".format(label)
                else -> null
            }
        }
    }
}

internal class AtomicJsonConfigStore<T>(
    private val file: Path,
    private val serializer: KSerializer<T>,
    private val tag: String,
    private val initialValue: () -> T
) {
    @Volatile
    private var cached: T? = null

    fun get(): T {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: read().also { cached = it }
        }
    }

    fun update(transform: (T) -> T): T = synchronized(this) {
        val updated = transform(get())
        write(updated)
        cached = updated
        updated
    }

    private fun read(): T {
        if (!file.exists()) {
            return initialValue().also(::write)
        }
        return runCatching {
            DefaultJson.decodeFromString(serializer, file.readText())
        }.onFailure {
            WeLogger.e(tag, "failed to read $file", it)
        }.getOrElse { initialValue() }
    }

    private fun write(value: T) {
        runCatching {
            Files.createDirectories(file.parent)
            val temporary = file.resolveSibling("${file.fileName}.tmp")
            temporary.writeText(DefaultJson.encodeToString(serializer, value))
            runCatching {
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            }.getOrElse {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure {
            WeLogger.e(tag, "failed to save $file", it)
        }
    }
}

@Composable
internal fun AutomationContactSettingsSelector(
    title: String,
    contacts: List<IWeContact>,
    selectionKey: Any,
    subtitle: (IWeContact) -> String,
    isConfigured: (IWeContact) -> Boolean,
    onDismiss: () -> Unit,
    onOpen: (IWeContact) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val currentLocale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val collator = remember(currentLocale) { Collator.getInstance(currentLocale) }
    val filteredContacts = remember(searchQuery, contacts, collator) {
        contacts.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                    it.wxId.contains(searchQuery, ignoreCase = true)
        }.sortedWith(
            compareBy<IWeContact> { it.displayName.isBlank() }
                .thenComparator { first, second ->
                    collator.compare(first.displayName, second.displayName)
                }
        )
    }

    BaseContactSelector(
        title = title,
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        filteredContacts = filteredContacts,
        allContacts = contacts,
        confirmButtonText = "",
        confirmButtonEnabled = false,
        showConfirmButton = false,
        dismissButtonText = "关闭",
        onDismiss = onDismiss,
        onConfirm = {},
        selectionKey = selectionKey,
        isSelected = isConfigured,
        subtitleProvider = subtitle,
        trailingControl = { contact ->
            TextButton(onClick = { onOpen(contact) }) { Text("设置") }
        },
        onItemClick = onOpen
    )
}

internal fun formatAutomationMinute(value: Int): String = formatMinuteOfDay(value)

@Composable
internal fun automationKeywordSummary(rule: AutomationKeywordRule, unrestrictedText: String): String {
    if (!rule.enabled) return unrestrictedText
    return when (rule.mode) {
        AutomationKeywordMode.STRING_LIST -> pluralStringResource(
            R.plurals.automation_keyword_contains_summary,
            rule.strings.size,
            rule.strings.size,
        )
        AutomationKeywordMode.EXACT -> pluralStringResource(
            R.plurals.automation_keyword_exact_summary,
            rule.strings.size,
            rule.strings.size,
        )
        AutomationKeywordMode.REGEX -> if (rule.regex.isBlank()) {
            "尚未填写正则表达式"
        } else {
            "匹配单个正则表达式"
        }
    }
}

private fun localizedAutomationString(resourceId: Int, vararg formatArgs: Any): String {
    val app = android.app.ActivityThread.currentApplication() ?: return resourceId.toString()
    return app.getString(resourceId, *formatArgs)
}
