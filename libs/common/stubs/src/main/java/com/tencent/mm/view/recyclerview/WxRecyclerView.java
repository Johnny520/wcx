package com.tencent.mm.view.recyclerview;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

public class WxRecyclerView extends ViewGroup {
    public WxRecyclerView(Context context) {
        super(context);
    }

    public WxRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public WxRecyclerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public WxRecyclerView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {

    }

    @Override
    public boolean requestChildRectangleOnScreen(@NonNull View child, @NonNull Rect rectangle, boolean immediate, int source) {
        return super.requestChildRectangleOnScreen(child, rectangle, immediate, source);
    }
}
