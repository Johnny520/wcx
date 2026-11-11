package com.Johnny.wcx.features.items.chat

import android.os.Message
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.reflection.ClassLoaders
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.invokeOriginal
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge

/**
 * 聊天页「附近直播」浮窗崩溃防御。
 *
 * 实测崩溃（微信 8.0.80 / Android 16，小米 shennong）：
 * ```
 * java.lang.NullPointerException: ... View.getResources()' on a null object reference
 *   at cn2.n0.a
 *   at pa6.n.getValue
 *   at com.tencent.mm.plugin.finder.feed.eg.<init>
 *   at com.tencent.mm.plugin.finder.nearby.live.view.NearbyLivePreviewView.m
 *   at com.tencent.mm.ui.chatting.component.c4.t0
 *   at com.tencent.mm.sdk.platformtools.i3.dispatchMessage
 *   at android.os.Looper.loopOnce
 * ```
 * 这是微信自身缺陷（栈内无任何模块代码），无法用常规 hookBefore/After 拦截——
 * 异常发生在微信自己的方法体内，构造器与 hookAfter 都接不住。
 *
 * 所以这里在微信的消息分发入口（sdk.platformtools.i3.dispatchMessage）套一层
 * 「替换 + invokeOriginal + try-catch」：仅当异常栈命中已知的微信自身崩溃
 * （含 finder 直播预览、不含任何模块帧）时才吞掉——消息循环继续，浮窗不显示，
 * 进程不闪退；其它一切异常照常 rethrow，交给崩溃处理器记录。
 */
@Feature(
    name = "聊天直播浮窗崩溃防御",
    categories = ["聊天"],
    description = "修复聊天页「附近直播」预览浮窗初始化时的微信自身 NPE 闪退（仅豁免该已知崩溃，其它异常照常上报）"
)
object ChatLivePreviewCrashGuard : SwitchFeature() {

    private const val TAG = "ChatLivePreviewCrashGuard"

    /** 模块自身的包名前缀：异常栈里只要出现模块帧，就说明是模块引起的，绝不能吞。 */
    private val MODULE_PACKAGES = listOf(
        "com.Johnny.wcx",
        "h.Hchat",
        "me.hd.wauxv",
        "bsh.",
        "org.luckypray.dexkit",
    )

    /** 已知微信自身崩溃的特征（聊天页「附近直播」预览浮窗初始化 NPE）。 */
    private const val VICTIM_PACKAGE = "com.tencent.mm.plugin.finder"
    private const val VICTIM_HINT = "nearby"

    override val defaultEnabled: Boolean = true

    override fun onEnable() {
        val handlerClass = runCatching {
            Class.forName("com.tencent.mm.sdk.platformtools.i3", false, ClassLoaders.HOST)
        }.getOrNull() ?: run {
            WeLogger.w(TAG, "未找到微信消息分发类 i3，防御不可用（可能微信版本变化）")
            return
        }

        val method = handlerClass.declaredMethods.firstOrNull {
            it.name == "dispatchMessage" &&
                it.parameterTypes.size == 1 &&
                Message::class.java == it.parameterTypes[0]
        } ?: run {
            WeLogger.w(TAG, "i3.dispatchMessage(Message) 未找到，防御不可用（可能微信版本变化）")
            return
        }

        XposedBridge.hookMethod(method, object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? {
                return try {
                    param.invokeOriginal()
                } catch (t: Throwable) {
                    if (isKnownWeChatCrash(t)) {
                        WeLogger.e(
                            TAG,
                            "已吞掉微信自身崩溃（聊天页「附近直播」浮窗 NPE，本次浮窗不显示）: ${t.message}",
                        )
                        null
                    } else {
                        throw t
                    }
                }
            }
        })

        WeLogger.i(TAG, "聊天直播浮窗崩溃防御已安装（i3.dispatchMessage）")
    }

    /** 栈里有 finder 直播预览帧、且没有任何模块帧 → 视为已知的微信自身崩溃。 */
    private fun isKnownWeChatCrash(t: Throwable): Boolean {
        var foundVictim = false
        var foundModule = false

        var cause: Throwable? = t
        while (cause != null && !foundModule) {
            for (frame in cause.stackTrace) {
                val cls = frame.className
                if (MODULE_PACKAGES.any { cls.startsWith(it) }) {
                    foundModule = true
                    break
                }
                if (!foundVictim && cls.startsWith(VICTIM_PACKAGE) && cls.contains(VICTIM_HINT)) {
                    foundVictim = true
                }
            }
            cause = cause.cause
        }

        return foundVictim && !foundModule
    }
}
