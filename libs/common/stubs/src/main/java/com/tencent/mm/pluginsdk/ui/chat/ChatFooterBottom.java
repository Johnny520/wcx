package com.tencent.mm.pluginsdk.ui.chat;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * 表情面板 / 工具面板 (AppPanel) 的容器。
 * <p>
 * 微信从不把它设为 GONE —— 收起状态靠 {@code ChatFooter.bottomMargin = -面板高}
 * 把它整个挤到屏幕外。
 */
public class ChatFooterBottom extends FrameLayout {

    public ChatFooterBottom(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ChatFooterBottom(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setIsHide(boolean hide) {
        throw new RuntimeException("Stub!");
    }
}
