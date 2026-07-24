package com.Johnny.wcx.features.items.chat

import android.app.Activity
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "移除媒体发送数量限制", categories = ["聊天"], description = "移除发送媒体的数量限制")
object RemoveSendMediaCountLimit : SwitchFeature() {

    override fun onEnable() {
        listOf(
            "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI",
            "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI"
        ).forEach {
            it.toClass().hookBeforeOnCreate {
                val activity = thisObject as Activity
                activity.intent.putExtra("max_select_count", 999)
            }
        }
    }
}
