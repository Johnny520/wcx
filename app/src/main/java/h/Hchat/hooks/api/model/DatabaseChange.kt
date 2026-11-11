package h.Hchat.hooks.api.model

import android.content.ContentValues

class DatabaseChange(
    @JvmField val operation: String?,
    @JvmField val table: String?,
    nullColumnHack: String?,
    values: ContentValues?,
    whereClause: String?,
    whereArgs: Array<String>?,
    @JvmField val result: Long,
    @JvmField val methodName: String?
) {
    @JvmField val nullColumnHack: String? = nullColumnHack
    @JvmField val values: ContentValues? = values?.let { ContentValues(it) }
    @JvmField val whereClause: String? = whereClause
    @JvmField val whereArgs: Array<String>? = whereArgs?.clone()

    fun isInsert(): Boolean = INSERT == operation

    fun isUpdate(): Boolean = UPDATE == operation

    fun isDelete(): Boolean = DELETE == operation

    companion object {
        const val INSERT = "insert"
        const val UPDATE = "update"
        const val DELETE = "delete"
    }
}
