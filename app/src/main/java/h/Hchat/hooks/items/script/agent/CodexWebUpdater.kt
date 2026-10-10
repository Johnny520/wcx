package h.Hchat.hooks.items.script.agent

import android.content.Context
import h.Hchat.hooks.items.script.market.PluginMarketSettings
import h.Hchat.utils.HLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// 服务器清单驱动的 codex 网页版运行时：下载 → 校验 sha256 → 切换 current → 自检，失败回滚
object CodexWebUpdater {
    private const val TAG = "[Hchat:CodexWebUpdater]"

    private const val MANIFEST_PATH = "/codex/web-version.json"

    private const val WEB_ROOT_REL = "opt/codex-web"

    private const val CACHE_DIR = ".cache"
    private const val PKG_NAME = "codexweb-pkg.tar.gz"
    private const val PART_NAME = "codexweb-pkg.tar.gz.part"
    private const val META_NAME = "codexweb-pkg.tar.gz.part.meta"
    private const val SCRIPT_NAME = "codexweb_update.sh"

    private const val REV_MARKER = ".codexweb_rev_DO_NOT_REMOVE"
    private const val BASELINE_REV = 1

    private const val UPDATE_TIMEOUT_SECONDS = 1800

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .callTimeout(3600, TimeUnit.SECONDS)
        .build()

    // 下载跑在模块自己的线程上：离开设置页（界面协程被取消）也继续，中断后下次从断点续传
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hchat-codex-web").apply { isDaemon = true }
    }

    @Volatile
    private var busy = false

    @Volatile
    private var progressPercent = 0

    @Volatile
    private var progressStage = ""

    @Volatile
    private var failureMessage = ""

    fun isBusy(): Boolean = busy

    fun progress(): Int = progressPercent

    fun stage(): String = progressStage

    fun lastError(): String = failureMessage

    // 立即返回，任务在后台线程跑；已有任务时返回 false
    fun start(context: Context, release: Release): Boolean {
        if (busy) return false
        busy = true
        progressPercent = 0
        progressStage = "准备下载"
        failureMessage = ""
        val app = context.applicationContext
        worker.execute {
            val result = update(app, release) { pct, msg ->
                progressPercent = pct
                progressStage = msg
            }
            failureMessage = result.exceptionOrNull()?.message.orEmpty()
            if (result.isSuccess) progressPercent = 100
            busy = false
        }
        return true
    }

    data class Release(
        val version: String,
        val rev: Int,
        val downloadUrl: String,
        val sha256: String,
        val size: Long,
        val notes: String,
    )

    data class Installed(val version: String)

    fun webRootDir(context: Context): File =
        File(ProotEnvironment.sandboxDir(context), WEB_ROOT_REL)

    fun currentDir(context: Context): File = File(webRootDir(context), "current")

    fun isInstalled(context: Context): Boolean =
        File(File(currentDir(context), "bin"), "node").isFile

    fun installed(context: Context): Installed? {
        if (!isInstalled(context)) return null
        val pkg = File(currentDir(context), "package.json")
        if (!pkg.isFile) return null
        return runCatching {
            val version = JSONObject(pkg.readText()).optString("version").trim()
            version.takeIf { it.isNotBlank() }?.let { Installed(it) }
        }.getOrNull()
    }

    fun installedRev(context: Context): Int = runCatching {
        val text = File(webRootDir(context), REV_MARKER).readText().trim()
        text.toIntOrNull() ?: BASELINE_REV
    }.getOrDefault(BASELINE_REV)

    fun needsUpdate(context: Context, remote: Release): Boolean {
        if (!isInstalled(context)) return true
        return remote.rev > installedRev(context)
    }

    fun check(context: Context): Result<Release> = runCatching {
        val base = PluginMarketSettings.serviceUrl(context).trim().trimEnd('/')
        check(base.isNotBlank()) { "请先在设置里配置插件仓库地址" }
        val request = Request.Builder()
            .url("$base$MANIFEST_PATH")
            .header("Accept", "application/json")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "获取 codex 网页版清单失败: HTTP ${response.code}" }
            val text = response.body?.string().orEmpty()
            val obj = runCatching { JSONObject(text) }.getOrElse { error("codex 网页版清单格式错误") }
            val version = obj.optString("version").trim()
            val rawUrl = obj.optString("url").trim()
            check(version.isNotBlank() && rawUrl.isNotBlank()) { "codex 网页版清单缺少 version/url" }
            Release(
                version = version,
                rev = obj.optInt("rev", BASELINE_REV),
                downloadUrl = if (rawUrl.startsWith("http")) rawUrl else base + rawUrl,
                sha256 = obj.optString("sha256").trim(),
                size = obj.optLong("size", 0L),
                notes = obj.optString("notes").trim(),
            )
        }
    }

    fun update(
        context: Context,
        release: Release,
        onProgress: ((Int, String) -> Unit)? = null,
    ): Result<Installed> = runCatching {
        val home = ProotEnvironment.homeDir(context)
        val cache = File(home, CACHE_DIR).apply { mkdirs() }
        val tarball = File(cache, PKG_NAME)
        val script = File(cache, SCRIPT_NAME)
        val root = webRootDir(context)
        check(File(ProotEnvironment.sandboxDir(context), "opt/codex/packages/standalone/current").exists()) {
            "终端环境未就绪，请先在设置里安装 Ubuntu 终端环境"
        }

        report(onProgress, 0, "准备下载")
        download(context, release, tarball) { pct -> report(onProgress, pct, "下载运行环境 $pct%") }

        report(onProgress, 92, "解压并切换版本")
        script.writeText(UPDATE_SCRIPT)
        val cmd = "sh /root/$CACHE_DIR/$SCRIPT_NAME '${release.version}' '/root/$CACHE_DIR/$PKG_NAME'"
        val result = ProotEnvironment.exec(context, cmd, timeoutSeconds = UPDATE_TIMEOUT_SECONDS)
        val ok = result.output.contains("HCHAT_WEB_OK")
        if (!ok) {
            val tail = result.output.trim().lines().filter { it.isNotBlank() }.takeLast(6).joinToString(" | ")
            HLog.e("$TAG 更新失败: ${result.exitInfo} $tail")
            error("更新失败: ${tail.ifBlank { "exit=${result.exitInfo}" }}")
        }

        report(onProgress, 97, "校验新版本")
        val after = installed(context) ?: error("更新后读不到网页版版本信息")
        File(root, REV_MARKER).writeText(release.rev.toString())
        check(installedRev(context) == release.rev) { "版本标记写入失败" }
        runCatching { tarball.delete() }
        report(onProgress, 100, "已更新到 ${after.version}")
        after
    }.onFailure {
        // 保留已下载的包：下次直接复用或从断点续传，不再重下 171MB
        HLog.e("$TAG update failed: ${it.message}", it)
    }

    private fun report(cb: ((Int, String) -> Unit)?, pct: Int, msg: String) {
        runCatching { cb?.invoke(pct, msg) }
    }

    private fun download(
        context: Context,
        release: Release,
        target: File,
        onProgress: (Int) -> Unit,
    ) {
        if (release.size > 0 && target.isFile && target.length() == release.size &&
            checksumOk(target, release.sha256)
        ) {
            onProgress(90)
            return
        }

        val part = File(target.parentFile, PART_NAME)
        val meta = File(target.parentFile, META_NAME)
        val mark = "${release.sha256}:${release.size}"
        if (part.isFile && runCatching { meta.readText().trim() }.getOrDefault("") != mark) part.delete()
        runCatching { meta.writeText(mark) }

        var have = part.length()
        if (release.size > 0 && have > release.size) {
            part.delete()
            have = 0
        }
        if (have > 0 && release.size in 1..have && checksumOk(part, release.sha256)) {
            onProgress(90)
            finish(part, target, meta)
            return
        }

        var attempt = 0
        var failure: Throwable? = null
        var succeeded = false
        // 网络抖动就自动从断点接着下，最多 5 次
        while (attempt < 5 && !succeeded) {
            attempt++
            have = if (release.size > 0 && part.length() > release.size) 0 else part.length()
            var restart = false
            var ok = false
            try {
                val builder = Request.Builder().url(release.downloadUrl).get()
                if (have > 0) builder.header("Range", "bytes=$have-")
                httpClient.newCall(builder.build()).execute().use { response ->
                    if (response.code == 416) {
                        // 服务器认为已下完或范围非法：清掉重来
                        part.delete()
                        have = 0
                        restart = true
                    } else if (!response.isSuccessful) {
                        check(false) { "下载运行环境失败: HTTP ${response.code}" }
                    } else {
                        val resuming = have > 0 && response.code == 206
                        if (!resuming) have = 0
                        val body = response.body ?: error("下载运行环境失败: 空响应体")
                        val total = if (release.size > 0) release.size else have + body.contentLength()
                        java.io.FileOutputStream(part, resuming).use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(64 * 1024)
                                var read: Int
                                var written = 0L
                                var lastPct = -1
                                while (input.read(buf).also { read = it } >= 0) {
                                    out.write(buf, 0, read)
                                    written += read
                                    if (total > 0) {
                                        val pct = ((have + written) * 90 / total).toInt().coerceIn(0, 90)
                                        if (pct != lastPct) {
                                            lastPct = pct
                                            onProgress(pct)
                                        }
                                    }
                                }
                            }
                        }
                        ok = true
                    }
                }
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Throwable) {
                failure = e
                HLog.e("$TAG 第 $attempt 次下载中断，已下 ${part.length()} 字节: ${e.message}")
                runCatching { Thread.sleep(2000L * attempt) }
            }
            if (restart) {
                if (attempt >= 5) error("下载运行环境失败: HTTP 416")
                continue
            }
            if (ok) succeeded = true
        }
        if (!succeeded) {
            failure?.let { throw it }
            error("下载运行环境失败: 已重试 5 次仍未完成")
        }

        if (release.size > 0) {
            check(part.length() == release.size) {
                "下载运行环境不完整: 期望 ${release.size} 实际 ${part.length()}，下次可从断点续传"
            }
        }
        if (release.sha256.isNotBlank() && !checksumOk(part, release.sha256)) {
            val actual = sha256(part)
            part.delete()
            error("运行环境校验失败: 期望 ${release.sha256} 实际 $actual")
        }
        if (release.sha256.isBlank()) HLog.e("$TAG 清单未提供 sha256，已跳过校验")
        finish(part, target, meta)
    }

    private fun finish(part: File, target: File, meta: File) {
        if (target.exists()) target.delete()
        check(part.renameTo(target)) { "运行环境落盘失败: ${target.absolutePath}" }
        runCatching { meta.delete() }
    }

    private fun checksumOk(file: File, expected: String): Boolean =
        expected.isNotBlank() && file.isFile && sha256(file).equals(expected, ignoreCase = true)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } >= 0) digest.update(buf, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val UPDATE_SCRIPT = """
        #!/bin/sh
        # Hchat codex 网页版运行时更新脚本（由模块写入，勿手改）
        VER="${'$'}1"
        PKG="${'$'}2"
        case "${'$'}VER" in */*|*" "*) echo "HCHAT_WEB_ERR 版本号非法: ${'$'}VER"; exit 1;; esac
        BASE=/opt/codex-web
        mkdir -p "${'$'}BASE/releases" || { echo "HCHAT_WEB_ERR 无法创建 ${'$'}BASE"; exit 1; }
        cd "${'$'}BASE" || { echo "HCHAT_WEB_ERR 找不到 ${'$'}BASE"; exit 1; }
        [ -f "${'$'}PKG" ] || { echo "HCHAT_WEB_ERR 包不存在: ${'$'}PKG"; exit 1; }

        PREV=""
        [ -L current ] && PREV=${'$'}(readlink current)

        restore_backup() {
            if [ -d "releases/.backup-${'$'}VER" ]; then
                rm -rf "releases/${'$'}VER"
                mv "releases/.backup-${'$'}VER" "releases/${'$'}VER"
            fi
            rm -rf "releases/.staging-${'$'}VER"
        }

        # 同名旧版本先挪走，保证任何阶段失败都能回滚
        if [ -d "releases/${'$'}VER" ]; then mv "releases/${'$'}VER" "releases/.backup-${'$'}VER"; fi
        rm -rf "releases/.staging-${'$'}VER"
        mkdir -p "releases/.staging-${'$'}VER"

        if ! tar -xf "${'$'}PKG" -C "releases/.staging-${'$'}VER"; then
            echo "HCHAT_WEB_ERR 解压失败"
            restore_backup
            exit 1
        fi
        if [ ! -f "releases/.staging-${'$'}VER/bin/node" ]; then
            echo "HCHAT_WEB_ERR 包内缺少 bin/node"
            restore_backup
            exit 1
        fi

        chmod +x "releases/.staging-${'$'}VER/bin/node" 2>/dev/null

        rm -rf "releases/${'$'}VER"
        mv "releases/.staging-${'$'}VER" "releases/${'$'}VER"
        ln -sfn "releases/${'$'}VER" current

        NVER=${'$'}(./current/bin/node --version 2>&1 | grep -oE 'v[0-9]+\.[0-9]+\.[0-9]+' | head -1)
        echo "HCHAT_WEB_NODE ${'$'}NVER"
        if [ -z "${'$'}NVER" ]; then
            echo "HCHAT_WEB_ERR 运行时无法启动: ${'$'}NVER"
            [ -n "${'$'}PREV" ] && ln -sfn "${'$'}PREV" current
            rm -rf "releases/${'$'}VER"
            restore_backup
            exit 1
        fi

        # 成功后清理其它版本与残留，只留当前版本
        for d in releases/*; do
            [ "${'$'}d" = "releases/${'$'}VER" ] && continue
            rm -rf "${'$'}d"
        done
        rm -rf releases/.backup-* releases/.staging-* 2>/dev/null
        rm -f "${'$'}PKG"
        echo "HCHAT_WEB_OK version=${'$'}VER"
    """.trimIndent() + "\n"
}
