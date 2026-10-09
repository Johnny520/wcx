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
    private val methodShowMsgQuoteContainer by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
            paramTypes("boolean", "boolean")
            returnType = "void"
            // 原实现写了 usingEqStrings("")（空字符串），该条件无法命中任何字符串常量，
            // matcher 实际只靠类名/参数/返回类型生效，属于无效条件，这里移除。
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
