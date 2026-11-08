package h.Hchat.hooks.items.script.agent

import android.content.Context
import h.Hchat.BuildConfig
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import h.Hchat.hooks.items.script.market.PluginMarketSettings
import h.Hchat.utils.HLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// proot 终端环境：proot 二进制随 APK 的 jniLibs 打包，运行时取可执行副本，容器内假 UID 0
object ProotEnvironment {
    private const val TAG = "[Hchat:Proot]"

    private const val ROOTFS_PATH = "/rootfs/ubuntu-base-arm64-v3.tar.gz"
    private const val ROOTFS_SHA256 =
        "641817ccf2fc996e6dd32e685fe38a14f5ee9f94852b0468d08e638b646b16d1"
    // 旧指纹一并放行：内置 CLI 版本差异可由 codex 更新入口补齐，不让老用户白下 300MB
    private val ROOTFS_SHA256_ACCEPTED = setOf(
        ROOTFS_SHA256,
        "858ad64fc0d91c514a48c3daa4342607aa7645b3e7be4c65d77cfb17f858b0f1",
    )

    private const val TERMINAL_ASSET_DIR = "terminal"
    private val TERMINAL_SCRIPTS = listOf(
        "setup.sh",
        "sandbox_proot.sh",
        "sandbox_chroot.sh",
        "init.sh",
        "utils.sh",
        "universal_runner.sh",
        "termux-x11.sh",
    )

    private val AGENT_DOC_ASSETS = listOf(
        "script_plugin_agent_guide.md",
        "SCRIPT_PLUGIN_API.md",
    )

    private const val WECHAT_APK_MOUNT = "/wechat/base.apk"
    private const val WECHAT_MOUNT_DIR = "/wechat"

    private const val ENV_DIR = "proot_env"
    private const val SANDBOX_DIR = "sandbox"
    private const val BIN_DIR = "bin"
    private const val HOME_DIR = "home"
    private const val TMP_DIR = "tmp"
    private const val SETUP_OK_MARKER = ".terminal_setup_ok_DO_NOT_REMOVE"

    private const val ROOTFS_SHA_MARKER = ".rootfs_sha256_DO_NOT_REMOVE"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .callTimeout(600, TimeUnit.SECONDS)
        .build()

    enum class State { NOT_INSTALLED, INSTALLING, READY, DEGRADED, ERROR }

    data class Status(
        val state: State,
        val message: String = "",
        val progress: Int = -1,
    )

    private val statusRef = AtomicReference(Status(State.NOT_INSTALLED))
    @Volatile private var installing = false

    fun currentStatus(): Status = statusRef.get()

    private fun setStatus(status: Status) {
        statusRef.set(status)
    }

    fun envRoot(context: Context): File {
        val base = runCatching { context.dataDir }.getOrDefault(context.filesDir)
        return File(File(base, "Hchat"), ENV_DIR).apply { if (!exists()) mkdirs() }
    }

    fun sandboxDir(context: Context): File = File(envRoot(context), SANDBOX_DIR)
    fun binDir(context: Context): File = File(envRoot(context), BIN_DIR)
    fun homeDir(context: Context): File = File(envRoot(context), HOME_DIR)

    fun prootBinary(context: Context): File =
        File(nativeLibDir(context), "libproot.so")

    fun link2symlinkLib(context: Context): File =
        File(nativeLibDir(context), "liblink2symlink.so")

    fun loaderLib(context: Context): File =
        File(nativeLibDir(context), "libloader.so")

    private fun nativeLibDir(context: Context): String {
        moduleApkPath()?.let { apk ->
            val parent = File(apk).parentFile
            if (parent != null) {
                val libRoot = File(parent, "lib")
                if (libRoot.isDirectory) {
                    libRoot.listFiles()?.forEach { archDir ->
                        if (File(archDir, "libproot.so").exists()) return archDir.absolutePath
                    }
                }
                listOf("arm64", "arm64-v8a").forEach { arch ->
                    val d = File(libRoot, arch)
                    if (File(d, "libproot.so").exists()) return d.absolutePath
                }
            }
        }
        return runCatching { moduleContext(context).applicationInfo.nativeLibraryDir }
            .getOrElse { "" }
    }

    fun moduleApkPath(): String? {
        val cl = ProotEnvironment::class.java.classLoader
        if (cl != null) {
            val s = cl.toString()
            val idx = s.indexOf("module=")
            if (idx >= 0) {
                val start = idx + 7
                var end = s.indexOf(",", start)
                if (end < 0) end = s.indexOf("]", start)
                if (end > start) {
                    val p = s.substring(start, end).trim()
                    if (p.isNotEmpty() && File(p).exists()) return p
                }
            }
        }
        runCatching {
            File("/data/app").listFiles()?.forEach { dir ->
                if (dir != null && dir.isDirectory && dir.name.contains("h.Hchat")) {
                    val apk = File(dir, "base.apk")
                    if (apk.exists()) return apk.absolutePath
                    dir.listFiles()?.forEach { sub ->
                        if (sub.isDirectory && sub.name.contains("h.Hchat")) {
                            val a = File(sub, "base.apk")
                            if (a.exists()) return a.absolutePath
                        }
                    }
                }
            }
        }
        return null
    }

    private fun moduleContext(context: Context): Context {
        return if (context.packageName == BuildConfig.APPLICATION_ID) {
            context
        } else {
            context.createPackageContext(
                BuildConfig.APPLICATION_ID,
                Context.CONTEXT_IGNORE_SECURITY,
            )
        }
    }

    fun wechatApkPath(context: Context): String? {
        runCatching {
            val src = context.applicationInfo?.sourceDir
            if (!src.isNullOrEmpty() && File(src).exists()) return src
        }
        runCatching {
            val p = context.packageCodePath
            if (!p.isNullOrEmpty() && File(p).exists()) return p
        }
        runCatching {
            File("/data/app").listFiles()?.forEach { dir ->
                if (dir != null && dir.isDirectory && dir.name.contains("com.tencent.mm")) {
                    val apk = File(dir, "base.apk")
                    if (apk.exists()) return apk.absolutePath
                    dir.listFiles()?.forEach { sub ->
                        if (sub.isDirectory && sub.name.contains("com.tencent.mm")) {
                            val a = File(sub, "base.apk")
                            if (a.exists()) return a.absolutePath
                        }
                    }
                }
            }
        }
        return null
    }

    fun isReady(context: Context): Boolean {
        val sandbox = sandboxDir(context)
        val marker = File(envRoot(context), SETUP_OK_MARKER)
        val bash = File(sandbox, "bin/bash")
        return sandbox.isDirectory && marker.exists() && bash.exists()
    }

    fun needsUpgrade(context: Context): Boolean =
        isReady(context) && rootfsOutdated(context)

    fun refreshStatus(context: Context): Status {
        val status = when {
            installing -> statusRef.get().takeIf { it.state == State.INSTALLING }
                ?: Status(State.INSTALLING, "安装中")
            isReady(context) -> Status(State.READY, "终端环境就绪")
            else -> Status(State.NOT_INSTALLED, "未安装 Ubuntu 终端环境")
        }
        setStatus(status)
        return status
    }

    @Synchronized
    fun install(
        context: Context,
        force: Boolean = false,
        onProgress: ((Int, String) -> Unit)? = null,
    ): Result<Unit> = runCatching {
        val upgrade = !force && isReady(context) && rootfsOutdated(context)
        if (!force && !upgrade && isReady(context)) {
            setStatus(Status(State.READY, "终端环境就绪"))
            return@runCatching
        }
        installing = true
        try {
            report(onProgress, 0, "准备目录")
            val env = envRoot(context)
            sandboxDir(context).mkdirs()
            binDir(context).mkdirs()
            homeDir(context).mkdirs()
            File(env, TMP_DIR).apply { mkdirs() }

            report(onProgress, 5, "释放终端脚本")
            extractScripts(context)

            report(onProgress, 10, "下载 Ubuntu 环境")
            val tarball = File(env, "rootfs.tar.gz")
            downloadRootfs(context, tarball, onProgress)

            if (force || upgrade) {
                report(onProgress, 68, "替换旧容器")
                deleteTree(sandboxDir(context))
                File(env, SETUP_OK_MARKER).delete()
                sandboxDir(context).mkdirs()
            }

            report(onProgress, 70, "解压 Ubuntu 环境")
            extractRootfs(context, tarball)
            tarball.delete()

            report(onProgress, 90, "初始化容器配置")
            configureContainer(context)

            File(env, SETUP_OK_MARKER).writeText("ok")
            File(env, ROOTFS_SHA_MARKER).writeText(ROOTFS_SHA256)
            report(onProgress, 100, "完成")
            setStatus(Status(State.READY, "终端环境就绪"))
        } finally {
            installing = false
        }
    }.onFailure {
        installing = false
        HLog.e("$TAG install failed: ${it.message}", it)
        setStatus(Status(State.ERROR, "安装失败: ${it.message.orEmpty()}"))
    }

    @Synchronized
    fun uninstall(context: Context): Result<Unit> = runCatching {
        deleteTree(envRoot(context))
        setStatus(Status(State.NOT_INSTALLED, "已删除终端环境"))
    }

    fun diskUsage(context: Context): Long = dirSize(envRoot(context))

    private fun installedRootfsSha(context: Context): String =
        runCatching { File(envRoot(context), ROOTFS_SHA_MARKER).readText().trim() }.getOrDefault("")

    private fun rootfsOutdated(context: Context): Boolean =
        installedRootfsSha(context) !in ROOTFS_SHA256_ACCEPTED

    private fun report(cb: ((Int, String) -> Unit)?, pct: Int, msg: String) {
        setStatus(Status(State.INSTALLING, msg, pct))
        runCatching { cb?.invoke(pct, msg) }
    }

    private fun extractScripts(context: Context) {
        val binDir = binDir(context)
        binDir.mkdirs()
        val apk = moduleApkPath()
        if (apk != null) {
            java.util.zip.ZipFile(apk).use { zip ->
                for (name in TERMINAL_SCRIPTS) {
                    val entry = zip.getEntry("assets/$TERMINAL_ASSET_DIR/$name")
                        ?: error("模块 APK 缺少 assets/$TERMINAL_ASSET_DIR/$name")
                    val target = File(binDir, name.removeSuffix(".sh"))
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target.setExecutable(true, false)
                    target.setReadable(true, false)
                }
            }
            return
        }
        val assets = moduleContext(context).assets
        for (name in TERMINAL_SCRIPTS) {
            val target = File(binDir, name.removeSuffix(".sh"))
            assets.open("$TERMINAL_ASSET_DIR/$name").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setExecutable(true, false)
            target.setReadable(true, false)
        }
    }

    private fun downloadRootfs(
        context: Context,
        target: File,
        onProgress: ((Int, String) -> Unit)?,
    ) {
        val base = PluginMarketSettings.serviceUrl(context).trimEnd('/')
        val url = "$base$ROOTFS_PATH"
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "下载 rootfs 失败: HTTP ${response.code}" }
            val body = response.body ?: error("下载 rootfs 失败: 空响应体")
            val total = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            target.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var downloaded = 0L
                    while (input.read(buf).also { read = it } >= 0) {
                        out.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val pct = 10 + (downloaded * 60 / total).toInt().coerceIn(0, 60)
                            report(onProgress, pct, "下载 Ubuntu 环境 ${downloaded / 1048576}MB")
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(ROOTFS_SHA256, ignoreCase = true)) {
                "rootfs 校验失败: 期望 $ROOTFS_SHA256 实际 $actual"
            }
        }
    }

    private fun extractRootfs(context: Context, tarball: File) {
        val sandbox = sandboxDir(context)
        sandbox.mkdirs()
        val proot = prootBinary(context)
        val ok = if (proot.canExecute()) {
            runCatching { extractWithProot(context, tarball, sandbox) }
                .onFailure { HLog.e("$TAG proot extract failed, fallback: ${it.message}") }
                .isSuccess
        } else {
            false
        }
        if (!ok) {
            extractWithFallback(context, tarball, sandbox)
        }
    }

    private fun extractWithProot(context: Context, tarball: File, sandbox: File) {
        val proot = prootBinary(context).absolutePath
        val args = mutableListOf(proot, "--kill-on-exit", "-w", "/")
        for (mnt in systemBinds()) {
            if (File(mnt).exists()) args += listOf("-b", "${File(mnt).canonicalPath}")
        }
        args += listOf(
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", sandbox.absolutePath,
            "-r", "/",
            "-0",
            "--link2symlink",
            "--sysvipc",
            "-L",
            "/system/bin/sh", "-c",
            "cd ${sandbox.absolutePath} && tar -xf ${tarball.absolutePath}",
        )
        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(true)
        pb.environment()["PROOT_TMP_DIR"] = File(envRoot(context), TMP_DIR).absolutePath
        pb.environment()["PROOT_LOADER"] =
            File(nativeLibDir(context), "libloader.so").absolutePath
        val process = pb.start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val finished = process.waitFor(300, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("proot 解压超时")
        }
        check(process.exitValue() == 0) { "proot 解压退出码 ${process.exitValue()}: $out" }
    }

    private fun extractWithFallback(context: Context, tarball: File, sandbox: File) {
        val pb = ProcessBuilder(
            "/system/bin/sh", "-c",
            "cd ${sandbox.absolutePath} && tar -xf ${tarball.absolutePath}",
        )
        pb.redirectErrorStream(true)
        pb.environment()["LD_PRELOAD"] = link2symlinkLib(context).absolutePath
        val process = pb.start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val finished = process.waitFor(300, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("fallback 解压超时")
        }
        check(process.exitValue() == 0) { "fallback 解压退出码 ${process.exitValue()}: $out" }
    }

    private fun configureContainer(context: Context) {
        val etc = File(sandboxDir(context), "etc").apply { mkdirs() }
        File(etc, "hostname").writeText("Hchat\n")
        File(etc, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        File(etc, "hosts").writeText(
            "127.0.0.1   localhost.localdomain localhost\n" +
                "::1         localhost ip6-localhost ip6-loopback\n",
        )
        File(etc, "apt/apt.conf.d").mkdirs()
        File(etc, "apt/apt.conf.d/99norecommends").writeText(
            "APT::Install-Recommends \"false\";\nAPT::Install-Suggests \"false\";\n",
        )
        prepareRuntimeFiles(context)
    }


    fun prepareRuntimeFiles(context: Context) {
        if (!isReady(context) && !sandboxDir(context).isDirectory) return
        runCatching { writeGroupFile(context) }
        runCatching { writeShellConfig(context) }
        runCatching { migrateLegacyApiKey(context) }
        runCatching { ensureCodexHint(context) }
        runCatching { ensureCodexConfig(context) }
        runCatching { CodexSettings.apply(context) }
        runCatching { ensureCodexAgentsMd(context, File(homeDir(context), ".codex")) }
        runCatching { ensureAgentDocs(context, File(homeDir(context), ".codex")) }
        runCatching { ensureDexclubAutostart(context) }
    }

    private val ANDROID_GROUP_NAMES = mapOf(
        0 to "root",
        1000 to "system",
        1001 to "radio",
        1015 to "sdcard_rw",
        1028 to "sdcard_r",
        1077 to "android_external_storage",
        1079 to "android_readproc",
        3001 to "net_bt_admin",
        3002 to "net_bt",
        3003 to "inet",
        3004 to "net_raw",
        3009 to "readproc",
        9997 to "everybody",
    )

    private fun writeGroupFile(context: Context) {
        val groupFile = File(File(sandboxDir(context), "etc"), "group")
        val existing = runCatching { groupFile.readText() }.getOrDefault("")
        val present = existing.lineSequence().mapNotNull { line ->
            val parts = line.split(':')
            if (parts.size >= 3) parts[2].trim().toIntOrNull() else null
        }.toHashSet()
        val gids = linkedSetOf<Int>()
        runCatching {
            File("/proc/self/status").readLines().forEach { line ->
                val values = when {
                    line.startsWith("Gid:") -> line.removePrefix("Gid:").trim()
                    line.startsWith("Groups:") -> line.removePrefix("Groups:").trim()
                    else -> ""
                }
                values.split(' ', '\t').forEach { v ->
                    v.trim().toIntOrNull()?.let { gids += it }
                }
            }
        }
        val add = gids.filter { it !in present }.map { gid ->
            "${ANDROID_GROUP_NAMES[gid] ?: "android_$gid"}:x:$gid"
        }
        if (add.isEmpty()) return
        groupFile.parentFile?.mkdirs()
        if (existing.isNotEmpty() && !existing.endsWith("\n")) groupFile.appendText("\n")
        groupFile.appendText(add.joinToString("\n", postfix = "\n"))
    }

    private fun writeShellConfig(context: Context) {
        val home = homeDir(context).apply { mkdirs() }
        val bashrc = File(home, ".bashrc")
        if (!bashrc.exists() || bashrc.length() == 0L) {
            runCatching { bashrc.writeText(SHELL_BASHRC) }
        }
        val profile = File(home, ".profile")
        if (!profile.exists() || profile.length() == 0L) {
            runCatching { profile.writeText(SHELL_PROFILE) }
        }
    }

    // 老版本 .bashrc 里的明文密钥必须删掉，否则 bash -l 会盖掉启动时注入的值
    private fun migrateLegacyApiKey(context: Context) {
        val bashrc = File(homeDir(context), ".bashrc")
        val existing = runCatching { bashrc.readText() }.getOrDefault("")
        if (existing.isBlank() || !existing.contains(CodexSettings.ENV_KEY_NAME)) return
        val assign = Regex(
            "(?m)^[ \\t]*(?:export[ \\t]+)?" + Regex.escape(CodexSettings.ENV_KEY_NAME) +
                "[ \\t]*=[ \\t]*\"?([^\"\\n]*)\"?[ \\t]*$"
        )
        val legacy = assign.find(existing)?.groupValues?.get(1)?.trim().orEmpty()
        if (legacy.isNotBlank() && legacy != LEGACY_API_KEY_PLACEHOLDER) {
            runCatching { CodexSettings.adoptLegacyKey(context, legacy) }
        }
        val cleaned = existing.lines()
            .filterNot { line -> assign.containsMatchIn(line) || line.contains(LEGACY_API_KEY_COMMENT) }
            .joinToString("\n")
        if (cleaned != existing) runCatching { bashrc.writeText(cleaned) }
    }

    // 只补缺的键与表，绝不覆盖用户改动；dexclub MCP 表必须追加到末尾（插开头会吞掉后面的键）
    private fun ensureCodexConfig(context: Context) {
        val dir = File(homeDir(context), ".codex").apply { mkdirs() }
        val cfg = File(dir, "config.toml")
        val existing = runCatching { cfg.readText() }.getOrDefault("")
        if (existing.isBlank() || isStockCodexConfig(existing)) {
            runCatching { cfg.writeText(codexDefaultConfig(context)) }
            ensureCodexAgentsMd(context, dir)
            return
        }
        var text = existing
        val missing = CODEX_REQUIRED_KEYS.filterNot { (key, _) ->
            Regex("(?m)^\\s*" + Regex.escape(key) + "\\s*=").containsMatchIn(text)
        }
        if (missing.isNotEmpty()) {
            val block = buildString {
                append("# Hchat：proot 下 codex 自带沙箱不可用，自动补齐（可自行修改）\n")
                missing.forEach { (key, value) -> append(key).append(" = ").append(value).append('\n') }
            }
            text = block + text
        }
        if (!Regex("(?m)^\\s*\\[mcp_servers\\.dexclub\\]").containsMatchIn(text)) {
            val appended = buildString {
                if (!text.endsWith("\n")) append('\n')
                append("\n# Hchat：内置微信逆向工具（DexClub），codex 会自动用它确认 hook 点；删掉这一整段即可关闭\n")
                append("[mcp_servers.dexclub]\n")
                append("url = \"http://127.0.0.1:8787/mcp\"\n")
            }
            text += appended
        }
        if (text != existing) runCatching { cfg.writeText(text) }
    }

    private fun isStockCodexConfig(text: String): Boolean {
        val markers = listOf(
            "# Hchat 内置 codex 配置",
            "# Hchat 内置 codex 默认配置",
            "# proot 环境里关闭 codex 自带沙箱",
        )
        if (markers.none { text.contains(it) }) return false
        val stock = setOf(
            "sandbox_mode = \"danger-full-access\"",
            "approval_policy = \"never\"",
            "model_provider = \"1\"",
            "model = \"你的模型\"",
            "model_reasoning_effort = \"xhigh\"",
            "model_reasoning_effort = \"high\"",
            "[features]",
            "image_generation = false",
            "[model_providers.1]",
            "name = \"1\"",
            "base_url = \"模型供应商url\"",
            "base_url = \"模型供应商的URL\"",
            "env_key = \"a_API_KEY\"",
            "trust_level = \"trusted\"",
            "[mcp_servers.dexclub]",
            "url = \"http://127.0.0.1:8787/mcp\"",
        )
        return text.lineSequence()
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotEmpty() }
            .all { it in stock || it.startsWith("[projects.\"") }
    }

    private fun ensureCodexAgentsMd(context: Context, codexDir: File) {
        val agents = File(codexDir, "AGENTS.md")
        if (agents.exists() && agents.length() > 0L) return
        runCatching { agents.writeText(codexAgentsMd(context)) }
    }

    private fun ensureAgentDocs(context: Context, codexDir: File) {
        val docsDir = File(codexDir, "docs").apply { mkdirs() }
        val apk = moduleApkPath()
        if (apk != null) {
            runCatching {
                java.util.zip.ZipFile(apk).use { zip ->
                    for (name in AGENT_DOC_ASSETS) {
                        val entry = zip.getEntry("assets/$name") ?: continue
                        val target = File(docsDir, name)
                        if (target.exists() && target.length() == entry.size) continue
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
            return
        }
        val assets = moduleContext(context).assets
        for (name in AGENT_DOC_ASSETS) {
            runCatching {
                val target = File(docsDir, name)
                val srcLen = assets.open(name).use { it.readBytes().size.toLong() }
                if (target.exists() && target.length() == srcLen) return@runCatching
                assets.open(name).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private const val DEXCLUB_AUTOSTART_MARKER = "# Hchat-dexclub-autostart"

    private fun ensureDexclubAutostart(context: Context) {
        val bashrc = File(homeDir(context), ".bashrc")
        val existing = runCatching { bashrc.readText() }.getOrDefault("")
        if (existing.contains(DEXCLUB_AUTOSTART_MARKER)) {
            if (existing.contains(DEXCLUB_AUTOSTART_BLOCK.trim())) return
            val cleaned = stripMarkedBlock(existing, DEXCLUB_AUTOSTART_MARKER)
            runCatching {
                bashrc.writeText(
                    (if (cleaned.isNotEmpty() && !cleaned.endsWith("\n")) "$cleaned\n" else cleaned) +
                        DEXCLUB_AUTOSTART_BLOCK
                )
            }
            return
        }
        runCatching {
            bashrc.parentFile?.mkdirs()
            if (existing.isNotEmpty() && !existing.endsWith("\n")) bashrc.appendText("\n")
            bashrc.appendText(DEXCLUB_AUTOSTART_BLOCK)
        }
    }

    private const val CODEX_HINT_MARKER = "# Hchat-codex-hint"

    private fun ensureCodexHint(context: Context) {
        val bashrc = File(homeDir(context), ".bashrc")
        val existing = runCatching { bashrc.readText() }.getOrDefault("")
        if (existing.contains(CODEX_HINT_MARKER)) {
            if (existing.contains(CODEX_HINT_BLOCK.trim())) return
            val cleaned = stripMarkedBlock(existing, CODEX_HINT_MARKER)
            runCatching {
                bashrc.writeText(
                    (if (cleaned.isNotEmpty() && !cleaned.endsWith("\n")) "$cleaned\n" else cleaned) +
                        CODEX_HINT_BLOCK
                )
            }
            return
        }
        runCatching {
            bashrc.parentFile?.mkdirs()
            if (existing.isNotEmpty() && !existing.endsWith("\n")) bashrc.appendText("\n")
            bashrc.appendText(CODEX_HINT_BLOCK)
        }
    }

    private fun stripMarkedBlock(text: String, marker: String): String {
        val lines = text.lines()
        val out = ArrayList<String>(lines.size)
        var skipping = false
        for (line in lines) {
            if (!skipping && line.contains(marker) && !line.contains("$marker-end")) {
                skipping = true
                continue
            }
            if (skipping) {
                if (line.contains("$marker-end")) skipping = false
                continue
            }
            out.add(line)
        }
        return out.joinToString("\n").trimEnd('\n')
    }

    fun stopDexclub(context: Context) {
        if (!isReady(context)) return
        runCatching {
            exec(
                context,
                "for p in \$(ls /proc 2>/dev/null | grep -E '^[0-9]+\$'); do " +
                    "if grep -qa dexclub /proc/\$p/cmdline 2>/dev/null; then kill -9 \"\$p\" 2>/dev/null; fi; " +
                    "done; true",
                timeoutSeconds = 15,
            )
        }
    }

    private val DEXCLUB_AUTOSTART_BLOCK = """
        |$DEXCLUB_AUTOSTART_MARKER（自动生成，勿手工编辑；删掉这一整段即可关闭内置逆向）
        |if [ -n "${'$'}PS1" ] && [ -x /opt/dexclub/bin/mcp ]; then
        |  (
        |    flock -n 9 || exit 0
        |    if (exec 3<>/dev/tcp/127.0.0.1/8787) 2>/dev/null; then
        |      exec 3>&- 3<&-        # 已在监听：健康，什么都不做（绝不误杀）
        |    else
        |      for __p in ${'$'}(ls /proc 2>/dev/null | grep -E '^[0-9]+${'$'}'); do
        |        if grep -qa dexclub /proc/${'$'}__p/cmdline 2>/dev/null; then kill -9 "${'$'}__p" 2>/dev/null; fi
        |      done
        |      unset __p
        |      JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 \
        |      DEXCLUB_MCP_HOST=127.0.0.1 DEXCLUB_MCP_PORT=8787 \
        |        nohup /opt/dexclub/bin/mcp >/root/.dexclub-mcp.log 2>&1 &
        |      disown 2>/dev/null || true
        |      __i=0
        |      while [ ${'$'}__i -lt 40 ]; do
        |        if (exec 3<>/dev/tcp/127.0.0.1/8787) 2>/dev/null; then exec 3>&- 3<&-; break; fi
        |        sleep 0.5; __i=${'$'}((__i+1))
        |      done
        |      unset __i
        |    fi
        |  ) 9>/tmp/.dexclub-autostart.lock
        |fi
        |$DEXCLUB_AUTOSTART_MARKER-end
        |""".trimMargin()

    private val CODEX_HINT_BLOCK = """
        |$CODEX_HINT_MARKER（自动生成，勿手工编辑；删掉这一整段即可关闭提示）
        |if [ -n "${'$'}PS1" ]; then
        |  if [ -n "${'$'}a_API_KEY" ]; then
        |    printf '\033[36m提示：输入 codex 回车即可进入 codex。\033[0m\n'
        |  else
        |    printf '\033[33m提示：codex 还没配置密钥，配好后这里会自动注入。\033[0m\n'
        |  fi
        |fi
        |$CODEX_HINT_MARKER-end
        |""".trimMargin()

    private val SHELL_BASHRC = """
        |# Hchat Ubuntu 终端配置（可自行修改，不会被覆盖）
        |export PS1='\[\e[1;32m\]\u@\h\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]\$ '
        |export TERM=xterm-256color
        |export COLORTERM=truecolor
        |export LANG=C.UTF-8
        |export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        |export PROMPT_DIRTRIM=2
        |alias ls='ls --color=auto'
        |alias ll='ls -alF'
        |alias la='ls -A'
        |alias l='ls -CF'
        |alias grep='grep --color=auto'
        |alias ..='cd ..'
        |
        |# 模型密钥不写在这个文件里（明文不安全），由 Hchat 在打开终端时自动注入环境变量 a_API_KEY，
        |# codex 配置里 env_key 用的就是这个变量名。
        |""".trimMargin()

    private val SHELL_PROFILE = """
        |# Hchat Ubuntu 终端配置（自动生成，勿手工编辑）
        |[ -n "${'$'}BASH_VERSION" ] && [ -f ~/.bashrc ] && . ~/.bashrc
        |""".trimMargin()

    private const val LEGACY_API_KEY_PLACEHOLDER = "你的key"

    private const val LEGACY_API_KEY_COMMENT = "第三方中转接口的密钥"

    private val CODEX_REQUIRED_KEYS = listOf(
        "sandbox_mode" to "\"danger-full-access\"",
        "approval_policy" to "\"never\"",
    )

    private fun codexDefaultConfig(context: Context): String = """
            |# Hchat 内置 codex 配置（可自行修改）
            |
            |# 这两行不要删除
            |sandbox_mode = "danger-full-access"
            |approval_policy = "never"
            |
            |model_provider = "1"  # 用哪个供应商，对应下面 [model_providers.1] 这个名字
            |model = "你的模型"  # 填你要用的模型型号，例如 gpt-5.6-sol、gpt-6-astra
            |# 思考程度（model_reasoning_effort）不写就是由服务端决定；需要时可填 none/minimal/low/medium/high/xhigh/max
            |
            |[model_providers.1]
            |name = "1"  # 供应商名字，和上面 model_provider 对应即可
            |base_url = "模型供应商的URL"  # API 接口地址，一般以 /v1 结尾
            |env_key = "a_API_KEY"  # 这里填「环境变量名」不是密钥本身；密钥由 Hchat 自动注入，不会明文保存在这里
            |
            |# 内置微信逆向工具（DexClub），codex 会自动用它确认 hook 点，不要删除
            |[mcp_servers.dexclub]
            |url = "http://127.0.0.1:8787/mcp"
            |""".trimMargin()

    private fun codexAgentsMd(context: Context): String {
        val pluginDir = scriptPluginDir(context)
        return """
            |# Hchat 脚本插件开发说明（自动生成，可自行修改）
            |
            |## 插件目录
            |$pluginDir
            |
            |每个插件是这里的一个子目录，典型内容：
            |- `main.java` —— 插件入口脚本（BeanShell）
            |- `info.prop` —— 插件元数据
            |- `README.md` —— 插件说明
            |
            |## 常见任务
            |- 开发 / 修改插件：直接读写上面目录里对应插件的文件，保存即改。
            |- 测试插件：改动会在 Hchat 的「脚本插件」页面重新加载后生效（支持热重载），
            |  在手机上观察日志与实际效果。
            |- 新建插件：在插件目录下新建子目录，补上 `main.java` 与 `info.prop`。
            |
            |## 写微信插件前：先用 DexClub 逆向确认 hook 点
            |本机内置微信逆向工具 DexClub（MCP 名 `dexclub`），微信 APK 已挂在 `$WECHAT_APK_MOUNT`。
            |**不要凭记忆猜微信的类名/方法名/字段（都是混淆的，会随版本变）**，按下面步骤确认：
            |1. `open_target_session` 传 `input="$WECHAT_APK_MOUNT"` 建会话，拿到 session_id；
            |2. 用 `find_classes` / `find_methods` 按特征（字符串、调用、参数类型）定位目标；
            |3. `export_method_java` / `inspect_method` 看反编译确认签名，再写 hook。
            |确认后按下面的插件接口写 `main.java`（BeanShell）存进插件目录即可。
            |
            |## 模块插件接口文档（写插件前必读）
            |开发前先看接口，别凭空写：
            |- `~/.codex/docs/script_plugin_agent_guide.md` —— 脚本插件运行时能力清单（先读这份）
            |- `~/.codex/docs/SCRIPT_PLUGIN_API.md` —— 完整脚本插件 API（写具体调用时按需查）
            |用 `cat` 读取，只在需要时查对应章节，不要整篇读进上下文。
            |
            |## 环境说明
            |- 这是 Hchat 内置的 Ubuntu 24.04（arm64）proot 容器，以 root 身份运行。
            |- `/root` 是模块的 HOME，重启微信后仍然保留。
            |- 插件目录在容器内与手机上路径完全一致（经 /storage 直接可见）。
            |""".trimMargin()
    }

    private fun scriptPluginDir(context: Context): String =
        runCatching { ScriptPluginRuntime.scriptDir(context).absolutePath }
            .getOrDefault("/storage/emulated/0/Android/media/${context.packageName}/Hchat/脚本插件")

    fun systemBinds(): List<String> = listOf(
        "/apex", "/odm", "/product", "/system", "/system_ext", "/vendor",
        "/linkerconfig/ld.config.txt",
        "/linkerconfig/com.android.art/ld.config.txt",
        "/plat_property_contexts", "/property_contexts",
    )

    data class ExecResult(val exitInfo: String, val output: String)

    fun interactiveShell(context: Context): ShellSpec? {
        if (!isReady(context)) return null
        val proot = prootBinary(context)
        if (!proot.canExecute()) return null
        prepareRuntimeFiles(context)
        val sandbox = sandboxDir(context)
        val home = homeDir(context)
        val argv = mutableListOf(proot.absolutePath, "--kill-on-exit", "-w", "/root")
        for (mnt in systemBinds()) {
            val f = File(mnt)
            if (f.exists()) argv += listOf("-b", f.canonicalPath)
        }
        argv += listOf(
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/sdcard",
            "-b", "/storage",
            "-b", "${home.absolutePath}:/root",
        )
        wechatApkPath(context)?.let { apk ->
            argv += listOf("-b", "$apk:$WECHAT_APK_MOUNT")
        }
        argv += listOf(
            "-r", sandbox.absolutePath,
            "-0",
            "--link2symlink",
            "--sysvipc",
            "-L",
            "/bin/bash", "-l",
        )
        val env = mutableListOf(
            "PROOT_TMP_DIR=${File(envRoot(context), TMP_DIR).absolutePath}",
            "PROOT_LOADER=${File(nativeLibDir(context), "libloader.so").absolutePath}",
            "HOME=/root",
            "USER=root",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/local/bin:/usr/local/sbin",
        )
        env += codexEnv(context)
        return ShellSpec(proot.absolutePath, argv.toTypedArray(), env.toTypedArray(), envRoot(context).absolutePath)
    }

    private fun codexEnv(context: Context): List<String> =
        CodexSettings.env(context).map { (name, value) -> "$name=$value" }

    data class ShellSpec(
        val executable: String,
        val argv: Array<String>,
        val environment: Array<String>,
        val cwd: String,
    )

    private fun prootArgs(context: Context, command: String): List<String>? {
        val proot = prootBinary(context)
        if (!proot.canExecute()) return null
        val sandbox = sandboxDir(context)
        val home = homeDir(context)
        val procArgs = mutableListOf(proot.absolutePath, "--kill-on-exit", "-w", "/root")
        for (mnt in systemBinds()) {
            val f = File(mnt)
            if (f.exists()) procArgs += listOf("-b", f.canonicalPath)
        }
        procArgs += listOf(
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/sdcard",
            "-b", "/storage",
            "-b", "${home.absolutePath}:/home",
            "-b", "${home.absolutePath}:/root",
            "-r", sandbox.absolutePath,
            "-0",
            "--link2symlink",
            "--sysvipc",
            "-L",
            "/bin/bash", "-c",
            "export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/local/bin:/usr/local/sbin; " +
                "export HOME=/root; " +
                // 安卓进程会把 TMPDIR 传成宿主私有缓存路径，proot 里不存在 → 钉到 rootfs 内部
                "export TMPDIR=/root/.tmp TMP=/root/.tmp TEMP=/root/.tmp; " +
                "mkdir -p /root/.tmp 2>/dev/null; cd /root; $command",
        )
        return procArgs
    }

    private fun applyProotEnv(pb: ProcessBuilder, context: Context) {
        pb.environment().apply {
            put("PROOT_TMP_DIR", File(envRoot(context), TMP_DIR).absolutePath)
            put("PROOT_LOADER", File(File(nativeLibDir(context)), "libloader.so").absolutePath)
            CodexSettings.env(context).forEach { (name, value) -> put(name, value) }
        }
    }

    // 常驻服务：不等进程结束，输出重定向到宿主侧日志；proot 进程存活期间目标程序即存活
    fun startBackground(context: Context, command: String, logFile: File): Process? {
        if (!isReady(context)) return null
        val procArgs = prootArgs(context, command) ?: return null
        val pb = ProcessBuilder(procArgs)
        pb.redirectErrorStream(true)
        runCatching {
            logFile.parentFile?.mkdirs()
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        }
        applyProotEnv(pb, context)
        return runCatching { pb.start() }.getOrNull()
    }

    fun exec(context: Context, command: String, timeoutSeconds: Int = 120): ExecResult {
        if (!isReady(context)) {
            return ExecResult("退出码: -1", "Ubuntu 终端环境未安装，请先在设置里安装")
        }
        val procArgs = prootArgs(context, command)
            ?: return ExecResult("退出码: -1", "proot 二进制不可执行")
        val pb = ProcessBuilder(procArgs)
        pb.redirectErrorStream(true)
        applyProotEnv(pb, context)
        val process = try {
            pb.start()
        } catch (e: Throwable) {
            return ExecResult("退出码: -1", "启动 proot 失败: ${e.message.orEmpty()}")
        }
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        val reader = Thread {
            runCatching {
                while (true) {
                    val n = process.inputStream.read(buf)
                    if (n < 0) break
                    synchronized(out) {
                        if (out.size() < 2 * 1024 * 1024) out.write(buf, 0, n)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(1000)
            val text = synchronized(out) { out.toByteArray().toString(Charsets.UTF_8) }
            return ExecResult("退出码: -1 (超时 ${timeoutSeconds}s)", "$text\n[命令超时，已强制结束]")
        }
        reader.join(2000)
        val text = synchronized(out) { out.toByteArray().toString(Charsets.UTF_8) }
        return ExecResult("退出码: ${process.exitValue()}", text)
    }

    // 不跟随软链、按 inode 去重（≈ du 口径）：跟软链遍历会把同一份文件重复计入
    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        val seen = HashSet<Any>()
        runCatching {
            java.nio.file.Files.walkFileTree(
                dir.toPath(),
                object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(
                        file: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        if (attrs.isRegularFile) {
                            val key = attrs.fileKey()
                            if (key == null || seen.add(key)) size += attrs.size()
                        }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: java.nio.file.Path,
                        exc: java.io.IOException,
                    ): java.nio.file.FileVisitResult = java.nio.file.FileVisitResult.CONTINUE
                },
            )
        }
        return size
    }

    private fun deleteTree(file: File) {
        if (!file.exists()) return
        file.walkBottomUp().forEach { runCatching { it.delete() } }
    }
}
