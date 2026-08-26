package com.Johnny.wcx.features.items.beautify

import android.content.Context
import androidx.annotation.StringRes

/**
 * 与 WeKit 同名结构（BeautifyText 消息模型）。原版本依赖多语言框架，
 * 本模块简化为：Resource 直接用标准 Context.getString 解析。
 */
internal sealed interface BeautifyText {
    data class Resource(
        @param:StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : BeautifyText

    data class Raw(val value: String) : BeautifyText
}

internal fun beautifyText(@StringRes id: Int, vararg args: Any): BeautifyText =
    BeautifyText.Resource(id, args.toList())

/** 字面量消息（合并转写后常用 beautifyText("中文") 形式）。 */
internal fun beautifyText(value: String, vararg args: Any): BeautifyText =
    BeautifyText.Raw(if (args.isEmpty()) value else value.format(*args))

internal fun Context.resolveBeautifyText(text: BeautifyText): String = when (text) {
    is BeautifyText.Resource -> getString(text.id, *text.args.toTypedArray())
    is BeautifyText.Raw -> text.value
}

/** 顶层便捷函数（同名）：应用上下文解析资源字符串。 */
internal fun localizedBeautifyString(@StringRes id: Int, vararg formatArgs: Any): String {
    val app = android.app.ActivityThread.currentApplication() ?: return id.toString()
    return app.getString(id, *formatArgs)
}