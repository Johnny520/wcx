package com.Johnny.wcx.utils.fs

import android.os.Environment
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.utils.HostInfo
import java.nio.file.Path
import kotlin.io.path.div

object KnownPaths {

    val internalStorage: Path by lazy {
        Environment.getExternalStorageDirectory().asPath
    }

    val moduleData by lazy {
        (internalStorage / "Android" / "data" /
                runCatching { HostInfo.packageName }.getOrDefault(PackageNames.WECHAT) /
                BuildConfig.TAG).createDirsSafe()
    }

    val codeCacheDir: Path by lazy {
        HostInfo.application.codeCacheDir.asPath
    }

    /** 模块数据根目录（Python 脚本等扩展数据挂在它下面）。 */
    val moduleRoot: Path by lazy { moduleData }

    val moduleCache by lazy {
        (internalStorage / "Android" / "data" /
                runCatching { HostInfo.packageName }.getOrDefault(PackageNames.WECHAT)
                / "cache" / BuildConfig.TAG).createDirsSafe()
    }

    val moduleAssets by lazy {
        (moduleData / "assets").createDirsSafe()
    }

    val downloads by lazy {
        (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toPath() / BuildConfig.TAG)
            .createDirsSafe()
    }
}
