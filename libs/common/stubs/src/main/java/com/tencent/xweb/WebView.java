package com.tencent.xweb;

import android.content.Context;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.webkit.ValueCallback;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class WebView extends FrameLayout {

    public WebView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public WebView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public WebView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public void evaluateJavascript(String str, ValueCallback valueCallback) {
        throw new RuntimeException("Stub!");
    }

    public void goBack() {
        throw new RuntimeException("Stub!");
    }

    public void goForward() {
        throw new RuntimeException("Stub!");
    }

    public void reload() {
        throw new RuntimeException("Stub!");
    }

    public boolean canGoBack() {
        throw new RuntimeException("Stub!");
    }

    public boolean canGoForward() {
        throw new RuntimeException("Stub!");
    }

    public String getTitle() {
        throw new RuntimeException("Stub!");
    }

    public String getUrl() {
        throw new RuntimeException("Stub!");
    }

    public View getView() {
        throw new RuntimeException("Stub!");
    }

    public View getWebViewUI() {
        throw new RuntimeException("Stub!");
    }

    public Looper getWebViewLooper() {
        throw new RuntimeException("Stub!");
    }
}
