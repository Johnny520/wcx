package com.tencent.mm.pluginsdk.ui.chat;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;

/**
 * Stub for WeChat's AppPanel (chat footer tool panel container). Compile-time only;
 * at runtime the real WeChat class is loaded from the host APK.
 */
public class AppPanel extends ViewGroup {
    public AppPanel(Context context) {
        super(context);
    }

    public AppPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setPortHeighPx(int px) {
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
    }
}
