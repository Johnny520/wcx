package com.Johnny.wcx.features.items.chat

import android.view.KeyEvent
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "快捷清除引用", categories = ["聊天"], description = "在输入退格时若输入框无文字自动清除引用")
object QuickRemoveQuote : SwitchFeature(), IResolveDex {

    private val methodSupportAutoCompleteOnKey by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            name = "onKey"
            usingEqStrings("ChatFooterKtHelper", "supportAutoComplete err")
        }
    }
    // 目标：ChatFooter 中 (boolean, boolean) -> void 的引用容器显示方法。
    // 该签名在 ChatFooter 中存在多个候选（L0/U1/o0/w2 共 4 个），仅凭 类名+参数+返回类型 无法唯一锁定，
    // 追加方法体内引用的字符串常量 "handleQuoteMsgFillingFrom" 作为判别特征（与 Hchat 实现一致）。
    // allowMultiple/allowFailure = true：即便目标微信版本出现多候选，也取首个匹配而非抛异常，
    // 避免整个模块 Dex 初始化失败。
    private val methodShowMsgQuoteContainer by dexMethod(allowMultiple = true, allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
            paramTypes("boolean", "boolean")
            returnType = "void"
            usingEqStrings("handleQuoteMsgFillingFrom")
        }
    }

    override fun onEnable() {
        methodSupportAutoCompleteOnKey.hookBefore {
            val event = args[2] as KeyEvent
            if (event.action != KeyEvent.ACTION_DOWN || event.keyCode != KeyEvent.KEYCODE_DEL) return@hookBefore

            val chatFooter = runCatching {
                val helper = thisObject.reflekt()
                    .firstField {
                        type { clazz -> clazz.name.startsWith("com.tencent.mm.pluginsdk.ui.chat.") }
                    }.get() ?: return@runCatching null
                helper.reflekt()
                    .firstField { type = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" }
                    .get() as? ChatFooter
            }.getOrNull() ?: return@hookBefore

            val text = chatFooter.lastText
            val quoteMsgId = chatFooter.lastQuoteMsgId

            if (text.isEmpty() && quoteMsgId != 0L) {
                methodShowMsgQuoteContainer.method.invoke(chatFooter, false, true)
            }
        }
    }
}
