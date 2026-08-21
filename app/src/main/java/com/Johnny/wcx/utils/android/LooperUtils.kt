package com.Johnny.wcx.utils.android

import android.os.Handler
import android.os.Looper
import androidx.core.os.postDelayed

@Suppress("ObjectPropertyName")
val _mainHandler by lazy { Handler(Looper.getMainLooper()) }

inline fun runOnUiThread(crossinline action: () -> Unit) {
    _mainHandler.post {
        action()
    }
}

inline fun runOnUiThread(delayInMillis: Long, crossinline action: () -> Unit) {
    _mainHandler.postDelayed(delayInMillis) {
        action()
    }
}
