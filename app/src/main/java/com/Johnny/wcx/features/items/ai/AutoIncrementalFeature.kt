package com.Johnny.wcx.features.items.ai

import com.Johnny.wcx.ai.AutoIncrementalScheduler
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature

/**
 * 档案自动增量整理的后台入口。
 *
 * 开关在「AI 助手 → 设置 → 档案自动整理」；这里只负责启动/停止计时。
 * 默认关闭：会花用户的模型额度。
 */
@Feature(
    name = "AI 自动增量整理",
    categories = ["API"],
    description = "每天到点对已就绪的好友档案补一次新增聊天记录（默认关闭）",
)
object AutoIncrementalFeature : ApiFeature() {

    override fun onEnable() {
        AutoIncrementalScheduler.onEnable()
    }

    override fun onDisable() {
        AutoIncrementalScheduler.onDisable()
    }
}
