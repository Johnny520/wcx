package com.Johnny.wcx.utils

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.utils.android.showToast
import kotlin.system.exitProcess
import com.Johnny.wcx.utils.WeLogger

fun restartHost() {
    showToast("正在重启...")
    runCatching {
        val instance = "com.tencent.mm.process.KillProcessHelperActivity".toClass()
            .reflekt().firstField().getStatic()!!
        instance.reflekt().firstMethod().invoke(HostInfo.application, true)
    }.onFailure { e ->
        WeLogger.e("KillHostUtils", "restartHost failed", e)
    }
}

fun killHost() {
    exitProcess(0)
}
