package h.Hchat.hooks.items.script.agent

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import h.Hchat.hooks.items.script.market.PluginMarketSettings
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 终端显示字体：不随模块内置，用户选中时才从更新服务器下载，存 Hchat/fonts 复用
object TerminalFonts {

    // 默认跟随系统等宽字体，与加字体功能之前的表现一致
    const val DEFAULT_ID = "system"

    private const val PREFS_NAME = "Hchat_terminal_config"
    private const val KEY_FONT_ID = "terminal_font_id"
    private const val FONT_DIR = "fonts"

    data class Font(val id: String, val label: String, val sizeKb: Int, val fileName: String)

    // 顺序即设置页与终端菜单里的显示顺序，第一项是默认值
    val ALL = listOf(
        Font(DEFAULT_ID, "系统默认", 0, ""),
        Font("jetbrains_mono", "JetBrains Mono", 199, "JetBrainsMono-Regular.ttf"),
        Font("source_code_pro", "Source Code Pro", 205, "SourceCodePro-Regular.ttf"),
        Font("ubuntu_mono", "Ubuntu Mono", 201, "UbuntuMono-Regular.ttf"),
    )

    private val cache = ConcurrentHashMap<String, Typeface>()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hchat-term-font").apply { isDaemon = true }
    }

    fun byId(id: String?): Font = ALL.firstOrNull { it.id == id } ?: ALL.first()

    fun selectedId(context: Context): String {
        val saved = runCatching {
            HchatStorage.preferences(context, PREFS_NAME).getString(KEY_FONT_ID, DEFAULT_ID)
        }.getOrNull()
        return if (ALL.any { it.id == saved }) saved!! else DEFAULT_ID
    }

    fun select(context: Context, id: String) {
        val target = byId(id).id
        runCatching {
            HchatStorage.preferences(context, PREFS_NAME).edit()
                .putString(KEY_FONT_ID, target).apply()
        }.onFailure { HLog.e("[Hchat:Term] 保存终端字体失败: ${it.message}", it) }
    }

    fun isDownloaded(context: Context, font: Font): Boolean = fontFile(context, font) != null

    fun downloadUrl(context: Context, font: Font): String =
        PluginMarketSettings.serviceUrl(context).trimEnd('/') + "/fonts/" + font.fileName

    // 取字体对象；未下载或加载失败一律回退系统等宽字体（字体不对可以忍，终端打不开不行）
    fun typeface(context: Context, id: String? = null): Typeface {
        val font = byId(id ?: selectedId(context))
        if (font.fileName.isEmpty()) return Typeface.MONOSPACE
        cache[font.id]?.let { return it }
        val loaded = fontFile(context, font)?.let { file ->
            runCatching { Typeface.createFromFile(file) }
                .onFailure { HLog.e("[Hchat:Term] 加载字体 ${font.id} 失败: ${it.message}", it) }
                .getOrNull()
        }
        if (loaded == null) return Typeface.MONOSPACE
        cache[font.id] = loaded
        return loaded
    }

    // 后台下载字体到 Hchat/fonts/<文件名>-<字节数>，清掉同名旧副本；成功回调在主线程
    fun download(context: Context, font: Font, onSuccess: () -> Unit, onError: (String) -> Unit) {
        worker.execute {
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            fun finish(ok: Boolean, msg: String? = null) {
                if (!done.compareAndSet(false, true)) return
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        if (ok) onSuccess() else onError(msg ?: "未知错误")
                    }
                }
            }
            try {
                val request = Request.Builder().url(downloadUrl(context, font)).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) { finish(false, "HTTP ${response.code}"); return@execute }
                    val body = response.body ?: run { finish(false, "响应为空"); return@execute }
                    val bytes = body.bytes()
                    val dir = File(HchatStorage.storageDir(context), FONT_DIR).apply { mkdirs() }
                    val target = File(dir, "${font.fileName}-${bytes.size}")
                    if (target.length() != bytes.size.toLong()) {
                        dir.listFiles()?.forEach { old ->
                            if (old.name.startsWith("${font.fileName}-") && old.name != target.name) old.delete()
                        }
                        target.writeBytes(bytes)
                    }
                    val tf = runCatching { Typeface.createFromFile(target) }.getOrNull()
                    if (tf == null) { finish(false, "字体文件损坏"); return@execute }
                    cache[font.id] = tf
                    finish(true)
                }
            } catch (e: Throwable) {
                HLog.e("[Hchat:Term] 下载字体 ${font.id} 失败: ${e.message}", e)
                finish(false, e.message)
            }
        }
    }

    private fun fontFile(context: Context, font: Font): File? {
        if (font.fileName.isEmpty()) return null
        val dir = File(HchatStorage.storageDir(context), FONT_DIR)
        if (!dir.isDirectory) return null
        return dir.listFiles()
            ?.firstOrNull { it.name.startsWith("${font.fileName}-") && it.length() > 0L }
    }
}