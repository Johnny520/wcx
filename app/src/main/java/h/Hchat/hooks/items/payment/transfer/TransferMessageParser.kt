package h.Hchat.hooks.items.payment.transfer

import java.util.Locale
import java.util.regex.Pattern

data class TransferMessageInfo(
    val transactionId: String,
    val transId: String,
    val payerUsername: String,
    val receiverUsername: String,
    val invalidTime: Int,
    val totalFee: Int,
    val amount: Double,
    val paySubtype: String,
    val transferAttach: String,
    val rawXml: String
) {
    val pending: Boolean
        get() = paySubtype.isBlank() || paySubtype in PENDING_PAY_SUBTYPES
}

private val PENDING_PAY_SUBTYPES = setOf("1", "7", "21", "27")

object TransferMessageParser {
    fun isLikelyTransferText(content: String?): Boolean {
        val xml = extractXml(content)
        return xml.isNotBlank() && looksLikeTransfer(xml)
    }

    fun parse(content: String?, fallbackPayer: String?): TransferMessageInfo? {
        val xml = extractXml(content)
        if (xml.isBlank() || !looksLikeTransfer(xml)) return null

        val transactionId = tag(xml, "transcationid")
            .ifBlank { tag(xml, "transactionid") }
            .ifBlank { tag(xml, "transaction_id") }
        val transId = tag(xml, "transferid")
            .ifBlank { tag(xml, "transfer_id") }
            .ifBlank { tag(xml, "trans_id") }
        val payer = tag(xml, "payer_username")
            .ifBlank { tag(xml, "payerusername") }
            .ifBlank { tag(xml, "fromusername") }
            .ifBlank { fallbackPayer.orEmpty() }
        val receiver = tag(xml, "receiver_username")
            .ifBlank { tag(xml, "receiverusername") }
            .ifBlank { tag(xml, "tousername") }
        val invalidTime = tag(xml, "invalidtime").toIntOrNull() ?: 0
        val totalFee = tag(xml, "total_fee").toIntOrNull()
            ?: tag(xml, "feederval").toIntOrNull()
            ?: amountToFen(parseAmount(xml))
        val paySubtype = tag(xml, "paysubtype")
        return TransferMessageInfo(
            transactionId = transactionId,
            transId = transId,
            payerUsername = payer,
            receiverUsername = receiver,
            invalidTime = invalidTime,
            totalFee = totalFee,
            amount = parseAmount(xml),
            paySubtype = paySubtype,
            transferAttach = tag(xml, "transfer_attach").ifBlank { tag(xml, "transferattach") },
            rawXml = xml
        )
    }

    fun containsKeywords(text: String, keywords: String): Boolean {
        if (text.isBlank() || keywords.isBlank()) return false
        return keywords.split("|", ",", "，")
            .map { it.trim() }
            .any { it.isNotEmpty() && text.contains(it, ignoreCase = true) }
    }

    private fun extractXml(content: String?): String {
        if (content.isNullOrBlank()) return ""
        val index = content.indexOf(":\n")
        return if (index > 0 && content.indexOf("<", index) > index) {
            content.substring(index + 2)
        } else {
            content
        }
    }

    private fun looksLikeTransfer(xml: String): Boolean {
        val lower = xml.lowercase(Locale.US)
        return lower.contains("<wcpayinfo")
            && (lower.contains("<transferid") || lower.contains("<transfer_id")
                || lower.contains("<trans_id") || lower.contains("<transcationid")
                || lower.contains("<transactionid") || lower.contains("<transaction_id"))
    }

    private fun tag(xml: String, tag: String): String {
        if (xml.isBlank() || tag.isBlank()) return ""
        return try {
            val pattern = Pattern.compile(
                "<$tag\\b[^>]*>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?\\s*</$tag>",
                Pattern.CASE_INSENSITIVE or Pattern.DOTALL
            )
            pattern.matcher(xml).let { matcher ->
                if (matcher.find()) matcher.group(1)?.trim().orEmpty() else ""
            }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun parseAmount(xml: String): Double {
        val fen = tag(xml, "total_fee").toIntOrNull() ?: tag(xml, "feederval").toIntOrNull()
        if (fen != null) return fen / 100.0
        val desc = tag(xml, "feedesc")
        val numeric = desc.replace(Regex("[^0-9.]"), "")
        return numeric.toDoubleOrNull() ?: 0.0
    }

    private fun amountToFen(amount: Double): Int = (amount * 100.0).toInt()
}
