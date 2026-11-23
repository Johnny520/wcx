package com.Johnny.wcx.activity.settings

import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.features.core.BaseFeature

/**
 * 「归属型」功能登记表 —— 全模块唯一的集中化声明处。
 *
 * 原则：一个功能族的能力全部集中在它自己的入口页面里调，**不在功能总列表 / 搜索 / 分类计数里单独出现**。
 * 典型例子：AI 助手 —— 接口配置、提示音、自动回复、双击建议、群聊总结，全部在「实验室 → AI 助手」里，
 * 用户不需要去「API」分类里一个一个找；密友功能同理，全部在「密友功能」分类内。
 *
 * 新增归属型子功能时**只在这里加一行**，功能页、首页搜索、分类计数、已启用统计会自动跟随，不要在多个页面分别写过滤。
 */
internal object InternalFeatureGroups {

    /** 父入口名（与功能页里的入口卡片文案一致），用于注释与诊断展示。 */
    const val AI_ASSISTANT = "AI 助手"

    /** 父入口 -> 归属它的子功能 @Feature name 列表。 */
    private val groups: Map<String, List<String>> = mapOf(
        AI_ASSISTANT to listOf(
            "AI 自动回复",
            "AI 建议双击",
            "群聊总结",
            "AI 档案入口",
            "聊天记录 AI 总结",
        ),
    )

    /** 所有被父入口收编的子功能名。 */
    private val hiddenNames: Set<String> = groups.values.flatten().toSet()

    fun isHidden(featureName: String): Boolean = featureName in hiddenNames

    /** 归属某父入口的子功能名（父页面若要做「已开启 N 项」统计时用）。 */
    fun childrenOf(parent: String): List<String> = groups[parent].orEmpty()
}

/**
 * 功能列表的统一取数口径：总列表里剥掉归属型子功能。
 * 功能页、首页搜索、分类计数、已启用统计都应该用它，保证「列表里看到的 = 能直接找到的」。
 */
internal val VisibleHookItems: List<BaseFeature>
    get() = FeaturesProvider.ALL_HOOK_ITEMS.filterNot { InternalFeatureGroups.isHidden(it.name) }
