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
 * 本版本近期更新列表
 * 只有新增/改动的功能才列在这里
 */
val RECENT_UPDATES = listOf(
    RecentUpdateItem(
        name = "禁止主页下滑进入最近页面",
        description = "禁止主页下滑手势，同时支持通过加号菜单和标题栏图标唤起微信原生小程序面板",
        featureKey = "禁止主页下滑进入最近页面"
    ),
    RecentUpdateItem(
        name = "群事件提示显示wxid",
        description = "新增独立开关控制群事件提示中是否显示wxid编号",
        featureKey = "群成员行为监控"
    ),
    RecentUpdateItem(
        name = "消息过滤屏蔽功能增强",
        description = "新增屏蔽规则生效范围（指定群聊）、选择会话弹窗搜索框",
        featureKey = "消息过滤"
    ),
    RecentUpdateItem(
        name = "朋友圈关键词屏蔽优化",
        description = "新增好友范围配置、关键词分组管理、白名单、匹配模式切换",
        featureKey = "朋友圈关键词屏蔽"
    ),
    RecentUpdateItem(
        name = "朋友圈广告拦截增强",
        description = "双重拦截逻辑（数据源层+View渲染层），彻底堵住广告漏网",
        featureKey = "拦截朋友圈广告"
    ),
    RecentUpdateItem(
        name = "禁止朋友圈视频自动播放增强",
        description = "多重Hook点拦截，覆盖视频预加载和自动播放全部路径",
        featureKey = "禁止朋友圈视频自动播放"
    ),
    RecentUpdateItem(
        name = "语音面板伪装时长",
        description = "语音面板发送自定义音频时统一应用伪装语音时长配置",
        featureKey = "伪装语音时长"
    ),
    RecentUpdateItem(
        name = "移除旧变量{链接昵称}",
        description = "彻底移除{链接昵称}，自动迁移旧配置到\$nickname",
        featureKey = "群成员行为监控"
    ),
    RecentUpdateItem(
        name = "自动@新人入群欢迎",
        description = "入群欢迎消息自动@新人，支持分群独立欢迎语配置",
        featureKey = "群成员行为监控"
    ),
    RecentUpdateItem(
        name = "Java脚本引擎全面增强",
        description = "自动创建脚本目录+示例脚本(demo_sample)；全部脚本执行逻辑增加异常捕获，错误输出到模块日志，脚本出错不再闪退",
        featureKey = "脚本引擎 (Java)"
    ),
)