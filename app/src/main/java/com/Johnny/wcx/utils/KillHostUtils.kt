package com.Johnny.wcx.utils

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.utils.android.showToast
import kotlin.system.exitProcess
import com.Johnny.wcx.utils.WeLogger

/**
 * 用微信自己的 KillProcessHelperActivity 重启进程（热重启）。
 *
 * @return true 表示已成功发起重启；false 表示失败（调用方必须给用户可见提示，
 *         不能再像以前那样吞掉异常让界面卡在「正在重启…」）。
 */
fun restartHost(): Boolean {
    val ok = runCatching {
        val instance = "com.tencent.mm.process.KillProcessHelperActivity".toClass()
            .reflekt().firstField().getStatic()
            ?: error("KillProcessHelperActivity 实例为空")
        instance.reflekt().firstMethod().invoke(HostInfo.application, true)
        true
    }.getOrElse { e ->
        WeLogger.e("KillHostUtils", "restartHost failed", e)
        false
    }
    if (ok) {
        showToast("正在重启...")
    } else {
        showToast("自动重启失败，请手动划掉微信后重新打开")
    }
    return ok
}

fun killHost() {
    exitProcess(0)
}
