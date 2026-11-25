package com.Johnny.wcx.ai

import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * AI 档案的落盘层：路径、原子写、档案与索引的读写。
 *
 * 数据放在 `HostInfo.application.filesDir/ai`，也就是微信进程的私有目录
 * （`/data/data/com.tencent.mm/files/ai`）。
 *
 * **AI 页与 AI 功能同处微信进程** —— 设置页由 `ActivityProxy` 代理到微信进程启动
 * （`WeSettingsInjector.openSettingsDialog`），因此这里不需要任何跨进程通道：
 * 页面直接读档案，功能直接写档案。
 *
 * 写入走「临时文件 + rename」：AI 调用是异步的，读取侧可能在写入过程中进来，
 * 半截 JSON 会让整份档案解析失败。
 */
object AiProfileStore {

    private const val TAG = "AiProfileStore"

    /**
     * `ignoreUnknownKeys` 必须开：档案是「旧版本文件 + 新版本代码」混着用的，
     * 删字段或改名字时不能因此读不出整份档案。
     * `encodeDefaults` 开着是为了让文件里出现 `autoReply: false` 这种显式值，
     * 排查「开关到底是没写还是写了 false」时不用猜。
     * `prettyPrint` 只在 debug 开：缩进能让写盘大 20~30%，500 条记忆的档案每次写都多几十 KB。
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = BuildConfig.DEBUG
    }

    private val privateRoot: Path by lazy {
        HostInfo.application.filesDir.toPath().resolve("ai")
    }

    val root: Path get() = ensure(privateRoot)
    val profilesDir: Path get() = ensure(root / "profiles")

    /** 好友摘要数组（AI 页列表只读这个）。 */
    val indexFile: Path get() = root / "index.json"

    /** 微信进程导出的好友快照，含 `pyInitial` / `quanPin`，供 A-Z 分组。 */
    val friendsFile: Path get() = root / "friends.json"

    fun profileFile(wxId: String): Path = profilesDir / "$wxId.json"

    /**
     * 进程内档案缓存，最多 [CACHE_SIZE] 份。
     *
     * 读档案的人要的往往只是两个开关（自动回复的每条落库消息、长按菜单、设置页轮询），
     * 而一份 500 条记忆的档案约 150KB / 250KB 内存 —— 每条消息都读盘 + 全量解析纯属浪费。
     *
     * 失效只在写路径做：档案只有本对象能写（`saveProfile` / `deleteProfile`），
     * 且设置页与功能同处微信进程，所以不需要按 mtime 校验。
     */
    private const val CACHE_SIZE = 4

    private val cache = object : LinkedHashMap<String, AiProfile>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AiProfile>?): Boolean =
            size > CACHE_SIZE
    }

    /**
     * 开着自动回复的会话集合，**只查内存**。
     *
     * 消息落库是全局 hook：每条消息都会进来，而绝大多数会话没有开自动回复。原来为了拿
     * `autoReply` 这个布尔值，每条文字消息都要 `readProfile`（未命中 [cache] 时读盘 + 解析
     * 整份档案）。这里把开关复制进索引，启动时预热一次，之后每条消息只做一次 Set 查找。
     *
     * 失效只在写路径（[saveProfile] / [deleteProfile] / [rebuildIndex]），与 [cache] 同源。
     */
    @Volatile
    private var autoReplySet: Set<String>? = null

    /** 集合是否已经建好。没建好时 [isAutoReplyOn] 退回读档案，宁可慢一次也不漏回。 */
    @Volatile
    private var autoReplyReady = false

    // ── 档案 ────────────────────────────────────────────────────────────────

    @Synchronized
    fun readProfile(wxId: String): AiProfile? {
        cache[wxId]?.let { return it }
        val text = readText(profileFile(wxId)) ?: return null
        val profile = runCatching { json.decodeFromString<AiProfile>(text) }
            .getOrElse {
                // 解析失败不能让调用方以为「没有档案」，否则会覆盖掉还能修好的数据。
                log("profile 解析失败：$wxId：${it.javaClass.simpleName}: ${it.message}")
                return null
            }
        cache[wxId] = profile
        return profile
    }

    /**
     * 该会话是否开着自动回复（且档案已就绪）。**正常只查内存；预热未完成时回落读一次档案。**
     *
     * [cache] 命中时直接用档案里的值（最新）；未命中时查 [autoReplySet]，它只是索引里
     * `autoReply = true` 的那几条，启动后加载一次。消息落库的每条消息都会调它，
     * 所以预热完成后这里不碰文件。
     */
    fun isAutoReplyOn(wxId: String): Boolean {
        cache[wxId]?.let { return it.isReady && it.autoReply }
        // 预热还没完成：这条消息按旧路径读一次档案。反过来（先返回 false）会把
        // 升级后、预热完成前的消息整段漏掉。
        if (!autoReplyReady) return readProfile(wxId)?.let { it.isReady && it.autoReply } == true
        return wxId in (autoReplySet ?: emptySet())
    }

    /**
     * 启动后调一次（后台线程）：把开关集合建好。
     *
     * 旧索引没有 `autoReply` 字段，第一次会触发一次 [rebuildIndex]（读全部档案），
     * 所以**不能在消息线程上做**。
     */
    fun warmAutoReplySet() {
        if (autoReplyReady) return
        loadAutoReplySet()
    }

    @Synchronized
    private fun loadAutoReplySet(): Set<String> {
        autoReplySet?.let {
            autoReplyReady = true
            return it
        }
        val set = loadIndex().profiles
            .asSequence()
            .filter { it.state == AiProfileState.READY && it.autoReply }
            .mapTo(HashSet()) { it.wxId }
        autoReplySet = set
        autoReplyReady = true
        return set
    }

    /**
     * 写入档案并同步索引条目。
     *
     * 先写档案、再写索引：中途失败时索引少一条（列表看不到），比索引有但档案是空的好修 ——
     * `rebuildIndex()` 能按目录重建索引，反过来无法恢复档案内容。
     */
    @Synchronized
    fun saveProfile(profile: AiProfile): Boolean {
        val text = runCatching { json.encodeToString(profile) }.getOrElse {
            log("profile 序列化失败：${profile.wxId}：${it.javaClass.simpleName}")
            return false
        }
        if (!writeAtomic(profileFile(profile.wxId), text)) return false
        cache[profile.wxId] = profile
        upsertIndexEntry(profile.toSummary())
        // 与索引同步：开关是在设置页改的，改完下一条消息就要生效。
        autoReplySet = autoReplySet?.let { set ->
            if (profile.isReady && profile.autoReply) set + profile.wxId else set - profile.wxId
        }
        return true
    }

    /**
     * 读-改-写。用于开关、聊天目标这类只改几个字段的操作。
     *
     * 返回改后的档案；档案不存在时返回 null（不凭空创建，否则一个手滑点开弹窗
     * 就会给陌生人建一份空档案）。
     *
     * **内容没变就不落盘**：写给「清理无日期记忆」「保存未改动的关系目标」这类可能空转的
     * 调用方用。每次写都是「档案全量序列化 + 索引全量读改写」，一次空转等于白写两份文件。
     */
    @Synchronized
    fun patchProfile(wxId: String, block: (AiProfile) -> AiProfile): AiProfile? {
        val current = readProfile(wxId) ?: return null
        val updated = block(current)
        if (updated == current) return updated
        return if (saveProfile(updated)) updated else null
    }

    /** 删除档案。标签与记忆随文件一起消失 —— 这是全模块唯一的清除入口。 */
    @Synchronized
    fun deleteProfile(wxId: String): Boolean {
        cache.remove(wxId)
        autoReplySet = autoReplySet?.minus(wxId)
        val removed = delete(profileFile(wxId))
        removeIndexEntry(wxId)
        return removed
    }

    fun listProfileIds(): List<String> = runCatching {
        profilesDir.toFile().listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }
            ?: emptyList()
    }.getOrElse {
        log("list profiles failed: ${it.javaClass.simpleName}")
        emptyList()
    }

    // ── 索引 ────────────────────────────────────────────────────────────────

    fun loadIndex(): AiProfileIndex {
        val text = readText(indexFile) ?: return AiProfileIndex()
        val index = runCatching { json.decodeFromString<AiProfileIndex>(text) }
            .getOrElse {
                log("index 解析失败，将从档案目录重建：${it.javaClass.simpleName}")
                return rebuildIndex()
            }
        if (index.version < AiProfileIndex.CURRENT_VERSION) {
            // 旧索引没有 autoReply 字段，读出来全是 false —— 拿它判断开关会让自动回复
            // 全部失效。重建一次补上真实值，之后写出的都是新版本。
            log("index 版本 ${index.version} -> ${AiProfileIndex.CURRENT_VERSION}，重建索引")
            return rebuildIndex()
        }
        return index
    }

    /**
     * 按 `profiles/` 目录重建索引。
     *
     * 用于两种情况：索引文件坏了；或者档案是外部拷进来的（换机、手动恢复）。
     */
    @Synchronized
    fun rebuildIndex(): AiProfileIndex {
        val summaries = listProfileIds().mapNotNull { id -> readProfile(id)?.toSummary() }
            .sortedWith(compareBy({ it.pyInitial.ifBlank { AiLetterIndex.OTHER } }, { it.sortKey }))
        val index = AiProfileIndex(profiles = summaries)
        saveIndex(index)
        // 索引是开关集合的来源，重建后直接用它刷新，不必等下一次预热。
        autoReplySet = summaries.asSequence()
            .filter { it.state == AiProfileState.READY && it.autoReply }
            .mapTo(HashSet()) { it.wxId }
        autoReplyReady = true
        return index
    }

    @Synchronized
    fun saveIndex(index: AiProfileIndex): Boolean {
        val text = runCatching { json.encodeToString(index) }.getOrElse {
            log("index 序列化失败：${it.javaClass.simpleName}")
            return false
        }
        return writeAtomic(indexFile, text)
    }

    @Synchronized
    fun upsertIndexEntry(entry: AiProfileSummary) {
        val current = loadIndex().profiles
        val existing = current.firstOrNull { it.wxId == entry.wxId }
        // 摘要没变就不重写 index.json：初始化每片要写 2~3 次档案（进度 + 工具写入），
        // 其中大部分条目的 tagCount / memoryCount / progress 都没变，
        // 每次都序列化并落盘一份 300 条的列表纯属浪费。
        if (existing == entry) return
        val merged = current.filterNot { it.wxId == entry.wxId } + entry
        saveIndex(AiProfileIndex(profiles = merged.sortedWith(SUMMARY_ORDER)))
    }

    @Synchronized
    fun removeIndexEntry(wxId: String) {
        val current = loadIndex().profiles
        if (current.none { it.wxId == wxId }) return
        saveIndex(AiProfileIndex(profiles = current.filterNot { it.wxId == wxId }))
    }

    private val SUMMARY_ORDER =
        compareBy<AiProfileSummary>({ it.pyInitial.ifBlank { AiLetterIndex.OTHER } }, { it.sortKey })

    // ── 好友快照 ────────────────────────────────────────────────────────────

    fun loadFriends(): AiFriendSnapshot {
        val text = readText(friendsFile) ?: return AiFriendSnapshot()
        return runCatching { json.decodeFromString<AiFriendSnapshot>(text) }
            .getOrElse {
                log("friends 解析失败：${it.javaClass.simpleName}")
                AiFriendSnapshot()
            }
    }

    fun saveFriends(friends: List<AiFriend>): Boolean {
        val sorted = friends.sortedWith(compareBy({ it.groupKey }, { it.sortKey }))
        val snapshot = AiFriendSnapshot(
            exportedAt = System.currentTimeMillis(),
            friends = sorted,
        )
        val text = runCatching { json.encodeToString(snapshot) }.getOrElse {
            log("friends 序列化失败：${it.javaClass.simpleName}")
            return false
        }
        return writeAtomic(friendsFile, text)
    }

    // ── 底层 IO ─────────────────────────────────────────────────────────────

    fun readText(file: Path): String? = runCatching {
        if (file.exists()) file.readText() else null
    }.getOrElse {
        log("read failed: ${file.fileName}: ${it.javaClass.simpleName}")
        null
    }

    /**
     * 原子写。失败返回 false 而不是抛异常 —— 调用方（例如 AI 页保存开关）需要把失败
     * 反映成一次可回滚的 UI 操作，不希望整页崩掉。
     */
    fun writeAtomic(file: Path, text: String): Boolean = runCatching {
        file.parent?.createDirectories()
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        tmp.writeText(text)
        // 先试原子替换；外部存储（sdcardfs / FUSE）不一定支持 ATOMIC_MOVE，
        // 不支持时退化成普通替换 —— 同目录同文件系统的 rename 在实践上仍然是原子的。
        runCatching {
            Files.move(
                tmp,
                file,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
        true
    }.getOrElse {
        log("write failed: ${file.fileName}: ${it.javaClass.simpleName}: ${it.message}")
        false
    }

    fun delete(file: Path): Boolean = runCatching { Files.deleteIfExists(file) }
        .getOrElse {
            log("delete failed: ${file.fileName}: ${it.javaClass.simpleName}")
            false
        }

    private fun ensure(path: Path): Path = path.also {
        runCatching { it.createDirectories() }
    }

    /** 探针 / IO 失败的日志同时走 WeLogger 与 logcat：模块进程里 WeLogger 未必写得进目录。 */
    private fun log(message: String) {
        runCatching { WeLogger.w(TAG, message) }
        runCatching { android.util.Log.w("WCX-AiProfileStore", message) }
    }
}

/** 档案 → 索引条目。索引只保留列表要显示的字段，不复制标签与记忆。 */
fun AiProfile.toSummary(): AiProfileSummary = AiProfileSummary(
    wxId = wxId,
    nickname = nickname,
    remark = remark,
    pyInitial = AiLetterIndex.groupOf(pyInitial.ifBlank { nickname }),
    sortKey = quanPin.ifBlank { nickname.lowercase() },
    avatarUrl = avatarUrl,
    state = state,
    autoReply = isReady && autoReply,
    auditing = auditing,
    progress = progress,
    tagCount = tags.size,
    memoryCount = memories.size,
    lastError = lastError,
)
