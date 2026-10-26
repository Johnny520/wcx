package com.Johnny.wcx.features.items.beautify

import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.WePrefs
import com.Johnny.wcx.utils.fs.KnownPaths
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

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
            runCatching { importZip(zip) }
                .onSuccess { zip.delete() }
                .onFailure { WeLogger.w(TAG, "自动导入失败：${zip.name} / ${it.message}") }
                .getOrNull()
        }
    }

    /** 主题根目录，供设置页展示/引导用户放素材。 */
    fun themesDir(): File = root
}
