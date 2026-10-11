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

// 服务器清单驱动的内置 codex 更新：下载 → 校验 sha256 → 切换 current → 自检，失败回滚
object CodexUpdater {
    private const val TAG = "[Hchat:CodexUpdater]"

    private const val MANIFEST_PATH = "/codex/version.json"

    private const val STANDALONE_REL = "opt/codex/packages/standalone"

    private const val CACHE_DIR = ".cache"
    private const val PKG_NAME = "codex-pkg.tar.gz"
    private const val PART_NAME = "codex-pkg.tar.gz.part"
    private const val META_NAME = "codex-pkg.tar.gz.part.meta"
    private const val SCRIPT_NAME = "codex_update.sh"

    private const val REV_MARKER = ".codex_pkg_rev_DO_NOT_REMOVE"
    private const val BASELINE_REV = 1

    private const val UPDATE_TIMEOUT_SECONDS = 1800

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .callTimeout(1800, TimeUnit.SECONDS)
        .build()

    // 下载跑在模块自己的线程上：离开设置页也继续，中断后下次从断点续传
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hchat-codex-pkg").apply { isDaemon = true }
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
        val release: String,
        val downloadUrl: String,
        val sha256: String,
        val size: Long,
        val notes: String,
    )

    data class Installed(val version: String, val release: String)

    fun installed(context: Context): Installed? {
        val json = File(standaloneDir(context), "current/codex-package.json")
        if (!json.isFile) return null
        return runCatching {
            val obj = JSONObject(json.readText())
            val version = obj.optString("version").trim()
            if (version.isBlank()) return null
            val target = obj.optString("target").trim()
            Installed(version, if (target.isBlank()) version else "$version-$target")
        }.getOrNull()
    }

    fun installedRev(context: Context): Int = runCatching {
        val text = File(standaloneDir(context), REV_MARKER).readText().trim()
        text.toIntOrNull() ?: BASELINE_REV
    }.getOrDefault(BASELINE_REV)

    fun needsUpdate(context: Context, remote: Release): Boolean {
        val current = installed(context) ?: return true
        if (current.version != remote.version) return true
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
            check(response.isSuccessful) { "获取 codex 版本清单失败: HTTP ${response.code}" }
            val text = response.body?.string().orEmpty()
            val obj = runCatching { JSONObject(text) }.getOrElse { error("codex 版本清单格式错误") }
            val version = obj.optString("version").trim()
            val rawUrl = obj.optString("url").trim()
            check(version.isNotBlank() && rawUrl.isNotBlank()) { "codex 版本清单缺少 version/url" }
            Release(
                version = version,
                rev = obj.optInt("rev", BASELINE_REV),
                release = obj.optString("release").trim()
                    .ifBlank { "$version-aarch64-unknown-linux-musl" },
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
        val standalone = standaloneDir(context)
        check(File(standalone, "current").exists()) { "终端环境未就绪，请先安装 Ubuntu 终端环境" }

        report(onProgress, 0, "准备下载")
        download(context, release, tarball) { pct -> report(onProgress, pct, "下载 codex 包 $pct%") }

        report(onProgress, 92, "解压并切换版本")
        script.writeText(UPDATE_SCRIPT)
        val cmd = "sh /root/$CACHE_DIR/$SCRIPT_NAME '${release.release}' '/root/$CACHE_DIR/$PKG_NAME'"
        val result = ProotEnvironment.exec(context, cmd, timeoutSeconds = UPDATE_TIMEOUT_SECONDS)
        val ok = result.output.contains("HCHAT_CODEX_OK")
        if (!ok) {
            val tail = result.output.trim().lines().filter { it.isNotBlank() }.takeLast(6).joinToString(" | ")
            HLog.e("$TAG 更新失败: ${result.exitInfo} $tail")
            error("更新失败: ${tail.ifBlank { "exit=${result.exitInfo}" }}")
        }

        report(onProgress, 97, "校验新版本")
        val after = installed(context) ?: error("更新后读不到 codex 版本信息")
        check(after.version == release.version) {
            "更新后版本不一致: 期望 ${release.version} 实际 ${after.version}"
        }

        File(standalone, REV_MARKER).writeText(release.rev.toString())
        runCatching { tarball.delete() }
        report(onProgress, 100, "已更新到 ${after.version}")
        after
    }.onFailure {
        // 保留已下载的包：下次直接复用或从断点续传，不再重下 138MB
        HLog.e("$TAG update failed: ${it.message}", it)
    }

    fun standaloneDir(context: Context): File =
        File(ProotEnvironment.sandboxDir(context), STANDALONE_REL)

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
                        check(false) { "下载 codex 包失败: HTTP ${response.code}" }
                    } else {
                        val resuming = have > 0 && response.code == 206
                        if (!resuming) have = 0
                        val body = response.body ?: error("下载 codex 包失败: 空响应体")
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
                if (attempt >= 5) error("下载 codex 包失败: HTTP 416")
                continue
            }
            if (ok) succeeded = true
        }
        if (!succeeded) {
            failure?.let { throw it }
            error("下载 codex 包失败: 已重试 5 次仍未完成")
        }

        if (release.size > 0) {
            check(part.length() == release.size) {
                "下载 codex 包不完整: 期望 ${release.size} 实际 ${part.length()}，下次可从断点续传"
            }
        }
        if (release.sha256.isNotBlank() && !checksumOk(part, release.sha256)) {
            val actual = sha256(part)
            part.delete()
            error("codex 包校验失败: 期望 ${release.sha256} 实际 $actual")
        }
        if (release.sha256.isBlank()) HLog.e("$TAG 清单未提供 sha256，已跳过校验")
        finish(part, target, meta)
    }

    private fun finish(part: File, target: File, meta: File) {
        if (target.exists()) target.delete()
        check(part.renameTo(target)) { "codex 包落盘失败: ${target.absolutePath}" }
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
        # Hchat 内置 codex 更新脚本（由模块写入，勿手改）
        REL="${'$'}1"
        PKG="${'$'}2"
        case "${'$'}REL" in */*|*" "*) echo "HCHAT_CODEX_ERR 版本标识非法: ${'$'}REL"; exit 1;; esac
        BASE=/opt/codex/packages/standalone
        cd "${'$'}BASE" || { echo "HCHAT_CODEX_ERR 找不到 ${'$'}BASE"; exit 1; }
        [ -f "${'$'}PKG" ] || { echo "HCHAT_CODEX_ERR 包不存在: ${'$'}PKG"; exit 1; }

        PREV=""
        [ -L current ] && PREV=${'$'}(readlink current)

        restore_backup() {
            if [ -d "releases/.backup-${'$'}REL" ]; then
                rm -rf "releases/${'$'}REL"
                mv "releases/.backup-${'$'}REL" "releases/${'$'}REL"
            fi
            rm -rf "releases/.staging-${'$'}REL"
        }

        # 同名旧版本先挪走，保证任何阶段失败都能回滚
        if [ -d "releases/${'$'}REL" ]; then mv "releases/${'$'}REL" "releases/.backup-${'$'}REL"; fi
        rm -rf "releases/.staging-${'$'}REL"
        mkdir -p "releases/.staging-${'$'}REL"

        if ! tar -xf "${'$'}PKG" -C "releases/.staging-${'$'}REL"; then
            echo "HCHAT_CODEX_ERR 解压失败"
            restore_backup
            exit 1
        fi
        if [ ! -f "releases/.staging-${'$'}REL/bin/codex" ]; then
            echo "HCHAT_CODEX_ERR 包内缺少 bin/codex"
            restore_backup
            exit 1
        fi

        chmod +x "releases/.staging-${'$'}REL/bin/codex" 2>/dev/null
        chmod +x "releases/.staging-${'$'}REL/bin/codex-code-mode-host" 2>/dev/null
        [ -e "releases/.staging-${'$'}REL/codex" ] || ln -sfn bin/codex "releases/.staging-${'$'}REL/codex"

        rm -rf "releases/${'$'}REL"
        mv "releases/.staging-${'$'}REL" "releases/${'$'}REL"
        ln -sfn "releases/${'$'}REL" current

        VER=${'$'}(./current/bin/codex --version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?' | head -1)
        echo "HCHAT_CODEX_VERSION ${'$'}VER"
        if [ -z "${'$'}VER" ]; then
            echo "HCHAT_CODEX_ERR 新版本无法启动: ${'$'}VER"
            [ -n "${'$'}PREV" ] && ln -sfn "${'$'}PREV" current
            rm -rf "releases/${'$'}REL"
            restore_backup
            exit 1
        fi

        # 成功后清理其它版本与残留，只留当前版本
        for d in releases/*; do
            [ "${'$'}d" = "releases/${'$'}REL" ] && continue
            rm -rf "${'$'}d"
        done
        rm -rf releases/.backup-* releases/.staging-* 2>/dev/null
        rm -f "${'$'}PKG"
        echo "HCHAT_CODEX_OK version=${'$'}VER"
    """.trimIndent() + "\n"
}
