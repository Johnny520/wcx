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
        if (kind == HomeSidePanelActionKind.MARK_ALL_READ) {
            closePanel(null)
            executeAfterPanelClosed(kind)
        } else {
            closePanel { executeAfterPanelClosed(kind) }
        }
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
                WeConversationApi.markAllAsRead()
                publishMessage(beautifyText("已将全部未读消息标为已读"))
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
        try {
            activity.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            return false
        }
    }

    private companion object {
        const val PAYMENT_CODE_CLASS =
            "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI"
        const val PAYMENT_CODE_FALLBACK_CLASS =
            "com.tencent.mm.plugin.mall.ui.MallIndexUIv2"
    }
}
