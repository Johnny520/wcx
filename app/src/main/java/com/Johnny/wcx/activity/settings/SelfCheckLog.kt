package com.Johnny.wcx.activity.settings

import com.Johnny.wcx.utils.WeLogger

/**
 * 自检 / 诊断类页面的统一日志出口。
 *
 * 约定：页面上的列表、浮层进度、结果弹窗都只是「同一份结果单独拎出来展示」，
 * 真正的记录必须同时写进运行日志（`WeLogger` → 日志页可查看、可导出、可复制），
 * 否则用户报「XX 失败」时开发者拿不到任何上下文。
 *
 * 自动扫描（进入页面即执行）只记结束汇总，避免进出页面重复刷屏；
 * 用户主动点「一键自检 / 一键修复 / 重新检测」时逐项记录。
 */
internal object SelfCheckLog {
    private const val TAG = "自检"

    fun start(title: String, total: Int) {
        WeLogger.i(TAG, "开始：$title（共 $total 项）")
    }

    fun step(ok: Boolean, label: String, detail: String) {
        WeLogger.i(TAG, (if (ok) "✅ " else "❌ ") + label + "：" + detail)
    }

    fun end(title: String, okCount: Int, total: Int) {
        WeLogger.i(TAG, "结束：$title，通过 $okCount / $total 项")
    }
}
