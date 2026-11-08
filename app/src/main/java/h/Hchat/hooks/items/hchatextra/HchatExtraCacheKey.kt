package h.Hchat.hooks.items.hchatextra

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** FastKV keys are short; keep the full runtime identity in a collision-resistant digest. */
internal object HchatExtraCacheKey {
    private const val PREFIX = "hchat.extra."
    private const val MAX_PURPOSE_LENGTH = 64

    fun forLookup(runtimeKey: String, purpose: String): String? {
        if (runtimeKey.isBlank()) return null
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(runtimeKey.toByteArray(StandardCharsets.UTF_8))
        val hexChars = "0123456789abcdef"
        val digest = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val unsigned = byte.toInt() and 0xff
            digest[index * 2] = hexChars[unsigned ushr 4]
            digest[index * 2 + 1] = hexChars[unsigned and 0x0f]
        }
        val safePurpose = purpose
            .filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }
            .take(MAX_PURPOSE_LENGTH)
            .ifBlank { "entry" }
        return "$PREFIX${String(digest)}.$safePurpose"
    }
}
