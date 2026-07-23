package com.tencent.wcdb.database;

import com.tencent.wcdb.Cursor;

/* JADX INFO: loaded from: classes8.dex */
public interface SQLiteCursorDriver {
    void cursorClosed();

    void cursorDeactivated();

    void cursorRequeried(Cursor cursor);

    Cursor query(SQLiteDatabase.CursorFactory cursorFactory, Object[] objArr);

    void setBindArguments(String[] strArr);
}
