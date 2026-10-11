package h.Hchat.hooks.items.quickread

import android.content.Context
import h.Hchat.hooks.core.FeatureContext

// TODO(migration stub): 原实现依赖 QuickMarkReadFeature / QuickMarkReadSettings（未迁移）。
// 保留被调用成员签名（@JvmStatic 供 Java 侧静态调用）；后续应改接 WCX 的会话已读接口。
object QuickMarkReadRuntime {
    @JvmStatic
    fun install(context: FeatureContext) { }

    @JvmStatic
    fun isDragEnabled(context: Context?): Boolean = false

    @JvmStatic
    fun isPlusMenuEnabled(context: Context?): Boolean = false

    @JvmStatic
    fun markAllRead(context: Context?, showToast: Boolean): Int = 0

    @JvmStatic
    fun markConversationRead(context: Context?, username: String?, showToast: Boolean): Boolean = false
}
