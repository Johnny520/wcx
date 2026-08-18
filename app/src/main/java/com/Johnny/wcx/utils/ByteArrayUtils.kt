@file:Suppress("NOTHING_TO_INLINE")

package com.Johnny.wcx.utils

import java.nio.ByteBuffer

inline fun ByteArray.toByteBuffer() = ByteBuffer.wrap(this)

