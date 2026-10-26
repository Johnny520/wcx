package com.Johnny.wcx.features.items.beautify

import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.fs.KnownPaths
import org.json.JSONObject
import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 主题包（Theme Pack）。
 *
 * 目标：把分散的美化维度收进**一个可整体切换的主题包**，像系统主题那样一次换全套：
 * 壁纸、左右气泡、配色、文案、字体、底部 Tab 图标、表情面板图标、悬浮球图标。
 *
 * 目录结构（解压后位于 `.../WCX/themes/<id>/`）：
 * ```
 * manifest.json          {
 *                          "name": "主题名", "author": "作者", "version": "1.0",
 *                          "colors": { "brand": "#FF6B6B" },
 *                          "strings": { "app_name": "微信" },
 *                          "wallpaper": "wallpaper.png",
 *                          "bubble_left": "bubble_left.9.png",
 *                          "bubble_right": "bubble_right.9.png",
 *                          "font": "font/nickname.ttf",
 *                          "tab": ["tab/chats.png","tab/contacts.png","tab/discover.png","tab/me.png"],
 *                          "panel": "panel"
 *                        }
 * wallpaper.png / bubble_*.9.png / font/... / tab/... / panel/...
 * ```
 *
 * 所有资源都按相对路径从 manifest 里取，缺哪一项就退回内置能力，不会因为少一个文件就整套失效。
 */
object ThemePack {

    private const val TAG = "ThemePack"
    private const val MANIFEST = "manifest.json"
    private const val PREF_CURRENT = "theme_pack_current"

    /** 一个已安装的主题包。 */
    data class Pack(
        val id: String,
        val name: String,
        val author: String,
        val version: String,
        val dir: File,
        val manifest: JSONObject,
    ) {
        /** 取包内文件，路径越界（../）一律拒绝。 */
        fun asset(relative: String?): File? {
            if (relative.isNullOrBlank()) return null
            val file = File(dir, relative)
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
            val root = runCatching { dir.canonicalFile }.getOrNull() ?: return null
            if (!canonical.path.startsWith(root.path + File.separator)) return null
            return canonical.takeIf { it.isFile }
        }

        fun color(key: String): String? =
            manifest.optJSONObject("colors")?.optString(key)?.takeIf { it.isNotBlank() }

        fun text(key: String): String? =
            manifest.optJSONObject("strings")?.optString(key)?.takeIf { it.isNotBlank() }

        fun wallpaper(): File? = asset(manifest.optString("wallpaper"))

        /**
         * 分页面壁纸：某个页面单独设了就用它，没设就退回全局壁纸。
         *
         * @param key chats / contacts / discover / me / chatting
         */
        fun wallpaperFor(key: String): File? {
            val specific = asset(manifest.optString("wallpaper_$key"))
            return specific ?: wallpaper()
        }

        fun bubbleOther(): File? = asset(manifest.optString("bubble_left"))

        fun bubbleSelf(): File? = asset(manifest.optString("bubble_right"))

        fun font(): File? = asset(manifest.optString("font"))

        /** 底部四个 Tab（会话/通讯录/发现/我）的图标，下标与微信一致。 */
        fun tabIcon(index: Int): File? {
            val arr = manifest.optJSONArray("tab") ?: return null
            return asset(arr.optString(index))
        }

        /** 表情面板图标目录下的第 index 张（按文件名排序，保证稳定）。 */
        fun panelIcons(): List<File> {
            val rel = manifest.optString("panel").takeIf { it.isNotBlank() } ?: return emptyList()
            val dir = File(this.dir, rel)
            return dir.listFiles { f -> f.isFile && f.name.endsWith(".png", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?: emptyList()
        }
    }

    private val root: File by lazy {
        File(KnownPaths.moduleData.toFile(), "themes").apply { mkdirs() }
    }

    /** 全部已安装主题包（按名字排序）。 */
    fun list(): List<Pack> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { load(it) }.sortedBy { it.name.lowercase() }
    }

    fun find(id: String): Pack? {
        if (id.isBlank()) return null
        return load(File(root, id))
    }

    private fun load(dir: File): Pack? {
        val manifestFile = File(dir, MANIFEST)
        if (!manifestFile.isFile) return null
        val json = runCatching { JSONObject(manifestFile.readText()) }.getOrElse {
            WeLogger.w(TAG, "主题包清单解析失败：${dir.name} / ${it.message}")
            return null
        }
        return Pack(
            id = dir.name,
            name = json.optString("name").takeIf { it.isNotBlank() } ?: dir.name,
            author = json.optString("author"),
            version = json.optString("version"),
            dir = dir,
            manifest = json,
        )
    }

    var currentId: String
        get() = WePrefs.getString(PREF_CURRENT) ?: ""
        set(value) {
            WePrefs.putString(PREF_CURRENT, value)
        }

    fun current(): Pack? = find(currentId)

    /** 当前主题包，且必须在启用状态下才返回（关掉主题包时其它美化维度按各自开关走）。 */
    fun active(): Pack? {
        if (currentId.isBlank()) return null
        return find(currentId)?.also {
            if (!it.dir.isDirectory) {
                WeLogger.w(TAG, "主题包目录不存在：${it.id}")
                return null
            }
        }
    }

    /**
     * 导入主题包（zip）。解压到 `themes/<id>/`，成功后**不自动应用**，
     * 由调用方决定是否切换——导入失败时不留半成品目录。
     */
    fun importZip(zip: File): Result<Pack> = runCatching {
        require(zip.isFile) { "文件不存在" }
        val id = "pack_" + System.currentTimeMillis().toString(36)
        val target = File(root, id)
        target.mkdirs()

        var entries = 0
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name
                // 防 Zip-Slip：拒绝绝对路径与越界
                val out = File(target, name)
                val canonical = out.canonicalFile
                if (!canonical.path.startsWith(target.canonicalFile.path + File.separator)) {
                    WeLogger.w(TAG, "跳过越界条目：$name")
                    continue
                }
                if (entry.isDirectory) {
                    canonical.mkdirs()
                } else {
                    canonical.parentFile?.mkdirs()
                    canonical.outputStream().use { output -> input.copyTo(output) }
                    entries++
                }
            }
        }
        if (entries == 0) {
            target.deleteRecursively()
            error("压缩包里没有任何文件")
        }
        // 有的包会把内容套一层目录，这里向下找一层 manifest
        if (!File(target, MANIFEST).isFile) {
            val inner = target.listFiles()?.firstOrNull { File(it, MANIFEST).isFile }
            if (inner != null) {
                inner.listFiles()?.forEach { it.renameTo(File(target, it.name)) }
                inner.deleteRecursively()
            }
        }
        val pack = load(target) ?: run {
            target.deleteRecursively()
            error("缺少 $MANIFEST 或格式不正确")
        }
        WeLogger.i(TAG, "主题包已导入：${pack.name}（${pack.id}），共 $entries 个文件")
        pack
    }

    fun delete(id: String): Boolean {
        if (id.isBlank()) return false
        if (currentId == id) currentId = ""
        val dir = File(root, id)
        val ok = dir.exists() && dir.deleteRecursively()
        if (ok) WeLogger.i(TAG, "主题包已删除：$id")
        return ok
    }

    /**
     * 把直接丢进 themes 目录的 zip 自动装上（装完删掉 zip）。
     *
     * 这样用户不需要文件选择器：用任意文件管理器把主题包丢进目录，回来点「刷新」即可。
     */
    fun importPendingZips(): List<Pack> {
        val zips = root.listFiles { f -> f.isFile && f.name.endsWith(".zip", ignoreCase = true) }
            ?: return emptyList()
        return zips.mapNotNull { zip ->
            // importZip 本身已返回 Result，不能再套一层 runCatching（会变成 Result<Result<Pack>>）
            importZip(zip)
                .onSuccess { zip.delete() }
                .onFailure { WeLogger.w(TAG, "自动导入失败：${zip.name} / ${it.message}") }
                .getOrNull()
        }
    }

    /**
     * 把主题包打成 zip，直接分享给别人导入即可。
     * 输出到 `themes/exported/`，文件名取自主题名（非法字符替换掉）。
     */
    fun exportZip(pack: Pack): Result<File> = runCatching {
        val outDir = File(root, "exported").apply { mkdirs() }
        val safeName = pack.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { pack.id }
        val out = File(outDir, "$safeName.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            pack.dir.walkTopDown()
                .filter { it.isFile && it.name != out.name }
                .forEach { file ->
                    val entryName = file.relativeTo(pack.dir).invariantSeparatorsPath
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { input -> input.copyTo(zos) }
                    zos.closeEntry()
                }
        }
        WeLogger.i(TAG, "主题包已导出：${out.absolutePath}（${out.length() / 1024} KB）")
        out
    }

    /**
     * 用现成的几张图生成一个主题包（用于把内置主题也导出分享）。
     */
    fun createPackFromFiles(
        name: String,
        wallpaper: File?,
        bubbleLeft: File?,
        bubbleRight: File?,
    ): Result<Pack> = runCatching {
        val id = "pack_" + System.currentTimeMillis().toString(36)
        val dir = File(root, id).apply { mkdirs() }
        val manifest = JSONObject().apply {
            put("name", name)
            put("author", "WCX")
            put("version", "1.0")
        }
        wallpaper?.takeIf { it.isFile }?.let {
            it.copyTo(File(dir, "wallpaper.png"), overwrite = true)
            manifest.put("wallpaper", "wallpaper.png")
        }
        bubbleLeft?.takeIf { it.isFile }?.let {
            it.copyTo(File(dir, "bubble_left.9.png"), overwrite = true)
            manifest.put("bubble_left", "bubble_left.9.png")
        }
        bubbleRight?.takeIf { it.isFile }?.let {
            it.copyTo(File(dir, "bubble_right.9.png"), overwrite = true)
            manifest.put("bubble_right", "bubble_right.9.png")
        }
        File(dir, MANIFEST).writeText(manifest.toString(2))
        load(dir) ?: error("生成主题包清单失败")
    }

    /**
     * 新建一个空主题包：只写清单，图片留空。
     *
     * 自由度最高的用法——有人只想换两个气泡、不想换壁纸，那就只补气泡那两张图，
     * 其余项留空即保持微信原样，不会因为缺图整套失效。
     */
    fun createEmptyPack(name: String, author: String = "我"): Result<Pack> = runCatching {
        val id = "pack_" + System.currentTimeMillis().toString(36)
        val dir = File(root, id).apply { mkdirs() }
        val manifest = JSONObject().apply {
            put("name", name)
            put("author", author)
            put("version", "1.0")
        }
        File(dir, MANIFEST).writeText(manifest.toString(2))
        load(dir) ?: error("新建主题包失败")
    }

    /**
     * 给主题包写入一张图片。
     *
     * @param role wallpaper / bubble_left / bubble_right
     * @param ninePatch 气泡必须是 9-patch（.9.png），由调用方决定文件后缀
     */
    fun writeImage(pack: Pack, context: Context, uri: Uri, role: String, ninePatch: Boolean = false): Pack? {
        val fileName = when (role) {
            "wallpaper" -> "wallpaper.png"
            "wallpaper_chats" -> "wallpaper_chats.png"
            "wallpaper_contacts" -> "wallpaper_contacts.png"
            "wallpaper_discover" -> "wallpaper_discover.png"
            "wallpaper_me" -> "wallpaper_me.png"
            "wallpaper_chatting" -> "wallpaper_chatting.png"
            "bubble_left" -> if (ninePatch) "bubble_left.9.png" else "bubble_left.png"
            "bubble_right" -> if (ninePatch) "bubble_right.9.png" else "bubble_right.png"
            else -> return null
        }
        val ok = runCatching {
            val dest = File(pack.dir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } != null
        }.onFailure { WeLogger.w(TAG, "写入图片失败：$role / ${it.message}") }.getOrDefault(false)
        if (!ok) return null

        val manifestFile = File(pack.dir, MANIFEST)
        val manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrElse { JSONObject() }
        manifest.put(role, fileName)
        manifestFile.writeText(manifest.toString(2))
        WeLogger.i(TAG, "主题包 ${pack.name} 已更新 $role -> $fileName")
        return load(pack.dir)
    }

    /** 导出目录，分享时从这里取 zip。 */
    fun exportDir(): File = File(root, "exported").apply { mkdirs() }

    /** 主题根目录，供设置页展示/引导用户放素材。 */
    fun themesDir(): File = root
}
