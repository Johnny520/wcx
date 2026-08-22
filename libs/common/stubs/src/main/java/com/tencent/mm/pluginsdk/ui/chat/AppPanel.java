package com.tencent.mm.pluginsdk.ui.chat;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/**
 * 「+」工具面板。是 {@link ChatFooterBottom} 的子 View，高度为 {@code wrap_content}，
 * 内部撑开到 {@code setPortHeighPx()} 给的 port height。
 */
public class AppPanel extends LinearLayout {

    public AppPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setPortHeighPx(int px) {
        throw new RuntimeException("Stub!");
    }
}
