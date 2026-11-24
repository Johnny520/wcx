package com.Johnny.wcx.utils.fix

import android.content.res.Resources
import android.util.TypedValue
import com.Johnny.wcx.utils.WeLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.xmlpull.v1.XmlPullParserException
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
 * 崩溃不止这一条：8.0.80 的 `drawable/aoe`（CheckBox 的 background selector）第 4 行
 * `<item>` 引用了一个同样缺失的 raw svg，解析 selector 时抛的是
 * `XmlPullParserException: <item> tag requires a 'drawable' attribute`，
 * 外层包成 `Resources$NotFoundException`，随后 CheckBox 构造失败 → 「通话中来电」界面直接崩。
 * 所以兜底范围从「只有 raw svg」扩到「raw svg + 所有 .xml drawable」。
 *
 * 兜底策略（只保护 res/raw/*.svg 与 *.xml）:
 * - 其余 drawable 加载：类型检查后立即放行，**不改变原调用路径、零额外开销**；
 * - 命中 `res/raw/xxx.svg`：调用原实现，只有确实抛「资源/文件找不到」时才降级为 null
 *   （ImageView 的 src 为空，界面继续渲染，不闪退）；其它异常原样抛出，不掩盖真实问题。
 */
object ResourceFallbackGuard {

    private const val TAG = "ResourceFallbackGuard"
    private const val RAW_SVG_PREFIX = "res/raw/"
    private const val SVG_SUFFIX = ".svg"
    private const val XML_SUFFIX = ".xml"

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
                        // 注意：不能写死 args[0]。loadDrawableForCookie 的签名在不同 Android
                        // 版本是 (Resources, TypedValue, int, int) / (Resources, TypedValue, int, int, String)，
                        // 0 号参数一直是 Resources wrapper —— 原来按 args[0] 取 TypedValue 恒为 null，
                        // 于是整段兜底一次都没命中过（日志里 332 次 installed、0 次 missing raw svg），
                        // 微信照样因为 res/raw/*.svg 缺失 inflate 失败而闪退。
                        val value = param.args.firstOrNull { it is TypedValue } as? TypedValue ?: return
                        val path = value.string ?: return
                        // 只对 raw svg 与 xml drawable 走兜底，图片等其它资源直接放行，零额外开销。
                        val isRawSvg = path.startsWith(RAW_SVG_PREFIX) && path.endsWith(SVG_SUFFIX)
                        val isXml = path.endsWith(XML_SUFFIX)
                        if (!isRawSvg && !isXml) return

                        try {
                            param.result = XposedBridge.invokeOriginalMethod(
                                param.method,
                                param.thisObject,
                                param.args,
                            )
                        } catch (t: Throwable) {
                            if (t.isMissingResource()) {
                                WeLogger.w(TAG, "missing resource, degrade to null: $path")
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
            // selector / state-list 里 item 引用的子 drawable 缺失时抛的是这个，
            // 不是 NotFoundException，必须一起兜住（通话中来电界面的 CheckBox 崩溃就是它）。
            if (current is XmlPullParserException) {
                val message = current.message.orEmpty()
                if (message.contains("requires a 'drawable' attribute")) return true
            }
            current = current.cause
            depth++
        }
        return false
    }
}
