package com.Johnny.wcx.features.items.beautify

import android.view.View
import android.widget.AbsListView
import android.widget.ListView
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.chat.ConversationGrouping
import com.Johnny.wcx.utils.invokeOriginalMethod

@Feature(name = "隐藏主页下滑「最近」页", categories = ["界面美化"], description = "禁用主页下滑功能")
object HideHomeScreenSwipeDownPage : SwitchFeature() {

    override fun onEnable() {
        ListView::class.reflekt()
            .firstMethod {
                name = "addHeaderView"
                parameterCount = 3
            }
            .hookBefore {
                if (thisObject!!.javaClass.simpleName != "ConversationListView") return@hookBefore
                val view = args[0] as View
                val className = view.javaClass.simpleName
                if (className == "TaskBarContainer") {
                    val heightDp = if (!ConversationGrouping.isEnabled) 48 else 94
                    val heightPx = (heightDp * view.resources.displayMetrics.density).toInt()
                    val spacer = View(view.context).apply {
                        layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, heightPx)
                    }
                    invokeOriginalMethod(args = arrayOf(spacer, null, true))
                    result = null
                }
            }
    }
}
