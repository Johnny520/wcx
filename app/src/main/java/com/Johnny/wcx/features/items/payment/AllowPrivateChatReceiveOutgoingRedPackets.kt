package com.Johnny.wcx.features.items.payment

import android.app.Activity
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger

@Feature(name = "允许领取私聊红包", categories = ["红包与支付"], description = "允许打开私聊中自己发出的红包\n可能导致发送红包提示「请求不成功」")
object AllowPrivateChatReceiveOutgoingRedPackets : SwitchFeature() {

    private const val TAG = "AllowPrivateChatReceiveOutgoingRedPackets"

    override fun onEnable() {
        listOf(
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyPrepareUI",
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewPrepareUI"
        ).forEach {
            it.toClass().hookBeforeOnCreate {
                val activity = thisObject as Activity
                val intent = activity.intent

                // 获取当前会话ID，用于判断是否为分裂群组
                val chatUser = intent.getStringExtra("key_way")
                    ?: intent.getStringExtra("Chat_User")
                    ?: intent.getStringExtra("Contact_User")

                WeLogger.d(TAG, "LuckyMoneyPrepareUI onCreate, chatUser=$chatUser")

                // 仅在分裂群组（假群聊ID包含"@@chatroom"）时设置key_type=1，
                // 正常私聊和群聊不修改参数，避免影响正常红包发送。
                if (chatUser != null && chatUser.contains("@@chatroom")) {
                    WeLogger.i(TAG, "split group detected ($chatUser), setting key_type=1")
                    intent.putExtra("key_type", 1)
                } else {
                    WeLogger.d(TAG, "normal chat ($chatUser), skip key_type modification")
                }
            }
        }
    }
}
