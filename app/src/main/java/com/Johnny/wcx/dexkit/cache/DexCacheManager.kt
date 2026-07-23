package com.Johnny.wcx.dexkit.cache

import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.features.core.BaseFeature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.fs.createDirsSafe
import com.Johnny.wcx.utils.unreachable
import org.json.JSONObject
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Dex 缓存管理器
 * 负责管理 Dex 查找结果的缓存，支持版本控制和增量更新。
 *
 * 缓存的 key → value 由各 [com.Johnny.wcx.dexkit.dsl.BaseDexDelegate] 直接提供
 */
object DexCacheManager {

    private const val TAG = "DexCacheManager"

    private const val CACHE_DIR_NAME = "dex_cache"
    private const val CACHE_FILE_SUFFIX = ".json"
    private const val KEY_HOST_VERSION = "host_version"

    private val cacheDir: Path by lazy {
        (KnownPaths.moduleData / CACHE_DIR_NAME).createDirsSafe()
    }

    fun init(currentVer: String) {
        val cachedVer = WePrefs.getString(KEY_HOST_VERSION)
        if (cachedVer != currentVer) {
            WeLogger.i(TAG, "host version changed: $cachedVer -> $currentVer, resetting all cache")
            clearAllCache()
            Preferences.noDexResolve = false
            WeLogger.i(TAG, "disabling NO_DEX_RESOLVE due to host version change")
        }

        WePrefs.putString(KEY_HOST_VERSION, currentVer)
    }

    /**
     * 检查 Feature 的缓存是否完整有效。
     *
     * 有效条件：
     * 1. 缓存文件存在
     * 2. methodHash 匹配（检测代码变化）
     * 3. [item] 的每个委托 key 都有非空值
     */
    fun isItemCacheValid(item: IResolveDex): Boolean {
        if (item !is BaseFeature) unreachable()

        val cacheFile = getCacheFile(item.name)
        if (!cacheFile.exists()) {
            WeLogger.d(TAG, "cache not found for ${item.name}")
            return false
        }

        return try {
            val json = JSONObject(cacheFile.readText())

            val cachedHash = json.optString("methodHash", "")
            val currentHash = calculateMethodHash(item)
            if (cachedHash != currentHash) {
                WeLogger.d(TAG, "resolveDex of ${item.displayName} changed: cached=$cachedHash, current=$currentHash")
                return false
            }

            // 每个委托对应一个 key，全部必须存在且非空
            val missingOrEmpty = item.dexDelegates.filter { delegate ->
                val v = json.optString(delegate.key, "")
                v.isEmpty() || v == "null"
            }

            if (missingOrEmpty.isNotEmpty()) {
                WeLogger.d(TAG, "cache incomplete for ${item.displayName}, missing keys: ${missingOrEmpty.map { it.key }}")
                return false
            }

            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to read cache for: ${item.displayName}", e)
            false
        }
    }

    /**
     * 将 [item] 所有委托的当前描述符持久化到缓存文件。
     * 数据来自 [IResolveDex.collectDescriptors]。
     */
    fun saveItemCache(item: IResolveDex) {
        if (item !is BaseFeature) {
            error("item is not BaseFeature")
        }

        val cacheFile = getCacheFile(item.name)
        try {
            val json = JSONObject()
            json.put("methodHash", calculateMethodHash(item))
            json.put("timestamp", System.currentTimeMillis())

            item.collectDescriptors().forEach { (key, value) ->
                json.put(key, value)
            }

            cacheFile.writeText(json.toString(2))
            WeLogger.d(TAG, "cache saved for: ${item.displayName}")
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to save cache for: ${item.displayName}", e)
        }
    }

    /**
     * 从缓存文件加载原始 Map（不包含元数据 key）。
     * 由 [IResolveDex.loadFromCache] 消费，后者负责逐委托分发。
     */
    fun loadItemCache(item: IResolveDex): Map<String, Any>? {
        if (item !is BaseFeature) {
            error("item is not BaseFeature")
        }

        val cacheFile = getCacheFile(item.name)
        if (!cacheFile.exists()) return null

        return try {
            val json = JSONObject(cacheFile.readText())
            buildMap {
                for (key in json.keys()) {
                    if (key !in META_KEYS) put(key, json.get(key))
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to load cache for: ${item.displayName}", e)
            null
        }
    }

    fun deleteCache(path: String) {
        getCacheFile(path).deleteIfExists()
    }

    fun clearAllCache() {
        cacheDir.listDirectoryEntries().forEach { path ->
            path.deleteIfExists()
        }
        WeLogger.i(TAG, "all cache cleared")
    }

    fun getOutdatedItems(items: List<IResolveDex>): List<IResolveDex> =
        items.filter { !isItemCacheValid(it) }

    // ---------------------------------------------------------------------------

    private val META_KEYS = setOf("methodHash", "timestamp")

    private fun getCacheFile(path: String): Path =
        cacheDir / (path.replace("/", "_") + CACHE_FILE_SUFFIX)

    /**
     * 获取 resolveDex 方法编译时生成的哈希，用于检测实现变化。
     */
    private fun calculateMethodHash(item: IResolveDex): String {
        val className = item.javaClass.name
        val hash = GeneratedMethodHashes.HASHES[className]
        if (hash.isNullOrBlank())
            error("failed to retrieve method hash for item $className; this shouldn't happen")
        return hash
    }
}
