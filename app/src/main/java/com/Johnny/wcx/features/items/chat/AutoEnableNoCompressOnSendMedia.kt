package com.Johnny.wcx.features.items.chat

import android.app.Activity
import com.Johnny.reflekt.utils.toClass
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "自动启用发送原图", categories = ["聊天"], description = "发送媒体时自动勾选发送原图选项")
object AutoEnableNoCompressOnSendMedia : SwitchFeature() {

    override fun onEnable() {
        listOf(
            "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI",
            "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI"
        ).forEach {
            it.toClass().hookBeforeOnCreate {
                val activity = thisObject as Activity
                activity.intent.putExtra("send_raw_img", true)
            }
        }
    }
}
