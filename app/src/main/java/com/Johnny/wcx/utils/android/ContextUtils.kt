package com.Johnny.wcx.utils.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Process
import android.os.UserManager

inline val Context.isDarkMode
    get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

inline val Context.androidUserId: Long
    get() {
        val userManager = getSystemService<UserManager>()
        val userHandle = Process.myUserHandle()
        return userManager.getSerialNumberForUser(userHandle)
    }

// it is the caller's responsibility to ensure the class is a service
inline fun <reified T : Any> Context.getSystemService(): T =
    getSystemService(T::class.java)!!

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
