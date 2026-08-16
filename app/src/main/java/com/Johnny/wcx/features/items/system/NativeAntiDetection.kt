package com.Johnny.wcx.features.items.system

import android.content.pm.PackageManager
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.writeText

@Feature(
    name = "阻止 Native 检测",
    categories = ["系统与隐私"],
    description = "在 Native 层拦截 /proc/self/maps 扫描、链接库枚举、模块安装探测与反调试, 防止微信通过绕过 Java Hook 的方式检测本模块与 Hook 框架。需重启微信后生效 (实验性)"
)
object NativeAntiDetection : SwitchFeature() {

    private const val TAG = "NativeAntiDetection"

    /**
     * On-disk request flag. `native_init` reads this the moment
     * `libwekit_native.so` loads (before WeChat's anti-tamper runs) and, if it
     * exists, installs the libc/libdl hooks using the tokens listed inside it.
     * The path must match `flag_file_path()` in `native_hook.rs`.
     */
    private val flagFile by lazy {
        KnownPaths.moduleData / "native_anti_detection_enabled.flag"
    }

    override fun onEnable() {
        // All hooking happens in native_init on the next launch; here we only
        // record the request and the tokens the native layer must hide.
        runCatching {
            flagFile.writeText(collectModuleTokens().joinToString("\n"))
            WeLogger.i(TAG, "wrote anti-detection flag; effective after WeChat restart")
        }.onFailure { WeLogger.e(TAG, "failed to write anti-detection flag", it) }
    }

    override fun onDisable() {
        runCatching {
            flagFile.deleteIfExists()
            WeLogger.i(TAG, "removed anti-detection flag; effective after WeChat restart")
        }.onFailure { WeLogger.e(TAG, "failed to remove anti-detection flag", it) }
    }

    /**
     * Resolve every on-disk string that identifies this module so the native
     * hooks can hide them from WeChat. Always includes the package name;
     * augments with the APK source dir, data dir and native lib dir when the
     * host lets us query them.
     */
    private fun collectModuleTokens(): List<String> {
        val tokens = linkedSetOf(PackageNames.MODULE)

        runCatching {
            val pm = HostInfo.application.packageManager
            val info = pm.getApplicationInfo(PackageNames.MODULE, 0)
            info.sourceDir?.let(tokens::add)
            info.publicSourceDir?.let(tokens::add)
            info.dataDir?.let(tokens::add)
            info.nativeLibraryDir?.let(tokens::add)
        }.onFailure {
            if (it is PackageManager.NameNotFoundException) {
                WeLogger.d(TAG, "module package not visible; hiding by name only")
            } else {
                WeLogger.w(TAG, "failed to resolve module paths", it)
            }
        }

        return tokens.filter { it.isNotEmpty() }
    }
}
