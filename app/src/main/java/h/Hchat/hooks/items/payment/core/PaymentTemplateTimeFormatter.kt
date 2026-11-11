package h.Hchat.hooks.items.payment.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PaymentTemplateTimeFormatter {
    const val DEFAULT_PATTERN = "yyyy-MM-dd HH:mm:ss"

    fun normalizePattern(value: String?): String {
        return value.orEmpty().trim().ifBlank { DEFAULT_PATTERN }
    }

    fun isValidPattern(value: String?): Boolean {
        return runCatching {
            SimpleDateFormat(normalizePattern(value), Locale.CHINA)
        }.isSuccess
    }

    fun format(pattern: String?, timestamp: Long): String {
        val date = Date(timestamp)
        return runCatching {
            SimpleDateFormat(normalizePattern(pattern), Locale.CHINA).format(date)
        }.getOrElse {
            SimpleDateFormat(DEFAULT_PATTERN, Locale.CHINA).format(date)
        }
    }
}
