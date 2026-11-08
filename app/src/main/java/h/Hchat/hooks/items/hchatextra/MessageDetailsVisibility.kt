package h.Hchat.hooks.items.hchatextra

/** Only controls Hchat's extra label; native message rows and time separators remain intact. */
internal object MessageDetailsVisibility {
    fun shouldShow(type: Int): Boolean = when (type) {
        // Native system-message predicates, verified across WeChat 8.0.49–8.0.77.
        10000, 10002, 570425393, 64, 603979825,
        889192497, 922746929, 268445456, 268445458,
        285222674, -1879048191, 1077936177 -> false
        else -> true
    }
}
