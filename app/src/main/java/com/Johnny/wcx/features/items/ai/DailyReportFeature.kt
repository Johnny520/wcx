package com.Johnny.wcx.features.items.ai

import com.Johnny.wcx.ai.daily.DailyReportScheduler
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature

/**
 * 每日总结的后台入口：把调度器挂到功能生命周期上。
 *
 * 开关在「AI 助手 → 总结」页（自动生成 / 时间），这里只负责启动与停止计时。
 */
@Feature(
    name = "AI 每日总结",
    categories = ["API"],
    description = "每天到点把好友往来、群聊要点与工作群进度整理成一份报告",
)
object DailyReportFeature : ApiFeature() {

    override fun onEnable() {
        DailyReportScheduler.onEnable()
    }

    override fun onDisable() {
        DailyReportScheduler.onDisable()
    }
}
