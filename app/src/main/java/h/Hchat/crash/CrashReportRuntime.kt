package h.Hchat.crash

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import h.Hchat.BuildConfig
import h.Hchat.loader.utils.NativeLibraryLoader
import h.Hchat.preferences.HchatStorage
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog
import h.Hchat.utils.HLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.lang.ref.WeakReference
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object CrashReportRuntime : Application.ActivityLifecycleCallbacks {
    private const val TAG = "[Hchat:Crash]"
    private const val SHOW_DELAY_MS = 700L
    private const val DISPLAY_LIMIT = 32 * 1024
    private const val FILE_LIMIT = 1024 * 1024
    private const val SECTION_SEPARATOR = "\n\n==============================\n\n"

    private const val JAVA_PENDING = "pending_java.log"
    private const val NATIVE_PENDING = "pending_native.log"
    private const val REPORT_PENDING = "pending_report.log"
    private const val LAST_REPORT = "last_crash.log"
    private const val PREVIOUS_LAUNCH = "previous_launch"
    private const val LAST_NATIVE_EXIT = "last_native_exit"
    private const val LAST_ANR_EXIT = "last_anr_exit"

    private val prepared = AtomicBoolean(false)
    private val installed = AtomicBoolean(false)
    private val handlingJavaCrash = AtomicBoolean(false)
    private val nativeInstallFailureLogged = AtomicBoolean(false)
    private val showingReport = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var nativeLoaded = false

    @Volatile
    private var captureEnabled = false

    @Volatile
    private var hostApplication: Application? = null

    @Volatile
    private var hostModuleClassLoader: ClassLoader? = null

    @Volatile
    private var resumedActivity = WeakReference<Activity>(null)

    @Volatile
    private var activeReportDialog: VoiceForwardMiuixDialog.DialogHandle? = null

    @Volatile
    private var activeReportActivity = WeakReference<Activity>(null)

    @Volatile
    private var activeReportArchiveFlag: AtomicBoolean? = null

    private lateinit var crashDir: File
    private lateinit var javaPendingFile: File
    private lateinit var nativePendingFile: File
    private lateinit var pendingReportFile: File
    private lateinit var lastReportFile: File
    private var environmentHeader: String = ""

    @JvmStatic
    fun prepare(application: Application) {
        hostApplication = application
        captureEnabled = runCatching { CrashReportSettings.isEnabled(application) }
            .onFailure { HLog.e("$TAG 读取异常捕获设置失败: ${it.message}", it) }
            .getOrDefault(false)
        if (!prepared.compareAndSet(false, true)) {
            if (captureEnabled) ensureJavaHandler()
            return
        }
        runCatching {
            crashDir = File(HchatStorage.storageDir(application), "crash").apply { mkdirs() }
            javaPendingFile = File(crashDir, JAVA_PENDING)
            nativePendingFile = File(crashDir, NATIVE_PENDING)
            pendingReportFile = File(crashDir, REPORT_PENDING)
            lastReportFile = File(crashDir, LAST_REPORT)
            environmentHeader = buildEnvironmentHeader(application)
            if (captureEnabled) ensureJavaHandler()
        }.onFailure {
            prepared.set(false)
            HLog.e("$TAG Java 捕获器初始化失败: ${it.message}", it)
        }
    }

    @JvmStatic
    fun install(application: Application, moduleClassLoader: ClassLoader) {
        hostApplication = application
        hostModuleClassLoader = moduleClassLoader
        prepare(application)
        if (!prepared.get()) return
        if (!captureEnabled) {
            discardPendingReports()
            return
        }
        if (!installed.compareAndSet(false, true)) {
            ensureJavaHandler()
            ensureNativeHandler()
            return
        }
        runCatching {
            collectPreviousCrash(application)
            nativeLoaded = NativeLibraryLoader().loadCrashGuard(application, moduleClassLoader)
            ensureNativeHandler()
            application.registerActivityLifecycleCallbacks(this)
            mainHandler.post {
                ensureJavaHandler()
                ensureNativeHandler()
            }
        }.onFailure {
            installed.set(false)
            HLog.e("$TAG 初始化失败: ${it.message}", it)
        }
    }

    @JvmStatic
    fun onSettingChanged(context: Context, enabled: Boolean) {
        captureEnabled = enabled
        if (!enabled) {
            if (::crashDir.isInitialized) discardPendingReports()
            return
        }
        val application = (context.applicationContext as? Application) ?: hostApplication ?: return
        prepare(application)
        val classLoader = hostModuleClassLoader
        if (classLoader != null) {
            install(application, classLoader)
        } else {
            ensureJavaHandler()
        }
    }

    private fun ensureJavaHandler() {
        if (!captureEnabled || !::javaPendingFile.isInitialized) return
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is HchatUncaughtExceptionHandler) return
        Thread.setDefaultUncaughtExceptionHandler(HchatUncaughtExceptionHandler(current))
    }

    private fun ensureNativeHandler() {
        if (!captureEnabled || !nativeLoaded || !::nativePendingFile.isInitialized) return
        val result = runCatching { NativeCrashBridge.install(nativePendingFile.absolutePath) }
        if (result.getOrDefault(false)) {
            nativeInstallFailureLogged.set(false)
        } else if (nativeInstallFailureLogged.compareAndSet(false, true)) {
            val error = result.exceptionOrNull()
            if (error != null) {
                HLog.e("$TAG Native 捕获器安装失败: ${error.message}", error)
            } else {
                HLog.e("$TAG Native 捕获器未能接管全部崩溃信号")
            }
        }
    }

    private fun writeJavaCrash(thread: Thread, throwable: Throwable) {
        if (!captureEnabled) return
        if (isIgnoredAllocationFailure(throwable)) return
        if (!handlingJavaCrash.compareAndSet(false, true)) return
        runCatching {
            crashDir.mkdirs()
            FileOutputStream(javaPendingFile, false).use { stream ->
                PrintWriter(OutputStreamWriter(stream, StandardCharsets.UTF_8), false).use { writer ->
                    writer.println("Hchat 捕获到 Java 层异常")
                    writer.println("时间: ${formatTime(System.currentTimeMillis())}")
                    writer.print(environmentHeader)
                    writer.println("线程: ${thread.name} (id=${thread.id})")
                    writer.println()
                    throwable.printStackTrace(writer)
                    writer.flush()
                    runCatching { stream.fd.sync() }
                }
            }
        }
    }

    private fun collectPreviousCrash(context: Application) {
        if (!captureEnabled) return
        val launchMarker = File(crashDir, PREVIOUS_LAUNCH)
        val nativeExitMarker = File(crashDir, LAST_NATIVE_EXIT)
        val anrExitMarker = File(crashDir, LAST_ANR_EXIT)
        val previousLaunch = readLong(launchMarker)
        val lastNativeExit = readLong(nativeExitMarker)
        val lastAnrExit = readLong(anrExitMarker)
        val nativeCapturedAt = nativePendingFile
            .takeIf { it.isFile && it.length() > 0L }
            ?.lastModified()
            ?: 0L

        val sections = ArrayList<String>()
        readTextFile(pendingReportFile, FILE_LIMIT)
            ?.takeIf { it.isNotBlank() && !isIgnoredAllocationFailureText(it) }
            ?.let(sections::add)
        readTextFile(javaPendingFile, FILE_LIMIT)
            ?.takeIf { it.isNotBlank() && !isIgnoredAllocationFailureText(it) }
            ?.let(sections::add)

        val nativeBody = readTextFile(nativePendingFile, FILE_LIMIT)
        val systemExit = findNativeExit(
            context = context,
            previousLaunch = previousLaunch,
            lastHandledTimestamp = lastNativeExit,
            capturedAt = nativeCapturedAt,
            capturedPid = parseNativePid(nativeBody)
        )
        if (!nativeBody.isNullOrBlank() || systemExit != null) {
            val timestamp = systemExit?.timestamp?.takeIf { it > 0L }
                ?: nativeCapturedAt.takeIf { it > 0L }
                ?: System.currentTimeMillis()
            sections += buildString {
                appendLine("Hchat 捕获到 Native 层异常")
                appendLine("时间: ${formatTime(timestamp)}")
                append(environmentHeader)
                if (!nativeBody.isNullOrBlank()) {
                    appendLine()
                    appendLine("--- Native 信号记录 ---")
                    appendLine(nativeBody.trim())
                }
                if (systemExit != null) {
                    appendLine()
                    appendLine("--- Android 退出记录 ---")
                    append(systemExit.details)
                }
            }.trimEnd()
        }

        val systemAnr = findAnrExit(
            context = context,
            previousLaunch = previousLaunch,
            lastHandledTimestamp = lastAnrExit
        )
        if (systemAnr != null) {
            sections += buildString {
                appendLine("Hchat 捕获到 ANR 异常")
                appendLine("时间: ${formatTime(systemAnr.timestamp)}")
                append(environmentHeader)
                appendLine()
                appendLine("--- Android ANR 退出记录 ---")
                append(systemAnr.details)
            }.trimEnd()
        }

        val persisted = sections.isEmpty() || writeTextFile(pendingReportFile, mergeCrashSections(sections))
        if (persisted) {
            if (systemExit != null) writeLong(nativeExitMarker, systemExit.timestamp)
            if (systemAnr != null) writeLong(anrExitMarker, systemAnr.timestamp)
            javaPendingFile.delete()
            nativePendingFile.delete()
            writeLong(launchMarker, System.currentTimeMillis())
        }
    }

    private fun findNativeExit(
        context: Application,
        previousLaunch: Long,
        lastHandledTimestamp: Long,
        capturedAt: Long,
        capturedPid: Int
    ): SystemExitRecord? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            CrashExitInfoApi30.findNativeExit(
                context = context,
                previousLaunch = previousLaunch,
                lastHandledTimestamp = lastHandledTimestamp,
                capturedAt = capturedAt,
                capturedPid = capturedPid
            )
        }.onFailure {
            HLog.e("$TAG 读取系统 Native 退出记录失败: ${it.message}", it)
        }.getOrNull()
    }

    private fun findAnrExit(
        context: Application,
        previousLaunch: Long,
        lastHandledTimestamp: Long
    ): SystemExitRecord? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            CrashExitInfoApi30.findAnrExit(
                context = context,
                previousLaunch = previousLaunch,
                lastHandledTimestamp = lastHandledTimestamp
            )
        }.onFailure {
            HLog.e("$TAG 读取系统 ANR 退出记录失败: ${it.message}", it)
        }.getOrNull()
    }

    private fun parseNativePid(body: String?): Int {
        val raw = body
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("pid=") }
            ?.substringAfter('=')
            ?.trim()
            .orEmpty()
        if (raw.isEmpty()) return 0
        return runCatching {
            when {
                raw.startsWith("0x", ignoreCase = true) -> raw.substring(2).toLong(16)
                else -> raw.toLong()
            }.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: 0
        }.getOrDefault(0)
    }

    private fun mergeCrashSections(sections: List<String>): String {
        val newestFirst = ArrayList<String>()
        var used = 0
        for (section in sections.asReversed()) {
            val normalized = section.trim()
            if (normalized.isEmpty()) continue
            val separatorLength = if (newestFirst.isEmpty()) 0 else SECTION_SEPARATOR.length
            if (normalized.length + used + separatorLength <= FILE_LIMIT) {
                newestFirst += normalized
                used += normalized.length + separatorLength
            } else if (newestFirst.isEmpty()) {
                val marker = "\n\n[异常日志超过保存上限，后续内容已截断]"
                newestFirst += normalized.take(FILE_LIMIT - marker.length) + marker
                break
            }
        }
        return newestFirst.asReversed().joinToString(SECTION_SEPARATOR)
    }

    private fun scheduleReport(activity: Activity) {
        if (!captureEnabled) return
        if (!::pendingReportFile.isInitialized || !pendingReportFile.isFile) return
        if (!showingReport.compareAndSet(false, true)) return
        mainHandler.postDelayed({
            if (!captureEnabled) {
                showingReport.set(false)
                return@postDelayed
            }
            if (resumedActivity.get() !== activity || activity.isFinishing || activity.isDestroyed) {
                showingReport.set(false)
                resumedActivity.get()?.takeIf { !it.isFinishing && !it.isDestroyed }?.let(::scheduleReport)
                return@postDelayed
            }
            val report = readTextFile(pendingReportFile, FILE_LIMIT)
            if (report.isNullOrBlank()) {
                pendingReportFile.delete()
                showingReport.set(false)
                return@postDelayed
            }
            val display = if (report.length <= DISPLAY_LIMIT) {
                report
            } else {
                report.take(DISPLAY_LIMIT) + "\n\n[日志较长，界面仅展示部分内容；点击日志或复制按钮仍会复制完整内容]"
            }
            val archiveFlag = AtomicBoolean(true)
            val handle = VoiceForwardMiuixDialog.showCopyableLog(
                activity = activity,
                title = "检测到微信异常",
                message = display,
                copyText = report,
                onDismiss = {
                    if (archiveFlag.get()) archiveReport(report)
                    activeReportDialog = null
                    activeReportActivity = WeakReference(null)
                    activeReportArchiveFlag = null
                    showingReport.set(false)
                }
            )
            if (handle.isShowing()) {
                activeReportDialog = handle
                activeReportActivity = WeakReference(activity)
                activeReportArchiveFlag = archiveFlag
            } else {
                archiveFlag.set(false)
                showingReport.set(false)
            }
        }, SHOW_DELAY_MS)
    }

    private fun archiveReport(report: String) {
        if (writeTextFile(lastReportFile, report)) pendingReportFile.delete()
    }

    private fun discardPendingReports() {
        activeReportArchiveFlag?.set(false)
        activeReportDialog?.close()
        activeReportDialog = null
        activeReportActivity = WeakReference(null)
        activeReportArchiveFlag = null
        showingReport.set(false)
        if (!::crashDir.isInitialized) return
        javaPendingFile.delete()
        nativePendingFile.delete()
        pendingReportFile.delete()
        crashDir.listFiles()?.forEach { file ->
            if (file.name.contains(".tmp-")) file.delete()
        }
        writeLong(File(crashDir, PREVIOUS_LAUNCH), System.currentTimeMillis())
    }

    private fun buildEnvironmentHeader(context: Application): String {
        val wechatPackage = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val wechatVersion = wechatPackage?.versionName.orEmpty()
        @Suppress("DEPRECATION")
        val wechatVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            wechatPackage?.longVersionCode ?: 0L
        } else {
            wechatPackage?.versionCode?.toLong() ?: 0L
        }
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            readProcessName()
        }
        return buildString {
            appendLine("模块版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("微信版本: $wechatVersion ($wechatVersionCode)")
            appendLine("进程: $processName (pid=${Process.myPid()}, ${if (Process.is64Bit()) "64" else "32"} 位)")
            appendLine("系统: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("系统指纹: ${Build.FINGERPRINT}")
        }
    }

    private fun readProcessName(): String {
        return runCatching {
            File("/proc/self/cmdline").readText().substringBefore('\u0000')
        }.getOrDefault("")
    }

    private fun readTextFile(file: File, limit: Int): String? {
        if (!file.isFile) return null
        return runCatching {
            file.inputStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (total < limit) {
                    val read = input.read(buffer, 0, minOf(buffer.size, limit - total))
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    total += read
                }
                String(output.toByteArray(), StandardCharsets.UTF_8)
            }
        }.getOrNull()
    }

    private fun writeTextFile(file: File, text: String): Boolean {
        val temp = File(file.parentFile, "${file.name}.tmp-${Process.myPid()}-${Thread.currentThread().id}")
        return runCatching {
            file.parentFile?.mkdirs()
            FileOutputStream(temp, false).use { stream ->
                OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(text)
                    writer.flush()
                    stream.fd.sync()
                }
            }
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        }.onFailure {
            HLog.e("$TAG 写入崩溃记录失败: ${file.name} ${it.message}", it)
        }.getOrDefault(false).also {
            if (!it) temp.delete()
        }
    }

    private fun readLong(file: File): Long {
        return runCatching { file.readText().trim().toLong() }.getOrDefault(0L)
    }

    private fun writeLong(file: File, value: Long) {
        writeTextFile(file, value.toString())
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
    }

    private fun isIgnoredAllocationFailure(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        repeat(8) {
            val candidate = current ?: return false
            if (candidate is OutOfMemoryError &&
                candidate.message.orEmpty().contains("Failed to allocate", ignoreCase = true)
            ) {
                return true
            }
            current = candidate.cause
        }
        return false
    }

    private fun isIgnoredAllocationFailureText(text: String): Boolean {
        return text.contains("OutOfMemoryError", ignoreCase = true) &&
            text.contains("Failed to allocate", ignoreCase = true)
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = WeakReference(activity)
        if (!captureEnabled) return
        ensureJavaHandler()
        ensureNativeHandler()
        scheduleReport(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity.get() === activity) resumedActivity = WeakReference(null)
        if (activeReportActivity.get() === activity) {
            activeReportArchiveFlag?.set(false)
            activeReportDialog?.close()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (activeReportActivity.get() === activity) {
            activeReportArchiveFlag?.set(false)
            activeReportDialog?.close()
        }
    }

    private class HchatUncaughtExceptionHandler(
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            writeJavaCrash(thread, throwable)
            if (previous != null && previous !== this) {
                try {
                    previous.uncaughtException(thread, throwable)
                } finally {
                    handlingJavaCrash.set(false)
                }
                return
            }
            Process.killProcess(Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }
}
