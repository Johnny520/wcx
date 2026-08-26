package com.tencent.wcdb.database;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tencent.wcdb.Cursor;
import com.tencent.wcdb.support.CancellationSignal;

public final class SQLiteDatabase {

    public Cursor rawQuery(@NonNull String statement, @Nullable Object[] args) {
        throw new RuntimeException("Stub!");
    }

    public void execSQL(@NonNull String statement, @Nullable Object[] args) {
        throw new RuntimeException("Stub!");
    }

    // returns: changed row count
    public int delete(@NonNull String table, @Nullable String conditions, @Nullable String[] args) {
        throw new RuntimeException("Stub!");
    }

    public interface CursorFactory {
        Cursor newCursor(SQLiteDatabase sQLiteDatabase, SQLiteCursorDriver sQLiteCursorDriver, String str, SQLiteProgram sQLiteProgram);

        SQLiteProgram newQuery(SQLiteDatabase sQLiteDatabase, String str, Object[] objArr, CancellationSignal cancellationSignal);
    }
}
