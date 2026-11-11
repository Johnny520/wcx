package h.Hchat.hooks.items.linkparse

/** Own messages must have an explicit successful database transition. */
internal object LinkParseEntryPolicy {
    fun acceptDatabase(insert: Boolean, update: Boolean, outgoing: Boolean, rowStatus: Int, changedStatus: Int?): Boolean =
        if (outgoing) (insert || update) && changedStatus == SENT && rowStatus == SENT else insert

    private const val SENT = 2
}
