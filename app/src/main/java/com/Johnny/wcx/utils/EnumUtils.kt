package com.Johnny.wcx.utils

@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
inline fun enumValueOfClass(enumClass: Class<*>, name: String): Enum<*> {
    return java.lang.Enum.valueOf(enumClass as Class<out Enum<*>?>, name)
}
