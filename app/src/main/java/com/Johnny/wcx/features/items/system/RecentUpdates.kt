package com.Johnny.wcx.features.items.system

import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.ClickableFeature

/**
 * 近期更新数据：本版本新增/改动的功能项
 * 每条包含功能名称、简短说明、对应的功能开关名称（用于定位跳转）
 */
data class RecentUpdateItem(
    val name: String,
    val description: String,
    val featureKey: String // 对应功能开关的 name，用于定位跳转
)

/**
 * 近期更新列表 — 由 VersionChangelog 动态生成
 * 优先使用版本匹配的更新日志，匹配失败回退到静态列表
 */
val RECENT_UPDATES: List<RecentUpdateItem>
    get() {
        val changelog = getCurrentVersionChangelog()
        return if (changelog.isNotEmpty()) {
            changelog.map { it.toRecentUpdateItem() }
        } else {
            // 兜底：找不到版本日志时展示提示
            listOf(
                RecentUpdateItem(
                    name = "当前版本暂无更新说明",
                    description = "请检查模块是否已更新至最新版本",
                    featureKey = ""
                )
            )
        }
    }