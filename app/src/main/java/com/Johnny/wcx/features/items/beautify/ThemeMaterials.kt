package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.app.Application
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.createDirsSafe
import java.io.File
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.exists

/**
 * 主题素材层：APK 内置素材提取、4 页壁纸解析、「当前页面该用哪张图」。
 *
 * 只做文件层的事 —— 不碰视图、不碰 hook、不碰开关。
 * 2026-10-27 从 ThemeStore 单体拆出。
 */
internal object ThemeMaterials {

    private const val TAG = "ThemeMaterials"

    private val hostApp: Application? by lazy {
        runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? Application
        }.getOrNull()
    }

    // ══════════════════════════════════════════════════════════
    // 读模块 APK 里的素材
    // ══════════════════════════════════════════════════════════

    /**
     * 读模块 APK 内置素材（背景图 / 点九气泡），三条路兜底。
     *
     * 原来只有 createPackageContext 一条路，在微信进程里一旦被拒，
     * 所有内置主题就只剩一个纯色块 —— 用户感知就是「点了默认主题没效果」。
     */
    fun readBuiltinAsset(assetPath: String): ByteArray? {
        runCatching {
            val pkgCtx = hostApp?.createPackageContext(BuildConfig.APPLICATION_ID, 0)
            pkgCtx?.assets?.open(assetPath)?.use { return it.readBytes() }
        }.onFailure { WeLogger.w(TAG, "createPackageContext 读素材失败：$assetPath（${it.message}）") }

        runCatching {
            ThemeMaterials::class.java.classLoader?.getResourceAsStream("assets/$assetPath")
                ?.use { return it.readBytes() }
        }.onFailure { WeLogger.w(TAG, "ClassLoader 读素材失败：$assetPath（${it.message}）") }

        runCatching {
            val apk = moduleApkPath() ?: return@runCatching
            java.util.zip.ZipFile(apk).use { zip ->
                zip.getEntry("assets/$assetPath")?.let { entry ->
                    zip.getInputStream(entry).use { return it.readBytes() }
                }
            }
        }.onFailure { WeLogger.w(TAG, "ZipFile 读素材失败：$assetPath（${it.message}）") }

        WeLogger.w(TAG, "内置素材三种方式都读不到：$assetPath")
        return null
    }

    /** 模块自身 APK 路径（直接从 zip 读 assets 的兜底）。 */
    private fun moduleApkPath(): String? = runCatching {
        hostApp?.packageManager
            ?.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)
            ?.sourceDir
    }.getOrNull()

    // ══════════════════════════════════════════════════════════
    // 提取到模块数据目录
    // ══════════════════════════════════════════════════════════

    /** 确保某个主题的素材已提取（壁纸 + 左右气泡）。 */
    fun ensureExtracted(theme: ThemeInfo, force: Boolean = false) {
        runCatching {
            val dir = ThemePaths.themeDir(theme)
            val entries = listOf(
                theme.wallpaperAsset to ThemePaths.wallpaperFile(theme),
                theme.bubbleOtherAsset to ThemePaths.bubbleFile(theme, ThemePaths.LEFT_BUBBLE_FILE),
                theme.bubbleSelfAsset to ThemePaths.bubbleFile(theme, ThemePaths.RIGHT_BUBBLE_FILE)
            )
            for ((asset, dest) in entries) {
                // Path 没有 size()，要用 Files.size；0 字节也算「没提取成功」
                if (!force && dest.exists() && Files.size(dest) > 0L) continue
                val bytes = readBuiltinAsset(asset)
                if (bytes == null) {
                    WeLogger.e(TAG, "内置素材提取失败：${theme.id} / $asset（主题将缺少这一项）")
                    continue
                }
                try {
                    dir.createDirsSafe()
                    Files.write(dest, bytes)
                    if (Files.size(dest) <= 0L) WeLogger.w(TAG, "素材写入为空：$asset")
                } catch (e: Throwable) {
                    WeLogger.w(TAG, "提取素材失败：$asset", e)
                }
            }
        }.onFailure { WeLogger.w(TAG, "提取内置素材异常：${it.message}") }
    }

    /** 一次性提取全部内置主题（缩略图直接从本地文件加载）。 */
    fun ensureAllExtracted() {
        runCatching { BuiltinThemes.all.forEach { ensureExtracted(it) } }
            .onFailure { WeLogger.w(TAG, "提取全部素材异常：${it.message}") }
    }

    /** 内置主题缩略图文件；首次调用会把素材从 APK 提取出来。 */
    fun thumbnail(id: String): File? = runCatching {
        val theme = BuiltinThemes.find(id) ?: return@runCatching null
        ensureExtracted(theme)
        ThemePaths.wallpaperFile(theme).takeIf { it.exists() }?.toFile()
    }.getOrNull()

    // ══════════════════════════════════════════════════════════
    // 4 页素材解析（主题包分页优先，内置主题退回全局）
    // ══════════════════════════════════════════════════════════

    /** 4 个页面各自的壁纸文件；内置主题没有分页素材时用同一张全局图。 */
    fun tabWallpaperFiles(activeId: String): List<File?> {
        val pack = ThemePack.active()
        val builtin: File? = BuiltinThemes.find(activeId)?.let { theme ->
            runCatching { ensureExtracted(theme) }
            ThemePaths.wallpaperFile(theme).takeIf { it.exists() }?.toFile()
        }
        return listOf("chats", "contacts", "discover", "me").map { key ->
            pack?.wallpaperFor(key) ?: builtin
        }
    }

    /**
     * 「当前页面该用哪张壁纸」—— 全部规则集中在这一处。
     *
     * - 聊天页：只用微信自带背景；只有开了「随机聊天背景」才用图池随机图，绝不吃主题壁纸
     * - 扫一扫 / 看图页：一律不挂（相机取景、照片会被遮挡）
     * - 只有微信主界面（LauncherUI 的 4 个 Tab）吃主题壁纸，其它页面不挂
     * - 主题包优先，且分页面素材（某页单独设了就用它，没设退回全局）
     */
    fun wallpaperSourceFor(activity: Activity?): java.nio.file.Path? {
        if (activity != null && ThemePages.isChatting(activity)) {
            if (!runCatching { com.Johnny.wcx.preferences.WePrefs.getBoolOrFalse(ThemeExtras.KEY_RANDOM_WALLPAPER) }
                    .getOrDefault(false)
            ) return null
            val pool = runCatching { ThemePack.randomPoolFiles() }.getOrDefault(emptyList())
            return pool.takeIf { it.isNotEmpty() }?.random()?.toPath()
        }
        if (activity != null && (ThemePages.isScanner(activity) || ThemePages.isPhotoViewer(activity))) return null
        if (activity != null && !ThemePages.isLauncher(activity)) return null

        val key = activity?.let { ThemePages.pageWallpaperKey(it) }
        ThemePack.active()?.let { pack ->
            val candidates = buildList {
                if (key != null) pack.wallpaperFor(key)?.let(::add)
                pack.wallpaper()?.let(::add)
            }.distinctBy { it.absolutePath }
            candidates.firstOrNull()?.let { return it.toPath() }
        }
        // 内置主题：素材可能被清理掉（清缓存/换设备），这里顺手补一次提取，
        // 否则重启后只剩一个纯色块，用户会以为「应用了没生效」。
        val builtin = BuiltinThemes.find(activeId) ?: return null
        runCatching { ensureExtracted(builtin) }
            .onFailure { WeLogger.w(TAG, "补提取内置素材失败：${it.message}") }
        val file = ThemePaths.wallpaperFile(builtin).takeIf { it.exists() } ?: return null
        return file
    }
}
