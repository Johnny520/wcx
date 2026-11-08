package h.Hchat.hooks.api.runtime;

import android.database.Cursor;
import android.content.ContentValues;
import android.text.TextUtils;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.utils.KavaReflector;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信数据库查询 API。
 *
 * 通过 DexKit 定位 MMKernel/CoreStorage/SqliteDB wrapper，运行时懒加载数据库对象。
 */
public final class WeChatDatabaseApi {
    public interface Logger {
        void log(String message);
    }

    private final DexFinder dexFinder;
    private final Logger logger;
    private volatile Object coreStorage;
    private volatile Object dbWrapper;
    private volatile Method queryMethod;
    private volatile Object messageStorage;
    private volatile Method messageTableMethod;
    private volatile Method nativeMessageByIdMethod;
    private volatile Object nativeMessageUpdateStorage;
    private volatile Method nativeMessageUpdateMethod;
    private volatile List<Method> insertMethods;
    private volatile Method updateMethod;
    private volatile Method deleteMethod;
    private final Map<String, String> messageTableCache = new ConcurrentHashMap<>();
    private final Map<String, Object> storageObjectCache = new ConcurrentHashMap<>();
    private volatile List<String> messageTables;

    public WeChatDatabaseApi(DexFinder dexFinder, Logger logger) {
        this.dexFinder = dexFinder;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return dexFinder != null
                && dexFinder.coreStorageGetter != null
                && dexFinder.sqliteDbWrapperClass != null;
    }

    public boolean isReady() {
        return dbWrapper != null && queryMethod != null;
    }

    public Cursor rawQuery(String sql, String[] args) {
        if (TextUtils.isEmpty(sql)) return null;
        if (!ensureReady()) return null;
        return rawQueryInternal(sql, args, true);
    }

    public List<Map<String, Object>> query(String sql, String[] args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Cursor cursor = rawQuery(sql, args);
        if (cursor == null) return rows;
        try {
            String[] columns = cursor.getColumnNames();
            if (columns == null || !cursor.moveToFirst()) return rows;
            do {
                Map<String, Object> row = new HashMap<>();
                for (int i = 0; i < columns.length; i++) {
                    row.put(columns[i], cursorValue(cursor, i));
                }
                rows.add(row);
            } while (cursor.moveToNext());
        } catch (Throwable e) {
            log("SQL 结果读取异常: " + e.getMessage());
        } finally {
            try {
                cursor.close();
            } catch (Throwable ignored) {}
        }
        return rows;
    }

    public String queryFirstString(String sql, String[] args, String columnName) {
        Cursor cursor = rawQuery(sql, args);
        if (cursor == null) return "";
        try {
            if (!cursor.moveToFirst()) return "";
            int index = !TextUtils.isEmpty(columnName) ? cursor.getColumnIndex(columnName) : 0;
            if (index < 0) index = 0;
            String value = cursor.getString(index);
            return value != null ? value : "";
        } catch (Throwable e) {
            log("SQL 单值读取异常: " + e.getMessage());
            return "";
        } finally {
            try {
                cursor.close();
            } catch (Throwable ignored) {}
        }
    }

    public int update(String table, ContentValues values, String whereClause, String[] whereArgs) {
        if (TextUtils.isEmpty(table) || values == null) return -1;
        if (!ensureReady()) return -1;
        Method method = updateMethod;
        if (method == null) {
            method = findUpdateMethod(dbWrapper.getClass());
            updateMethod = method;
        }
        if (method == null) {
            log("未找到 SqliteDB 更新方法: " + dbWrapper.getClass().getName());
            return -1;
        }
        try {
            Object result = KavaReflector.invoke(method, dbWrapper, table, values, whereClause, whereArgs);
            return result instanceof Number ? ((Number) result).intValue() : -1;
        } catch (Throwable e) {
            log("SQL 更新异常: " + e.getMessage() + " table=" + table);
            return -1;
        }
    }

    public long insert(String table, String nullColumnHack, ContentValues values) {
        if (TextUtils.isEmpty(table) || values == null) return -1L;
        if (!ensureReady()) return -1L;
        List<Method> methods = insertMethods;
        if (methods == null) {
            methods = findInsertMethods(dbWrapper.getClass());
            insertMethods = methods;
        }
        if (methods == null || methods.isEmpty()) {
            log("未找到 SqliteDB 插入方法: " + dbWrapper.getClass().getName());
            return -1L;
        }
        for (Method method : methods) {
            try {
                Object result = KavaReflector.invoke(method, dbWrapper, table, nullColumnHack, values);
                long rowId = result instanceof Number ? ((Number) result).longValue() : -1L;
                if (rowId >= 0L) return rowId;
            } catch (Throwable ignored) {}
        }
        log("SQL 插入失败: table=" + table);
        return -1L;
    }

    public int delete(String table, String whereClause, String[] whereArgs) {
        if (TextUtils.isEmpty(table)) return -1;
        if (!ensureReady()) return -1;
        Method method = deleteMethod;
        if (method == null) {
            method = findDeleteMethod(dbWrapper.getClass());
            deleteMethod = method;
        }
        if (method == null) {
            log("未找到 SqliteDB 删除方法: " + dbWrapper.getClass().getName());
            return -1;
        }
        try {
            Object result = KavaReflector.invoke(method, dbWrapper, table, whereClause, whereArgs);
            return result instanceof Number ? ((Number) result).intValue() : -1;
        } catch (Throwable e) {
            log("SQL 删除异常: " + e.getMessage() + " table=" + table);
            return -1;
        }
    }

    public String messageTableForTalker(String talker) {
        if (TextUtils.isEmpty(talker)) return "";
        String cached = messageTableCache.get(talker);
        if (!TextUtils.isEmpty(cached)) return cached;
        if (!ensureReady()) return "";

        String table = invokeMessageTableMethod(talker);
        if (isUsableMessageTable(table)) {
            messageTableCache.put(talker, table);
            return table;
        }

        table = findMessageTableMethod(talker);
        if (isUsableMessageTable(table)) {
            messageTableCache.put(talker, table);
            return table;
        }
        return "";
    }

    public Object nativeMessageById(long msgId) {
        if (msgId <= 0L || dexFinder == null || dexFinder.localMessageClass == null) return null;
        if (!ensureReady()) return null;

        Object storage = messageStorage;
        Method method = nativeMessageByIdMethod;
        Object value = invokeNativeMessageById(storage, method, msgId);
        if (value != null) return value;

        storage = getCoreStorage();
        if (storage == null) return null;
        Set<Object> visited = new HashSet<>();
        for (Object candidate : storageFieldObjects(storage)) {
            if (candidate == null || visited.contains(candidate)) continue;
            visited.add(candidate);
            method = findNativeMessageByIdMethod(candidate);
            value = invokeNativeMessageById(candidate, method, msgId);
            if (value == null) continue;
            messageStorage = candidate;
            nativeMessageByIdMethod = method;
            return value;
        }
        return null;
    }

    public boolean updateNativeMessageContent(long msgId, String content, Object sourceMessage) {
        if (msgId <= 0L || dexFinder == null || dexFinder.localMessageClass == null) return false;
        Object nativeMessage = dexFinder.localMessageClass.isInstance(sourceMessage)
                ? sourceMessage
                : nativeMessageById(msgId);
        if (nativeMessage == null) return false;

        Method method = ensureNativeMessageUpdateMethod(nativeMessage);
        Object storage = nativeMessageUpdateStorage;
        if (method == null || storage == null) return false;

        Field contentField = KavaReflector.findFieldRecursive(nativeMessage.getClass(), "field_content");
        if (contentField == null) {
            contentField = KavaReflector.findFieldRecursive(nativeMessage.getClass(), "content");
        }
        if (contentField == null || contentField.getType() != String.class) return false;

        Object oldContent = KavaReflector.readField(contentField, nativeMessage);
        if (!KavaReflector.writeField(contentField, nativeMessage, content)) return false;
        try {
            Object result = KavaReflector.invokeOrThrow(method, storage, msgId, nativeMessage);
            int rows = result instanceof Number ? ((Number) result).intValue() : 0;
            if (rows > 0) return true;
            KavaReflector.writeField(contentField, nativeMessage, oldContent);
            return false;
        } catch (Throwable e) {
            KavaReflector.writeField(contentField, nativeMessage, oldContent);
            log("原生消息更新异常: " + e.getMessage() + " msgId=" + msgId);
            return false;
        }
    }

    public Object storageObjectForMethod(Method method) {
        if (method == null) return null;
        Class<?> owner = method.getDeclaringClass();
        if (owner == null) return null;
        String cacheKey = owner.getName();
        Object cached = storageObjectCache.get(cacheKey);
        if (cached != null) {
            if (owner.isInstance(cached)) {
                return cached;
            }
            storageObjectCache.remove(cacheKey);
        }
        Object storage = getCoreStorage();
        if (storage == null) return null;

        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object> queue = new ArrayDeque<>();
        queue.add(storage);
        while (!queue.isEmpty()) {
            Object current = queue.removeFirst();
            if (current == null || visited.contains(current)) continue;
            visited.add(current);
            if (owner.isInstance(current)) {
                storageObjectCache.put(cacheKey, current);
                return current;
            }
            enqueueNestedObjects(current, queue, visited);
        }
        return null;
    }

    public List<String> messageTables() {
        List<String> cached = messageTables;
        if (cached != null) return cached;
        if (!ensureReady()) return new ArrayList<>();
        synchronized (this) {
            if (messageTables != null) return messageTables;
            List<String> result = new ArrayList<>();
            for (Map<String, Object> row : query(
                    "SELECT name FROM sqlite_master WHERE type='table' "
                            + "AND name NOT LIKE 'sqlite_%'",
                    null)) {
                String table = stringValue(row, "name");
                if (isLikelyMessageTable(table)) {
                    result.add(table);
                }
            }
            messageTables = result;
            return result;
        }
    }

    private synchronized boolean ensureReady() {
        if (dbWrapper != null && queryMethod != null) return true;
        if (!isAvailable()) {
            return false;
        }
        Object storage = getCoreStorage();
        if (storage == null) return false;
        Object wrapper = findDbWrapper(storage);
        if (wrapper == null) {
            log("未找到 SqliteDB wrapper");
            return false;
        }
        Method method = findQueryMethod(wrapper.getClass());
        if (method == null) {
            log("未找到 SqliteDB 查询方法: " + wrapper.getClass().getName());
            return false;
        }
        dbWrapper = wrapper;
        queryMethod = method;
        log("数据库API已就绪: wrapper=" + wrapper.getClass().getName()
                + " query=" + method.getName());
        return true;
    }

    private Cursor rawQueryInternal(String sql, String[] args, boolean logError) {
        try {
            Object result = KavaReflector.invoke(queryMethod, dbWrapper, sql, args);
            return result instanceof Cursor ? (Cursor) result : null;
        } catch (Throwable e) {
            if (logError) log("SQL 查询异常: " + e.getMessage() + " sql=" + sql);
            return null;
        }
    }

    private String invokeMessageTableMethod(String talker) {
        Object storage = messageStorage;
        Method method = messageTableMethod;
        if (storage == null || method == null) return "";
        try {
            Object value = KavaReflector.invoke(method, storage, talker);
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String findMessageTableMethod(String talker) {
        Object storage = getCoreStorage();
        if (storage == null) return "";
        Set<Object> visited = new HashSet<>();
        for (Object candidate : storageFieldObjects(storage)) {
            if (candidate == null || visited.contains(candidate)) continue;
            visited.add(candidate);
            Method method = findMessageTableMethod(candidate, talker);
            if (method == null) continue;
            Object value = KavaReflector.invoke(method, candidate, talker);
            if (value instanceof String) {
                String table = (String) value;
                if (isUsableMessageTable(table)) {
                    messageStorage = candidate;
                    messageTableMethod = method;
                    return table;
                }
            }
        }
        return "";
    }

    private Object invokeNativeMessageById(Object storage, Method method, long msgId) {
        if (storage == null || method == null) return null;
        try {
            Object value = KavaReflector.invoke(method, storage, msgId);
            return dexFinder.localMessageClass.isInstance(value) ? value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Method findNativeMessageByIdMethod(Object candidate) {
        if (candidate == null || dexFinder == null || dexFinder.localMessageClass == null) return null;
        Class<?> cur = candidate.getClass();
        while (cur != null && cur != Object.class) {
            for (Method method : KavaReflector.declaredMethods(cur)) {
                if (!isNativeMessageByIdMethod(method)) continue;
                return KavaReflector.accessible(method);
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private synchronized Method ensureNativeMessageUpdateMethod(Object nativeMessage) {
        Method cached = nativeMessageUpdateMethod;
        Object cachedStorage = nativeMessageUpdateStorage;
        if (cached != null && cachedStorage != null
                && cached.getDeclaringClass().isInstance(cachedStorage)
                && isNativeMessageUpdateMethod(cached, nativeMessage)) {
            return cached;
        }

        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Object> candidates = new ArrayList<>();
        if (messageStorage != null) candidates.add(messageStorage);
        Object storage = getCoreStorage();
        if (storage != null) candidates.addAll(storageFieldObjects(storage));
        for (Object candidate : candidates) {
            if (candidate == null || !visited.add(candidate)) continue;
            Method method = findNativeMessageUpdateMethod(candidate, nativeMessage);
            if (method == null) continue;
            nativeMessageUpdateStorage = candidate;
            nativeMessageUpdateMethod = method;
            return method;
        }
        return null;
    }

    private Method findNativeMessageUpdateMethod(Object candidate, Object nativeMessage) {
        Class<?> cur = candidate.getClass();
        while (cur != null && cur != Object.class) {
            for (Method method : KavaReflector.declaredMethods(cur)) {
                if (isNativeMessageUpdateMethod(method, nativeMessage)) {
                    return KavaReflector.accessible(method);
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private boolean isNativeMessageUpdateMethod(Method method, Object nativeMessage) {
        if (method == null || nativeMessage == null || KavaReflector.isStatic(method)) return false;
        Class<?> returnType = method.getReturnType();
        if (returnType != int.class && returnType != Integer.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 2
                && (params[0] == long.class || params[0] == Long.class)
                && params[1].isAssignableFrom(nativeMessage.getClass());
    }

    private boolean isNativeMessageByIdMethod(Method method) {
        if (method == null || KavaReflector.isStatic(method)) return false;
        if (dexFinder == null || dexFinder.localMessageClass == null) return false;
        if (!dexFinder.localMessageClass.isAssignableFrom(method.getReturnType())) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 1 && (params[0] == long.class || params[0] == Long.class);
    }

    private List<Object> storageFieldObjects(Object storage) {
        List<Object> result = new ArrayList<>();
        Class<?> cur = storage.getClass();
        while (cur != null && cur != Object.class) {
            for (Field field : KavaReflector.declaredFields(cur)) {
                try {
                    if (KavaReflector.isStatic(field)) continue;
                    Class<?> type = field.getType();
                    if (type == null || type.isPrimitive() || type == String.class) continue;
                    Object value = KavaReflector.readField(field, storage);
                    if (value != null) result.add(value);
                } catch (Throwable ignored) {}
            }
            cur = cur.getSuperclass();
        }
        return result;
    }

    private Method findMessageTableMethod(Object candidate, String talker) {
        Class<?> cur = candidate.getClass();
        while (cur != null && cur != Object.class) {
            for (Method method : KavaReflector.declaredMethods(cur)) {
                if (!isMessageTableNameMethod(method)) continue;
                Object value = KavaReflector.invoke(method, candidate, talker);
                if (value instanceof String && isUsableMessageTable((String) value)) {
                    return method;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private boolean isMessageTableNameMethod(Method method) {
        if (method == null || KavaReflector.isStatic(method)) return false;
        if (method.getReturnType() != String.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 1 && params[0] == String.class;
    }

    private boolean isUsableMessageTable(String table) {
        if (!isSafeTableName(table)) return false;
        String exists = queryFirstString(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                new String[]{table},
                "name");
        if (TextUtils.isEmpty(exists)) return false;
        Cursor cursor = rawQueryInternal(
                "SELECT msgId,msgSvrId,type,status,isSend,createTime,talker,content FROM "
                        + quoteTable(table) + " LIMIT 0",
                null,
                false);
        if (cursor == null) return false;
        try {
            return true;
        } finally {
            try {
                cursor.close();
            } catch (Throwable ignored) {}
        }
    }

    private boolean isLikelyMessageTable(String table) {
        if (!isSafeTableName(table)) return false;
        Cursor cursor = rawQueryInternal("PRAGMA table_info(" + quoteTable(table) + ")", null, false);
        if (cursor == null) return false;
        boolean hasMsgId = false;
        boolean hasMsgSvrId = false;
        boolean hasCreateTime = false;
        boolean hasContent = false;
        try {
            int index = cursor.getColumnIndex("name");
            if (index < 0) return false;
            while (cursor.moveToNext()) {
                String name = cursor.getString(index);
                if ("msgId".equals(name)) hasMsgId = true;
                if ("msgSvrId".equals(name)) hasMsgSvrId = true;
                if ("createTime".equals(name)) hasCreateTime = true;
                if ("content".equals(name)) hasContent = true;
            }
            return hasMsgId && hasMsgSvrId && hasCreateTime && hasContent;
        } catch (Throwable ignored) {
            return false;
        } finally {
            try {
                cursor.close();
            } catch (Throwable ignored) {}
        }
    }

    private boolean isSafeTableName(String table) {
        if (TextUtils.isEmpty(table)) return false;
        for (int i = 0; i < table.length(); i++) {
            char c = table.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_') {
                continue;
            }
            return false;
        }
        return true;
    }

    public String quoteTable(String table) {
        return isSafeTableName(table) ? "`" + table + "`" : "";
    }

    private static String stringValue(Map<String, Object> row, String key) {
        Object value = row != null ? row.get(key) : null;
        return value != null ? String.valueOf(value) : "";
    }

    public Object getCoreStorage() {
        if (coreStorage != null) return coreStorage;
        try {
            Method getter = dexFinder.coreStorageGetter;
            Object target = KavaReflector.isStatic(getter) ? null : dexFinder.mmKernelClass;
            Object storage = KavaReflector.invoke(getter, target);
            if (storage != null) {
                coreStorage = storage;
                return storage;
            }
        } catch (Throwable e) {
            log("获取 CoreStorage 失败: " + e.getMessage());
        }
        return null;
    }

    private Object findDbWrapper(Object storage) {
        if (storage == null || dexFinder.sqliteDbWrapperClass == null) return null;
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object> queue = new ArrayDeque<>();
        queue.add(storage);
        while (!queue.isEmpty()) {
            Object current = queue.removeFirst();
            if (current == null || visited.contains(current)) continue;
            visited.add(current);

            if (dexFinder.sqliteDbWrapperClass.isInstance(current)) {
                return current;
            }

            Method method = findQueryMethod(current.getClass());
            if (method != null) {
                return current;
            }

            enqueueNestedObjects(current, queue, visited);
        }
        return null;
    }

    private void enqueueNestedObjects(Object source,
                                      ArrayDeque<Object> queue,
                                      Set<Object> visited) {
        Class<?> cur = source.getClass();
        while (cur != null && cur != Object.class) {
            for (Field field : KavaReflector.declaredFields(cur)) {
                try {
                    if (KavaReflector.isStatic(field)) continue;
                    Class<?> type = field.getType();
                    if (type == null || type.isPrimitive() || type == String.class) continue;
                    Object value = KavaReflector.readField(field, source);
                    if (value != null && !visited.contains(value)) {
                        queue.addLast(value);
                    }
                } catch (Throwable ignored) {}
            }
            cur = cur.getSuperclass();
        }
    }

    private Method findQueryMethod(Class<?> wrapperClass) {
        Method best = null;
        Class<?> cur = wrapperClass;
        while (cur != null && cur != Object.class) {
            for (Method method : KavaReflector.declaredMethods(cur)) {
                if (!isQueryMethod(method)) continue;
                if (best == null || queryPriority(method) > queryPriority(best)) {
                    best = method;
                }
            }
            cur = cur.getSuperclass();
        }
        return best;
    }

    private Method findUpdateMethod(Class<?> wrapperClass) {
        Class<?> cur = wrapperClass;
        while (cur != null && cur != Object.class) {
            for (Method method : KavaReflector.declaredMethods(cur)) {
                if (!isUpdateMethod(method)) continue;
                return method;
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private Method findDeleteMethod(Class<?> wrapperClass) {
        Class<?> cur = wrapperClass;
        while (cur != null && cur != Object.class) {
            for (Method method : KavaReflector.declaredMethods(cur)) {
                if (!isDeleteMethod(method)) continue;
                return method;
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private List<Method> findInsertMethods(Class<?> wrapperClass) {
        List<Method> result = new ArrayList<>();
        Class<?> cur = wrapperClass;
        while (cur != null && cur != Object.class) {
            for (Method method : KavaReflector.declaredMethods(cur)) {
                if (!isInsertMethod(method)) continue;
                result.add(method);
            }
            cur = cur.getSuperclass();
        }
        return result;
    }

    private boolean isInsertMethod(Method method) {
        if (method == null) return false;
        Class<?> returnType = method.getReturnType();
        if (returnType != long.class && returnType != Long.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 3
                && params[0] == String.class
                && params[1] == String.class
                && params[2] == ContentValues.class;
    }

    private boolean isUpdateMethod(Method method) {
        if (method == null) return false;
        Class<?> returnType = method.getReturnType();
        if (returnType != int.class && returnType != Integer.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 4
                && params[0] == String.class
                && params[1] == ContentValues.class
                && params[2] == String.class
                && params[3] == String[].class;
    }

    private boolean isDeleteMethod(Method method) {
        if (method == null) return false;
        Class<?> returnType = method.getReturnType();
        if (returnType != int.class && returnType != Integer.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 3
                && params[0] == String.class
                && params[1] == String.class
                && params[2] == String[].class;
    }

    private boolean isQueryMethod(Method method) {
        if (method == null || method.getReturnType() != Cursor.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 2 && params[0] == String.class && params[1] == String[].class;
    }

    private int queryPriority(Method method) {
        String name = method.getName();
        if ("rawQuery".equals(name)) return 100;
        if ("f".equals(name)) return 90;
        if ("j".equals(name)) return 80;
        if ("a".equals(name)) return 70;
        return 10;
    }

    private Object cursorValue(Cursor cursor, int index) {
        try {
            switch (cursor.getType(index)) {
                case Cursor.FIELD_TYPE_NULL:
                    return "";
                case Cursor.FIELD_TYPE_INTEGER:
                    return cursor.getLong(index);
                case Cursor.FIELD_TYPE_FLOAT:
                    return cursor.getDouble(index);
                case Cursor.FIELD_TYPE_BLOB:
                    return cursor.getBlob(index);
                case Cursor.FIELD_TYPE_STRING:
                default:
                    String value = cursor.getString(index);
                    return value != null ? value : "";
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatDatabaseApi] " + message);
    }
}
