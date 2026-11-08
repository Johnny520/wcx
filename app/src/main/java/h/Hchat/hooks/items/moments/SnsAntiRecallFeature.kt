package h.Hchat.hooks.items.moments

import android.content.ContentValues
import android.content.SharedPreferences
import android.database.Cursor
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.momentsfake.MomentsFakeInteractionNodeIdentity
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap

class SnsAntiRecallFeature : BaseFeature() {
    private var hooker: SnsAntiRecallHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "朋友圈防撤回"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(SnsAntiRecallSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        hooker = SnsAntiRecallHooker(context).also { it.install() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        hooker = null
    }

    companion object {
        const val ID = "sns_anti_recall"
    }
}

private class SnsAntiRecallHooker(
    private val context: FeatureContext
) {
    private val prefs: SharedPreferences =
        HchatStorage.preferences(context.hostContext(), SnsAntiRecallSettings.PREFS_NAME)
    private val methodPrefs: SharedPreferences =
        DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private val internalQuery = ThreadLocal.withInitial { false }

    fun install(): Boolean {
        var count = 0
        val wrapperClass = context.dexFinder().sqliteDbWrapperClass
        var current: Class<*>? = wrapperClass
        while (current != null && current != Any::class.java) {
            count += hookDatabaseClass(current)
            current = current.superclass
        }
        count += hookNamedDatabaseClass("com.tencent.wcdb.database.SQLiteDatabase")
        count += hookNamedDatabaseClass("com.tencent.wcdb.compat.SQLiteDatabase")
        count += hookNamedDatabaseClass("android.database.sqlite.SQLiteDatabase")
        count += hookLegacySnsUserPageSwitch()
        return count > 0
    }

    private fun hookNamedDatabaseClass(className: String): Int {
        return try {
            hookDatabaseClass(KavaReflector.loadClass(className, context.hostClassLoader()))
        } catch (_: Throwable) {
            0
        }
    }

    private fun hookDatabaseClass(dbClass: Class<*>?): Int {
        if (dbClass == null) return 0
        var count = 0
        for (method in KavaReflector.declaredMethods(dbClass)) {
            if (!isSnsWriteMethod(method) && !isSnsRawQueryMethod(method)) continue
            if (!hookedMethods.add(method)) continue
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (isSnsWriteMethod(method)) {
                            handleSnsWrite(param.thisObject, param.args, isSnsUpdateMethod(method))
                        } else if (isSnsRawQueryMethod(method)) {
                            if (internalQuery.get() == true) return
                            if (!isMomentAntiRecallEnabled()) return
                            handleSnsQuery(param.args)
                        }
                    } catch (e: Throwable) {
                        HLog.e("[Hchat:SnsAntiRecall] Hook处理失败", e)
                    }
                }
            })
            count++
        }
        return count
    }

    private fun isMomentAntiRecallEnabled(): Boolean {
        return prefs.getBoolean(SnsAntiRecallSettings.KEY_ENABLE, SnsAntiRecallSettings.DEFAULT_ENABLE)
    }

    private fun isCommentAntiRecallEnabled(): Boolean {
        return prefs.getBoolean(
            SnsAntiRecallSettings.KEY_COMMENT_ENABLE,
            SnsAntiRecallSettings.DEFAULT_COMMENT_ENABLE
        )
    }

    private fun isForceLegacyProfileEnabled(): Boolean {
        return prefs.getBoolean(
            SnsAntiRecallSettings.KEY_FORCE_LEGACY_PROFILE,
            SnsAntiRecallSettings.DEFAULT_FORCE_LEGACY_PROFILE
        )
    }

    private fun isCustomMarkEnabled(): Boolean {
        return prefs.getBoolean(
            SnsAntiRecallSettings.KEY_CUSTOM_MARK_ENABLE,
            SnsAntiRecallSettings.DEFAULT_CUSTOM_MARK_ENABLE
        )
    }

    private fun customMarkText(): String {
        return if (isCustomMarkEnabled()) {
            prefs.getString(
                SnsAntiRecallSettings.KEY_CUSTOM_MARK_TEXT,
                SnsAntiRecallSettings.DEFAULT_CUSTOM_MARK_TEXT
            ).orEmpty().trim().ifBlank { SnsAntiRecallSettings.DEFAULT_CUSTOM_MARK_TEXT }
        } else {
            SnsAntiRecallSettings.DEFAULT_CUSTOM_MARK_TEXT
        }
    }

    private fun isCommentCustomMarkEnabled(): Boolean {
        return prefs.getBoolean(
            SnsAntiRecallSettings.KEY_COMMENT_CUSTOM_MARK_ENABLE,
            SnsAntiRecallSettings.DEFAULT_COMMENT_CUSTOM_MARK_ENABLE
        )
    }

    private fun commentCustomMarkText(): String {
        return if (isCommentCustomMarkEnabled()) {
            prefs.getString(
                SnsAntiRecallSettings.KEY_COMMENT_CUSTOM_MARK_TEXT,
                SnsAntiRecallSettings.DEFAULT_COMMENT_CUSTOM_MARK_TEXT
            ).orEmpty().trim().ifBlank { SnsAntiRecallSettings.DEFAULT_COMMENT_CUSTOM_MARK_TEXT }
        } else {
            SnsAntiRecallSettings.DEFAULT_COMMENT_CUSTOM_MARK_TEXT
        }
    }

    private fun isSnsUpdateMethod(method: Method): Boolean {
        if (method.returnType != Integer.TYPE) return false
        if (method.name != "update" && method.name != "updateWithOnConflict") return false
        return method.parameterTypes.any { ContentValues::class.java.isAssignableFrom(it) }
    }

    private fun isSnsInsertOrReplaceMethod(method: Method): Boolean {
        if (method.returnType != java.lang.Long.TYPE) return false
        val name = method.name
        if (name != "insert" &&
            name != "insertWithOnConflict" &&
            name != "replace" &&
            name != "replaceOrThrow"
        ) {
            return false
        }
        return method.parameterTypes.any { ContentValues::class.java.isAssignableFrom(it) }
    }

    private fun isSnsWriteMethod(method: Method): Boolean {
        return isSnsUpdateMethod(method) || isSnsInsertOrReplaceMethod(method)
    }

    private fun isSnsRawQueryMethod(method: Method): Boolean {
        val name = method.name
        if (name != "rawQuery" && name != "rawQueryWithFactory") return false
        return method.parameterTypes.any { it == String::class.java }
    }

    private fun hookLegacySnsUserPageSwitch(): Int {
        val method = findEnableFlutterSnsPageMethod() ?: return 0
        if (!hookedMethods.add(method)) return 0
        return try {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isMomentAntiRecallEnabled() && isForceLegacyProfileEnabled()) {
                        param.result = false
                    }
                }
            })
            1
        } catch (e: Throwable) {
            HLog.e("[Hchat:SnsAntiRecall] Hook旧版朋友圈主页开关失败: ${e.message}", e)
            0
        }
    }

    private fun findEnableFlutterSnsPageMethod(): Method? {
        val runtimeKey = methodCacheKey()
        DexMethodCache.load(methodPrefs, runtimeKey, context.hostClassLoader(), CACHE_ENABLE_FLUTTER_SNS_PAGE)
            ?.takeIf { isEnableFlutterSnsPageMethod(it) }
            ?.let { return it }

        val method = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingEqStrings("enableFlutterSNSPage", "com.tencent.mm.plugin.sns.router.SnsRouter")
                        }
                    )
                }
            ).mapNotNull { it.getMethodInstance(context.hostClassLoader()) }
                .firstOrNull { isEnableFlutterSnsPageMethod(it) }
        }.getOrElse {
            HLog.e("[Hchat:SnsAntiRecall] 定位旧版朋友圈主页开关失败: ${it.message}", it)
            null
        }

        if (method != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, CACHE_ENABLE_FLUTTER_SNS_PAGE, method)
        } else {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_ENABLE_FLUTTER_SNS_PAGE)
        }
        return method
    }

    private fun isEnableFlutterSnsPageMethod(method: Method): Boolean {
        return method.parameterTypes.isEmpty() && method.returnType == java.lang.Boolean.TYPE
    }

    private fun handleSnsWrite(db: Any?, args: Array<Any?>?, allowMomentDelete: Boolean) {
        if (!isSnsInfoTable(tableArg(args))) return
        val values = contentValuesArg(args) ?: return
        if (isCommentAntiRecallEnabled()) {
            restoreDeletedComments(db, args, values)
        }
        if (!allowMomentDelete) return
        if (!isMomentAntiRecallEnabled()) return
        val type = intValue(values, "type", "field_type")
            ?: queryOldInt(db, values, args, "type", "field_type")
            ?: return
        if (!MOMENTS_CONTENT_TYPES.contains(type)) return
        val sourceType = intValue(values, "sourceType", "field_sourceType") ?: return
        if (sourceType != 0) return

        values.remove("sourceType")
        values.remove("field_sourceType")
        markTextValue(values, "contentDesc")
        markTextValue(values, "field_contentDesc")
        markContentBytes(values, "content")
        markContentBytes(values, "field_content")
    }

    private fun restoreDeletedComments(db: Any?, args: Array<Any?>?, values: ContentValues) {
        val attrKey = when {
            values.containsKey("attrBuf") -> "attrBuf"
            values.containsKey("field_attrBuf") -> "field_attrBuf"
            else -> return
        }
        val newBytes = runCatching { values.getAsByteArray(attrKey) }.getOrNull() ?: return
        val oldBytes = queryOldAttrBuf(db, values, args) ?: return
        val merged = mergeDeletedComments(oldBytes, newBytes) ?: return
        values.put(attrKey, merged)
    }

    private fun handleSnsQuery(args: Array<Any?>?) {
        if (args == null) return
        for (index in args.indices) {
            val sql = args[index] as? String ?: continue
            val rewritten = rewriteSnsSql(sql)
            if (rewritten != sql) {
                args[index] = rewritten
                return
            }
        }
    }

    private fun rewriteSnsSql(sql: String): String {
        if (!SNS_SELECT_REGEX.containsMatchIn(sql)) return sql
        var newSql = sql
        if (PROFILE_QUERY_REGEX.containsMatchIn(sql)) {
            newSql = enhanceSourceTypeInFilters(newSql)
            newSql = SOURCE_TYPE_PROFILE_REGEX.replace(newSql, "(1=1)")
            if (!newSql.contains("1=1 or snsId", ignoreCase = true)) {
                newSql = SNS_ID_LOWER_BOUND_REGEX.replace(newSql, "(1=1 or snsId >=")
            }
        }
        newSql = SOURCE_TYPE_TIMELINE_REGEX.replace(newSql, "(1=1)")
        return newSql
    }

    private fun queryOldAttrBuf(db: Any?, values: ContentValues, args: Array<Any?>?): ByteArray? {
        if (db == null || args == null) return null
        val snsId = longValue(values, "snsId", "field_snsId")
        if (snsId != null) {
            for (column in arrayOf("snsId", "field_snsId")) {
                querySingleBlob(db, "SELECT attrBuf FROM SnsInfo WHERE $column=? LIMIT 1", arrayOf(snsId.toString()))
                    ?.let { return it }
                querySingleBlob(db, "SELECT field_attrBuf FROM SnsInfo WHERE $column=? LIMIT 1", arrayOf(snsId.toString()))
                    ?.let { return it }
            }
        }
        val rowId = longValue(values, "rowid")
        if (rowId != null) {
            querySingleBlob(
                db,
                "SELECT attrBuf FROM SnsInfo WHERE rowid=? LIMIT 1",
                arrayOf(rowId.toString())
            )?.let { return it }
        }
        val where = whereClauseArg(args).takeIf { it.isNotBlank() } ?: return null
        val whereArgs = stringArrayArg(args)
        return querySingleBlob(db, "SELECT attrBuf FROM SnsInfo WHERE $where LIMIT 1", whereArgs)
            ?: querySingleBlob(db, "SELECT field_attrBuf FROM SnsInfo WHERE $where LIMIT 1", whereArgs)
    }

    private fun queryOldInt(db: Any?, values: ContentValues, args: Array<Any?>?, vararg columns: String): Int? {
        if (db == null || args == null) return null
        val snsId = longValue(values, "snsId", "field_snsId")
        if (snsId != null) {
            for (matchColumn in arrayOf("snsId", "field_snsId")) {
                for (column in columns) {
                    querySingleInt(db, "SELECT $column FROM SnsInfo WHERE $matchColumn=? LIMIT 1", arrayOf(snsId.toString()))
                        ?.let { return it }
                }
            }
        }
        val rowId = longValue(values, "rowid")
        if (rowId != null) {
            for (column in columns) {
                querySingleInt(db, "SELECT $column FROM SnsInfo WHERE rowid=? LIMIT 1", arrayOf(rowId.toString()))
                    ?.let { return it }
            }
        }
        val where = whereClauseArg(args).takeIf { it.isNotBlank() } ?: return null
        val whereArgs = stringArrayArg(args)
        for (column in columns) {
            querySingleInt(db, "SELECT $column FROM SnsInfo WHERE $where LIMIT 1", whereArgs)
                ?.let { return it }
        }
        return null
    }

    private fun querySingleBlob(db: Any, sql: String, selectionArgs: Array<String>?): ByteArray? {
        var cursor: Cursor? = null
        return try {
            cursor = invokeRawQuery(db, sql, selectionArgs)
            if (cursor == null || !cursor.moveToFirst()) return null
            val index = (0 until cursor.columnCount).firstOrNull { column ->
                val name = runCatching { cursor.getColumnName(column) }.getOrNull().orEmpty()
                name.equals("attrBuf", ignoreCase = true) || name.equals("field_attrBuf", ignoreCase = true)
            } ?: 0
            cursor.getBlob(index)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { cursor?.close() }
        }
    }

    private fun querySingleInt(db: Any, sql: String, selectionArgs: Array<String>?): Int? {
        var cursor: Cursor? = null
        return try {
            cursor = invokeRawQuery(db, sql, selectionArgs)
            if (cursor == null || !cursor.moveToFirst()) return null
            if (cursor.isNull(0)) return null
            cursor.getInt(0)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { cursor?.close() }
        }
    }

    private fun invokeRawQuery(db: Any, sql: String, selectionArgs: Array<String>?): Cursor? {
        val args = arrayOf(sql, selectionArgs)
        val method = KavaReflector.findCompatibleMethod(db.javaClass, "rawQuery", *args)
            ?: KavaReflector.findCompatibleMethod(db.javaClass, "rawQueryWithFactory", null, sql, selectionArgs, null)
            ?: return null
        val previous = internalQuery.get()
        internalQuery.set(true)
        return try {
            if (method.name == "rawQueryWithFactory") {
                KavaReflector.invoke(method, db, null, sql, selectionArgs, null) as? Cursor
            } else {
                KavaReflector.invoke(method, db, *args) as? Cursor
            }
        } finally {
            internalQuery.set(previous)
        }
    }

    private fun mergeDeletedComments(oldBytes: ByteArray, newBytes: ByteArray): ByteArray? {
        return try {
            val oldObject = parseSnsObject(oldBytes) ?: return null
            val newObject = parseSnsObject(newBytes) ?: return null
            val oldComments = commentList(oldObject)
            val newComments = commentList(newObject)
            if (oldComments.isEmpty()) return null
            val newKeys = newComments.mapTo(LinkedHashSet()) { commentKey(it) }
            val restored = oldComments.filter { old ->
                if (MomentsFakeInteractionNodeIdentity.isFakeCommentNode(old)) {
                    return@filter false
                }
                val key = commentKey(old)
                key !in newKeys && markCommentDeleted(old)
            }
            if (restored.isEmpty()) return null
            newComments.addAll(restored)
            writeCommentCount(newObject, newComments.size)
            serializeSnsObject(newObject)
        } catch (e: Throwable) {
            HLog.e("[Hchat:SnsAntiRecall] 合并朋友圈评论失败: ${e.message}", e)
            null
        }
    }

    private fun parseSnsObject(bytes: ByteArray): Any? {
        val clazz = KavaReflector.loadClass(SNS_OBJECT_CLASS, context.hostClassLoader()) ?: return null
        val constructor = KavaReflector.findConstructor(clazz) ?: return null
        val instance = KavaReflector.newInstance(constructor) ?: return null
        val parseMethod = KavaReflector.findCompatibleMethod(instance.javaClass, "parseFrom", bytes) ?: return null
        if (!KavaReflector.invokeSuccessfully(parseMethod, instance, bytes)) return null
        return instance
    }

    private fun serializeSnsObject(instance: Any): ByteArray? {
        return KavaReflector.invokeMethod(instance, "toByteArray") as? ByteArray
    }

    @Suppress("UNCHECKED_CAST")
    private fun commentList(snsObject: Any): MutableList<Any> {
        return KavaReflector.readField(snsObject, "CommentUserList") as? MutableList<Any>
            ?: LinkedList()
    }

    private fun writeCommentCount(snsObject: Any, count: Int) {
        KavaReflector.writeField(snsObject, "CommentCount", count)
        KavaReflector.writeField(snsObject, "CommentUserListCount", count)
    }

    private fun markCommentDeleted(comment: Any): Boolean {
        val contentField = commentContentField(comment) ?: return false
        val content = stringField(comment, contentField)
        val mark = commentCustomMarkText()
        if (content.contains(mark)) return true
        return KavaReflector.writeField(comment, contentField, markText(content, mark))
    }

    private fun commentKey(comment: Any): String {
        val contentField = commentContentField(comment)
        val serverId = firstPositiveLong(comment, COMMENT_SERVER_ID_FIELDS.asIterable())
        val localId = firstPositiveInt(comment, COMMENT_LOCAL_ID_FIELDS.filterNot { it == contentField })
        val createTime = firstPositiveInt(comment, COMMENT_CREATE_TIME_FIELDS.asIterable())
        val username = stringField(comment, "d")
        val content = contentField
            ?.let { stringField(comment, it) }
            .orEmpty()
            .removePrefix(commentCustomMarkText())
            .trimStart()
        if (serverId > 0L) return "svr:$serverId"
        if (localId > 0) return "local:$localId"
        return "fallback:$username:$createTime:$content"
    }

    private fun commentContentField(comment: Any): String? {
        return COMMENT_CONTENT_FIELDS.firstOrNull { KavaReflector.readField(comment, it) is String }
    }

    private fun firstPositiveInt(target: Any, fieldNames: Iterable<String>): Int {
        return fieldNames.firstNotNullOfOrNull { fieldName ->
            intField(target, fieldName).takeIf { it > 0 }
        } ?: 0
    }

    private fun firstPositiveLong(target: Any, fieldNames: Iterable<String>): Long {
        return fieldNames.firstNotNullOfOrNull { fieldName ->
            longField(target, fieldName).takeIf { it > 0L }
        } ?: 0L
    }

    private fun stringField(target: Any, fieldName: String): String {
        return KavaReflector.readField(target, fieldName) as? String ?: ""
    }

    private fun intField(target: Any, fieldName: String): Int {
        val value = KavaReflector.readField(target, fieldName)
        return (value as? Number)?.toInt() ?: 0
    }

    private fun longField(target: Any, fieldName: String): Long {
        val value = KavaReflector.readField(target, fieldName)
        return (value as? Number)?.toLong() ?: 0L
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
            .takeIf { it.isNotBlank() }
            ?.let { "$it|$CACHE_SCHEMA" }
            .orEmpty()
    }

    private fun enhanceSourceTypeInFilters(sql: String): String {
        return SOURCE_TYPE_IN_REGEX.replace(sql) { match ->
            val column = match.groupValues[1]
            val values = match.groupValues[2].trim()
            val existing = values.split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .toSet()
            if (existing.contains(0) && existing.contains(2)) {
                match.value
            } else {
                val suffix = values.takeIf { it.isNotBlank() }?.let { ",$it" }.orEmpty()
                "($column in (0,2$suffix))"
            }
        }
    }

    private fun tableArg(args: Array<Any?>?): String {
        if (args == null || args.isEmpty()) return ""
        (args[0] as? String)?.let { return it }
        return args.filterIsInstance<String>().firstOrNull { isSnsInfoTable(it) }.orEmpty()
    }

    private fun contentValuesArg(args: Array<Any?>?): ContentValues? {
        if (args == null) return null
        return args.firstNotNullOfOrNull { it as? ContentValues }
    }

    private fun isSnsInfoTable(table: String?): Boolean {
        return table.equals("SnsInfo", ignoreCase = true)
    }

    private fun intValue(values: ContentValues, vararg keys: String): Int? {
        for (key in keys) {
            if (!values.containsKey(key)) continue
            runCatching { values.getAsInteger(key) }.getOrNull()?.let { return it }
            val raw = runCatching { values.get(key) }.getOrNull()
            when (raw) {
                is Number -> return raw.toInt()
                is String -> raw.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun longValue(values: ContentValues, vararg keys: String): Long? {
        for (key in keys) {
            if (!values.containsKey(key)) continue
            runCatching { values.getAsLong(key) }.getOrNull()?.let { return it }
            val raw = runCatching { values.get(key) }.getOrNull()
            when (raw) {
                is Number -> return raw.toLong()
                is String -> raw.toLongOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun whereClauseArg(args: Array<Any?>): String {
        var afterValues = false
        for (arg in args) {
            if (arg is ContentValues) {
                afterValues = true
                continue
            }
            if (afterValues && arg is String) return arg
        }
        return ""
    }

    @Suppress("UNCHECKED_CAST")
    private fun stringArrayArg(args: Array<Any?>): Array<String>? {
        return args.firstOrNull { it is Array<*> && it.javaClass.componentType == String::class.java } as? Array<String>
    }

    private fun markTextValue(values: ContentValues, key: String) {
        if (!values.containsKey(key)) return
        val current = runCatching { values.getAsString(key) }.getOrNull().orEmpty()
        val marked = markText(current, customMarkText())
        if (marked != current) values.put(key, marked)
    }

    private fun markContentBytes(values: ContentValues, key: String) {
        if (!values.containsKey(key)) return
        val bytes = runCatching { values.getAsByteArray(key) }.getOrNull() ?: return
        val marked = markSnsContentBytes(bytes) ?: return
        values.put(key, marked)
    }

    private fun markSnsContentBytes(bytes: ByteArray): ByteArray? {
        return try {
            val markBytes = customMarkText().toByteArray(StandardCharsets.UTF_8)
            val out = ByteArrayOutputStream(bytes.size + markBytes.size + 8)
            var pos = 0
            var changed = false
            var foundContent = false
            while (pos < bytes.size) {
                val tag = readVarint(bytes, pos) ?: return null
                pos = tag.next
                val fieldNumber = (tag.value ushr 3).toInt()
                val wireType = (tag.value and 0x7).toInt()
                writeVarint(out, tag.value)
                when (wireType) {
                    0 -> {
                        val value = readVarint(bytes, pos) ?: return null
                        pos = value.next
                        writeVarint(out, value.value)
                    }
                    1 -> {
                        if (pos + 8 > bytes.size) return null
                        out.write(bytes, pos, 8)
                        pos += 8
                    }
                    2 -> {
                        val length = readVarint(bytes, pos) ?: return null
                        pos = length.next
                        val size = length.value.toInt()
                        if (size < 0 || pos + size > bytes.size) return null
                        if (fieldNumber == SNS_CONTENT_FIELD) {
                            foundContent = true
                            val current = String(bytes, pos, size, StandardCharsets.UTF_8)
                            val marked = markText(current, customMarkText()).toByteArray(StandardCharsets.UTF_8)
                            writeVarint(out, marked.size.toLong())
                            out.write(marked)
                            changed = changed || marked.size != size ||
                                !marked.contentEquals(bytes.copyOfRange(pos, pos + size))
                        } else {
                            writeVarint(out, length.value)
                            out.write(bytes, pos, size)
                        }
                        pos += size
                    }
                    5 -> {
                        if (pos + 4 > bytes.size) return null
                        out.write(bytes, pos, 4)
                        pos += 4
                    }
                    else -> return null
                }
            }
            if (!foundContent) {
                writeVarint(out, ((SNS_CONTENT_FIELD shl 3) or 2).toLong())
                writeVarint(out, markBytes.size.toLong())
                out.write(markBytes)
                changed = true
            }
            if (changed) out.toByteArray() else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun markText(text: String, mark: String): String {
        if (text.contains(mark)) return text
        return if (text.isBlank()) mark else "$mark $text"
    }

    private fun readVarint(bytes: ByteArray, offset: Int): Varint? {
        var pos = offset
        var shift = 0
        var result = 0L
        while (pos < bytes.size && shift < 64) {
            val b = bytes[pos].toInt() and 0xFF
            pos++
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return Varint(result, pos)
            shift += 7
        }
        return null
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var current = value
        while (true) {
            if ((current and -0x80L) == 0L) {
                out.write(current.toInt())
                return
            }
            out.write(((current and 0x7FL) or 0x80L).toInt())
            current = current ushr 7
        }
    }

    private data class Varint(val value: Long, val next: Int)

    companion object {
        private const val SNS_OBJECT_CLASS = "com.tencent.mm.protocal.protobuf.SnsObject"
        private const val SNS_CONTENT_FIELD = 5
        private const val CACHE_PREFS = "Hchat_sns_anti_recall_method_cache"
        private const val CACHE_SCHEMA = "sns_anti_recall_v2"
        private const val CACHE_ENABLE_FLUTTER_SNS_PAGE = "enable_flutter_sns_page"
        private val COMMENT_CONTENT_FIELDS = arrayOf("h", "m")
        private val COMMENT_CREATE_TIME_FIELDS = arrayOf("i", "n")
        private val COMMENT_LOCAL_ID_FIELDS = arrayOf("j", "m", "o", "n", "p")
        private val COMMENT_SERVER_ID_FIELDS = arrayOf("r", "u", "q", "t")
        private val MOMENTS_CONTENT_TYPES = setOf(
            1, 2, 3, 4, 5, 9, 10, 12, 13, 14, 15, 18, 19, 26, 28, 30, 34, 36, 41, 42, 47, 54
        )
        private val SNS_SELECT_REGEX = Regex(
            "select\\s+\\*,\\s*rowid\\s+from\\s+SnsInfo",
            setOf(RegexOption.IGNORE_CASE)
        )
        private val PROFILE_QUERY_REGEX = Regex(
            "\\bWHERE\\b[\\s\\S]*?(?:SnsInfo\\.)?userName\\s*=",
            setOf(RegexOption.IGNORE_CASE)
        )
        private val SOURCE_TYPE_TIMELINE_REGEX = Regex(
            "\\(\\s*(?:SnsInfo\\.)?sourceType\\s*&\\s*2\\s*!=\\s*0\\s*\\)",
            setOf(RegexOption.IGNORE_CASE)
        )
        private val SOURCE_TYPE_PROFILE_REGEX = Regex(
            "\\(\\s*(?:SnsInfo\\.)?sourceType\\s*&\\s*128\\s*!=\\s*0\\s*\\)",
            setOf(RegexOption.IGNORE_CASE)
        )
        private val SNS_ID_LOWER_BOUND_REGEX = Regex(
            "\\(\\s*snsId\\s*>=",
            setOf(RegexOption.IGNORE_CASE)
        )
        private val SOURCE_TYPE_IN_REGEX = Regex(
            "\\(\\s*((?:SnsInfo\\.)?sourceType)\\s+in\\s*\\(([^)]*)\\)\\s*\\)",
            setOf(RegexOption.IGNORE_CASE)
        )
    }
}
