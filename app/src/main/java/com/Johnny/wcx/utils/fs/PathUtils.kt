@file:Suppress("NOTHING_TO_INLINE")

package com.Johnny.wcx.utils.fs

import android.net.Uri
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.moveTo

@Suppress("NOTHING_TO_INLINE")
inline fun Path.createDirsSafe(): Path {
    runCatching { createDirectories() }
    return this
}

inline val String.asPath get() = Path(this)

inline val File.asPath: Path get() = toPath()

inline val Path.asAndroidUri: Uri get() = Uri.fromFile(toFile())

/** Replaces [target] atomically when supported, falling back only when atomic moves are unavailable. */
fun Path.moveReplacing(target: Path): Path = try {
    moveTo(target, REPLACE_EXISTING, ATOMIC_MOVE)
} catch (_: AtomicMoveNotSupportedException) {
    moveTo(target, REPLACE_EXISTING)
}
