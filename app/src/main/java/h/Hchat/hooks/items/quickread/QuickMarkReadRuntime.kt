package h.Hchat.hooks.items.quickread

import android.content.Context
import h.Hchat.hooks.core.FeatureContext

// TODO(migration stub): 原实现依赖 QuickMarkReadFeature / QuickMarkReadSettings（未迁移）。
// 保留被调用成员签名；后续应改接 WCX 的 WeConversationApi.markAsRead / markAllAsRead。
object QuickMarkReadRuntime {
    fun install(context: FeatureContext) { }

    fun markAllRead(context: Context?, showToast: Boolean): Int = 0

    fun markConversationRead(context: Context?, username: String?, showToast: Boolean): Boolean = false
}
