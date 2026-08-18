package com.Johnny.wcx.loader.entry.zygisk

import android.util.Log
import androidx.annotation.Keep
import com.Johnny.wcx.loader.abc.IClassLoaderHelper
import com.Johnny.wcx.loader.abc.ILoaderService

@Keep
internal class ZygiskLoaderService(
    private val modulePath: String,
    private val versionName: String,
    private val versionCode: Int,
) : ILoaderService {

    override var classLoaderHelper: IClassLoaderHelper? = null

    override val loaderName: String = "Zygisk 加载器"

    override val entryPointName: String = "com.Johnny.wcx.loader.entry.zygisk.ZygiskEntry"

    override val loaderVersionName: String get() = versionName

    override val loaderVersionCode: Int get() = versionCode

    override val mainModulePath: String get() = modulePath

    override fun log(msg: String) {
        Log.i(TAG, msg)
    }

    override fun log(tr: Throwable) {
        Log.e(TAG, tr.toString(), tr)
    }

    override fun queryExtension(key: String, vararg args: Any?): Any? = null

    companion object {
        private const val TAG = "ZygiskLoaderService"
    }
}
