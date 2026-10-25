package com.Johnny.wcx.features.items.beautify.home_screen_panel

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlinedfilled.Camera
import com.composables.icons.materialsymbols.outlinedfilled.Cancel
import com.composables.icons.materialsymbols.outlinedfilled.Extension
import com.composables.icons.materialsymbols.outlinedfilled.Favorite
import com.composables.icons.materialsymbols.outlinedfilled.Mark_chat_read
import com.composables.icons.materialsymbols.outlinedfilled.Movie
import com.composables.icons.materialsymbols.outlinedfilled.Qr_code_scanner
import com.composables.icons.materialsymbols.outlinedfilled.Settings
import com.composables.icons.materialsymbols.outlinedfilled.Update
import com.composables.icons.materialsymbols.outlinedfilled.Wallet

import com.Johnny.wcx.activity.settings.SettingsActivity
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.items.beautify.BeautifyText
import com.Johnny.wcx.features.items.beautify.beautifyText
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.killHost
import com.Johnny.wcx.utils.restartHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal data class HomeSidePanelActionSpec(
    val kind: HomeSidePanelActionKind,
    val labelRes: String,
    val icon: ImageVector,
)

internal fun homeSidePanelActionSpec(kind: HomeSidePanelActionKind): HomeSidePanelActionSpec = when (kind) {
    HomeSidePanelActionKind.SCAN -> HomeSidePanelActionSpec(
        kind,
        "扫一扫",
        MaterialSymbols.OutlinedFilled.Qr_code_scanner,
    )

    HomeSidePanelActionKind.MOMENTS -> HomeSidePanelActionSpec(
        kind,
        "朋友圈",
        MaterialSymbols.OutlinedFilled.Camera,
    )

    HomeSidePanelActionKind.WALLET -> HomeSidePanelActionSpec(
        kind,
        "钱包",
        MaterialSymbols.OutlinedFilled.Wallet,
    )

    HomeSidePanelActionKind.CHANNELS -> HomeSidePanelActionSpec(
        kind,
        "视频号",
        MaterialSymbols.OutlinedFilled.Movie,
    )

    HomeSidePanelActionKind.WECHAT_SETTINGS -> HomeSidePanelActionSpec(
        kind,
        "设置",
        MaterialSymbols.OutlinedFilled.Settings,
    )

    HomeSidePanelActionKind.FAVORITES -> HomeSidePanelActionSpec(
        kind,
        "收藏夹",
        MaterialSymbols.OutlinedFilled.Favorite,
    )

    HomeSidePanelActionKind.WEKIT_SETTINGS -> HomeSidePanelActionSpec(
        kind,
        "模块设置",
        MaterialSymbols.OutlinedFilled.Extension,
    )

    HomeSidePanelActionKind.RESTART_WECHAT -> HomeSidePanelActionSpec(
        kind,
        "重启微信",
        MaterialSymbols.OutlinedFilled.Update,
    )

    HomeSidePanelActionKind.FORCE_STOP_WECHAT -> HomeSidePanelActionSpec(
        kind,
        "强行停止",
        MaterialSymbols.OutlinedFilled.Cancel,
    )

    HomeSidePanelActionKind.MARK_ALL_READ -> HomeSidePanelActionSpec(
        kind,
        "清空未读",
        MaterialSymbols.OutlinedFilled.Mark_chat_read,
    )
}

internal class HomeSidePanelActionExecutor(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val closePanel: ((() -> Unit)?) -> Unit,
    private val publishMessage: (BeautifyText) -> Unit,
) {

    fun execute(kind: HomeSidePanelActionKind) {
        // 关闭面板后立即执行动作，不依赖关闭动画的回调：
        // HomeSidePanel.animateTo 在动画被 cancel 时 onAnimationEnd 会直接 return，
        // afterClosed 将永不触发，导致动作丢失（表现为“点击无反应”）。
        WeLogger.i(TAG, "侧边栏动作触发：$kind")
        closePanel(null)
        runCatching { executeAfterPanelClosed(kind) }
            .onFailure { WeLogger.e(TAG, "侧边栏动作执行异常：$kind", it) }
    }

    fun openPaymentCode() {
        closePanel {
            val opened = tryStartActivity(
                Intent().setClassName(activity.packageName, PAYMENT_CODE_CLASS),
            ) || tryStartActivity(
                Intent().setClassName(activity.packageName, PAYMENT_CODE_FALLBACK_CLASS),
            )
            if (!opened) {
                publishMessage(beautifyText("当前微信版本无法打开此动作"))
            }
        }
    }

    private fun executeAfterPanelClosed(kind: HomeSidePanelActionKind) {
        when (kind) {
            HomeSidePanelActionKind.SCAN -> {
                startWeChatActivity("com.tencent.mm.plugin.scanner.ui.BaseScanUI")
            }

            HomeSidePanelActionKind.MOMENTS -> {
                startWeChatActivity("com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI")
            }

            HomeSidePanelActionKind.WALLET -> {
                startWeChatActivity("com.tencent.mm.plugin.mall.ui.MallIndexUIv2") {
                    putExtra("key_not_goto_launcher_ui_when_back", true)
                }
            }

            HomeSidePanelActionKind.CHANNELS -> {
                startWeChatActivity("com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI")
            }

            HomeSidePanelActionKind.WECHAT_SETTINGS -> {
                startWeChatActivity("com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI")
            }

            HomeSidePanelActionKind.FAVORITES -> {
                startWeChatActivity("com.tencent.mm.plugin.fav.ui.FavoriteIndexUI")
            }

            HomeSidePanelActionKind.WEKIT_SETTINGS -> startActivity(
                Intent(activity, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )

            HomeSidePanelActionKind.RESTART_WECHAT -> restartHost()
            HomeSidePanelActionKind.FORCE_STOP_WECHAT -> killHost()
            HomeSidePanelActionKind.MARK_ALL_READ -> scope.launch(Dispatchers.IO) {
                // 只改数据库时聊天列表上的角标不会立刻消失，用户会以为「点了没反应」，
                // 所以标完已读必须再刷新一次会话列表。
                val updated = runCatching { WeConversationApi.markAllAsRead() }
                    .onFailure { WeLogger.w(TAG, "标全部已读失败：" + (it.message ?: it.javaClass.simpleName)) }
                    .isSuccess
                val refreshed = runCatching { WeConversationApi.reloadConversations() }
                    .onFailure { WeLogger.w(TAG, "刷新会话列表失败：" + (it.message ?: it.javaClass.simpleName)) }
                    .isSuccess
                WeLogger.i(TAG, "标全部已读：更新=$updated，刷新=$refreshed")
                publishMessage(
                    beautifyText(
                        when {
                            !updated -> "标为已读失败，详见日志"
                            !refreshed -> "已标为已读，刷新列表失败"
                            else -> "已将全部未读消息标为已读"
                        }
                    )
                )
            }
        }
    }

    private fun startWeChatActivity(
        className: String,
        extras: Intent.() -> Unit = {},
    ) {
        startActivity(Intent().setClassName(activity.packageName, className).apply(extras))
    }

    private fun startActivity(intent: Intent) {
        if (!tryStartActivity(intent)) {
            publishMessage(beautifyText("当前微信版本无法打开此动作"))
        }
    }

    private fun tryStartActivity(intent: Intent): Boolean {
        val target = intent.component?.className ?: intent.action ?: "?"
        return try {
            activity.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            WeLogger.w(TAG, "启动失败（ActivityNotFound）：$target")
            false
        } catch (e: Throwable) {
            // 除 ActivityNotFound 外还可能是 SecurityException / 目标 Activity 需要额外参数等，
            // 这些以前全部被吞掉，表现就是「点了没反应」。
            WeLogger.e(TAG, "启动失败（${e.javaClass.simpleName}）：$target", e)
            false
        }
    }

    private companion object {
        const val TAG = "HomeSidePanelAction"
        const val PAYMENT_CODE_CLASS =
            "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI"
        const val PAYMENT_CODE_FALLBACK_CLASS =
            "com.tencent.mm.plugin.mall.ui.MallIndexUIv2"
    }
}
