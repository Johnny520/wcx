package com.Johnny.wcx.utils

import android.content.Context
import com.Johnny.wcx.constants.PackageNames

object SignatureVerifier {

    private const val TAG = "SignatureVerifier"

    private external fun nativeVerify(context: Context, packageName: String): Boolean

    fun verify(context: Context) {
        if (nativeVerify(context, PackageNames.MODULE)) {
            WeLogger.i(TAG, "signature verification succeeded")
            return
        }

        WeLogger.e(TAG, "signature verification failed")
        error("signature verification failed")
    }
}
