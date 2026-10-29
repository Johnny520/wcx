package com.Johnny.wcx.features.items.contacts

import android.app.Activity
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger

@Feature(name = "移除消息批量转发限制", categories = ["联系人与群组"], description = "移除消息多选目标的 9 个数量限制")
object RemoveMessageBatchForwardLimit : SwitchFeature() {

    private const val TAG = "RemoveMessageBatchForwardLimit"

    override fun onEnable() {
        // 8.0.80 适配：MvvmSelectContactUI 在新版已不存在（hook 挂空，功能实际一直没生效），
        // 真正读取 intent extra "max_limit_num" 的是下面两个界面（SelectConversationUI::M8/o8、
        // SelectContactUI::R7/a8）。保留旧类名做多版本兼容，解析不到会静默跳过。
        listOf(
            "com.tencent.mm.ui.transmit.SelectConversationUI",
            "com.tencent.mm.ui.contact.SelectContactUI",
            "com.tencent.mm.ui.mvvm.MvvmSelectContactUI",
            "com.tencent.mm.ui.mvvm.MvvmContactListUI"
        ).forEach {
            it.toClassOrNull()?.hookBeforeOnCreate {
                val activity = thisObject as Activity
                activity.intent.putExtra("max_limit_num", 999)
                WeLogger.i(TAG, "removed batch forward limit for $it")
            }
        }
    }
}
