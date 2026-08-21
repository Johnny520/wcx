package com.Johnny.wcx.features.items.chat

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.reflection.bool

@Feature(name = "禁用消息折叠", categories = ["聊天"], description = "阻止聊天消息被折叠")
object DisableMessageCollapsing : SwitchFeature(), IResolveDex {

    private val methodFoldMsg by dexMethod {
        matcher {
            usingStrings(".msgsource.sec_msg_node.clip-len")
            paramTypes(null, CharSequence::class.java, null, bool, null, null)
        }
    }

    override fun onEnable() {
        methodFoldMsg.hookBefore {
            result = null
        }
    }
}
