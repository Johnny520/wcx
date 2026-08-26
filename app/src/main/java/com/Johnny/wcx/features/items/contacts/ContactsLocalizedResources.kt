package com.Johnny.wcx.features.items.contacts

import android.content.Context
import androidx.annotation.StringRes

/** 同名函数简化版：数量字符串用标准资源解析。 */
internal fun localizedContactsQuantity(
    @StringRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String {
    val app = android.app.ActivityThread.currentApplication() ?: return id.toString()
    return app.resources.getQuantityString(id, quantity, *formatArgs)
}

internal fun Context.localizedContactsQuantity(
    @StringRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = resources.getQuantityString(id, quantity, *formatArgs)