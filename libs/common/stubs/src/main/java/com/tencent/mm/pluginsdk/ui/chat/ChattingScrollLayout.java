package com.tencent.mm.pluginsdk.ui.chat;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/**
 * 会话页的纵向根容器: [消息列表宿主 (weight=1)] + [ChatFooter] + 若干 ViewStub。
 * <p>
 * 微信"展开"底部面板的方式不是布局变化, 而是在 {@code scrollContentTo(y, ...)} 之后
 * 对除消息列表宿主外的所有子 View 做 {@code setTranslationY(-y)}。
 */
public class ChattingScrollLayout extends LinearLayout {

    public ChattingScrollLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ChattingScrollLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setAlwaysScroll(boolean alwaysScroll) {
        throw new RuntimeException("Stub!");
    }
}
