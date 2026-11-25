package com.Johnny.wcx.features.items.chat

import android.view.View
import android.widget.Button as AndroidButton
import android.widget.ImageButton
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.utils.findViewByChildIndexes
import com.Johnny.wcx.ui.utils.findViewWhich
import com.Johnny.wcx.ui.utils.findViewsWhich
import com.Johnny.wcx.utils.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

@Feature(name = "聊天输入栏钩子", categories = ["API"], description = "集中提供聊天输入栏相关钩子")
object ChatFooterHooks : ApiFeature(), IResolveDex {

    private const val TAG = "ChatFooterHooks"

    // ChatFooter.l0() 的原始名是 initSmileyBtn：微信在内部 tracing 里会打出这个串，
    // 所以按字符串引用就能定位，不依赖混淆名。
    private val methodInitSmileyBtn by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings("initSmileyBtn")
        }
    }

    /**
     * 输入栏初始化完成的通知。
     *
     * 注入型功能（聊天工具栏）只在 ChatFooter 构造器里注入，会漏掉两种场景：
     * 在聊天页里打开开关、以及同一个 ChattingUI 切会话时 footer 被复用。
     * 这里每次初始化都通知一次，注册方自己保证幂等。
     */
    private val footerListeners = CopyOnWriteArrayList<(ChatFooter) -> Unit>()

    fun addFooterListener(listener: (ChatFooter) -> Unit) {
        if (!footerListeners.contains(listener)) footerListeners.add(listener)
    }

    fun removeFooterListener(listener: (ChatFooter) -> Unit) {
        footerListeners.remove(listener)
    }

    override fun onEnable() {
        methodInitSmileyBtn.hookAfter {
            val chatFooter = thisObject as? ChatFooter ?: return@hookAfter

            // 1) 先通知监听者：即使下面的原生按钮结构变了，工具栏补注入也不受影响。
            footerListeners.forEach { listener ->
                runCatching { listener(chatFooter) }
                    .onFailure { WeLogger.w(TAG, "footer 监听回调失败：${it.message}") }
            }

            // 2) 原生按钮绑定：结构变化只跳过本次，绝不让 NPE 带走主线程。
            val searchedView = chatFooter.findViewByChildIndexes<View>(0) ?: run {
                WeLogger.w(TAG, "ChatFooter 子层级变化，跳过长按绑定")
                return@hookAfter
            }
            val imgButtons = searchedView.findViewsWhich<ImageButton> { view ->
                view.javaClass.simpleName == "WeImageButton"
            }.toList()

            if (VoicePanel.isEnabled) {
                imgButtons.getOrNull(0)?.setOnLongClickListener { view ->
                    VoicePanel.openPanel(view)
                    true
                }
            }

            if (StickerPanel.isEnabled) {
                imgButtons.getOrNull(1)?.setOnLongClickListener { view ->
                    StickerPanel.openPanel(view)
                    true
                }
            }

            if (ChatInputBarEnhancements.isEnabled) {
                val menuBtn = imgButtons.lastOrNull()
                val sendBtn = searchedView.findViewWhich<AndroidButton> { view ->
                    view.javaClass.name == "android.widget.Button" && run {
                        val text = (view as AndroidButton).text?.toString()?.trim() ?: ""
                        text == "发送" || text.equals("send", ignoreCase = true)
                    }
                }
                listOfNotNull(menuBtn, sendBtn).forEach { button ->
                    button.setOnLongClickListener { view ->
                        ChatInputBarEnhancements.showMenu(view.context, chatFooter)
                        true
                    }
                }
            }
        }
    }
}
