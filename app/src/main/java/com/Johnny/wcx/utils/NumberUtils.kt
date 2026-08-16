package com.Johnny.wcx.utils

@Suppress("NOTHING_TO_INLINE")
inline fun Long.coerceToInt(): Int {
    return this.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}
