package h.Hchat.hooks.items.conversationgroup

import android.content.Context
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object ConversationGroupStore {
    enum class ReorderAction {
        TOP,
        UP,
        DOWN,
        BOTTOM
    }

    data class ImportResult(
        val success: Boolean,
        val groupCount: Int = 0,
        val message: String = ""
    )

    const val PREFS_NAME = "Hchat_conversation_groups"
    const val KEY_ENABLE = "enabled"
    const val DEFAULT_ENABLE = false

    internal const val KEY_DATA = "groups_v1"
    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_ACCOUNTS = "accounts"
    private const val KEY_AUTOMATIC_SEEN_GROUPS = "automaticSeenGroups"
    private const val KEY_AUTOMATIC_SEEN_INITIALIZED = "automaticSeenInitialized"
    private const val SCHEMA_VERSION = 3
    private const val EXPORT_FORMAT = "HchatConversationGroups"
    private const val MIN_EXPORT_SCHEMA_VERSION = 1
    private const val EXPORT_SCHEMA_VERSION = 2
    private const val VIRTUAL_TALKER_PREFIX = "wxid_hchat_group_"
    private const val TAG = "[Hchat:ConversationGroup]"
    private val lock = Any()
    @Volatile private var cachedSnapshot: CachedSnapshot? = null

    @JvmStatic
    fun accountKey(): String {
        return runCatching { WeChatApis.account()?.selfWxId().orEmpty().trim() }
            .getOrElse {
                HLog.e("$TAG 读取当前账号失败: ${it.message}", it)
                ""
            }
    }

    @JvmStatic
    fun load(context: Context): List<ConversationGroup> = synchronized(lock) {
        val account = accountKey()
        if (account.isBlank()) return@synchronized emptyList()
        loadLocked(context, account, persistRepairs = true)
    }

    @JvmStatic
    fun save(context: Context, groups: List<ConversationGroup>): Boolean = synchronized(lock) {
        val account = accountKey()
        if (account.isBlank()) return@synchronized false
        val currentById = loadLocked(context, account, persistRepairs = true).associateBy { it.id }
        val cleaned = groups.map { group ->
            val retainedConversations = normalizedConversationIds(group.conversationIds)
            val previous = currentById[group.id.trim()]
            val explicitlyRemoved = previous?.conversationIds.orEmpty().filterNot(
                retainedConversations::contains
            )
            val retainedOrder = normalizedConversationIds(group.conversationOrderIds)
                .filterNot(explicitlyRemoved::contains)
            val completedOrder = if (retainedOrder.isNotEmpty()) {
                retainedOrder + retainedConversations.filterNot(retainedOrder::contains)
            } else {
                emptyList()
            }
            group.copy(conversationOrderIds = completedOrder)
        }
        saveLocked(context, account, normalize(cleaned))
    }

    @JvmStatic
    fun newGroup(parentId: String?): ConversationGroup {
        return ConversationGroup(
            id = UUID.randomUUID().toString(),
            name = "",
            parentId = parentId?.trim()?.takeIf(String::isNotEmpty)
        )
    }

    @JvmStatic
    fun normalize(groups: List<ConversationGroup>): List<ConversationGroup> {
        val unique = LinkedHashMap<String, ConversationGroup>()
        groups.forEach { source ->
            val id = source.id.trim()
            val name = source.name.trim()
            if (id.isBlank() || name.isBlank()) return@forEach

            val conversations = source.conversationIds
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .toList()
            val conversationOrder = source.conversationOrderIds
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .toList()
            val pinnedConversations = source.pinnedConversationIds
                .asSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && it in conversations }
                .distinct()
                .toList()
            val bottomConversations = source.bottomConversationIds
                .asSequence()
                .map(String::trim)
                .filter {
                    it.isNotEmpty() && it in conversations && it !in pinnedConversations
                }
                .distinct()
                .toList()
            val automaticGroups = source.automaticGroupIds
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .toList()
            val automaticLabels = source.automaticGroupLabelIds
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .toList()
            val automaticOfficialIncludes = source.automaticOfficialIncludeIds
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .toList()
            val automaticOfficialExcludes = source.automaticOfficialExcludeIds
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .toList()
            val existing = unique[id]
            if (existing == null) {
                unique[id] = source.copy(
                    id = id,
                    name = name,
                    parentId = source.parentId?.trim()?.takeIf(String::isNotEmpty),
                    order = source.order.coerceAtLeast(0),
                    conversationIds = conversations,
                    conversationOrderIds = conversationOrder,
                    pinnedConversationIds = pinnedConversations,
                    bottomConversationIds = bottomConversations,
                    avatarPath = source.avatarPath.trim(),
                    automaticGroupIds = automaticGroups,
                    automaticGroupLabelIds = automaticLabels,
                    automaticOfficialIncludeIds = automaticOfficialIncludes
                        .filterNot(automaticOfficialExcludes::contains),
                    automaticOfficialExcludeIds = automaticOfficialExcludes
                )
            } else {
                unique[id] = existing.copy(
                    conversationIds = (existing.conversationIds + conversations).distinct(),
                    conversationOrderIds = (
                        existing.conversationOrderIds + conversationOrder
                        ).distinct(),
                    pinnedConversationIds = (
                        existing.pinnedConversationIds + pinnedConversations
                        ).distinct(),
                    bottomConversationIds = (
                        existing.bottomConversationIds + bottomConversations
                        ).filterNot { it in existing.pinnedConversationIds || it in pinnedConversations }
                        .distinct(),
                    automaticGroupIds = (existing.automaticGroupIds + automaticGroups).distinct(),
                    automaticGroupLabelIds = (
                        existing.automaticGroupLabelIds + automaticLabels
                        ).distinct(),
                    automaticOfficialIncludeIds = (
                        existing.automaticOfficialIncludeIds + automaticOfficialIncludes
                        ).distinct(),
                    automaticOfficialExcludeIds = (
                        existing.automaticOfficialExcludeIds + automaticOfficialExcludes
                        ).distinct()
                )
            }
        }

        val ids = unique.keys
        val parents = unique.mapValuesTo(LinkedHashMap()) { (id, group) ->
            group.parentId?.takeIf { it != id && it in ids }
        }
        repairCycles(parents)

        val repairedParents = unique.values.map { group ->
            group.copy(parentId = parents[group.id])
        }
        val normalizedOrders = HashMap<String, Int>()
        repairedParents.withIndex()
            .groupBy { it.value.parentId }
            .values
            .forEach { siblings ->
                siblings.sortedWith(
                    compareByDescending<IndexedValue<ConversationGroup>> { it.value.pinned }
                        .thenBy { it.value.order }
                        .thenBy { it.index }
                ).forEachIndexed { order, indexed ->
                    normalizedOrders[indexed.value.id] = order
                }
            }
        val repaired = repairedParents.map { group ->
            group.copy(order = normalizedOrders[group.id] ?: 0)
        }
        val owners = HashMap<String, String>()
        repaired.forEach { group ->
            group.conversationIds.forEach { owners[it] = group.id }
        }
        return repaired.map { group ->
            val conversations = group.conversationIds.filter { owners[it] == group.id }
            val conversationOrder = group.conversationOrderIds.filter { talker ->
                owners[talker]?.let { it == group.id } ?: true
            }
            group.copy(
                conversationIds = conversations,
                conversationOrderIds = conversationOrder,
                pinnedConversationIds = group.pinnedConversationIds.filter { it in conversations },
                bottomConversationIds = group.bottomConversationIds.filter {
                    it in conversations && it !in group.pinnedConversationIds
                },
                automaticOfficialIncludeIds = group.automaticOfficialIncludeIds
                    .filterNot(group.automaticOfficialExcludeIds::contains)
            )
        }
    }

    @JvmStatic
    fun descendantIds(groups: List<ConversationGroup>, id: String): Set<String> {
        val rootId = id.trim()
        if (rootId.isBlank()) return emptySet()
        if (groups.none { it.id == rootId }) return emptySet()

        val descendants = linkedSetOf<String>()
        var frontier = setOf(rootId)
        while (frontier.isNotEmpty()) {
            val children = groups.asSequence()
                .filter { it.parentId in frontier && it.id !in descendants }
                .map { it.id }
                .toCollection(linkedSetOf())
            descendants.addAll(children)
            frontier = children
        }
        descendants.remove(rootId)
        return descendants
    }

    @JvmStatic
    fun conversationOwner(groups: List<ConversationGroup>, talker: String): String? {
        val normalizedTalker = talker.trim()
        if (normalizedTalker.isBlank()) return null
        return groups.firstOrNull {
            normalizedTalker in it.conversationIds
        }?.id
    }

    @JvmStatic
    fun setConversationGroup(context: Context, talker: String, groupId: String?): Boolean =
        synchronized(lock) {
            val account = accountKey()
            val normalizedTalker = talker.trim()
            val targetId = groupId?.trim()?.takeIf(String::isNotEmpty)
            if (account.isBlank() || normalizedTalker.isBlank() ||
                normalizedTalker.startsWith(VIRTUAL_TALKER_PREFIX)
            ) {
                return@synchronized false
            }
            val current = loadLocked(context, account, persistRepairs = true)
            if (targetId != null && current.none { it.id == targetId }) {
                return@synchronized false
            }
            val currentOwner = conversationOwner(current, normalizedTalker)
            val hasStoredOrder = current.any {
                normalizedTalker in it.conversationOrderIds
            }
            if (currentOwner == targetId && (targetId != null || !hasStoredOrder)) {
                return@synchronized true
            }

            val updated = current.map { group ->
                val withoutTalker = group.conversationIds.filterNot { it == normalizedTalker }
                val withoutOrder = group.conversationOrderIds.filterNot {
                    it == normalizedTalker
                }
                val withoutPinned = group.pinnedConversationIds.filterNot { it == normalizedTalker }
                val withoutBottom = group.bottomConversationIds.filterNot { it == normalizedTalker }
                if (group.id == targetId) {
                    group.copy(
                        conversationIds = withoutTalker + normalizedTalker,
                        conversationOrderIds = if (group.conversationOrderIds.isNotEmpty()) {
                            withoutOrder + normalizedTalker
                        } else {
                            withoutOrder
                        },
                        pinnedConversationIds = withoutPinned,
                        bottomConversationIds = withoutBottom
                    )
                } else {
                    group.copy(
                        conversationIds = withoutTalker,
                        conversationOrderIds = withoutOrder,
                        pinnedConversationIds = withoutPinned,
                        bottomConversationIds = withoutBottom
                    )
                }
            }
            saveLocked(context, account, normalize(updated))
        }

    @JvmStatic
    fun setConversationGroups(
        context: Context,
        talkers: Collection<String>,
        groupId: String?
    ): Boolean = synchronized(lock) {
        val account = accountKey()
        val targetId = groupId?.trim()?.takeIf(String::isNotEmpty)
        val normalizedTalkers = talkers.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith(VIRTUAL_TALKER_PREFIX) }
            .toCollection(linkedSetOf())
        if (account.isBlank() || normalizedTalkers.isEmpty()) return@synchronized false

        val current = loadLocked(context, account, persistRepairs = true)
        if (targetId != null && current.none { it.id == targetId }) return@synchronized false
        val updated = current.map { group ->
            val retained = group.conversationIds.filterNot(normalizedTalkers::contains)
            val retainedOrder = group.conversationOrderIds.filterNot(
                normalizedTalkers::contains
            )
            val retainedPinned = group.pinnedConversationIds.filterNot(normalizedTalkers::contains)
            val retainedBottom = group.bottomConversationIds.filterNot(normalizedTalkers::contains)
            if (group.id == targetId) {
                group.copy(
                    conversationIds = (retained + normalizedTalkers).distinct(),
                    conversationOrderIds = if (group.conversationOrderIds.isNotEmpty()) {
                        (retainedOrder + normalizedTalkers).distinct()
                    } else {
                        retainedOrder
                    },
                    pinnedConversationIds = retainedPinned,
                    bottomConversationIds = retainedBottom
                )
            } else {
                group.copy(
                    conversationIds = retained,
                    conversationOrderIds = retainedOrder,
                    pinnedConversationIds = retainedPinned,
                    bottomConversationIds = retainedBottom
                )
            }
        }
        saveLocked(context, account, normalize(updated))
    }

    @JvmStatic
    fun setConversationPinned(
        context: Context,
        groupId: String,
        talker: String,
        pinned: Boolean
    ): Boolean = synchronized(lock) {
        val account = accountKey()
        val normalizedGroupId = groupId.trim()
        val normalizedTalker = talker.trim()
        if (account.isBlank() || normalizedGroupId.isBlank() || normalizedTalker.isBlank()) {
            return@synchronized false
        }
        val current = loadLocked(context, account, persistRepairs = true)
        val index = current.indexOfFirst {
            it.id == normalizedGroupId && normalizedTalker in it.conversationIds
        }
        if (index < 0) return@synchronized false
        val group = current[index]
        val nextPinned = if (pinned) {
            listOf(normalizedTalker) + group.pinnedConversationIds.filterNot {
                it == normalizedTalker
            }
        } else {
            group.pinnedConversationIds.filterNot { it == normalizedTalker }
        }
        val nextBottom = if (pinned) {
            group.bottomConversationIds.filterNot { it == normalizedTalker }
        } else {
            group.bottomConversationIds
        }
        val nextOrder = if (pinned && group.conversationOrderIds.isNotEmpty()) {
            listOf(normalizedTalker) + group.conversationOrderIds.filterNot {
                it == normalizedTalker
            }
        } else {
            group.conversationOrderIds
        }
        if (nextPinned == group.pinnedConversationIds &&
            nextBottom == group.bottomConversationIds &&
            nextOrder == group.conversationOrderIds
        ) return@synchronized true
        val updated = current.toMutableList()
        updated[index] = group.copy(
            conversationOrderIds = nextOrder,
            pinnedConversationIds = nextPinned,
            bottomConversationIds = nextBottom
        )
        saveLocked(context, account, normalize(updated))
    }

    @JvmStatic
    fun setConversationBottom(
        context: Context,
        groupId: String,
        talker: String,
        bottom: Boolean
    ): Boolean = synchronized(lock) {
        val account = accountKey()
        val normalizedGroupId = groupId.trim()
        val normalizedTalker = talker.trim()
        if (account.isBlank() || normalizedGroupId.isBlank() || normalizedTalker.isBlank()) {
            return@synchronized false
        }
        val current = loadLocked(context, account, persistRepairs = true)
        val index = current.indexOfFirst {
            it.id == normalizedGroupId && normalizedTalker in it.conversationIds
        }
        if (index < 0) return@synchronized false
        val group = current[index]
        val nextBottom = if (bottom) {
            group.bottomConversationIds.filterNot { it == normalizedTalker } + normalizedTalker
        } else {
            group.bottomConversationIds.filterNot { it == normalizedTalker }
        }
        val nextPinned = if (bottom) {
            group.pinnedConversationIds.filterNot { it == normalizedTalker }
        } else {
            group.pinnedConversationIds
        }
        val nextOrder = if (bottom && group.conversationOrderIds.isNotEmpty()) {
            group.conversationOrderIds.filterNot { it == normalizedTalker } + normalizedTalker
        } else {
            group.conversationOrderIds
        }
        if (nextBottom == group.bottomConversationIds &&
            nextPinned == group.pinnedConversationIds &&
            nextOrder == group.conversationOrderIds
        ) return@synchronized true
        val updated = current.toMutableList()
        updated[index] = group.copy(
            conversationOrderIds = nextOrder,
            pinnedConversationIds = nextPinned,
            bottomConversationIds = nextBottom
        )
        saveLocked(context, account, normalize(updated))
    }

    @JvmStatic
    fun saveConversationOrder(
        context: Context,
        groupId: String,
        talkers: Collection<String>
    ): Boolean = synchronized(lock) {
        val account = accountKey()
        val normalizedGroupId = groupId.trim()
        if (account.isBlank() || normalizedGroupId.isBlank()) return@synchronized false

        val current = loadLocked(context, account, persistRepairs = true)
        val index = current.indexOfFirst { it.id == normalizedGroupId }
        if (index < 0) return@synchronized false

        val order = normalizedConversationIds(talkers).filter { talker ->
            current.none { group ->
                group.id != normalizedGroupId && talker in group.conversationIds
            }
        }
        val group = current[index]
        if (order == group.conversationOrderIds) return@synchronized true

        val updated = current.toMutableList()
        updated[index] = group.copy(conversationOrderIds = order)
        saveLocked(context, account, normalize(updated))
    }

    @JvmStatic
    fun restoreDefaultConversationOrder(context: Context, groupId: String): Boolean {
        return saveConversationOrder(context, groupId, emptyList())
    }

    internal fun appendMissingConversationOrders(
        context: Context,
        effectiveConversationIds: Map<String, List<String>>
    ): List<ConversationGroup> = synchronized(lock) {
        val account = accountKey()
        if (account.isBlank()) return@synchronized emptyList()
        val current = loadLocked(context, account, persistRepairs = true)
        val updated = current.map { group ->
            if (group.conversationOrderIds.isEmpty()) {
                group
            } else {
                val missing = effectiveConversationIds[group.id].orEmpty()
                    .filterNot(group.conversationOrderIds::contains)
                if (missing.isEmpty()) group else group.copy(
                    conversationOrderIds = group.conversationOrderIds + missing
                )
            }
        }
        if (updated == current) return@synchronized current
        if (saveLocked(context, account, normalize(updated))) normalize(updated) else current
    }

    @JvmStatic
    fun isEnabled(context: Context): Boolean {
        return HchatStorage.preferences(context, PREFS_NAME)
            .getBoolean(KEY_ENABLE, DEFAULT_ENABLE)
    }

    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) {
        val committed = HchatStorage.preferences(context, PREFS_NAME)
            .edit()
            .putBoolean(KEY_ENABLE, enabled)
            .commit()
        if (!committed) HLog.e("$TAG 保存启用状态失败")
    }

    @JvmStatic
    fun loadAutomaticSeenGroupIds(context: Context): Set<String> = synchronized(lock) {
        val account = accountKey()
        if (account.isBlank()) return@synchronized emptySet()
        val raw = HchatStorage.preferences(context, PREFS_NAME)
            .getString(KEY_AUTOMATIC_SEEN_GROUPS, "").orEmpty()
        runCatching {
            val root = if (raw.isBlank()) JSONObject() else JSONObject(raw)
            val accounts = root.optJSONObject(KEY_ACCOUNTS) ?: return@runCatching emptySet()
            val values = accounts.optJSONArray(account) ?: return@runCatching emptySet()
            buildSet {
                for (index in 0 until values.length()) {
                    values.optString(index).trim()
                        .takeIf(String::isNotEmpty)
                        ?.let(::add)
                }
            }
        }.getOrDefault(emptySet())
    }

    @JvmStatic
    fun automaticSeenBaselineInitialized(context: Context): Boolean = synchronized(lock) {
        val account = accountKey()
        if (account.isBlank()) return@synchronized false
        val raw = HchatStorage.preferences(context, PREFS_NAME)
            .getString(KEY_AUTOMATIC_SEEN_GROUPS, "").orEmpty()
        runCatching {
            val root = if (raw.isBlank()) JSONObject() else JSONObject(raw)
            val initialized = root.optJSONObject(KEY_AUTOMATIC_SEEN_INITIALIZED)
                ?.optBoolean(account, false) == true
            val hasSeenGroups = (root.optJSONObject(KEY_ACCOUNTS)
                ?.optJSONArray(account)?.length() ?: 0) > 0
            initialized || hasSeenGroups
        }.getOrDefault(false)
    }

    @JvmStatic
    fun saveAutomaticSeenGroupIds(context: Context, ids: Collection<String>): Boolean = synchronized(lock) {
        val account = accountKey()
        if (account.isBlank()) return@synchronized false
        val prefs = HchatStorage.preferences(context, PREFS_NAME)
        val raw = prefs.getString(KEY_AUTOMATIC_SEEN_GROUPS, "").orEmpty()
        val root = if (raw.isBlank()) JSONObject() else runCatching { JSONObject(raw) }
            .getOrElse { JSONObject() }
        val accounts = root.optJSONObject(KEY_ACCOUNTS) ?: JSONObject()
        val initialized = root.optJSONObject(KEY_AUTOMATIC_SEEN_INITIALIZED) ?: JSONObject()
        val values = JSONArray()
        ids.map(String::trim).filter(String::isNotEmpty).distinct().forEach(values::put)
        accounts.put(account, values)
        initialized.put(account, true)
        root.put(KEY_ACCOUNTS, accounts)
        root.put(KEY_AUTOMATIC_SEEN_INITIALIZED, initialized)
        prefs.edit().putString(KEY_AUTOMATIC_SEEN_GROUPS, root.toString()).commit()
    }

    @JvmStatic
    fun addGroup(context: Context, group: ConversationGroup): Boolean = synchronized(lock) {
        val account = accountKey()
        if (account.isBlank()) return@synchronized false
        val current = loadLocked(context, account, persistRepairs = true)
        val id = group.id.trim()
        val name = group.name.trim()
        val parentId = group.parentId?.trim()?.takeIf(String::isNotEmpty)
        if (id.isBlank() || name.isBlank() || current.any { it.id == id }) {
            return@synchronized false
        }
        if (parentId != null && current.none { it.id == parentId }) return@synchronized false

        val claimed = normalizedConversationIds(group.conversationIds)
        val requestedConversationOrder = normalizedConversationIds(
            group.conversationOrderIds
        ).toList()
        val conversationOrder = if (requestedConversationOrder.isNotEmpty()) {
            requestedConversationOrder + claimed.filterNot(requestedConversationOrder::contains)
        } else {
            emptyList()
        }
        val nextOrder = current.count { it.parentId == parentId }
        val updated = current.map { existing ->
            existing.copy(
                conversationIds = existing.conversationIds.filterNot(claimed::contains),
                conversationOrderIds = existing.conversationOrderIds.filterNot(
                    claimed::contains
                ),
                pinnedConversationIds = existing.pinnedConversationIds.filterNot(claimed::contains),
                bottomConversationIds = existing.bottomConversationIds.filterNot(claimed::contains)
            )
        } + group.copy(
            id = id,
            name = name,
            parentId = parentId,
            order = nextOrder,
            conversationIds = claimed.toList(),
            conversationOrderIds = conversationOrder,
            pinnedConversationIds = group.pinnedConversationIds.filter(claimed::contains),
            bottomConversationIds = group.bottomConversationIds.filter {
                it in claimed && it !in group.pinnedConversationIds
            }
        )
        saveLocked(context, account, normalize(updated))
    }

    @JvmStatic
    fun updateGroup(context: Context, group: ConversationGroup): Boolean = synchronized(lock) {
        val account = accountKey()
        if (account.isBlank()) return@synchronized false
        val current = loadLocked(context, account, persistRepairs = true)
        val id = group.id.trim()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0 || group.name.trim().isBlank()) return@synchronized false

        val parentId = group.parentId?.trim()?.takeIf(String::isNotEmpty)
        if (!isValidParent(current, id, parentId)) return@synchronized false

        val claimed = normalizedConversationIds(group.conversationIds)
        val explicitlyRemoved = current[index].conversationIds.filterNot(claimed::contains).toSet()
        val requestedConversationOrder = normalizedConversationIds(group.conversationOrderIds)
            .filterNot(explicitlyRemoved::contains)
        val conversationOrder = if (requestedConversationOrder.isNotEmpty()) {
            requestedConversationOrder + claimed.filterNot(requestedConversationOrder::contains)
        } else {
            emptyList()
        }
        val claimedPinned = group.pinnedConversationIds.filter(claimed::contains).distinct()
        val claimedBottom = group.bottomConversationIds.filter {
            it in claimed && it !in claimedPinned
        }.distinct()
        val nextOrder = if (current[index].parentId == parentId) {
            group.order.coerceAtLeast(0)
        } else {
            current.count { it.parentId == parentId && it.id != id }
        }
        val updated = current.mapIndexed { currentIndex, existing ->
            if (currentIndex == index) {
                group.copy(
                    id = id,
                    name = group.name.trim(),
                    parentId = parentId,
                    order = nextOrder,
                    conversationIds = claimed.toList(),
                    conversationOrderIds = conversationOrder,
                    pinnedConversationIds = claimedPinned,
                    bottomConversationIds = claimedBottom
                )
            } else {
                existing.copy(
                    conversationIds = existing.conversationIds.filterNot(claimed::contains),
                    conversationOrderIds = existing.conversationOrderIds.filterNot(
                        claimed::contains
                    ),
                    pinnedConversationIds = existing.pinnedConversationIds.filterNot(claimed::contains),
                    bottomConversationIds = existing.bottomConversationIds.filterNot(claimed::contains)
                )
            }
        }
        saveLocked(context, account, normalize(updated))
    }

    @JvmStatic
    fun deleteGroup(context: Context, id: String): Boolean {
        return deleteGroups(context, listOf(id))
    }

    @JvmStatic
    fun deleteGroups(context: Context, ids: Collection<String>): Boolean = synchronized(lock) {
        val account = accountKey()
        if (account.isBlank()) return@synchronized false
        val current = loadLocked(context, account, persistRepairs = true)
        val requested = ids.asSequence().map(String::trim).filter(String::isNotEmpty).toSet()
        val existingIds = current.mapTo(hashSetOf()) { it.id }
        val deletedIds = requested.filterTo(linkedSetOf()) { it in existingIds }
        if (deletedIds.isEmpty()) return@synchronized false

        val updated = removeGroups(current, deletedIds)
        saveLocked(context, account, normalize(updated))
    }

    @JvmStatic
    fun exportCurrentAccount(context: Context): String? = synchronized(lock) {
        val account = accountKey()
        if (account.isBlank()) return@synchronized null
        runCatching {
            JSONObject().apply {
                put("format", EXPORT_FORMAT)
                put("schema", EXPORT_FORMAT)
                put("version", EXPORT_SCHEMA_VERSION)
                put("exportedAt", System.currentTimeMillis())
                put("groups", encodeGroups(loadLocked(context, account, persistRepairs = true)))
            }.toString(2)
        }.getOrElse {
            HLog.e("$TAG 导出当前账号的聊天分组失败: ${it.message}", it)
            null
        }
    }

    @JvmStatic
    fun importCurrentAccount(context: Context, raw: String): ImportResult =
        importCurrentAccount(context, raw, accountKey()) { false }

    /** 文件选择器可能跨账号或被取消，读取完成后仍须在提交边界复查。 */
    internal fun importCurrentAccount(
        context: Context,
        raw: String,
        expectedAccount: String,
        canceled: () -> Boolean
    ): ImportResult = synchronized(lock) {
        if (canceled()) return@synchronized ImportResult(false, message = "导入已取消")
        val account = accountKey()
        if (account != expectedAccount) {
            return@synchronized ImportResult(false, message = "微信账号已切换，请重新选择文件")
        }
        if (account.isBlank()) {
            return@synchronized ImportResult(false, message = "当前微信账号尚未就绪")
        }
        val parsed = runCatching { parseImport(raw) }.getOrElse {
            HLog.e("$TAG 校验聊天分组导入文件失败: ${it.message}", it)
            return@synchronized ImportResult(false, message = it.message ?: "导入文件格式错误")
        }
        if (canceled()) return@synchronized ImportResult(false, message = "导入已取消")
        if (accountKey() != expectedAccount) {
            return@synchronized ImportResult(false, message = "微信账号已切换，请重新选择文件")
        }
        if (!saveLocked(context, account, parsed)) {
            return@synchronized ImportResult(false, message = "保存聊天分组失败")
        }
        if (!resetAutomaticSeenBaselineLocked(context, account)) {
            HLog.e("$TAG 导入后重置账号 $account 的新群基线失败")
        }
        ImportResult(true, groupCount = parsed.size, message = "已导入 ${parsed.size} 个分组")
    }

    @JvmStatic
    fun moveGroup(context: Context, id: String, parentId: String?): Boolean = synchronized(lock) {
        val account = accountKey()
        if (account.isBlank()) return@synchronized false
        val current = loadLocked(context, account, persistRepairs = true)
        val groupId = id.trim()
        val targetParent = parentId?.trim()?.takeIf(String::isNotEmpty)
        val index = current.indexOfFirst { it.id == groupId }
        if (index < 0 || !isValidParent(current, groupId, targetParent)) {
            return@synchronized false
        }
        if (current[index].parentId == targetParent) return@synchronized true

        val nextOrder = current.count { it.parentId == targetParent && it.id != groupId }
        val updated = current.toMutableList()
        updated[index] = updated[index].copy(parentId = targetParent, order = nextOrder)
        saveLocked(context, account, normalize(updated))
    }

    @JvmStatic
    fun reorderGroup(context: Context, id: String, action: ReorderAction): Boolean =
        synchronized(lock) {
            val account = accountKey()
            if (account.isBlank()) return@synchronized false
            val current = loadLocked(context, account, persistRepairs = true)
            val groupId = id.trim()
            val target = current.firstOrNull { it.id == groupId } ?: return@synchronized false
            val siblings = current.filter {
                it.parentId == target.parentId && it.pinned == target.pinned
            }.sortedBy { it.order }
            val currentIndex = siblings.indexOfFirst { it.id == groupId }
            if (currentIndex < 0) return@synchronized false
            val targetIndex = when (action) {
                ReorderAction.TOP -> 0
                ReorderAction.UP -> (currentIndex - 1).coerceAtLeast(0)
                ReorderAction.DOWN -> (currentIndex + 1).coerceAtMost(siblings.lastIndex)
                ReorderAction.BOTTOM -> siblings.lastIndex
            }
            if (targetIndex == currentIndex) return@synchronized true

            val reordered = siblings.toMutableList().apply {
                add(targetIndex, removeAt(currentIndex))
            }
            val orders = reordered.mapIndexed { order, group -> group.id to order }.toMap()
            val updated = current.map { group ->
                orders[group.id]?.let { group.copy(order = it) } ?: group
            }
            saveLocked(context, account, normalize(updated))
        }

    private fun loadLocked(
        context: Context,
        account: String,
        persistRepairs: Boolean
    ): List<ConversationGroup> {
        val prefs = HchatStorage.preferences(context, PREFS_NAME)
        val raw = prefs.getString(KEY_DATA, "").orEmpty()
        cachedSnapshot?.takeIf { it.account == account && it.raw == raw }
            ?.let { return it.groups }
        if (raw.isBlank()) {
            return emptyList<ConversationGroup>().also {
                cachedSnapshot = CachedSnapshot(account, raw, it)
            }
        }
        var cachedRaw = raw
        val groups = runCatching {
            val root = JSONObject(raw)
            val storedSchemaVersion = root.optInt(KEY_SCHEMA_VERSION, 0)
            val accounts = root.optJSONObject(KEY_ACCOUNTS) ?: return@runCatching emptyList()
            val source = parseGroups(accounts.optJSONArray(account))
            val normalized = normalize(source)
            if (persistRepairs && (
                    normalized != source || storedSchemaVersion != SCHEMA_VERSION
                )
            ) {
                accounts.put(account, encodeGroups(normalized))
                root.put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                root.put(KEY_ACCOUNTS, accounts)
                val repairedRaw = root.toString()
                if (!prefs.edit().putString(KEY_DATA, repairedRaw).commit()) {
                    HLog.e("$TAG 保存账号 $account 的修复结果失败")
                } else {
                    cachedRaw = repairedRaw
                }
            }
            normalized
        }.getOrElse {
            HLog.e("$TAG 读取账号 $account 的聊天分组失败: ${it.message}", it)
            emptyList()
        }
        cachedSnapshot = CachedSnapshot(account, cachedRaw, groups)
        return groups
    }

    private fun saveLocked(
        context: Context,
        account: String,
        groups: List<ConversationGroup>
    ): Boolean {
        val prefs = HchatStorage.preferences(context, PREFS_NAME)
        val raw = prefs.getString(KEY_DATA, "").orEmpty()
        val root = if (raw.isBlank()) {
            JSONObject()
        } else {
            runCatching { JSONObject(raw) }.getOrElse {
                HLog.e("$TAG 配置已损坏，拒绝覆盖现有账号数据: ${it.message}", it)
                return false
            }
        }
        val accounts = root.optJSONObject(KEY_ACCOUNTS) ?: JSONObject()
        accounts.put(account, encodeGroups(groups))
        root.put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        root.put(KEY_ACCOUNTS, accounts)
        val updatedRaw = root.toString()
        val committed = prefs.edit().putString(KEY_DATA, updatedRaw).commit()
        if (!committed) HLog.e("$TAG 保存账号 $account 的聊天分组失败")
        else cachedSnapshot = CachedSnapshot(account, updatedRaw, groups)
        return committed
    }

    private fun resetAutomaticSeenBaselineLocked(context: Context, account: String): Boolean {
        val prefs = HchatStorage.preferences(context, PREFS_NAME)
        val raw = prefs.getString(KEY_AUTOMATIC_SEEN_GROUPS, "").orEmpty()
        if (raw.isBlank()) return true
        val root = runCatching { JSONObject(raw) }.getOrElse {
            HLog.e("$TAG 读取账号 $account 的新群基线失败: ${it.message}", it)
            return false
        }
        root.optJSONObject(KEY_ACCOUNTS)?.remove(account)
        root.optJSONObject(KEY_AUTOMATIC_SEEN_INITIALIZED)?.remove(account)
        return prefs.edit().putString(KEY_AUTOMATIC_SEEN_GROUPS, root.toString()).commit()
    }

    private fun parseGroups(array: JSONArray?): List<ConversationGroup> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val conversations = buildList {
                    val values = item.optJSONArray("conversationIds") ?: JSONArray()
                    for (valueIndex in 0 until values.length()) {
                        values.optString(valueIndex)
                            .takeIf(String::isNotBlank)
                            ?.let { add(it) }
                    }
                }
                val conversationOrder = stringList(item, "conversationOrderIds")
                val pinnedConversations = buildList {
                    val values = item.optJSONArray("pinnedConversationIds") ?: JSONArray()
                    for (valueIndex in 0 until values.length()) {
                        values.optString(valueIndex)
                            .takeIf(String::isNotBlank)
                            ?.let { add(it) }
                    }
                }
                val bottomConversations = buildList {
                    val values = item.optJSONArray("bottomConversationIds") ?: JSONArray()
                    for (valueIndex in 0 until values.length()) {
                        values.optString(valueIndex)
                            .takeIf(String::isNotBlank)
                            ?.let { add(it) }
                    }
                }
                add(
                    ConversationGroup(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        parentId = item.optString("parentId").takeIf(String::isNotBlank),
                        order = item.optInt("order", index),
                        conversationIds = conversations,
                        conversationOrderIds = conversationOrder,
                        pinnedConversationIds = pinnedConversations,
                        bottomConversationIds = bottomConversations,
                        pinned = item.booleanOrDefault("pinned", false),
                        avatarPath = item.stringOrDefault("avatarPath", ""),
                        unreadCountMode = unreadCountMode(item),
                        previewLatestMessage = item.booleanOrDefault(
                            "previewLatestMessage",
                            true
                        ),
                        roundAvatar = item.booleanOrDefault("roundAvatar", true),
                        showEmpty = item.booleanOrDefault("showEmpty", false),
                        automaticGroupingEnabled = item.booleanOrDefault(
                            "automaticGroupingEnabled",
                            false
                        ),
                        automaticAllGroups = item.booleanOrDefault("automaticAllGroups", false),
                        automaticNewGroups = item.booleanOrDefault("automaticNewGroups", false),
                        automaticMutedGroups = item.booleanOrDefault("automaticMutedGroups", false),
                        automaticOwnedGroups = item.booleanOrDefault("automaticOwnedGroups", false),
                        automaticEnterpriseGroups = item.booleanOrDefault(
                            "automaticEnterpriseGroups",
                            false
                        ),
                        automaticOfficialAccounts = item.booleanOrDefault(
                            "automaticOfficialAccounts",
                            false
                        ),
                        automaticGroupIds = stringList(item, "automaticGroupIds"),
                        automaticGroupLabelIds = stringList(item, "automaticGroupLabelIds"),
                        automaticOfficialIncludeIds = stringList(
                            item,
                            "automaticOfficialIncludeIds"
                        ),
                        automaticOfficialExcludeIds = stringList(
                            item,
                            "automaticOfficialExcludeIds"
                        )
                    )
                )
            }
        }
    }

    private fun stringList(item: JSONObject, key: String): List<String> {
        val values = item.optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until values.length()) {
                values.optString(index).trim()
                    .takeIf(String::isNotEmpty)
                    ?.let(::add)
            }
        }
    }

    private fun encodeGroups(groups: List<ConversationGroup>): JSONArray = JSONArray().apply {
        groups.forEach { group ->
            put(JSONObject().apply {
                put("id", group.id)
                put("name", group.name)
                put("parentId", group.parentId ?: JSONObject.NULL)
                put("order", group.order)
                put("conversationIds", JSONArray().apply {
                    group.conversationIds.forEach { put(it) }
                })
                put("conversationOrderIds", JSONArray().apply {
                    group.conversationOrderIds.forEach { put(it) }
                })
                put("pinnedConversationIds", JSONArray().apply {
                    group.pinnedConversationIds.forEach { put(it) }
                })
                put("bottomConversationIds", JSONArray().apply {
                    group.bottomConversationIds.forEach { put(it) }
                })
                put("pinned", group.pinned)
                put("avatarPath", group.avatarPath)
                put("unreadCountMode", group.unreadCountMode.value)
                put(
                    "showUnreadCount",
                    group.unreadCountMode != ConversationGroupUnreadMode.HIDDEN
                )
                put("previewLatestMessage", group.previewLatestMessage)
                put("roundAvatar", group.roundAvatar)
                put("showEmpty", group.showEmpty)
                put("automaticGroupingEnabled", group.automaticGroupingEnabled)
                put("automaticAllGroups", group.automaticAllGroups)
                put("automaticNewGroups", group.automaticNewGroups)
                put("automaticMutedGroups", group.automaticMutedGroups)
                put("automaticOwnedGroups", group.automaticOwnedGroups)
                put("automaticEnterpriseGroups", group.automaticEnterpriseGroups)
                put("automaticOfficialAccounts", group.automaticOfficialAccounts)
                put("automaticGroupIds", JSONArray().apply {
                    group.automaticGroupIds.forEach(::put)
                })
                put("automaticGroupLabelIds", JSONArray().apply {
                    group.automaticGroupLabelIds.forEach(::put)
                })
                put("automaticOfficialIncludeIds", JSONArray().apply {
                    group.automaticOfficialIncludeIds.forEach(::put)
                })
                put("automaticOfficialExcludeIds", JSONArray().apply {
                    group.automaticOfficialExcludeIds.forEach(::put)
                })
            })
        }
    }

    private fun parseImport(raw: String): List<ConversationGroup> {
        require(raw.isNotBlank()) { "导入文件为空" }
        val root = JSONObject(raw)
        require(root.optString("format") == EXPORT_FORMAT &&
            root.opt("schema") is String && root.optString("schema") == EXPORT_FORMAT
        ) { "不是 Hchat 聊天分组文件" }
        val version = root.optInt("version", -1)
        require(root.opt("version") is Number &&
            version in MIN_EXPORT_SCHEMA_VERSION..EXPORT_SCHEMA_VERSION
        ) {
            "不支持的聊天分组文件版本"
        }
        val array = root.optJSONArray("groups") ?: error("导入文件缺少分组数据")
        val ids = hashSetOf<String>()
        val parsed = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: error("第 ${index + 1} 个分组格式错误")
                require(item.opt("id") is String && item.opt("name") is String) {
                    "第 ${index + 1} 个分组字段格式错误"
                }
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                require(id.isNotBlank()) { "第 ${index + 1} 个分组缺少 ID" }
                require(name.isNotBlank()) { "第 ${index + 1} 个分组缺少名称" }
                require(ids.add(id)) { "导入文件包含重复分组 ID" }
                val parentId = if (item.isNull("parentId")) {
                    null
                } else {
                    require(item.opt("parentId") is String) { "分组 $name 的上级分组格式错误" }
                    item.optString("parentId").trim().takeIf(String::isNotEmpty)
                }
                val conversationsJson = item.optJSONArray("conversationIds")
                    ?: error("分组 $name 缺少会话列表")
                val conversationOrderJson = if (item.has("conversationOrderIds")) {
                    val value = item.opt("conversationOrderIds")
                    require(value is JSONArray) {
                        "分组 $name 的固定会话顺序格式错误"
                    }
                    value
                } else {
                    JSONArray()
                }
                val pinnedConversationsJson = if (item.has("pinnedConversationIds")) {
                    val value = item.opt("pinnedConversationIds")
                    require(value is JSONArray) {
                        "分组 $name 的置顶会话列表格式错误"
                    }
                    value
                } else {
                    JSONArray()
                }
                val bottomConversationsJson = if (item.has("bottomConversationIds")) {
                    val value = item.opt("bottomConversationIds")
                    require(value is JSONArray) {
                        "分组 $name 的置底会话列表格式错误"
                    }
                    value
                } else {
                    JSONArray()
                }
                if (item.has("order")) {
                    require(item.opt("order") is Number) { "分组 $name 的排序字段格式错误" }
                }
                requireOptionalType<Boolean>(item, "pinned", name, "主页置顶")
                requireOptionalType<String>(item, "avatarPath", name, "头像路径")
                requireOptionalType<Boolean>(item, "showUnreadCount", name, "未读数字")
                if (item.has("unreadCountMode")) {
                    require(item.opt("unreadCountMode") is String &&
                        ConversationGroupUnreadMode.fromValue(
                            item.optString("unreadCountMode")
                        ) != null
                    ) { "分组 $name 的未读数模式格式错误" }
                }
                requireOptionalType<Boolean>(
                    item,
                    "previewLatestMessage",
                    name,
                    "最新消息预览"
                )
                requireOptionalType<Boolean>(item, "roundAvatar", name, "圆形头像")
                requireOptionalType<Boolean>(item, "showEmpty", name, "空分组显示")
                requireOptionalType<Boolean>(
                    item,
                    "automaticGroupingEnabled",
                    name,
                    "自动归拢"
                )
                requireOptionalType<Boolean>(item, "automaticAllGroups", name, "所有群聊")
                requireOptionalType<Boolean>(item, "automaticNewGroups", name, "新群聊")
                requireOptionalType<Boolean>(item, "automaticMutedGroups", name, "免打扰群聊")
                requireOptionalType<Boolean>(item, "automaticOwnedGroups", name, "自己创建的群聊")
                requireOptionalType<Boolean>(
                    item,
                    "automaticEnterpriseGroups",
                    name,
                    "企业微信群聊"
                )
                requireOptionalType<Boolean>(
                    item,
                    "automaticOfficialAccounts",
                    name,
                    "公众号"
                )
                listOf(
                    "automaticGroupIds",
                    "automaticGroupLabelIds",
                    "automaticOfficialIncludeIds",
                    "automaticOfficialExcludeIds"
                ).forEach { key ->
                    if (item.has(key)) require(item.opt(key) is JSONArray) {
                        "分组 $name 的 $key 格式错误"
                    }
                }
                val conversations = buildList {
                    for (conversationIndex in 0 until conversationsJson.length()) {
                        val value = conversationsJson.opt(conversationIndex)
                        require(value is String) { "分组 $name 的会话数据格式错误" }
                        value.trim().takeIf(String::isNotEmpty)?.let { add(it) }
                    }
                }
                val conversationOrder = buildList {
                    for (conversationIndex in 0 until conversationOrderJson.length()) {
                        val value = conversationOrderJson.opt(conversationIndex)
                        require(value is String) { "分组 $name 的固定会话顺序数据格式错误" }
                        value.trim().takeIf(String::isNotEmpty)?.let { add(it) }
                    }
                }
                val pinnedConversations = buildList {
                    for (conversationIndex in 0 until pinnedConversationsJson.length()) {
                        val value = pinnedConversationsJson.opt(conversationIndex)
                        require(value is String) { "分组 $name 的置顶会话数据格式错误" }
                        value.trim().takeIf(String::isNotEmpty)?.let { add(it) }
                    }
                }
                val bottomConversations = buildList {
                    for (conversationIndex in 0 until bottomConversationsJson.length()) {
                        val value = bottomConversationsJson.opt(conversationIndex)
                        require(value is String) { "分组 $name 的置底会话数据格式错误" }
                        value.trim().takeIf(String::isNotEmpty)?.let { add(it) }
                    }
                }
                add(
                    ConversationGroup(
                        id = id,
                        name = name,
                        parentId = parentId,
                        order = item.optInt("order", index).coerceAtLeast(0),
                        conversationIds = conversations,
                        conversationOrderIds = conversationOrder,
                        pinnedConversationIds = pinnedConversations,
                        bottomConversationIds = bottomConversations,
                        pinned = item.booleanOrDefault("pinned", false),
                        avatarPath = item.stringOrDefault("avatarPath", ""),
                        unreadCountMode = unreadCountMode(item),
                        previewLatestMessage = item.booleanOrDefault(
                            "previewLatestMessage",
                            true
                        ),
                        roundAvatar = item.booleanOrDefault("roundAvatar", true),
                        showEmpty = item.booleanOrDefault("showEmpty", false),
                        automaticGroupingEnabled = item.booleanOrDefault(
                            "automaticGroupingEnabled",
                            false
                        ),
                        automaticAllGroups = item.booleanOrDefault("automaticAllGroups", false),
                        automaticNewGroups = item.booleanOrDefault("automaticNewGroups", false),
                        automaticMutedGroups = item.booleanOrDefault("automaticMutedGroups", false),
                        automaticOwnedGroups = item.booleanOrDefault("automaticOwnedGroups", false),
                        automaticEnterpriseGroups = item.booleanOrDefault(
                            "automaticEnterpriseGroups",
                            false
                        ),
                        automaticOfficialAccounts = item.booleanOrDefault(
                            "automaticOfficialAccounts",
                            false
                        ),
                        automaticGroupIds = stringList(item, "automaticGroupIds"),
                        automaticGroupLabelIds = stringList(item, "automaticGroupLabelIds"),
                        automaticOfficialIncludeIds = stringList(
                            item,
                            "automaticOfficialIncludeIds"
                        ),
                        automaticOfficialExcludeIds = stringList(
                            item,
                            "automaticOfficialExcludeIds"
                        )
                    )
                )
            }
        }
        parsed.forEach { group ->
            require(group.parentId == null || group.parentId in ids) {
                "分组 ${group.name} 的上级分组不存在"
            }
        }
        return normalize(parsed)
    }

    private fun removeGroups(
        groups: List<ConversationGroup>,
        deletedIds: Set<String>
    ): List<ConversationGroup> {
        val byId = groups.associateBy { it.id }

        fun survivingParent(startId: String?): String? {
            var currentId = startId
            val visited = hashSetOf<String>()
            while (currentId != null && visited.add(currentId)) {
                val current = byId[currentId] ?: return null
                if (current.id !in deletedIds) return current.id
                currentId = current.parentId
            }
            return null
        }

        val transferred = linkedMapOf<String, MutableList<String>>()
        val transferredOrder = linkedMapOf<String, MutableList<String>>()
        val fixedOrderTargets = hashSetOf<String>()
        groups.asSequence()
            .filter { it.id in deletedIds }
            .forEach { deleted ->
                survivingParent(deleted.parentId)?.let { parentId ->
                    transferred.getOrPut(parentId, ::arrayListOf).addAll(deleted.conversationIds)
                    val orderedManualConversations = deleted.conversationOrderIds.filter {
                        it in deleted.conversationIds
                    }
                    transferredOrder.getOrPut(parentId, ::arrayListOf).addAll(
                        orderedManualConversations + deleted.conversationIds.filterNot(
                            orderedManualConversations::contains
                        )
                    )
                    if (deleted.conversationOrderIds.isNotEmpty() &&
                        deleted.conversationIds.isNotEmpty()
                    ) {
                        fixedOrderTargets += parentId
                    }
                }
            }
        return groups.asSequence()
            .filterNot { it.id in deletedIds }
            .map { group ->
                val incomingConversations = transferred[group.id].orEmpty()
                val nextConversations = (group.conversationIds + incomingConversations).distinct()
                val nextConversationOrder = when {
                    group.conversationOrderIds.isNotEmpty() -> (
                        group.conversationOrderIds + transferredOrder[group.id].orEmpty()
                        ).distinct()
                    group.id in fixedOrderTargets -> (
                        group.conversationIds + transferredOrder[group.id].orEmpty()
                        ).distinct()
                    else -> emptyList()
                }
                group.copy(
                    parentId = survivingParent(group.parentId),
                    conversationIds = nextConversations,
                    conversationOrderIds = nextConversationOrder,
                    pinnedConversationIds = group.pinnedConversationIds.filter {
                        it in nextConversations
                    },
                    bottomConversationIds = group.bottomConversationIds.filter {
                        it in nextConversations && it !in group.pinnedConversationIds
                    }
                )
            }
            .toList()
    }

    private fun unreadCountMode(item: JSONObject): ConversationGroupUnreadMode {
        val stored = item.opt("unreadCountMode")
        if (stored is String) {
            ConversationGroupUnreadMode.fromValue(stored)?.let { return it }
        }
        return ConversationGroupUnreadMode.fromLegacy(
            item.booleanOrDefault("showUnreadCount", true)
        )
    }

    private fun repairCycles(parents: MutableMap<String, String?>) {
        val states = HashMap<String, Int>()

        fun visit(id: String) {
            when (states[id]) {
                1, 2 -> return
            }
            states[id] = 1
            val parentId = parents[id]
            if (parentId != null) {
                if (states[parentId] == 1) {
                    parents[id] = null
                } else {
                    visit(parentId)
                }
            }
            states[id] = 2
        }

        parents.keys.forEach(::visit)
    }

    private fun isValidParent(
        groups: List<ConversationGroup>,
        groupId: String,
        parentId: String?
    ): Boolean {
        if (parentId == null) return true
        if (parentId == groupId || groups.none { it.id == parentId }) return false
        return parentId !in descendantIds(groups, groupId)
    }

    private fun normalizedConversationIds(values: Collection<String>): Set<String> {
        return values.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toCollection(linkedSetOf())
    }

    private fun JSONObject.booleanOrDefault(key: String, defaultValue: Boolean): Boolean {
        return opt(key) as? Boolean ?: defaultValue
    }

    private fun JSONObject.stringOrDefault(key: String, defaultValue: String): String {
        return (opt(key) as? String)?.trim() ?: defaultValue
    }

    private inline fun <reified T> requireOptionalType(
        item: JSONObject,
        key: String,
        groupName: String,
        fieldName: String
    ) {
        if (item.has(key)) {
            require(item.opt(key) is T) { "分组 $groupName 的${fieldName}字段格式错误" }
        }
    }

    private data class CachedSnapshot(
        val account: String,
        val raw: String,
        val groups: List<ConversationGroup>
    )
}
