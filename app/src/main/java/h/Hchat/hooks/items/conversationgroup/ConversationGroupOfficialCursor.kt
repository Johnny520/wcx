package h.Hchat.hooks.items.conversationgroup

import android.database.Cursor
import android.database.MatrixCursor

/** Copies official-account rows into the native conversation schema and ordering. */
internal object ConversationGroupOfficialCursor {
    fun merge(base: Cursor, extras: List<Cursor>, limit: Int = 0): Cursor {
        val originalPosition = base.position
        try {
            val columns = base.columnNames
            val usernameIndex = requiredColumn(base, "username")
            val flagIndex = requiredColumn(base, "flag")
            val timeIndex = requiredColumn(base, "conversationTime")
            val rows = linkedMapOf<String?, Array<Any?>>()
            (listOf(base) + extras).forEach { source ->
                val indices = columns.map { requiredColumn(source, it) }
                source.moveToPosition(-1)
                while (source.moveToNext()) {
                    val row = Array<Any?>(columns.size) { value(source, indices[it]) }
                    // Extras were queried after the native cursor and replace stale native rows.
                    rows[row[usernameIndex]?.toString()] = row
                }
            }
            val sorted = rows.values.sortedWith(
                compareByDescending<Array<Any?>> { (it[flagIndex] as? Number)?.toLong() }
                    .thenByDescending { (it[timeIndex] as? Number)?.toLong() }
            )
            val selected = if (limit > 0) sorted.take(limit) else sorted
            val result = MatrixCursor(columns, selected.size)
            try {
                selected.forEach(result::addRow)
            } catch (failure: Throwable) {
                closeQuietly(listOf(result))
                throw failure
            }
            closeQuietly(listOf(base) + extras)
            return result
        } catch (failure: Throwable) {
            closeQuietly(extras.filterNot { it === base })
            runCatching { base.moveToPosition(originalPosition) }
            throw failure
        }
    }

    fun filter(base: Cursor, hiddenIds: Set<String>): Cursor {
        if (hiddenIds.isEmpty()) return base
        val originalPosition = base.position
        try {
            val usernameIndex = requiredColumn(base, "username")
            val columns = base.columnNames
            val rows = arrayListOf<Array<Any?>>()
            var removed = false
            base.moveToPosition(-1)
            while (base.moveToNext()) {
                if (base.getString(usernameIndex) in hiddenIds) {
                    removed = true
                } else {
                    rows.add(Array(columns.size) { value(base, it) })
                }
            }
            if (!removed) {
                base.moveToPosition(originalPosition)
                return base
            }
            val result = MatrixCursor(columns, rows.size)
            try {
                rows.forEach(result::addRow)
            } catch (failure: Throwable) {
                closeQuietly(listOf(result))
                throw failure
            }
            closeQuietly(listOf(base))
            return result
        } catch (failure: Throwable) {
            runCatching { base.moveToPosition(originalPosition) }
            throw failure
        }
    }

    private fun requiredColumn(cursor: Cursor, name: String): Int {
        return cursor.getColumnIndex(name).also {
            require(it >= 0) { "Conversation cursor missing column: $name" }
        }
    }

    private fun value(cursor: Cursor, index: Int): Any? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
        else -> cursor.getString(index)
    }

    private fun closeQuietly(cursors: List<Cursor>) {
        val closed = arrayListOf<Cursor>()
        cursors.forEach { cursor ->
            if (closed.none { it === cursor }) {
                closed.add(cursor)
                runCatching { cursor.close() }
            }
        }
    }
}
