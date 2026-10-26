package com.Johnny.wcx.features.items.chat

import android.view.View
import android.view.ViewGroup
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.ClassLoaders
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 隐藏输入框右侧的「语音转文字」按钮。
 *
 * 旧实现有三个脆弱点，8.0.79 上很容易「开关是开的但按钮还在」：
 * 1. `findViewWhich<...> { ... }!!` —— 找不到直接 NPE，异常被 hook 吞掉后毫无反馈
 * 2. `((parent as ViewGroup).parent as ViewGroup).parent as ViewGroup` —— 微信改一层布局就 ClassCastException
 * 3. 用编译期类引用 `ChatFooter::class`，类名一变就是 NoClassDefFoundError
 *
 * 现在改成：反射按类名找 ChatFooter、构造完 post 到下一帧再遍历、只对
 * `SpeechInputLayout` 本身设 GONE（不动父级结构），找不到就记日志。
 */
@Feature(
    name = "禁用输入框快捷语音转文字",
    categories = ["聊天"],
    description = "隐藏输入框右侧的语音转文字按钮",
)
object DisableSpeechToTextButton : SwitchFeature() {

    private const val TAG = "DisableSpeechToText"

    private const val CHAT_FOOTER = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
    private const val SPEECH_INPUT = "com.tencent.mm.pluginsdk.ui.SpeechInputLayout"

    override fun onEnable() {
        runCatching {
            val clazz = XposedHelpers.findClass(CHAT_FOOTER, ClassLoaders.HOST)
            XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val footer = param.thisObject as? ViewGroup ?: return
                    // 构造期还没 addView 完，post 到下一帧再找
                    footer.post { runCatching { hideSpeechButton(footer) } }
                }
            })
            WeLogger.i(TAG, "已安装（$CHAT_FOOTER）")
        }.onFailure {
            WeLogger.w(TAG, "安装失败：${it.message}")
        }
    }

    private fun hideSpeechButton(root: ViewGroup) {
        val target = findSpeechInput(root)
        if (target == null) {
            WeLogger.i(TAG, "本次未找到 $SPEECH_INPUT（可能是非语音输入场景或版本结构变化）")
            return
        }
        if (target.visibility != View.GONE) {
            target.visibility = View.GONE
            WeLogger.i(TAG, "已隐藏语音转文字按钮")
        }
    }

    private fun findSpeechInput(v: View): View? {
        if (v.javaClass.name == SPEECH_INPUT) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findSpeechInput(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
