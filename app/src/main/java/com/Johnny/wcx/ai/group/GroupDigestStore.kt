package com.Johnny.wcx.ai.group

import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.ai.AiProfileStore
import com.Johnny.wcx.utils.WeLogger
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div

/**
 * 群总结的落盘层：单群配置、索引、缓存与对账。
 *
 * 数据放在 `filesDir/ai/groups/`，与好友档案同层、同进程 —— 设置页由 `ActivityProxy`
 * 代理到微信进程启动，所以这里不需要任何跨进程通道。
 *
 * 三条不能破的规则：
 *  1. **单群文件是唯一权威**，`index.json` 只是列表缓存；不一致时以单群文件为准。
 *  2. 所有索引变更走同一把锁（本对象的方法都是 `@Synchronized`），禁止「先 read 再单独 write」。
 *  3. 先写单群文件、再刷索引；删除时反过来（先改索引、再删文件）。
 *
 * IO 复用 [AiProfileStore] 的原子写与读删，不复制第二套。
 */
object GroupDigestStore {

    private const val TAG = "GroupDigestStore"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = BuildConfig.DEBUG
    }

    private val rootDir: Path by lazy {
        (AiProfileStore.root / "groups").also { runCatching { it.createDirectories() } }
    }

    val root: Path get() = rootDir

    val indexFile: Path get() = root / "index.json"

    fun configFile(groupId: String): Path = root / "$groupId.json"

    /** 单群配置缓存。摘要历史可能有几十 KB，列表页切来切去不必每次都读盘解析。 */
    private const val CACHE_SIZE = 8

    private val cache = object : LinkedHashMap<String, GroupDigestConfig>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GroupDigestConfig>?): Boolean =
            size > CACHE_SIZE
    }

    /** 索引缓存；任何写路径都会同步更新或置空。 */
    @Volatile
    private var cachedIndex: GroupIndex? = null

    /** 未读总数，入口红点每次轮询都读它；-1 表示需要从索引重算。 */
    @Volatile
    private var unreadTotalCache = -1

    /** 列表顺序：最近跑过的在前；同一时刻按群 id 稳定排序。 */
    private val SUMMARY_ORDER =
        compareByDescending<GroupSummary> { it.lastRunAt }.thenBy { it.groupId }

    // ── 单群配置 ────────────────────────────────────────────────────────────

    @Synchronized
    fun readConfig(groupId: String): GroupDigestConfig? {
        if (groupId.isEmpty() || groupId.contains('/')) return null
        cache[groupId]?.let { return it }
        val text = AiProfileStore.readText(configFile(groupId)) ?: return null
        val config = runCatching { json.decodeFromString<GroupDigestConfig>(text) }.getOrElse {
            // 解析失败不能当成「没有这个群」：那会让下一次写入直接覆盖掉还能修好的历史。
            log("config 解析失败：$groupId：${it.javaClass.simpleName}: ${it.message}")
            return null
        }
        cache[groupId] = config
        return config
    }

    /**
     * 写单群配置并同步索引。
     *
     * 写之前先裁尾：90 天 / 100 条。裁尾在内存里做，失败时返回 false，不留下半份配置。
     */
    @Synchronized
    fun saveConfig(config: GroupDigestConfig): Boolean {
        if (config.groupId.isEmpty() || config.groupId.contains('/')) return false
        val trimmed = config.trimmed(System.currentTimeMillis())
        val text = runCatching { json.encodeToString(trimmed) }.getOrElse {
            log("config 序列化失败：${config.groupId}：${it.javaClass.simpleName}")
            return false
        }
        if (!AiProfileStore.writeAtomic(configFile(trimmed.groupId), text)) return false
        cache[trimmed.groupId] = trimmed
        upsertIndexEntry(trimmed.toSummary())
        return true
    }

    /**
     * 读-改-写。用于改频率、开关、状态这类只动几个字段的操作。
     *
     * 内容没变就不落盘：`withStatus(BUSY)` 这类调用可能反复空转，
     * 每次写都是「整份配置序列化 + 索引全量读改写」。
     */
    @Synchronized
    fun update(groupId: String, block: (GroupDigestConfig) -> GroupDigestConfig): GroupDigestConfig? {
        val current = readConfig(groupId) ?: return null
        val updated = block(current)
        if (updated == current) return updated
        return if (saveConfig(updated)) updated else null
    }

    /**
     * 删除单群：**先改索引、再删文件**。
     *
     * 反过来会在索引里留一个指向空文件的条目，点开会报错；而索引先删时哪怕文件删失败，
     * 也只是多一个孤儿文件，[reconcile] 会补删。
     */
    @Synchronized
    fun deleteConfig(groupId: String): Boolean {
        if (groupId.isEmpty() || groupId.contains('/')) return false
        cache.remove(groupId)
        if (!removeIndexEntry(groupId)) return false
        return AiProfileStore.delete(configFile(groupId))
    }

    fun listGroupIds(): List<String> = runCatching {
        root.toFile().listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".json") && it.name != "index.json" }
            ?.map { it.name.removeSuffix(".json") }
            ?.toList()
            ?: emptyList()
    }.getOrElse {
        log("list groups failed: ${it.javaClass.simpleName}")
        emptyList()
    }

    // ── 索引 ────────────────────────────────────────────────────────────────

    @Synchronized
    fun loadIndex(): GroupIndex {
        cachedIndex?.let { return it }
        val text = AiProfileStore.readText(indexFile)
        if (text == null) {
            return GroupIndex().also { cachedIndex = it }
        }
        val index = runCatching { json.decodeFromString<GroupIndex>(text) }.getOrElse {
            log("index 解析失败，将从单群文件重建：${it.javaClass.simpleName}")
            return rebuildIndex()
        }
        if (index.version < GroupIndex.CURRENT_VERSION) {
            log("index 版本 ${index.version} -> ${GroupIndex.CURRENT_VERSION}，重建索引")
            return rebuildIndex()
        }
        cachedIndex = index
        return index
    }

    @Synchronized
    fun saveIndex(index: GroupIndex): Boolean {
        val text = runCatching { json.encodeToString(index) }.getOrElse {
            log("index 序列化失败：${it.javaClass.simpleName}")
            return false
        }
        if (!AiProfileStore.writeAtomic(indexFile, text)) return false
        cachedIndex = index
        unreadTotalCache = -1
        return true
    }

    /** 按 `groups/` 目录重建索引。索引坏了、换机、手动恢复都走这里。 */
    @Synchronized
    fun rebuildIndex(): GroupIndex {
        val summaries = listGroupIds()
            .mapNotNull { readConfig(it)?.toSummary() }
            .sortedWith(SUMMARY_ORDER)
        val index = GroupIndex(groups = summaries)
        saveIndex(index)
        return index
    }

    @Synchronized
    fun upsertIndexEntry(entry: GroupSummary) {
        val index = loadIndex()
        val existing = index.groups.firstOrNull { it.groupId == entry.groupId }
        // 摘要没变就不重写 index.json：状态轮询、空窗口这类更新大部分字段都没动。
        if (existing == entry) return
        val merged = index.groups.filterNot { it.groupId == entry.groupId } + entry
        saveIndex(GroupIndex(groups = merged.sortedWith(SUMMARY_ORDER)))
    }

    /** 群本来就不在索引里时返回 true：调用方的意图是「删掉」，已经不存在即达成。 */
    @Synchronized
    fun removeIndexEntry(groupId: String): Boolean {
        val index = loadIndex()
        if (index.groups.none { it.groupId == groupId }) return true
        return saveIndex(GroupIndex(groups = index.groups.filterNot { it.groupId == groupId }))
    }

    /**
     * 启动对账：索引与 `groups/` 目录双向补齐。
     *
     * 只在检测到差异时写盘。全量读一遍单群文件在 500 个群时约 1~2 秒，
     * 调用方必须放在 IO 线程，不能阻塞首页。
     */
    @Synchronized
    fun reconcile(): ReconcileResult {
        val before = loadIndex().groups.associateBy { it.groupId }
        val ids = listGroupIds().toSet()
        val rebuilt = ids.mapNotNull { readConfig(it)?.toSummary() }
        val after = rebuilt.associateBy { it.groupId }

        val added = after.keys.count { it !in before }
        val removed = before.keys.count { it !in ids }
        val repaired = after.count { (id, entry) -> before[id] != null && before[id] != entry }

        if (added > 0 || removed > 0 || repaired > 0) {
            saveIndex(GroupIndex(groups = rebuilt.sortedWith(SUMMARY_ORDER)))
            log("对账：新增 $added · 移除 $removed · 修正 $repaired")
        }
        return ReconcileResult(added = added, removed = removed, repaired = repaired)
    }

    // ── 未读 ────────────────────────────────────────────────────────────────

    /** 入口红点用：所有群未读之和，>99 由 UI 显示 `99+`。 */
    fun unreadTotal(): Int {
        val cached = unreadTotalCache
        if (cached >= 0) return cached
        val total = runCatching { loadIndex().groups.sumOf { it.unread } }.getOrDefault(0)
        unreadTotalCache = total
        return total
    }

    /**
     * 打开历史即清零该群未读。
     *
     * 写盘后未读总数缓存置无效，入口下次轮询（400ms）重算；
     * 返回 false 表示本来就没有未读或写盘失败。
     */
    @Synchronized
    fun markRead(groupId: String): Boolean {
        val current = readConfig(groupId) ?: return false
        if (current.digests.none { !it.read }) return false
        return saveConfig(current.markRead())
    }

    data class ReconcileResult(val added: Int, val removed: Int, val repaired: Int) {
        val changed: Boolean get() = added > 0 || removed > 0 || repaired > 0
    }

    // ── 日志 ────────────────────────────────────────────────────────────────

    /** 与 `AiProfileStore` 一致：同时走 WeLogger 与 logcat，模块进程里前者未必写得进目录。 */
    private fun log(message: String) {
        runCatching { WeLogger.w(TAG, message) }
        runCatching { android.util.Log.w("WCX-GroupDigestStore", message) }
    }
}
