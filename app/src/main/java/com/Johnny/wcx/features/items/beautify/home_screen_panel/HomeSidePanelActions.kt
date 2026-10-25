package com.Johnny.wcx.features.items.beautify.home_screen_panel

import android.app.Activity
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

import com.Johnny.wcx.features.items.beautify.BeautifyText
import com.Johnny.wcx.features.items.beautify.WeChatQuickActions
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.killHost
import com.Johnny.wcx.utils.restartHost
import kotlinx.coroutines.CoroutineScope

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
        // 关闭浮层失败（动画/手势状态异常）绝不能连累动作执行，否则就是「点了没反应」。
        runCatching { closePanel(null) }
            .onFailure { WeLogger.w(TAG, "关闭面板失败，继续执行动作：$kind", it) }
        runCatching { executeAfterPanelClosed(kind) }
            .onFailure { WeLogger.e(TAG, "侧边栏动作执行异常：$kind", it) }
    }

    fun openPaymentCode() {
        closePanel { WeChatQuickActions.openPaymentCode(activity) }
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

            HomeSidePanelActionKind.WEKIT_SETTINGS -> WeChatQuickActions.openModuleSettings(activity)

            HomeSidePanelActionKind.RESTART_WECHAT -> restartHost()
            HomeSidePanelActionKind.FORCE_STOP_WECHAT -> killHost()
            // 与 FAB 共用同一份实现：标已读 + 刷新会话列表，结果用 Toast 提示
            // （浮层此时已关闭，面板内消息用户看不到，会被当成「点了没反应」）。
            HomeSidePanelActionKind.MARK_ALL_READ -> WeChatQuickActions.markAllReadAsync(activity)
        }
    }

    private fun startWeChatActivity(
        className: String,
        extras: Intent.() -> Unit = {},
    ) {
        WeChatQuickActions.openWeChatActivity(activity, className, extras)
    }

    private companion object {
        const val TAG = "HomeSidePanelAction"
    }
}
