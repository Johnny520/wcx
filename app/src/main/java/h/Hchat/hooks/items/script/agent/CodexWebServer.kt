package h.Hchat.hooks.items.script.agent

import android.content.Context
import h.Hchat.utils.HLog
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

// codex 网页版服务：在 Ubuntu 环境里跑内置 Node 运行时，把界面暴露给手机浏览器
object CodexWebServer {
    private const val TAG = "[Hchat:CodexWeb]"

    const val PORT = 8214

    private const val LOG_NAME = ".codex-web.log"
    private const val READY_TIMEOUT_SECONDS = 120
    private const val MAIN_ARG = "src/server/main.js"

    @Volatile
    private var serverProcess: Process? = null

    fun url(): String = "http://127.0.0.1:$PORT"

    // proot 与宿主共享网络命名空间，直接探测端口即可判断服务状态
    fun isRunning(): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", PORT), 1200)
            true
        }
    }.getOrDefault(false)

    fun logFile(context: Context): File = File(ProotEnvironment.homeDir(context), LOG_NAME)

    fun start(context: Context): Result<Unit> = runCatching {
        check(ProotEnvironment.isReady(context)) { "Ubuntu 终端环境未安装" }
        check(CodexWebUpdater.isInstalled(context)) { "网页版运行环境未安装" }
        if (isRunning()) return@runCatching

        killStale(context)

        val log = logFile(context)
        runCatching { log.writeText("") }

        val process = ProotEnvironment.startBackground(context, RUN_COMMAND, log)
            ?: error("无法启动网页版服务进程")
        serverProcess = process

        val deadline = System.currentTimeMillis() + READY_TIMEOUT_SECONDS * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (isRunning()) return@runCatching
            if (!process.isAlive) {
                val tail = logTail(context, 8)
                error("服务进程已退出${if (tail.isBlank()) "" else "：$tail"}")
            }
            Thread.sleep(500)
        }
        error("启动超时：${logTail(context, 8).ifBlank { "服务未开始监听 $PORT" }}")
    }

    fun stop(context: Context): Result<Unit> = runCatching {
        runCatching { serverProcess?.destroy() }
        serverProcess = null
        if (!ProotEnvironment.isReady(context)) return@runCatching
        ProotEnvironment.exec(context, STOP_SCRIPT, timeoutSeconds = 30)
        Unit
    }

    fun logTail(context: Context, lines: Int = 40): String = runCatching {
        val log = logFile(context)
        if (!log.isFile) return@runCatching ""
        log.readLines().takeLast(lines).joinToString("\n")
    }.getOrDefault("")

    fun openInBrowser(context: Context) {
        runCatching {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url()),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure { HLog.e("$TAG 打开浏览器失败: ${it.message}", it) }
    }

    private fun killStale(context: Context) {
        if (!ProotEnvironment.isReady(context)) return
        runCatching { ProotEnvironment.exec(context, STOP_SCRIPT, timeoutSeconds = 20) }
    }

    // exec 让 node 顶替 bash 成为 proot 的直接跟踪目标：proot 进程活着，服务就活着
    private val RUN_COMMAND = """
        BASE=/opt/codex-web/current
        if [ ! -x "${'$'}BASE/bin/node" ]; then
            echo "HCHAT_WEBSRV_ERR 网页版运行环境未安装"
            exit 1
        fi
        CODEX_BIN=/opt/codex/packages/standalone/current/bin/codex
        [ -x "${'$'}CODEX_BIN" ] || CODEX_BIN=""
        cd "${'$'}BASE" || { echo "HCHAT_WEBSRV_ERR 找不到 ${'$'}BASE"; exit 1; }
        exec env CODEX_CLI_PATH="${'$'}CODEX_BIN" ./bin/node $MAIN_ARG --host 127.0.0.1 --port $PORT
    """.trimIndent()

    private val STOP_SCRIPT = """
        PAT="src/server/main."'js'
        for __p in ${'$'}(ls /proc 2>/dev/null | grep -E '^[0-9]+${'$'}'); do
            [ "${'$'}__p" = "${'$'}${'$'}" ] && continue
            if grep -qa "${'$'}PAT" /proc/${'$'}__p/cmdline 2>/dev/null; then kill -9 "${'$'}__p" 2>/dev/null; fi
        done
        unset __p PAT
        echo "HCHAT_WEBSRV_STOPPED"
    """.trimIndent()
}
