@file:Suppress("NOTHING_TO_INLINE")

package com.Johnny.wcx.utils.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.Johnny.wcx.utils.HostInfo

fun copyToClipboard(context: Context, content: String) {
    val clipboard = context.getSystemService<ClipboardManager>()
    val clip = ClipData.newPlainText("text", content)
    clipboard.setPrimaryClip(clip)
}

inline fun copyToClipboard(content: String) = copyToClipboard(HostInfo.application, content)

inline fun readTextFromClipboard(context: Context): String? {
    val clipboard = context.getSystemService<ClipboardManager>()
    val item = clipboard.primaryClip?.getItemAt(0) ?: return null
    return item.text?.toString()
}
