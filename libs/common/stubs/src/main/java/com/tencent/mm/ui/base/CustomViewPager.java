package com.tencent.mm.ui.base;

import android.content.Context;

import com.tencent.mm.ui.mogic.WxViewPager;

public class CustomViewPager extends WxViewPager {

    public CustomViewPager(Context context) {
        super(context);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        throw new RuntimeException("Stub!");
    }
}
