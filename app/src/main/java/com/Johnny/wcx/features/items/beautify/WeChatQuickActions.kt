package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.content.Intent
import com.Johnny.wcx.activity.settings.SettingsActivity
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.utils.WeLogger

/**
 * 微信页面快捷动作的唯一实现。
 *
 * 主屏 FAB（悬浮球展开菜单）与主屏侧边栏此前各写一套 startActivity：
 * 参数、flags、异常处理、日志全都不一样，结果是「修了 FAB 侧边栏照旧坏、修了侧边栏 FAB 照旧坏」。
 * 这里收口成一份，任何一处修好即全部生效。
 */
object WeChatQuickActions {

    private const val TAG = "WeChatQuickActions"

    /** 付款码页（部分版本改名，保留两个候选）。 */
    private const val PAYMENT_CODE_CLASS = "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI"
    private const val PAYMENT_CODE_FALLBACK_CLASS = "com.tencent.mm.plugin.mall.ui.MallIndexUIv2"

    /**
     * 打开微信内部页面。
     *
     * 同一 App 内启动，无需 exported 权限；组件名缺失（新版本删掉的页面）由调用方决定提示文案。
     *
     * @return 是否成功提交启动
     */
    fun openWeChatActivity(
        host: Activity,
        className: String,
        extras: Intent.() -> Unit = {},
    ): Boolean {
        if (className.isBlank()) {
            WeLogger.w(TAG, "目标页面为空，跳过")
            return false
        }
        val intent = Intent().setClassName(host.packageName, className).apply(extras)
        return try {
            host.startActivity(intent)
            WeLogger.i(TAG, "打开页面：$className")
            true
        } catch (e: Throwable) {
            // 新版本删除该页面、或页面改版都会走到这里，以前异常被吞掉，表现就是「点了没反应」。
            WeLogger.e(TAG, "打开页面失败（${e.javaClass.simpleName}）：$className", e)
            false
        }
    }

    /** 打开付款码，优先付款码页，失败回落钱包首页。 */
    fun openPaymentCode(host: Activity): Boolean =
        openWeChatActivity(host, PAYMENT_CODE_CLASS) ||
            openWeChatActivity(host, PAYMENT_CODE_FALLBACK_CLASS)

    /** 打开模块自己的设置页（经 Xposed 代理 Activity 重定向到模块 Activity）。 */
    fun openModuleSettings(host: Activity): Boolean = try {
        host.startActivity(Intent(host, SettingsActivity::class.java))
        WeLogger.i(TAG, "打开模块设置：已提交")
        true
    } catch (e: Throwable) {
        WeLogger.e(TAG, "打开模块设置失败（${e.javaClass.simpleName}）", e)
        false
    }

    /**
     * 标记全部已读并刷新会话列表。
     *
     * 只改数据库不会让聊天列表的角标立刻消失，用户会以为「点了没反应」，所以必须接着刷新。
     *
     * @return (是否更新成功, 是否刷新成功)
     */
    fun markAllRead(): Pair<Boolean, Boolean> {
        val updated = runCatching { WeConversationApi.markAllAsRead() }
            .onFailure { WeLogger.w(TAG, "标全部已读失败：" + (it.message ?: it.javaClass.simpleName)) }
            .isSuccess
        val refreshed = runCatching { WeConversationApi.reloadConversations() }
            .onFailure { WeLogger.w(TAG, "刷新会话列表失败：" + (it.message ?: it.javaClass.simpleName)) }
            .isSuccess
        WeLogger.i(TAG, "标全部已读：更新=$updated，刷新=$refreshed")
        return updated to refreshed
    }
}
