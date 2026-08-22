package com.tencent.mm.ui.mogic;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;

/**
 * Stub for WeChat's WxViewPager (WeChat's ViewPager subclass). Compile-time only.
 */
public class WxViewPager extends ViewGroup {

    public int currentItem;

    public WxViewPager(Context context) {
        super(context);
    }

    public WxViewPager(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
    }
}
