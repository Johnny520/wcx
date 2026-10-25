package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.Johnny.wcx.activity.settings.SettingsActivity
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 微信页面快捷动作的唯一实现。
 *
 * 主屏 FAB（悬浮球展开菜单）与主屏侧边栏此前各写一套 startActivity：
 * flags、异常处理、失败提示全不一样，结果就是「FAB 好用、侧边栏没反应」。
 * 这里收口成一份，两边都调它，以后新增动作也只写一次。
 */
object WeChatQuickActions {

    private const val TAG = "WeChatQuickActions"

    /** 付款码页（部分版本改名，保留两个候选）。 */
    private const val PAYMENT_CODE_CLASS = "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI"
    private const val PAYMENT_CODE_FALLBACK_CLASS = "com.tencent.mm.plugin.mall.ui.MallIndexUIv2"

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 解析一个可用的宿主 Activity。
     *
     * 侧边栏会先关闭浮层再执行动作，传进来的引用可能已经 finishing / destroyed；
     * 这里回落到 ActivityThread 里当前存活的 Activity，避免「拿着废引用启动，静默失败」。
     */
    fun resolveHost(host: Activity?): Activity? {
        if (host != null && !host.isFinishing && !host.isDestroyed) return host
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val thread = activityThreadClass.getDeclaredMethod("currentActivityThread")
                .invoke(null) ?: return@runCatching null
            val recordsField = activityThreadClass.getDeclaredField("mActivities")
                .apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val records = recordsField.get(thread) as? Map<Any, Any?> ?: return@runCatching null
            records.values.asSequence().mapNotNull { record ->
                record ?: return@mapNotNull null
                runCatching {
                    record.javaClass.getDeclaredField("activity")
                        .apply { isAccessible = true }
                        .get(record) as? Activity
                }.getOrNull()
            }.firstOrNull { !it.isFinishing && !it.isDestroyed }
        }.getOrNull()
    }

    /**
     * 打开微信内部页面（同一 App 内启动，无需 exported 权限）。
     *
     * @return 是否成功提交启动
     */
    fun openWeChatActivity(
        host: Activity?,
        className: String,
        extras: Intent.() -> Unit = {},
    ): Boolean {
        if (className.isBlank()) {
            WeLogger.w(TAG, "目标页面为空，跳过")
            return false
        }
        val target = resolveHost(host)
        if (target == null) {
            WeLogger.w(TAG, "没有可用 Activity，无法打开 $className")
            toast("当前无法打开该页面，详见日志")
            return false
        }
        val intent = Intent()
            .setClassName(target.packageName, className)
            .apply(extras)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            target.startActivity(intent)
            WeLogger.i(TAG, "打开页面：$className")
            true
        } catch (e: Throwable) {
            // 新版本删掉该页面 / 页面改版都会落到这里。以前异常被吞，表现就是「点了没反应」。
            WeLogger.e(TAG, "打开页面失败（${e.javaClass.simpleName}）：$className", e)
            toast("打开失败：${e.javaClass.simpleName}")
            false
        }
    }

    /** 打开付款码，优先付款码页，失败回落钱包首页。 */
    fun openPaymentCode(host: Activity?): Boolean =
        openWeChatActivity(host, PAYMENT_CODE_CLASS) ||
            openWeChatActivity(host, PAYMENT_CODE_FALLBACK_CLASS)

    /** 打开模块自己的设置页（经 Xposed 代理 Activity 重定向到模块 Activity）。 */
    fun openModuleSettings(host: Activity?): Boolean {
        val target = resolveHost(host) ?: run {
            WeLogger.w(TAG, "没有可用 Activity，无法打开模块设置")
            toast("当前无法打开模块设置，详见日志")
            return false
        }
        return try {
            target.startActivity(Intent(target, SettingsActivity::class.java))
            WeLogger.i(TAG, "打开模块设置：已提交")
            true
        } catch (e: Throwable) {
            WeLogger.e(TAG, "打开模块设置失败（${e.javaClass.simpleName}）", e)
            toast("打开模块设置失败：${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * 标记全部已读并刷新会话列表。
     *
     * 只改数据库不会让聊天列表角标立刻消失，必须接着刷新；两件事都可能在 5~10ms 以上，
     * 所以放到独立 scope（不依赖调用方的 Compose scope，浮层关闭也不会被取消）。
     */
    fun markAllReadAsync(host: Activity? = null) {
        ioScope.launch {
            val updated = runCatching { WeConversationApi.markAllAsRead() }
                .onFailure { WeLogger.w(TAG, "标全部已读失败：" + (it.message ?: it.javaClass.simpleName)) }
                .isSuccess
            val refreshed = runCatching { WeConversationApi.reloadConversations() }
                .onFailure { WeLogger.w(TAG, "刷新会话列表失败：" + (it.message ?: it.javaClass.simpleName)) }
                .isSuccess
            WeLogger.i(TAG, "标全部已读：更新=$updated，刷新=$refreshed")
            toast(
                when {
                    !updated -> "清空未读失败，详见日志"
                    !refreshed -> "已标为已读，刷新列表失败"
                    else -> "已将全部未读消息标为已读"
                }
            )
        }
    }

    /** 统一提示出口：浮层可能已关闭，所以用 Toast 而不是面板内消息。 */
    private fun toast(text: String) {
        val context: Context = HostInfo.application
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
        }
    }
}
