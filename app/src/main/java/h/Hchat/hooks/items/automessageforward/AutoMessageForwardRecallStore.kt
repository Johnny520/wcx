package h.Hchat.hooks.items.automessageforward

import android.content.Context
import h.Hchat.preferences.HchatStorage
import org.json.JSONArray
import org.json.JSONObject

internal data class AutoMessageForwardRecallRecord(
    val sourceTalker: String,
    val sourceMsgId: Long,
    val sourceMsgSvrId: Long,
    val targetTalker: String,
    val forwardedMsgId: Long,
    val ruleIds: Set<String>,
    val createdAt: Long
)

internal object AutoMessageForwardRecallStore {
    private const val KEY_RECORDS = "follow_recall_records_v1"
    const val MAX_RECORDS_PER_ACCOUNT = 512
    const val RECORD_TTL_MS = 24 * 60 * 60_000L

    @Synchronized
    fun load(context: Context, account: String): List<AutoMessageForwardRecallRecord> {
        if (account.isBlank()) return emptyList()
        val now = System.currentTimeMillis()
        val all = read(context)
        val valid = all.filter { now - it.record.createdAt in 0L..RECORD_TTL_MS }
        if (valid.size != all.size) write(context, valid)
        return valid.asSequence()
            .filter { it.account == account }
            .map { it.record }
            .toList()
            .takeLast(MAX_RECORDS_PER_ACCOUNT)
    }

    @Synchronized
    fun replace(
        context: Context,
        account: String,
        records: Collection<AutoMessageForwardRecallRecord>
    ) {
        if (account.isBlank()) return
        val now = System.currentTimeMillis()
        val retained = read(context).filter { item ->
            item.account != account && now - item.record.createdAt in 0L..RECORD_TTL_MS
        }
        val current = records.asSequence()
            .filter { record ->
                record.sourceTalker.isNotBlank() &&
                    (record.sourceMsgId > 0L || record.sourceMsgSvrId > 0L) &&
                    record.targetTalker.isNotBlank() &&
                    record.forwardedMsgId > 0L &&
                    record.ruleIds.isNotEmpty() &&
                    now - record.createdAt in 0L..RECORD_TTL_MS
            }
            .distinctBy { "${it.sourceTalker}:${it.sourceMsgSvrId}:${it.sourceMsgId}:${it.forwardedMsgId}" }
            .toList()
            .takeLast(MAX_RECORDS_PER_ACCOUNT)
            .map { StoredRecallRecord(account, it) }
        write(context, retained + current)
    }

    private fun read(context: Context): List<StoredRecallRecord> {
        val raw = HchatStorage.preferences(context, AutoMessageForwardSettings.PREFS_NAME)
            .getString(KEY_RECORDS, "")
            .orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val ruleIds = buildSet {
                        val values = item.optJSONArray("ruleIds") ?: return@buildSet
                        for (ruleIndex in 0 until values.length()) {
                            values.optString(ruleIndex).trim()
                                .takeIf { it.isNotBlank() }
                                ?.let(::add)
                        }
                    }
                    add(
                        StoredRecallRecord(
                            account = item.optString("account").trim(),
                            record = AutoMessageForwardRecallRecord(
                                sourceTalker = item.optString("sourceTalker").trim(),
                                sourceMsgId = item.optLong("sourceMsgId", 0L),
                                sourceMsgSvrId = item.optLong("sourceMsgSvrId", 0L),
                                targetTalker = item.optString("targetTalker").trim(),
                                forwardedMsgId = item.optLong("forwardedMsgId", 0L),
                                ruleIds = ruleIds,
                                createdAt = item.optLong("createdAt", 0L)
                            )
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, records: Collection<StoredRecallRecord>) {
        val encoded = JSONArray().apply {
            records.forEach { item ->
                put(JSONObject().apply {
                    put("account", item.account)
                    put("sourceTalker", item.record.sourceTalker)
                    put("sourceMsgId", item.record.sourceMsgId)
                    put("sourceMsgSvrId", item.record.sourceMsgSvrId)
                    put("targetTalker", item.record.targetTalker)
                    put("forwardedMsgId", item.record.forwardedMsgId)
                    put("createdAt", item.record.createdAt)
                    put("ruleIds", JSONArray(item.record.ruleIds.toList()))
                })
            }
        }.toString()
        HchatStorage.preferences(context, AutoMessageForwardSettings.PREFS_NAME)
            .edit()
            .putString(KEY_RECORDS, encoded)
            .commit()
    }

    private data class StoredRecallRecord(
        val account: String,
        val record: AutoMessageForwardRecallRecord
    )
}
