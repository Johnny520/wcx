package h.Hchat.hooks.api.sns

import android.content.Context
import h.Hchat.preferences.HchatStorage
import java.math.BigInteger

internal object SnsLocalPostIdentity {
    private const val LEGACY_PREFIX = 0x4843L
    private const val REGISTRY_PREFS = "Hchat_moments_fake_interaction_config"
    private const val REGISTRY_KEY = "fake_forward_ids_v1"

    fun createAbove(maxId: Long, positiveOffset: Long): Long? {
        if (positiveOffset <= 0L) return null
        val candidate = maxId + positiveOffset
        return candidate.takeIf { java.lang.Long.compareUnsigned(it, maxId) > 0 }
    }

    fun createInTimelineRange(
        olderOrEqualId: Long?,
        newerId: Long?,
        positiveOffset: Long
    ): Long? {
        if (positiveOffset <= 0L) return null
        val lower = olderOrEqualId?.let(::unsignedBigInteger) ?: BigInteger.ZERO
        val upper = newerId?.let(::unsignedBigInteger) ?: UNSIGNED_LONG_LIMIT
        val available = upper.subtract(lower).subtract(BigInteger.ONE)
        if (available.signum() <= 0) return null
        val offset = BigInteger.valueOf(positiveOffset).subtract(BigInteger.ONE).mod(available)
        val candidate = if (olderOrEqualId == null && newerId != null) {
            upper.subtract(BigInteger.ONE).subtract(offset)
        } else {
            lower.add(BigInteger.ONE).add(offset)
        }
        return runCatching { java.lang.Long.parseUnsignedLong(candidate.toString()) }.getOrNull()
    }

    fun isLocalOnly(context: Context, raw: Any?): Boolean {
        val id = normalize(raw) ?: return false
        if (isLegacyLocalOnly(id)) return true
        val key = java.lang.Long.toUnsignedString(id)
        return HchatStorage.preferences(context, REGISTRY_PREFS)
            .getStringSet(REGISTRY_KEY, emptySet())
            .orEmpty()
            .contains(key)
    }

    fun isLegacyLocalOnly(id: Long): Boolean = (id ushr 48) == LEGACY_PREFIX

    private fun normalize(raw: Any?): Long? {
        val value = raw?.toString()?.trim().orEmpty()
        return value.toLongOrNull() ?: runCatching { java.lang.Long.parseUnsignedLong(value) }.getOrNull()
    }

    private fun unsignedBigInteger(value: Long): BigInteger =
        BigInteger(java.lang.Long.toUnsignedString(value))

    private val UNSIGNED_LONG_LIMIT = BigInteger.ONE.shiftLeft(Long.SIZE_BITS)
}
