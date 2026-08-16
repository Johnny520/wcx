package com.Johnny.wcx.features.items.beautify

import android.view.View
import android.widget.AbsListView
import android.widget.ListView
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.chat.ConversationGrouping
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.invokeOriginal

@Feature(name = "隐藏主页下滑「最近」页", categories = ["界面美化"], description = "禁用主页下滑功能")
object HideHomeScreenSwipeDownPage : SwitchFeature() {

    private const val TAG = "HideHomeScreenSwipe"

    override fun onEnable() {
        // Hook ListView.addHeaderView — 拦截「最近」页 TaskBarContainer 的添加
        ListView::class.reflekt()
            .firstMethod {
                name = "addHeaderView"
                parameterCount = 3
            }
            .hookBefore {
                val view = args[0] as? View ?: return@hookBefore
                val className = view.javaClass.name
                // 使用 contains 模糊匹配，兼容不同微信版本的混淆类名
                if (!className.contains("TaskBar")) return@hookBefore

                try {
                    // 用等高的空白 spacer 替换 TaskBarContainer
                    val heightDp = if (!ConversationGrouping.isEnabled) 48 else 94
                    val heightPx = (heightDp * view.resources.displayMetrics.density).toInt()
                    val spacer = View(view.context).apply {
                        layoutParams = AbsListView.LayoutParams(
                            AbsListView.LayoutParams.MATCH_PARENT, heightPx
                        )
                    }
                    // 先调用原始方法添加 spacer，再阻止原 TaskBarContainer 的添加
                    invokeOriginal(args = arrayOf(spacer, null, true))
                    result = null
                } catch (e: Throwable) {
                    // 兜底：直接阻止添加，避免崩溃
                    WeLogger.w(TAG, "replace TaskBarContainer failed, blocking instead", e)
                    result = null
                }
            }
    }
}
