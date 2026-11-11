package h.Hchat.hooks.api.sns

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class SnsCachedPostRecord(
    val nativeInfo: Any,
    val values: ContentValues
)

internal data class SnsCachedNativeLookup(
    val querySucceeded: Boolean,
    val nativeInfo: Any?
)

internal class SnsCachedPostStorage(
    private val context: Context,
    private val classLoader: ClassLoader,
    private val dexKitBridge: DexKitBridge,
    private val logger: (String) -> Unit
) {
    private val prefs = DexMethodCache.prefs(context, PREFS_NAME)
    @Volatile private var storageGetter: Method? = null
    @Volatile private var getByIdMethod: Method? = null
    @Volatile private var getByUserMethod: Method? = null
    @Volatile private var getTimelineMethod: Method? = null
    @Volatile private var updateMethod: Method? = null
    @Volatile private var insertMethod: Method? = null
    @Volatile private var deleteMethod: Method? = null

    fun warmup(): Boolean {
        val storage = storageInstance() ?: return false
        val byId = getByIdMethod ?: locateMethod(CACHE_GET_BY_ID, ID_QUERY_ANCHORS) {
            isGetByIdMethod(it, storage.javaClass)
        }?.also { getByIdMethod = it }
        val byUser = getByUserMethod ?: locateMethod(CACHE_GET_BY_USER, USER_QUERY_ANCHORS) {
            isGetByUserMethod(it, storage.javaClass)
        }?.also { getByUserMethod = it }
        val timeline = getTimelineMethod ?: locateMethod(CACHE_GET_TIMELINE, TIMELINE_QUERY_ANCHORS) {
            isGetTimelineMethod(it, storage.javaClass)
        }?.also { getTimelineMethod = it }
        return byId != null && byUser != null && timeline != null
    }

    fun warmupReadWrite(): Boolean {
        val storage = storageInstance() ?: return false
        val byId = getByIdMethod?.takeIf { isGetByIdMethod(it, storage.javaClass) }
            ?: locateMethod(CACHE_GET_BY_ID, ID_QUERY_ANCHORS) {
                isGetByIdMethod(it, storage.javaClass)
            }?.also { getByIdMethod = it }
        val update = updateMethod?.takeIf { isUpdateMethod(it, storage.javaClass) }
            ?: locateMethod(CACHE_UPDATE, UPDATE_ANCHORS) {
                isUpdateMethod(it, storage.javaClass)
            }?.also { updateMethod = it }
        return byId != null && update != null
    }

    fun warmupLocalWrite(): Boolean {
        val storage = storageInstance() ?: return false
        val insert = insertMethod?.takeIf { isInsertMethod(it, storage.javaClass) }
            ?: locateMethod(CACHE_INSERT, INSERT_ANCHORS) { isInsertMethod(it, storage.javaClass) }
                ?.also { insertMethod = it }
        val delete = deleteMethod?.takeIf { isDeleteMethod(it, storage.javaClass) }
            ?: locateMethod(CACHE_DELETE, DELETE_ANCHORS) { isDeleteMethod(it, storage.javaClass) }
                ?.also { deleteMethod = it }
        return insert != null && delete != null && warmupReadWrite()
    }

    fun query(userName: String?, limit: Int, self: Boolean): List<SnsCachedPostRecord> {
        if (limit <= 0) return emptyList()
        val storage = storageInstance() ?: return emptyList()
        if (userName.isNullOrBlank()) return queryTimeline(storage, limit)
        val method = getByUserMethod
            ?.takeIf { isGetByUserMethod(it, storage.javaClass) }
            ?: locateMethod(CACHE_GET_BY_USER, USER_QUERY_ANCHORS) {
                isGetByUserMethod(it, storage.javaClass)
            }?.also { getByUserMethod = it }
            ?: return emptyList()
        val cursor = runCatching {
            KavaReflector.invokeOrThrow(
                method,
                storage,
                false,
                userName.orEmpty().trim(),
                limit.coerceAtMost(MAX_QUERY_LIMIT),
                self,
                "",
                0,
                0
            ) as? Cursor
        }.onFailure {
            logger("读取朋友圈缓存列表失败: ${it.message}")
        }.getOrNull() ?: return emptyList()
        return cursor.use(::readRecords)
    }

    private fun queryTimeline(storage: Any, limit: Int): List<SnsCachedPostRecord> {
        val method = getTimelineMethod
            ?.takeIf { isGetTimelineMethod(it, storage.javaClass) }
            ?: locateMethod(CACHE_GET_TIMELINE, TIMELINE_QUERY_ANCHORS) {
                isGetTimelineMethod(it, storage.javaClass)
            }?.also { getTimelineMethod = it }
            ?: return emptyList()
        val cursor = runCatching {
            KavaReflector.invokeOrThrow(
                method,
                storage,
                "",
                0,
                limit.coerceAtMost(MAX_QUERY_LIMIT)
            ) as? Cursor
        }.onFailure {
            logger("读取朋友圈时间线缓存失败: ${it.message}")
        }.getOrNull() ?: return emptyList()
        return cursor.use(::readRecords)
    }

    fun queryBySnsId(snsId: String): SnsCachedPostRecord? {
        return lookupNativeBySnsId(snsId).nativeInfo?.let(::recordFromNative)
    }

    fun lookupNativeBySnsId(snsId: String): SnsCachedNativeLookup {
        val id = databaseSnsId(snsId) ?: return SnsCachedNativeLookup(false, null)
        val storage = storageInstance() ?: return SnsCachedNativeLookup(false, null)
        val method = getByIdMethod
            ?.takeIf { isGetByIdMethod(it, storage.javaClass) }
            ?: locateMethod(CACHE_GET_BY_ID, ID_QUERY_ANCHORS) {
                isGetByIdMethod(it, storage.javaClass)
            }?.also { getByIdMethod = it }
            ?: return SnsCachedNativeLookup(false, null)
        val lookup = runCatching {
            KavaReflector.invokeOrThrow(method, storage, id)
        }.onFailure {
            logger("按 ID 读取朋友圈缓存失败: ${it.message}")
        }
        return lookup.fold(
            onSuccess = { SnsCachedNativeLookup(true, it) },
            onFailure = { SnsCachedNativeLookup(false, null) }
        )
    }

    fun update(nativeInfo: Any?): Boolean {
        if (nativeInfo == null || nativeInfo.javaClass.name != SnsInteractionLocator.SNS_INFO_CLASS) {
            return false
        }
        val storage = storageInstance() ?: return false
        val method = updateMethod?.takeIf { isUpdateMethod(it, storage.javaClass) }
            ?: locateMethod(CACHE_UPDATE, UPDATE_ANCHORS) {
                isUpdateMethod(it, storage.javaClass)
            }?.also { updateMethod = it }
            ?: return false
        val rawId = KavaReflector.readField(nativeInfo, "field_snsId")
            ?: KavaReflector.readField(nativeInfo, "snsId")
            ?: return false
        val snsId = (rawId as? Number)?.toLong() ?: rawId.toString().toLongOrNull() ?: return false
        return runCatching {
            KavaReflector.invokeOrThrow(method, storage, snsId, nativeInfo) == true
        }.onFailure {
            logger("更新朋友圈缓存失败: ${it.message}")
        }.getOrDefault(false)
    }

    fun insert(nativeInfo: Any?): Boolean {
        if (nativeInfo == null || nativeInfo.javaClass.name != SnsInteractionLocator.SNS_INFO_CLASS) return false
        val storage = storageInstance() ?: return false
        val method = insertMethod?.takeIf { isInsertMethod(it, storage.javaClass) }
            ?: locateMethod(CACHE_INSERT, INSERT_ANCHORS) { isInsertMethod(it, storage.javaClass) }
                ?.also { insertMethod = it }
            ?: return false
        return runCatching {
            val result = KavaReflector.invokeOrThrow(method, storage, nativeInfo)
            // WeChat casts WCDB's long rowId to int and treats only -1 as failure.
            // Large valid rowIds can therefore become negative after truncation.
            (result as? Number)?.toInt()?.let { it != -1 } ?: result == true
        }.onFailure { logger("插入本地朋友圈缓存失败: ${it.message}") }.getOrDefault(false)
    }

    fun delete(snsId: Long): Boolean {
        val storage = storageInstance() ?: return false
        val method = deleteMethod?.takeIf { isDeleteMethod(it, storage.javaClass) }
            ?: locateMethod(CACHE_DELETE, DELETE_ANCHORS) { isDeleteMethod(it, storage.javaClass) }
                ?.also { deleteMethod = it }
            ?: return false
        return runCatching { KavaReflector.invokeOrThrow(method, storage, snsId) == true }
            .onFailure { logger("删除本地朋友圈缓存失败: ${it.message}") }
            .getOrDefault(false)
    }

    private fun storageInstance(): Any? {
        val getter = storageGetter?.takeIf(::isStorageGetter) ?: locateStorageGetter()?.also {
            storageGetter = it
        } ?: return null
        return runCatching { KavaReflector.invokeOrThrow(getter, null) }
            .onFailure { logger("获取朋友圈缓存存储失败: ${it.message}") }
            .getOrNull()
    }

    private fun locateStorageGetter(): Method? {
        val runtimeKey = DexMethodCache.runtimeKey(context, classLoader)
        DexMethodCache.load(prefs, runtimeKey, classLoader, CACHE_STORAGE_GETTER)
            ?.takeIf(::isStorageGetter)
            ?.let { return it }
        val methods = findMethods(STORAGE_GETTER_ANCHORS).filter(::isStorageGetter)
        if (methods.size != 1) {
            DexMethodCache.clear(prefs, runtimeKey, CACHE_STORAGE_GETTER)
            logger("朋友圈缓存存储入口数量异常: ${methods.size}")
            return null
        }
        return methods.single().also {
            DexMethodCache.save(prefs, runtimeKey, CACHE_STORAGE_GETTER, it)
        }
    }

    private fun locateMethod(
        cacheName: String,
        anchors: List<String>,
        validator: (Method) -> Boolean
    ): Method? {
        val runtimeKey = DexMethodCache.runtimeKey(context, classLoader)
        DexMethodCache.load(prefs, runtimeKey, classLoader, cacheName)
            ?.takeIf(validator)
            ?.let { return it }
        val methods = findMethods(anchors).filter(validator)
        if (methods.size != 1) {
            DexMethodCache.clear(prefs, runtimeKey, cacheName)
            logger("朋友圈缓存查询入口数量异常: cache=$cacheName count=${methods.size}")
            return null
        }
        return methods.single().also { DexMethodCache.save(prefs, runtimeKey, cacheName, it) }
    }

    private fun findMethods(anchors: List<String>): List<Method> {
        lateinit var methods: List<Method>
        DexInstallScheduler.runDexKitTask {
            methods = runCatching {
                dexKitBridge.findMethod(
                    FindMethod().apply {
                        matcher(MethodMatcher().apply { usingStrings(anchors) })
                    }
                ).mapNotNull { data ->
                    runCatching { data.getMethodInstance(classLoader) }.getOrNull()
                }.distinctBy { it.toGenericString() }
            }.onFailure {
                logger("定位朋友圈缓存查询入口失败: ${it.message}")
            }.getOrDefault(emptyList())
        }
        return methods
    }

    private fun isStorageGetter(method: Method): Boolean {
        return Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            method.parameterTypes.isEmpty() &&
            !method.returnType.isPrimitive &&
            method.returnType.name.startsWith(SNS_STORAGE_PACKAGE)
    }

    private fun isGetByIdMethod(method: Method, storageClass: Class<*>): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            method.declaringClass.isAssignableFrom(storageClass) &&
            method.returnType.name == SnsInteractionLocator.SNS_INFO_CLASS &&
            types.size == 1 &&
            types[0] == java.lang.Long.TYPE
    }

    private fun isGetByUserMethod(method: Method, storageClass: Class<*>): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            method.declaringClass.isAssignableFrom(storageClass) &&
            Cursor::class.java.isAssignableFrom(method.returnType) &&
            types.contentEquals(
                arrayOf(
                    java.lang.Boolean.TYPE,
                    String::class.java,
                    Integer.TYPE,
                    java.lang.Boolean.TYPE,
                    String::class.java,
                    Integer.TYPE,
                    Integer.TYPE
                )
            )
    }

    private fun isGetTimelineMethod(method: Method, storageClass: Class<*>): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            method.declaringClass.isAssignableFrom(storageClass) &&
            Cursor::class.java.isAssignableFrom(method.returnType) &&
            types.contentEquals(
                arrayOf(
                    String::class.java,
                    Integer.TYPE,
                    Integer.TYPE
                )
            )
    }

    private fun isUpdateMethod(method: Method, storageClass: Class<*>): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            method.declaringClass.isAssignableFrom(storageClass) &&
            (method.returnType == java.lang.Boolean.TYPE || method.returnType == java.lang.Boolean::class.java) &&
            types.size == 2 &&
            types[0] == java.lang.Long.TYPE &&
            types[1].name == SnsInteractionLocator.SNS_INFO_CLASS
    }

    private fun isInsertMethod(method: Method, storageClass: Class<*>): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            method.declaringClass.isAssignableFrom(storageClass) && types.size == 1 &&
            types[0].name == SnsInteractionLocator.SNS_INFO_CLASS &&
            (method.returnType == Integer.TYPE || method.returnType == java.lang.Integer::class.java)
    }

    private fun isDeleteMethod(method: Method, storageClass: Class<*>): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            method.declaringClass.isAssignableFrom(storageClass) && types.contentEquals(arrayOf(java.lang.Long.TYPE)) &&
            (method.returnType == java.lang.Boolean.TYPE || method.returnType == java.lang.Boolean::class.java)
    }

    private fun readRecords(cursor: Cursor): List<SnsCachedPostRecord> {
        val snsInfoClass = KavaReflector.loadClass(SnsInteractionLocator.SNS_INFO_CLASS, classLoader)
            ?: return emptyList()
        val constructor = KavaReflector.findConstructor(snsInfoClass) ?: return emptyList()
        val convert = KavaReflector.findMethodRecursive(snsInfoClass, "convertFrom", Cursor::class.java)
            ?: return emptyList()
        val rowIdIndex = cursor.getColumnIndex("rowid")
        val result = ArrayList<SnsCachedPostRecord>(cursor.count.coerceAtLeast(0))
        while (cursor.moveToNext()) {
            val nativeInfo = KavaReflector.newInstance(constructor) ?: continue
            val record = runCatching {
                KavaReflector.invokeOrThrow(convert, nativeInfo, cursor)
                recordFromNative(nativeInfo, rowIdIndex.takeIf { it >= 0 }?.let(cursor::getLong))
            }.getOrNull() ?: continue
            result.add(record)
        }
        return result
    }

    private fun recordFromNative(nativeInfo: Any, localId: Long? = null): SnsCachedPostRecord? {
        if (nativeInfo.javaClass.name != SnsInteractionLocator.SNS_INFO_CLASS) return null
        val values = (KavaReflector.invokeMethod(nativeInfo, "convertTo") as? ContentValues)
            ?.let(::ContentValues)
            ?: return null
        val resolvedLocalId = localId
            ?: (KavaReflector.invokeMethod(nativeInfo, "getLocalid") as? Number)?.toLong()
            ?: (KavaReflector.readField(nativeInfo, "localid") as? Number)?.toLong()
        if (resolvedLocalId != null) values.put(LOCAL_ID_ALIAS, resolvedLocalId)
        return SnsCachedPostRecord(nativeInfo, values)
    }

    private fun databaseSnsId(raw: String): Long? {
        val value = raw.trim().trim('\'', '"').takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { return it }
        return runCatching { java.lang.Long.parseUnsignedLong(value) }.getOrNull()
    }

    companion object {
        const val LOCAL_ID_ALIAS = "hchatLocalId"
        private const val SNS_STORAGE_PACKAGE = "com.tencent.mm.plugin.sns.storage."
        private const val PREFS_NAME = "Hchat_sns_cached_post_storage_cache"
        private const val CACHE_STORAGE_GETTER = "sns_info_storage_getter_v1"
        private const val CACHE_GET_BY_ID = "sns_info_get_by_id_v1"
        private const val CACHE_GET_BY_USER = "sns_info_get_by_user_v1"
        private const val CACHE_GET_TIMELINE = "sns_info_get_timeline_v1"
        private const val CACHE_UPDATE = "sns_info_update_v1"
        private const val CACHE_INSERT = "sns_info_insert_v1"
        private const val CACHE_DELETE = "sns_info_delete_v1"
        // Improve timeline identity placement needs enough real rows to bracket older custom dates.
        private const val MAX_QUERY_LIMIT = 2000
        private val STORAGE_GETTER_ANCHORS = listOf(
            "getSnsInfoStorage",
            "com.tencent.mm.plugin.sns.model.SnsCore"
        )
        private val ID_QUERY_ANCHORS = listOf(
            "select *,rowid from SnsInfo  where SnsInfo.snsId=",
            " limit 1"
        )
        private val USER_QUERY_ANCHORS = listOf(
            "getCursorByUserName",
            "com.tencent.mm.plugin.sns.storage.SnsInfoStorage"
        )
        private val TIMELINE_QUERY_ANCHORS = listOf(
            "getAdCursorForTimeLine",
            "com.tencent.mm.plugin.sns.storage.SnsInfoStorage",
            " from AdSnsInfo where createTime >",
            " limit "
        )
        private val UPDATE_ANCHORS = listOf(
            "update",
            "com.tencent.mm.plugin.sns.storage.SnsInfoStorage",
            "snsId=?",
            "rowid"
        )
        private val INSERT_ANCHORS = listOf(
            "set",
            "com.tencent.mm.plugin.sns.storage.SnsInfoStorage",
            "SnsInfo"
        )
        private val DELETE_ANCHORS = listOf(
            "delete",
            "com.tencent.mm.plugin.sns.storage.SnsInfoStorage",
            "delete snsId:%s",
            "snsId=?"
        )
    }
}
