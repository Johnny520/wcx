package com.tencent.mm.pluginsdk.ui.chat;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class ChatFooter extends FrameLayout {

    public ChatFooter(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ChatFooter(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public String getLastText() {
        throw new RuntimeException("Stub!");
    }

    public void setLastText(String str) {
        throw new RuntimeException("Stub!");
    }

    public long getLastQuoteMsgId() {
        throw new RuntimeException("Stub!");
    }

    public void setMode(int i16) {
        throw new RuntimeException("Stub!");
    }
}
