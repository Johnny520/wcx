@file:Suppress("NOTHING_TO_INLINE")

package com.Johnny.wcx.utils.fs

import android.net.Uri
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

@Suppress("NOTHING_TO_INLINE")
inline fun Path.createDirsSafe(): Path {
    runCatching { createDirectories() }
    return this
}

inline val String.asPath get() = Path(this)

inline val File.asPath: Path get() = toPath()

inline val Path.asAndroidUri: Uri get() = Uri.fromFile(toFile())
