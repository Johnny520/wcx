package com.Johnny.wcx.utils

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.ui.utils.ForwardIcon
import com.Johnny.wcx.ui.utils.toBitmap
import com.Johnny.wcx.ui.utils.toDp

fun Uri.openInSystem(
    context: Context,
    useCustomTabs: Boolean = false
) {
    runCatching {
        if (useCustomTabs) {
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(Intent.ACTION_SEND)
                    .setComponent(
                        ComponentName(
                            if (HostInfo.isHost) context.packageName else PackageNames.WECHAT,
                            "com.tencent.mm.ui.tools.ShareImgUI"
                        )
                    )
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, toString()),
                PendingIntent.FLAG_IMMUTABLE
            )

            val intent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_ON)
                .setBookmarksButtonEnabled(true)
                .setDownloadButtonEnabled(true)
                .setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
                .apply {
                    val forwardBitmap = ForwardIcon.toBitmap(24.toDp(), 24.toDp())
                    setActionButton(
                        forwardBitmap,
                        "转发",
                        pendingIntent,
                        true
                    )
                }
                .build()

            intent.launchUrl(context, this)
        } else {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = this
            context.startActivity(intent)
        }
    }.onFailure {
        WeLogger.e("UriUtils", "failed to open url: $this", it)
    }
}

inline val Uri.fileExtension: String
    get() = pathSegments.last().substringAfterLast('.', "")
