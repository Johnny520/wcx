package com.Johnny.wcx.utils.android

import android.os.Handler
import android.os.Looper
import androidx.core.os.postDelayed

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

fun runOnUiThread(action: () -> Unit) {
    mainHandler.post {
        action()
    }
}

fun runOnUiThread(delayInMillis: Long, action: () -> Unit) {
    mainHandler.postDelayed(delayInMillis) {
        action()
    }
}
