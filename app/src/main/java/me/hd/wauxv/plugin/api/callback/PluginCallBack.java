package me.hd.wauxv.plugin.api.callback;

import java.io.File;

public final class PluginCallBack {
    private PluginCallBack() {
    }

    public interface HttpCallback {
        void onSuccess(int statusCode, String response);

        void onError(Exception e);
    }

    public interface DownloadCallback {
        void onSuccess(File file);

        void onError(Exception e);

        default void onProgress(int progress) {
        }
    }
}
