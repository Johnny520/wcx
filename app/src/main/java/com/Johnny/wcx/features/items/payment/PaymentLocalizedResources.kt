package com.Johnny.wcx.features.items.payment

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable

/** PaymentUiText 简化版：本模块不连带 i18n 体系，文本资源直接以字面量给出。 */
internal sealed interface PaymentUiText {
    data class Resource(val value: String) : PaymentUiText
    data class Raw(val value: String) : PaymentUiText
}

@Composable
internal fun PaymentUiText.resolve(): String = when (this) {
    is PaymentUiText.Resource -> value
    is PaymentUiText.Raw -> value
}

internal fun localizedPaymentString(@StringRes id: Int, vararg formatArgs: Any): String {
    val app = android.app.ActivityThread.currentApplication() ?: return id.toString()
    return app.getString(id, *formatArgs)
}

internal fun localizedPaymentQuantityString(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String {
    val app = android.app.ActivityThread.currentApplication() ?: return id.toString()
    return app.resources.getQuantityString(id, quantity, *formatArgs)
}