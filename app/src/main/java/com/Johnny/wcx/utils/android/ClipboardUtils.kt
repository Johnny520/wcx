@file:Suppress("NOTHING_TO_INLINE")

package com.Johnny.wcx.utils.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.Johnny.wcx.utils.HostInfo

fun copyToClipboard(context: Context, content: String) {
    // 剪贴板走 binder，内容过大（>~1MB）会抛 TransactionTooLargeException 导致二次崩溃：
    // 先截断到安全长度并注明，完整内容仍保存在本地文件；再以 try-catch 兜底写入失败。
    val MAX_CLIP_CHARS = 300_000
    val clipText = if (content.length > MAX_CLIP_CHARS) {
        content.take(MAX_CLIP_CHARS) + "\n\n[内容过长已截断，完整内容见模块崩溃日志文件]"
    } else content
    try {
        val clipboard = context.getSystemService<ClipboardManager>()
        val clip = ClipData.newPlainText("text", clipText)
        clipboard.setPrimaryClip(clip)
    } catch (e: Throwable) {
        com.Johnny.wcx.utils.WeLogger.w("Clipboard", "写入剪贴板失败", e)
    }
}

inline fun copyToClipboard(content: String) = copyToClipboard(HostInfo.application, content)

inline fun readTextFromClipboard(context: Context): String? {
    val clipboard = context.getSystemService<ClipboardManager>()
    val item = clipboard.primaryClip?.getItemAt(0) ?: return null
    return item.text?.toString()
}
