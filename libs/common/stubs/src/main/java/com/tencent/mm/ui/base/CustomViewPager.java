package com.tencent.mm.ui.base;

import android.content.Context;
import android.view.ViewGroup;

public class CustomViewPager extends ViewGroup {

    public CustomViewPager(Context context) {
        super(context);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        throw new RuntimeException("Stub!");
    }
}
