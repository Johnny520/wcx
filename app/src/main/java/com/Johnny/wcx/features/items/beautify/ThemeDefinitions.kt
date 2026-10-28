package com.Johnny.wcx.features.items.beautify

import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.fs.createDirsSafe
import kotlin.io.path.div
import kotlin.io.path.listDirectoryEntries

/**
 * 主题定义与路径 —— 只描述「主题是什么、素材放哪」，不承担 IO / 渲染 / hook 职责。
 *
 * 2026-10-27 主题功能整体重写的第一层：原来这些东西全部内嵌在 1300 多行的 ThemeStore 单体里，
 * 现在拆出来作为纯数据与定位的唯一真理源。
 */

/** 一个内置主题的完整定义。 */
data class ThemeInfo(
    val id: String,
    val name: String,
    val wallpaperAsset: String,
    val bubbleOtherAsset: String, // 对方气泡（left）
    val bubbleSelfAsset: String,  // 自己气泡（right）
    val previewColor: Long
)

/** 内置主题清单（唯一真理源）。 */
internal object BuiltinThemes {

    private const val TAG = "BuiltinThemes"

    val all: List<ThemeInfo> = listOf(
        ThemeInfo(
            id = "hellokitty", name = "Hello Kitty",
            wallpaperAsset = "theme_store/wallpaper/hellokitty.jpg",
            bubbleOtherAsset = "theme_store/bubbles/hellokitty/left_bubble.9.png",
            bubbleSelfAsset = "theme_store/bubbles/hellokitty/right_bubble.9.png",
            previewColor = 0xFFFFB6C1L
        ),
        ThemeInfo(
            id = "bear", name = "小熊",
            wallpaperAsset = "theme_store/wallpaper/bear.jpg",
            bubbleOtherAsset = "theme_store/bubbles/bear/left_bubble.9.png",
            bubbleSelfAsset = "theme_store/bubbles/bear/right_bubble.9.png",
            previewColor = 0xFFD7A76FL
        ),
        ThemeInfo(
            id = "puppy", name = "线条小狗",
            wallpaperAsset = "theme_store/wallpaper/puppy.jpg",
            bubbleOtherAsset = "theme_store/bubbles/puppy/left_bubble.9.png",
            bubbleSelfAsset = "theme_store/bubbles/puppy/right_bubble.9.png",
            previewColor = 0xFFFFE082L
        ),
        ThemeInfo(
            id = "chiikawa", name = "吉伊卡哇",
            wallpaperAsset = "theme_store/wallpaper/chiikawa.jpg",
            bubbleOtherAsset = "theme_store/bubbles/chiikawa/left_bubble.9.png",
            bubbleSelfAsset = "theme_store/bubbles/chiikawa/right_bubble.9.png",
            previewColor = 0xFFA8D8F0L
        ),
        ThemeInfo(
            id = "food_puppy", name = "美食小狗",
            wallpaperAsset = "theme_store/wallpaper/food_puppy.jpg",
            bubbleOtherAsset = "theme_store/bubbles/food_puppy/left_bubble.9.png",
            bubbleSelfAsset = "theme_store/bubbles/food_puppy/right_bubble.9.png",
            previewColor = 0xFFF7E0B0L
        ),
        ThemeInfo(
            id = "bunny", name = "小兔子",
            wallpaperAsset = "theme_store/wallpaper/bunny.jpg",
            bubbleOtherAsset = "theme_store/bubbles/bunny/left_bubble.9.png",
            bubbleSelfAsset = "theme_store/bubbles/bunny/right_bubble.9.png",
            previewColor = 0xFFFAD0DCL
        )
    )

    fun find(id: String): ThemeInfo? = all.firstOrNull { it.id == id }

    /** 主题色（壁纸缺失时兜底显示）。 */
    fun previewColor(id: String): Long = find(id)?.previewColor ?: 0xFFF0F0F0L
}

/**
 * 主题相关的全部目录与文件定位。
 *
 * 目录一律懒创建；调用方只需要知道「文件在哪」，不要各自拼路径。
 */
internal object ThemePaths {

    private const val TAG = "ThemePaths"

    /** 对方气泡（左 / 收到）。 */
    const val LEFT_BUBBLE_FILE = "left_bubble.9.png"

    /** 自己气泡（右 / 发出）。 */
    const val RIGHT_BUBBLE_FILE = "right_bubble.9.png"

    val storeDir by lazy {
        runCatching { (KnownPaths.moduleData / "theme_store").createDirsSafe() }
            .onFailure { WeLogger.w(TAG, "创建主题目录失败：${it.message}") }
            .getOrDefault(KnownPaths.moduleData / "theme_store")
    }

    val builtinDir by lazy {
        runCatching { (storeDir / "builtin").createDirsSafe() }
            .onFailure { WeLogger.w(TAG, "创建内置素材目录失败：${it.message}") }
            .getOrDefault(storeDir / "builtin")
    }

    /** 应用主题前，用户原有气泡的一次性备份（恢复默认时还回去）。 */
    val backupBubbleDir by lazy {
        runCatching { (storeDir / "backup_bubbles").createDirsSafe() }
            .onFailure { WeLogger.w(TAG, "创建气泡备份目录失败：${it.message}") }
            .getOrDefault(storeDir / "backup_bubbles")
    }

    fun themeDir(theme: ThemeInfo): java.nio.file.Path = builtinDir / theme.id

    fun wallpaperFile(theme: ThemeInfo): java.nio.file.Path =
        themeDir(theme) / theme.wallpaperAsset.substringAfterLast('/')

    fun bubbleFile(theme: ThemeInfo, sideFileName: String): java.nio.file.Path =
        themeDir(theme) / sideFileName

    /** 当前生效气泡（写进模块 assets，聊天页读的就是这两个）。 */
    fun activeBubble(sideFileName: String): java.nio.file.Path = KnownPaths.moduleAssets / sideFileName

    /** 备份里的一侧气泡。 */
    fun backupBubble(sideFileName: String): java.nio.file.Path = backupBubbleDir / sideFileName

    /** 备份目录是否已有内容（只备份第一次，之后一直保留直到恢复默认）。 */
    fun hasBubbleBackup(): Boolean = runCatching {
        backupBubbleDir.listDirectoryEntries().isNotEmpty()
    }.getOrDefault(false)
}

/**
 * 内置主题的用户自定义别名 / 描述（长按主题卡可改），纯持久化读写。
 */
internal object ThemeAliases {

    fun name(id: String): String = runCatching {
        WePrefs.getStringOrDef("theme_alias_$id", "")
    }.getOrDefault("")

    fun setName(id: String, alias: String) {
        runCatching { WePrefs.putString("theme_alias_$id", alias.trim()) }
            .onFailure { WeLogger.w("ThemeAliases", "写入主题别名失败：${it.message}") }
    }

    fun desc(id: String): String = runCatching {
        WePrefs.getStringOrDef("theme_desc_$id", "")
    }.getOrDefault("")

    fun setDesc(id: String, desc: String) {
        runCatching { WePrefs.putString("theme_desc_$id", desc.trim()) }
            .onFailure { WeLogger.w("ThemeAliases", "写入主题描述失败：${it.message}") }
    }

    /** 展示名 = 自定义别名优先。 */
    fun displayName(id: String, fallback: String): String = name(id).ifBlank { fallback }
}
