package com.Johnny.wcx.utils.fix

import android.content.res.Resources
import android.util.TypedValue
import com.Johnny.wcx.utils.WeLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.FileNotFoundException

/**
 * 「资源表里有 ID、APK 里没有文件」的资源缺失兜底。
 *
 * 触发场景（实测：微信 8.0.80 / 3220 + 小米 HyperOS，资源补丁不完整或 APK 资源被精简）：
 * `layout/baq` 第 185 行的 `WeImageView` 在构造时读取 `android:src="@raw/icon_filled_hangup"`，
 * Resources 按 ID 解析出 `res/raw/icon_filled_hangup.svg`，但 APK 里没有这个文件，
 * 于是 `ResourcesImpl.loadDrawableForCookie` 抛 `Resources$NotFoundException`，
 * 经 `LayoutInflater` 包装成 `InflateException`，直接打崩主线程。
 * 该异常与模块无关（资源属于 com.tencent.mm），但崩溃发生在微信内部、用户无法自救，
 * 因此这里做最小化兜底。
 *
 * 兜底策略（只保护 res/raw/ 目录下的 .svg）:
 * - 其余 drawable 加载：类型检查后立即放行，**不改变原调用路径、零额外开销**；
 * - 命中 `res/raw/xxx.svg`：调用原实现，只有确实抛「资源/文件找不到」时才降级为 null
 *   （ImageView 的 src 为空，界面继续渲染，不闪退）；其它异常原样抛出，不掩盖真实问题。
 */
object ResourceFallbackGuard {

    private const val TAG = "ResourceFallbackGuard"
    private const val RAW_SVG_PREFIX = "res/raw/"
    private const val SVG_SUFFIX = ".svg"

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        runCatching {
            // ResourcesImpl 是 hidden 类（不在公开 SDK 中），必须用反射按名取。
            val resourcesImpl = Class.forName("android.content.res.ResourcesImpl", false, null)
            XposedBridge.hookAllMethods(
                resourcesImpl,
                "loadDrawableForCookie",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val value = param.args.getOrNull(0) as? TypedValue ?: return
                        val path = value.string ?: return
                        if (!path.startsWith(RAW_SVG_PREFIX) || !path.endsWith(SVG_SUFFIX)) return

                        try {
                            param.result = XposedBridge.invokeOriginalMethod(
                                param.method,
                                param.thisObject,
                                param.args,
                            )
                        } catch (t: Throwable) {
                            if (t.isMissingResource()) {
                                WeLogger.w(TAG, "missing raw svg, degrade to null: $path")
                                param.result = null
                            } else {
                                throw t
                            }
                        }
                    }
                },
            )
            WeLogger.i(TAG, "installed for raw svg resources")
        }.onFailure { e ->
            WeLogger.e(TAG, "install failed", e)
        }
    }

    private fun Throwable.isMissingResource(): Boolean {
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth < 8) {
            if (current is Resources.NotFoundException || current is FileNotFoundException) return true
            current = current.cause
            depth++
        }
        return false
    }
}
