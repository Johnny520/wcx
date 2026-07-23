package com.Johnny.wcx.features.items.chat

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "禁用拍一拍", categories = ["聊天"], description = "双击他人头像时不发送拍一拍")
object DisablePat : SwitchFeature(), IResolveDex {

    private val methodAvatarDoubleClick by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.AvatarDoubleClickListener", "onDoubleClick: %s")
        }
    }

    override fun onEnable() {
        methodAvatarDoubleClick.hookBefore {
            result = true
        }
    }
}
