package com.Johnny.wcx.dynamic

import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 云端轻量特征库 — 自动增量更新识别规则，无需更新模块安装包。
 *
 * 云端仅同步微信新版特征识别规则（不用更新模块安装包），
 * 模块每次联网自动拉取最新识别模板，提前适配还未发布的微信版本改动。
 *
 * 缓存策略：
 * - 本地缓存已下载的特征规则
 * - 每次启动检查云端更新
 * - 增量更新，仅下载变更部分
 */
object CloudFeatureDB {

    private const val TAG = "CloudFeatureDB"
    private const val CLOUD_URL = "https://wcx-features.example.com/api/v1/features"
    private const val CACHE_FILE = "cloud_features.json"
    private const val CACHE_META = "cloud_features_meta.json"
    private const val UPDATE_INTERVAL_MS = 3_600_000L // 1小时

    data class CloudClassFeature(
        val id: String,
        val version: String,
        val className: String? = null,
        val superClass: String? = null,
        val methodMappings: Map<String, CloudMethodMapping> = emptyMap(),
        val fieldMappings: Map<String, CloudFieldMapping> = emptyMap(),
        val updatedAt: Long = 0
    )

    data class CloudMethodMapping(
        val methodName: String,
        val methodSign: String
    )

    data class CloudFieldMapping(
        val fieldName: String,
        val typeName: String
    )

    // 内存缓存
    private val features = ConcurrentHashMap<String, CloudClassFeature>()
    private var lastUpdateTime = 0L
    private var currentWeChatVersion = ""

    /**
     * 初始化云端特征库，加载本地缓存。
     */
    fun init(weChatVersion: String) {
        currentWeChatVersion = weChatVersion
        loadLocalCache()
        checkForUpdates()
    }

    /**
     * 获取指定 id 的特征。
     */
    fun getFeature(id: String): CloudClassFeature? {
        return features[id]
    }

    /**
     * 获取指定 id 的方法映射。
     */
    fun getMethodMapping(featureId: String, methodId: String): CloudMethodMapping? {
        return features[featureId]?.methodMappings?.get(methodId)
    }

    /**
     * 获取指定 id 的字段映射。
     */
    fun getFieldMapping(featureId: String, fieldId: String): CloudFieldMapping? {
        return features[featureId]?.fieldMappings?.get(fieldId)
    }

    /**
     * 检查云端更新。
     */
    fun checkForUpdates(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < UPDATE_INTERVAL_MS && features.isNotEmpty()) {
            WeLogger.d(TAG, "skip update check, last update was ${(now - lastUpdateTime) / 1000}s ago")
            return false
        }

        return try {
            fetchFromCloud()
        } catch (e: Exception) {
            WeLogger.w(TAG, "cloud feature update failed: ${e.message}")
            false
        }
    }

    /**
     * 强制从云端拉取最新特征库。
     */
    fun forceUpdate(): Boolean {
        return try {
            fetchFromCloud()
        } catch (e: Exception) {
            WeLogger.e(TAG, "force update failed", e)
            false
        }
    }

    // -----------------------------------------------------------------------
    // 本地缓存
    // -----------------------------------------------------------------------

    private fun loadLocalCache() {
        try {
            val cacheFile = KnownPaths.moduleData.resolve(CACHE_FILE)
            if (!cacheFile.exists()) {
                WeLogger.i(TAG, "no local cache found")
                return
            }

            val json = JSONObject(cacheFile.readText())
            val featuresArray = json.getJSONArray("features")
            features.clear()
            for (i in 0 until featuresArray.length()) {
                val feature = parseFeature(featuresArray.getJSONObject(i))
                features[feature.id] = feature
            }

            val metaFile = KnownPaths.moduleData.resolve(CACHE_META)
            if (metaFile.exists()) {
                val meta = JSONObject(metaFile.readText())
                lastUpdateTime = meta.optLong("lastUpdateTime", 0)
            }

            WeLogger.i(TAG, "loaded ${features.size} features from local cache")
        } catch (e: Exception) {
            WeLogger.w(TAG, "failed to load local cache: ${e.message}")
        }
    }

    private fun saveLocalCache() {
        try {
            val json = JSONObject()
            val featuresArray = JSONArray()
            features.values.forEach { feature ->
                featuresArray.put(serializeFeature(feature))
            }
            json.put("features", featuresArray)
            json.put("weChatVersion", currentWeChatVersion)

            KnownPaths.moduleData.resolve(CACHE_FILE).writeText(json.toString(2))

            val meta = JSONObject()
            meta.put("lastUpdateTime", lastUpdateTime)
            KnownPaths.moduleData.resolve(CACHE_META).writeText(meta.toString(2))

            WeLogger.d(TAG, "saved ${features.size} features to local cache")
        } catch (e: Exception) {
            WeLogger.w(TAG, "failed to save local cache: ${e.message}")
        }
    }

    // -----------------------------------------------------------------------
    // 云端拉取
    // -----------------------------------------------------------------------

    private fun fetchFromCloud(): Boolean {
        try {
            val url = URL("$CLOUD_URL?wechat_version=${HostInfo.versionName}&module_version=${HostInfo.versionName}")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                WeLogger.w(TAG, "cloud returned HTTP $responseCode")
                return false
            }

            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val json = JSONObject(response)
            parseAndMergeFeatures(json)

            lastUpdateTime = System.currentTimeMillis()
            saveLocalCache()

            WeLogger.i(TAG, "cloud update successful, ${features.size} features loaded")
            return true
        } catch (e: Exception) {
            throw e
        }
    }

    private fun parseAndMergeFeatures(json: JSONObject) {
        val featuresArray = json.getJSONArray("features")
        for (i in 0 until featuresArray.length()) {
            val featureJson = featuresArray.getJSONObject(i)
            val feature = parseFeature(featureJson)

            // 增量更新：仅更新版本号更新的特征
            val existing = features[feature.id]
            if (existing == null || feature.updatedAt > existing.updatedAt) {
                features[feature.id] = feature
                WeLogger.d(TAG, "updated feature: ${feature.id} (v${feature.version})")
            }
        }
    }

    private fun parseFeature(json: JSONObject): CloudClassFeature {
        val methodMappings = mutableMapOf<String, CloudMethodMapping>()
        json.optJSONObject("methodMappings")?.let { mm ->
            for (key in mm.keys()) {
                val m = mm.getJSONObject(key)
                methodMappings[key] = CloudMethodMapping(
                    methodName = m.getString("methodName"),
                    methodSign = m.getString("methodSign")
                )
            }
        }

        val fieldMappings = mutableMapOf<String, CloudFieldMapping>()
        json.optJSONObject("fieldMappings")?.let { fm ->
            for (key in fm.keys()) {
                val f = fm.getJSONObject(key)
                fieldMappings[key] = CloudFieldMapping(
                    fieldName = f.getString("fieldName"),
                    typeName = f.getString("typeName")
                )
            }
        }

        return CloudClassFeature(
            id = json.getString("id"),
            version = json.optString("version", "1.0"),
            className = json.optString("className", null),
            superClass = json.optString("superClass", null),
            methodMappings = methodMappings,
            fieldMappings = fieldMappings,
            updatedAt = json.optLong("updatedAt", 0)
        )
    }

    private fun serializeFeature(feature: CloudClassFeature): JSONObject {
        return JSONObject().apply {
            put("id", feature.id)
            put("version", feature.version)
            feature.className?.let { put("className", it) }
            feature.superClass?.let { put("superClass", it) }
            put("methodMappings", JSONObject().apply {
                feature.methodMappings.forEach { (k, v) ->
                    put(k, JSONObject().apply {
                        put("methodName", v.methodName)
                        put("methodSign", v.methodSign)
                    })
                }
            })
            put("fieldMappings", JSONObject().apply {
                feature.fieldMappings.forEach { (k, v) ->
                    put(k, JSONObject().apply {
                        put("fieldName", v.fieldName)
                        put("typeName", v.typeName)
                    })
                }
            })
            put("updatedAt", feature.updatedAt)
        }
    }
}