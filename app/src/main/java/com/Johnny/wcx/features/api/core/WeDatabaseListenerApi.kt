package com.Johnny.wcx.features.api.core

import android.annotation.SuppressLint
import android.content.ContentValues
import com.tencent.wcdb.database.SQLiteDatabase
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.constants.WeChatVersions
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

@SuppressLint("DiscouragedApi")
@Feature(name = "数据库监听服务", categories = ["API"], description = "提供数据库插入、更新与查询监听能力")
object WeDatabaseListenerApi : ApiFeature() {

    fun interface IInsertListener {
        fun onInsert(table: String, values: ContentValues)
    }

    fun interface IUpdateListener {
        fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int)
    }

    fun interface IQueryListener {
        fun onQuery(sql: String): String?
    }

    /** 插入过滤器：返回 false 表示阻止本次插入（result 置为 -1）。 */
    fun interface IInsertFilter {
        fun allowInsert(table: String, values: ContentValues): Boolean
    }

    private const val TAG = "WeDatabaseListenerApi"

    private val insertListeners = CopyOnWriteArrayList<IInsertListener>()
    private val updateListeners = CopyOnWriteArrayList<IUpdateListener>()
    private val queryListeners = CopyOnWriteArrayList<IQueryListener>()
    private val insertFilters = CopyOnWriteArrayList<IInsertFilter>()

    /**
     * 最近一次 `insertWithOnConflict` 的数据库实例（insert hook 时捕获）。
     * 供需要写库的调用方复用（如密友会话行重建时取 wcdb SQLiteDatabase 实例）。
     */
    @Volatile
    var lastInsertDb: SQLiteDatabase? = null
        private set

    fun addListener(listener: Any) {
        if (listener is IInsertListener) {
            insertListeners.add(listener)
        }
        if (listener is IUpdateListener) {
            updateListeners.add(listener)
        }
        if (listener is IQueryListener) {
            queryListeners.add(listener)
        }
        if (listener is IInsertFilter) {
            insertFilters.add(listener)
        }
    }

    fun removeListener(listener: Any) {
        if (listener is IInsertListener) {
            insertListeners.remove(listener)
        }
        if (listener is IUpdateListener) {
            updateListeners.remove(listener)
        }
        if (listener is IQueryListener) {
            queryListeners.remove(listener)
        }
        if (listener is IInsertFilter) {
            insertFilters.remove(listener)
        }
    }

    override fun onEnable() {
        hookDatabaseInsert()
        hookDatabaseUpdate()
        hookDatabaseQuery()
    }

    override fun onDisable() {
        insertListeners.clear()
        updateListeners.clear()
        insertFilters.clear()
        queryListeners.clear()
    }

    // ==================== 私有辅助方法 ====================

    private fun formatArgs(args: Array<out Any?>): String {
        return args.mapIndexed { index, arg ->
            "arg[$index](${arg?.javaClass?.simpleName ?: "null"})=$arg"
        }.joinToString(", ")
    }

    private fun logWithStack(
        methodName: String,
        table: String,
        args: Array<out Any?>,
        result: Any? = null
    ) {
        if (!Preferences.verboseLog) return

        val argsInfo = formatArgs(args)
        val resultStr = if (result != null) ", result=$result" else ""
        val stackStr = ", stack=${WeLogger.currentStackTrace}"

        WeLogger.logChunkedD(TAG, "[$methodName] table=$table$resultStr, args=[$argsInfo]$stackStr")
    }

    // ==================== Insert Hook ====================

    private fun hookDatabaseInsert() {
        val insertMethod = SQLiteDatabase::class.reflekt()
            .firstMethod {
                name = "insertWithOnConflict"
                parameters(String::class, String::class, ContentValues::class, Int::class)
            }
        // before：允许 IInsertFilter 阻止插入
        insertMethod.hookBefore {
            try {
                if (insertFilters.isEmpty()) return@hookBefore

                val table = args[0] as String
                val values = args[2] as ContentValues

                for (filter in insertFilters) {
                    if (!filter.allowInsert(table, values)) {
                        result = -1L
                        WeLogger.i(TAG, "insert blocked by filter: table=$table")
                        break
                    }
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "Insert filter dispatch failed", e)
            }
        }
        // after：原逻辑（记录 db 实例 + 通知插入监听）
        insertMethod.hookAfter {
            lastInsertDb = thisObject as? SQLiteDatabase
            try {
                if (insertListeners.isEmpty()) return@hookAfter

                val table = args[0] as String
                val values = args[2] as ContentValues

                logWithStack("Insert", table, args, result)
                insertListeners.forEach { it.onInsert(table, values) }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "Insert dispatch failed", e)
            }
        }
    }

    // ==================== Update Hook ====================

    private fun hookDatabaseUpdate() {
        listOf(
            "com.tencent.wcdb.compat.SQLiteDatabase", "com.tencent.wcdb.database.SQLiteDatabase"
        ).forEach { className ->
            className.toClass().reflekt()
                .firstMethod {
                    name = "updateWithOnConflict"
                    parameters(
                        String::class,
                        ContentValues::class,
                        String::class,
                        Array<String>::class,
                        Int::class
                    )
                }
                .hookBefore {
                    try {
                        if (updateListeners.isEmpty()) return@hookBefore

                        val table = args[0] as String
                        val values = args[1] as ContentValues
                        val whereClause = args[2] as String?

                        @Suppress("UNCHECKED_CAST")
                        val whereArgs = args[3] as Array<String>?
                        val conflictAlgorithm = args[4] as Int

                        logWithStack("Update", table, args)

                        updateListeners.forEach { it.onUpdate(table, values, whereClause, whereArgs, conflictAlgorithm) }
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "update dispatch failed", e)
                    }
                }
        }
    }

    // ==================== Query Hook ====================

    private fun hookDatabaseQuery() {
        val isPlay = HostInfo.isHostGooglePlay
        val version = HostInfo.versionCode
        val isNewVersion = !isPlay && version >= WeChatVersions.MM_8_0_43 || isPlay && version >= WeChatVersions.MM_8_0_48_PLAY

        if (isNewVersion) {
            hookNewQueryMethod()
        } else {
            hookOldQueryMethod()
        }
    }

    private fun hookNewQueryMethod() {
        com.tencent.wcdb.compat.SQLiteDatabase::class.reflekt()
            .firstMethod {
                name = "rawQuery"
                parameters(String::class, Array<Any>::class)
            }
            .hookBefore {
                try {
                    if (queryListeners.isEmpty()) return@hookBefore

                    val sql = args[0] as? String ?: return@hookBefore
                    var currentSql = sql

                    logWithStack("rawQuery", "N/A", args)

                    queryListeners.forEach { listener ->
                        listener.onQuery(currentSql)?.let { currentSql = it }
                    }

                    if (currentSql != sql) {
                        args[0] = currentSql
                        if (Preferences.verboseLog)
                            WeLogger.d(
                                TAG,
                                "[rawQuery] SQL modified: $sql -> $currentSql, stack=${WeLogger.currentStackTrace}"
                            )
                    }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "New version query dispatch failed", e)
                }
            }
    }

    private fun hookOldQueryMethod() {
        SQLiteDatabase::class.reflekt().firstMethod {
            name = "rawQueryWithFactory"
            parameterCount = 5
        }.hookBefore {
            try {
                if (queryListeners.isEmpty()) return@hookBefore

                val sql = args[1] as? String ?: return@hookBefore
                var currentSql = sql

                logWithStack(
                    "rawQueryWithFactory",
                    args[3] as? String ?: "N/A",
                    args
                )

                queryListeners.forEach { listener ->
                    listener.onQuery(currentSql)?.let { currentSql = it }
                }

                if (currentSql != sql) {
                    args[1] = currentSql
                    if (Preferences.verboseLog)
                        WeLogger.d(
                            TAG,
                            "[rawQueryWithFactory] SQL modified: $sql -> $currentSql, stack=${WeLogger.currentStackTrace}"
                        )
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "Old version query dispatch failed", e)
            }
        }
    }
}
