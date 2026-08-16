package com.Johnny.wcx.utils

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.utils.android.showToast
import kotlin.system.exitProcess

fun restartHost() {
    showToast("正在重启...")
    val instance = "com.tencent.mm.process.KillProcessHelperActivity".toClass()
        .reflekt().firstField().getStatic()!!
    instance.reflekt().firstMethod().invoke(HostInfo.application, true)
}

fun killHost() {
    exitProcess(0)
}
