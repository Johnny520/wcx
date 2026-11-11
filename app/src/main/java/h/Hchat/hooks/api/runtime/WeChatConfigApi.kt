package h.Hchat.hooks.api.runtime

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Process
import android.text.TextUtils

/**
 * 微信宿主运行环境 API。
 *
 * 统一读取宿主包名、版本号和当前进程名，避免功能层重复写环境判断。
 */
class WeChatConfigApi(
    private val hostContext: Context?,
    private val logger: Logger?
) {
    fun interface Logger {
        fun log(message: String)
    }

    @Volatile
    private var processNameCache: String? = null

    val isAvailable: Boolean
        get() = hostContext != null

    fun packageName(): String = hostContext?.packageName ?: ""

    fun wechatVersionName(): String {
        val info = packageInfo()
        return info?.versionName ?: ""
    }

    fun wechatVersionCode(): Long {
        val info = packageInfo() ?: return 0L
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    fun processName(): String {
        val cached = processNameCache
        if (!TextUtils.isEmpty(cached)) return cached ?: ""
        var name = ""
        try {
            if (Build.VERSION.SDK_INT >= 28) name = Application.getProcessName()
        } catch (_: Throwable) {
        }
        if (TextUtils.isEmpty(name)) name = findProcessNameByPid()
        processNameCache = if (!TextUtils.isEmpty(name)) name else packageName()
        return processNameCache ?: ""
    }

    fun isMainProcess(): Boolean {
        val pkg = packageName()
        val process = processName()
        return !TextUtils.isEmpty(pkg) && pkg == process
    }

    fun isProcess(suffixOrName: String?): Boolean {
        if (TextUtils.isEmpty(suffixOrName)) return false
        val name = processName()
        return if (suffixOrName!!.startsWith(":")) name.endsWith(suffixOrName) else suffixOrName == name
    }

    private fun packageInfo(): PackageInfo? {
        val context = hostContext ?: return null
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Throwable) {
            log("读取版本失败: ${e.message}")
            null
        }
    }

    private fun findProcessNameByPid(): String {
        val context = hostContext ?: return ""
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return ""
            val pid = Process.myPid()
            val processes = am.runningAppProcesses ?: return ""
            for (info in processes) {
                if (info != null && info.pid == pid) return info.processName
            }
            ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun log(message: String) {
        logger?.log("[WeChatConfigApi] $message")
    }
}
