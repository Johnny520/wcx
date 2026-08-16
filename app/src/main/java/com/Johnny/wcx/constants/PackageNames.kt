package com.Johnny.wcx.constants

import com.Johnny.wcx.BuildConfig

object PackageNames {

    const val WECHAT = "com.tencent.mm"
    const val MODULE = BuildConfig.APPLICATION_ID

    @Suppress("NOTHING_TO_INLINE")
    @JvmStatic
    inline fun isWeChat(packageName: String) = packageName.startsWith(WECHAT)
}
