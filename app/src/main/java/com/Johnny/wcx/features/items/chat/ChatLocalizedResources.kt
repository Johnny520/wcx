package com.Johnny.wcx.features.items.chat

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

internal fun localizedChatString(@StringRes id: Int, vararg formatArgs: Any): String {
    val app = android.app.ActivityThread.currentApplication() ?: return id.toString()
    return app.getString(id, *formatArgs)
}

internal fun Context.localizedChatString(@StringRes id: Int, vararg formatArgs: Any): String =
    getString(id, *formatArgs)

internal fun localizedChatQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String {
    val app = android.app.ActivityThread.currentApplication() ?: return id.toString()
    return app.resources.getQuantityString(id, quantity, *formatArgs)
}