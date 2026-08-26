package com.Johnny.wcx.features.items.chat_input_bar_menu

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

internal fun localizedChatInputString(@StringRes id: Int, vararg formatArgs: Any): String {
    val app = android.app.ActivityThread.currentApplication() ?: return id.toString()
    return app.getString(id, *formatArgs)
}

internal fun Context.localizedChatInputString(@StringRes id: Int, vararg formatArgs: Any): String =
    getString(id, *formatArgs)

internal fun Context.localizedChatInputQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = resources.getQuantityString(id, quantity, *formatArgs)