package com.Johnny.wcx.features.items.chat

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import org.luckypray.dexkit.DexKitBridge

@Feature(name = "禁止上传正在输入状态", categories = ["聊天"], description = "禁止微信上传「对方正在输入」状态")
object DisableTypingStatusUploading : SwitchFeature(), IResolveDex {

    private val classMmTypingSendReq by dexClass()

    override fun onEnable() {
        if (classMmTypingSendReq.isPlaceholder) return

        classMmTypingSendReq.reflekt().firstMethod { name = "doScene" }
            .hookBefore {
                result = -1
            }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        classMmTypingSendReq.find(dexKit, allowFailure = true) {
            searchPackages("com.tencent.mm.modelsimple")
            matcher {
                usingEqStrings(
                    "null cannot be cast to non-null type com.tencent.mm.protocal.MMTypingSend.Req",
                    "autoAuth"
                )
            }
        }
    }
}
