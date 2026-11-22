package com.Johnny.wcx.features.items.ai

import com.Johnny.wcx.ai.group.GroupDigestScheduler
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.HostInfo

/**
 * 群总结的后台入口：只负责把调度器挂到功能生命周期上。
 *
 * 每个群的开关在 AI 页的「群组」里，不是功能列表里的总开关 —— 这个功能没有全局关闭，
 * 关掉它等于所有群的配置都失效，用户要的是逐群控制。
 */
@Feature(
    name = "群聊总结",
    categories = ["API"],
    description = "按设定频率把群消息整理成摘要，结果显示在首页的群总结入口",
)
object AiGroupDigestFeature : ApiFeature() {

    override fun onEnable() {
        GroupDigestScheduler.onEnable()
        // 入口跟着 Activity 生命周期走，不依赖侧边栏功能是否开启。
        GroupDigestEntry.register(HostInfo.application)
    }

    override fun onDisable() {
        GroupDigestEntry.unregister()
        GroupDigestScheduler.onDisable()
    }
}
