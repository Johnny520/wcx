package h.Hchat.hooks.items.messageforward

import h.Hchat.hooks.api.sns.SnsContextMenuDispatcher
import h.Hchat.hooks.api.sns.SnsForwardContentResolver
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.preferences.HchatStorage

internal class SnsForwardMenuHooker(
    private val context: FeatureContext,
    private val resolver: SnsForwardContentResolver,
    private val onForward: (android.app.Activity, h.Hchat.hooks.api.sns.SnsForwardSnapshot) -> Unit,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = HchatStorage.preferences(context.hostContext(), MessageForwardSettings.PREFS_NAME)

    init {
        SnsContextMenuDispatcher.register(
            SnsContextMenuDispatcher.Entry(
                owner = OWNER,
                itemId = MENU_ITEM_ID,
                title = MENU_TITLE,
                order = 10,
                iconName = "icons_filled_share",
                isEnabled = ::isEnabled,
                onClick = { activity, target -> onForward(activity, target.snapshot) }
            )
        )
    }

    fun install(): Boolean {
        return SnsContextMenuDispatcher.install(context, resolver, logger)
    }

    fun destroy() {
        SnsContextMenuDispatcher.unregister(OWNER)
    }

    private fun isEnabled(): Boolean {
        return prefs.getBoolean(
            MessageForwardSettings.KEY_SNS_FORWARD_ENABLE,
            MessageForwardSettings.DEFAULT_ENABLE
        )
    }

    companion object {
        const val MENU_ITEM_ID = 0x4843534e
        private const val OWNER = "message_forward"
        private const val MENU_TITLE = "转发[H]"
    }
}
