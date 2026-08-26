package com.Johnny.wcx.features.items.beautify.home_screen_panel

import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.api.core.WeServiceApi
import com.Johnny.wcx.utils.WeLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.Method
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

internal data class HomeSidePanelWalletDisplayState(
    val defaultMaskEnabled: Boolean,
    val isMasked: Boolean = defaultMaskEnabled,
) {
    fun toggleFromCard(): HomeSidePanelWalletDisplayState = if (defaultMaskEnabled) {
        copy(isMasked = !isMasked)
    } else {
        this
    }

    fun reset() = copy(isMasked = defaultMaskEnabled)
}

internal data class HomeSidePanelWalletUiState(
    val balanceFen: Long? = null,
    val displayState: HomeSidePanelWalletDisplayState = HomeSidePanelWalletDisplayState(true),
) {
    fun withBalance(balanceFen: Long?): HomeSidePanelWalletUiState = copy(balanceFen = balanceFen)

    val displayBalance: String
        get() = if (displayState.isMasked) "******" else formatHomeSidePanelWalletBalance(balanceFen)
}

internal fun formatHomeSidePanelWalletBalance(balanceFen: Long?): String {
    if (balanceFen == null) return "¥ --"
    val formatter = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))
    return "¥ ${formatter.format(BigDecimal.valueOf(balanceFen, 2))}"
}

internal object HomeSidePanelWalletBalanceSource {
    const val BALANCE_KEY = "USERINFO_NEW_BALANCE_LONG_SYNC"

    private val lock = Any()
    private val _updates = MutableStateFlow<Long?>(null)
    private var readBalance: (() -> Long?)? = null

    val updates: StateFlow<Long?> = _updates.asStateFlow()

    fun install(reader: () -> Long?) {
        synchronized(lock) {
            readBalance = reader
            _updates.value = null
        }
    }

    fun clear() {
        synchronized(lock) {
            readBalance = null
            _updates.value = null
        }
    }

    fun read(): Long? = synchronized(lock) {
        readBalance?.invoke()
    }

    fun refresh() {
        synchronized(lock) {
            _updates.value = readBalance?.invoke()
        }
    }

    fun onCacheWrite(key: Any?, value: Any?) {
        synchronized(lock) {
            if ((key as? Enum<*>)?.name != BALANCE_KEY) return
            if (value !is Long) return
            _updates.value = value
        }
    }
}

internal fun readHomeSidePanelWalletBalance(
    walletCacheReadMethod: Method,
    walletPayPluginClass: Class<*>,
): Long? {
    return try {
        val walletPayService = WeServiceApi.getServiceByClass(walletPayPluginClass.interfaces[0])
        val walletCache = walletPayService.reflekt().firstMethod {
            parameters()
            returnType = walletCacheReadMethod.declaringClass
        }.invoke()!!
        val balanceKey = walletCacheReadMethod.parameterTypes[0].enumConstants!!
            .single { (it as Enum<*>).name == HomeSidePanelWalletBalanceSource.BALANCE_KEY }
        walletCacheReadMethod.invoke(walletCache, balanceKey, null) as? Long
    } catch (e: Throwable) {
        // 8.0.76 等版本上钱包服务/枚举可能变化：读取失败仅显示占位，不影响面板
        WeLogger.w("HomeSidePanelWallet", "read wallet balance failed: ${e.message}")
        null
    }
}
