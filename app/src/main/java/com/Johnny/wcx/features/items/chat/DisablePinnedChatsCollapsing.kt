package com.Johnny.wcx.features.items.chat

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "禁用置顶聊天折叠", categories = ["聊天"], description = "隐藏「折叠置顶聊天」选项\n启用本功能后, 需重启微信 2 次以使更改完全生效")
object DisablePinnedChatsCollapsing : SwitchFeature(), IResolveDex {

    private val methodAddCollapseChatItem by dexMethod {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            usingEqStrings("MicroMsg.FolderHelper", "fold item exist")
        }
    }
    private val methodIfShouldAddCollapseChatItem by dexMethod {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            usingEqStrings("MicroMsg.FolderHelper", "checkIfShowFoldItem, ifShow:")
            returnType(Boolean::class.java)
        }
    }

    override fun onEnable() {
        methodAddCollapseChatItem.hookBefore {
            WeDatabaseApi.execStatement("DELETE FROM rconversation WHERE username = 'message_fold'")
            result = null
        }
        methodIfShouldAddCollapseChatItem.hookBefore {
            WeDatabaseApi.execStatement("DELETE FROM rconversation WHERE username = 'message_fold'")
            result = false
        }
    }
}
