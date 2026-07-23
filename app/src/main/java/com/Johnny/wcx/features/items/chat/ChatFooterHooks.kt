package com.Johnny.wcx.features.items.chat

import android.view.View
import android.widget.ImageButton
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.utils.findViewByChildIndexes
import com.Johnny.wcx.ui.utils.findViewWhich
import com.Johnny.wcx.ui.utils.findViewsWhich
import android.widget.Button as AndroidButton

@Feature(name = "聊天输入栏钩子", categories = ["API"], description = "集中提供聊天输入栏相关钩子")
object ChatFooterHooks : ApiFeature(), IResolveDex {

    // ChatFooter.l0() is the deobfuscated initSmileyBtn — found by the string reference
    // WeChat emits in its internal tracing framework at ChatFooter.java:4198.
    private val methodInitSmileyBtn by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings("initSmileyBtn")
        }
    }

    override fun onEnable() {
        methodInitSmileyBtn.hookAfter {
            val chatFooter = thisObject as ChatFooter
            val searchedView = chatFooter.findViewByChildIndexes<View>(0)!!
            val imgButtons = searchedView.findViewsWhich<ImageButton> { view ->
                view.javaClass.simpleName == "WeImageButton"
            }.toList()

            if (VoicePanel.isEnabled) {
                val voiceBtn = imgButtons.first()
                voiceBtn.setOnLongClickListener { view ->
                    VoicePanel.openPanel(view)
                    true
                }
            }

            if (StickerPanel.isEnabled) {
                val emojiBtn = imgButtons[1]
                emojiBtn.setOnLongClickListener { v ->
                    StickerPanel.openPanel(v)
                    true
                }
            }

            if (ChatInputBarEnhancements.isEnabled) {
                val menuBtn = imgButtons.last()
                val sendBtn = searchedView.findViewWhich<AndroidButton> { view ->
                    view.javaClass.name == "android.widget.Button" && run {
                        val text = (view as AndroidButton).text?.toString()?.trim() ?: ""
                        text == "发送" || text.equals("send", ignoreCase = true)
                    }
                }!!

                listOf(menuBtn, sendBtn).forEach {
                    it.setOnLongClickListener { view ->
                        val context = view.context
                        ChatInputBarEnhancements.showMenu(context, chatFooter)
                        return@setOnLongClickListener true
                    }
                }
            }
        }
    }
}
