package com.Johnny.wcx.features.items.system

import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger

private const val TAG = "VersionChangelog"
private const val KEY_LAST_VERSION = "version_changelog_last_vc"
private const val KEY_UPGRADE_DIALOG_ENABLED = "version_changelog_upgrade_dialog"
private const val KEY_UPGRADE_DIALOG_SHOWN_FOR = "version_changelog_dialog_shown_for"

/**
 * 单条版本更新日志
 */
data class VersionChangelogEntry(
    val type: ChangelogType,
    val summary: String,
    val featureKey: String? = null // 可选，关联功能项名称用于跳转
)

enum class ChangelogType { NEW, FIX, OPTIMIZE }

/**
 * 单个版本的更新日志
 */
data class VersionChangelog(
    val versionCode: Int,    // 对应模块 versionCode
    val versionTag: String,  // 展示用版本标签，如 "v163"
    val entries: List<VersionChangelogEntry>
)

/**
 * ============================================================
 * 全版本更新日志清单 — 按版本号从小到大排列
 * 后续迭代新版本仅在此追加条目，无需修改页面展示代码
 * ============================================================
 */
val VERSION_CHANGELOGS: List<VersionChangelog> = listOf(
    VersionChangelog(
        versionCode = 163,
        versionTag = "v163",
        entries = listOf(
            VersionChangelogEntry(ChangelogType.NEW, "WEX小组件深色模式适配", "WEX首页小组件"),
            VersionChangelogEntry(ChangelogType.NEW, "微信主页侧滑侧边栏（天气/快捷按钮/功能跳转/每日一言）", "微信主页侧滑侧边栏"),
            VersionChangelogEntry(ChangelogType.NEW, "每日一图单击预览大图+长按配置API", "WEX首页小组件"),
            VersionChangelogEntry(ChangelogType.NEW, "近期更新板块默认折叠+状态持久化", null),
            VersionChangelogEntry(ChangelogType.FIX, "修复禁止主页下滑进入最近页面 DexKit Hook", "禁止主页下滑进入最近页面"),
            VersionChangelogEntry(ChangelogType.FIX, "修复朋友圈禁止视频自动播放多重拦截", "禁止朋友圈视频自动播放"),
            VersionChangelogEntry(ChangelogType.FIX, "DexKit全局查找失败防护（不崩溃）", null),
            VersionChangelogEntry(ChangelogType.OPTIMIZE, "近期更新板块自动拉取版本更新日志", null),
            VersionChangelogEntry(ChangelogType.FIX, "修复侧边栏头像无法获取+首页会话空白", "微信主页侧滑侧边栏"),
        )
    ),
    VersionChangelog(
        versionCode = 164,
        versionTag = "v164",
        entries = listOf(
            VersionChangelogEntry(ChangelogType.FIX, "修复侧边栏导致微信首页会话空白消失（延迟初始化+视图隔离）", "微信主页侧滑侧边栏"),
            VersionChangelogEntry(ChangelogType.FIX, "修复侧边栏顶部留白空隙（动态适配状态栏高度）", "微信主页侧滑侧边栏"),
            VersionChangelogEntry(ChangelogType.FIX, "修复侧边栏无法获取本机微信头像（多策略+缓存+昵称）", "微信主页侧滑侧边栏"),
            VersionChangelogEntry(ChangelogType.FIX, "修复WEX每日一图加载失败无兜底占位图", "WEX首页小组件"),
            VersionChangelogEntry(ChangelogType.FIX, "修复WEX首页小组件深色模式适配不完整", "WEX首页小组件"),
            VersionChangelogEntry(ChangelogType.NEW, "模块功能页「近期更新」动态化（版本升级自动读取更新日志）", null),
            VersionChangelogEntry(ChangelogType.OPTIMIZE, "侧滑栏与WEX美化小组件共存校验（独立容器/配置/作用域）", "微信主页侧滑侧边栏"),
            VersionChangelogEntry(ChangelogType.OPTIMIZE, "侧滑栏收起状态彻底移除视图树（GONE）+ 触摸事件隔离", "微信主页侧滑侧边栏"),
            VersionChangelogEntry(ChangelogType.OPTIMIZE, "侧滑栏展开最大宽度限制屏幕65%", "微信主页侧滑侧边栏"),
        )
    )
)

// ==================== 版本检测与匹配 ====================

/** 当前模块 versionCode */
fun currentModuleVersionCode(): Int = HostInfo.versionCode.toInt()

/** 上次记录的版本号 */
private var _lastRecordedVersion: Int? = null
fun lastRecordedVersionCode(): Int {
    if (_lastRecordedVersion == null) {
        _lastRecordedVersion = WePrefs.getIntOrDef(KEY_LAST_VERSION, 0)
    }
    return _lastRecordedVersion!!
}

/** 是否为新版本升级 */
fun isNewVersionUpgrade(): Boolean {
    val current = currentModuleVersionCode()
    val last = lastRecordedVersionCode()
    return current > last
}

/** 记录当前版本号（升级后调用） */
fun recordCurrentVersion() {
    val current = currentModuleVersionCode()
    WePrefs.putInt(KEY_LAST_VERSION, current)
    _lastRecordedVersion = current
    WeLogger.d(TAG, "版本号已记录: $current")
}

// ==================== 获取更新日志 ====================

/**
 * 获取当前版本对应的更新日志
 * 找不到匹配版本时返回所有新版日志（跨版本升级场景）
 */
fun getCurrentVersionChangelog(): List<VersionChangelogEntry> {
    val current = currentModuleVersionCode()
    val last = lastRecordedVersionCode()

    WeLogger.d(TAG, "当前版本: $current, 上次记录版本: $last")

    // 精确匹配当前版本
    val exactMatch = VERSION_CHANGELOGS.find { it.versionCode == current }
    if (exactMatch != null) {
        return exactMatch.entries
    }

    // 跨版本升级：收集 last+1 到 current 之间所有版本的更新条目
    val accumulated = mutableListOf<VersionChangelogEntry>()
    for (vc in VERSION_CHANGELOGS) {
        if (vc.versionCode in (last + 1)..current) {
            accumulated.addAll(vc.entries)
        }
    }

    if (accumulated.isNotEmpty()) {
        WeLogger.d(TAG, "跨版本升级，累计 ${accumulated.size} 条更新")
        return accumulated
    }

    WeLogger.w(TAG, "未找到版本 $current 的更新日志")
    return emptyList()
}

/** 获取指定版本的更新日志（用于展示历史版本） */
fun getChangelogForVersion(versionCode: Int): VersionChangelog? =
    VERSION_CHANGELOGS.find { it.versionCode == versionCode }

// ==================== 首次升级弹窗 ====================

/** 升级弹窗开关 */
var upgradeDialogEnabled by WePrefs.prefOption(KEY_UPGRADE_DIALOG_ENABLED, true)

/** 检查是否需要展示升级弹窗：新版本 + 开关开启 + 当前版本未弹过 */
fun shouldShowUpgradeDialog(): Boolean {
    if (!upgradeDialogEnabled) return false
    if (!isNewVersionUpgrade()) return false
    val current = currentModuleVersionCode()
    val shownFor = WePrefs.getIntOrDef(KEY_UPGRADE_DIALOG_SHOWN_FOR, 0)
    return current > shownFor
}

/** 标记当前版本升级弹窗已展示 */
fun markUpgradeDialogShown() {
    val current = currentModuleVersionCode()
    WePrefs.putInt(KEY_UPGRADE_DIALOG_SHOWN_FOR, current)
    WeLogger.d(TAG, "升级弹窗已标记展示: $current")
}

// ==================== 转换为 RecentUpdateItem（兼容现有UI） ====================

fun VersionChangelogEntry.toRecentUpdateItem(): RecentUpdateItem {
    val prefix = when (type) {
        ChangelogType.NEW -> "新增"
        ChangelogType.FIX -> "修复"
        ChangelogType.OPTIMIZE -> "优化"
    }
    return RecentUpdateItem(
        name = "[$prefix] $summary",
        description = if (featureKey != null) "点击跳转至：$featureKey" else "通用更新",
        featureKey = featureKey ?: ""
    )
}