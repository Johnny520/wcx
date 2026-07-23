package com.Johnny.wcx.ui.utils

import android.content.res.Resources

fun Int.toDp(): Int = (this * Resources.getSystem().displayMetrics.density).toInt()
