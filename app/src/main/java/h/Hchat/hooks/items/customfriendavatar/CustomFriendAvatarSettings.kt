package h.Hchat.hooks.items.customfriendavatar

import android.content.Context
import h.Hchat.preferences.HchatStorage

object CustomFriendAvatarSettings {
    const val PREFS_NAME = "Hchat_custom_friend_avatar_config"

    const val KEY_ENABLE = "enable"
    const val KEY_CHAT = "scope_chat"
    const val KEY_CONVERSATION = "scope_conversation"
    const val KEY_CONTACTS = "scope_contacts"
    const val KEY_PROFILE = "scope_profile"
    const val KEY_MOMENTS = "scope_moments"
    const val KEY_OTHER_UI = "scope_other_ui"
    const val KEY_DESKTOP_SHORTCUT = "scope_desktop_shortcut"
    const val KEY_NOTIFICATIONS = "scope_notifications"
    const val KEY_MOMENTS_NOTIFICATIONS = "scope_moments_notifications"
    const val KEY_CONVERSATION_MENU = "conversation_menu"
    const val KEY_CONFIGURED_FRIENDS = "configured_friends"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_SCOPE = true
    const val DEFAULT_MENU = true

    fun enabled(context: Context): Boolean = preferences(context).getBoolean(KEY_ENABLE, DEFAULT_ENABLE)

    fun scopeEnabled(context: Context, surface: AvatarSurface): Boolean {
        if (!enabled(context)) return false
        val key = when (surface) {
            AvatarSurface.CHAT -> KEY_CHAT
            AvatarSurface.CONVERSATION -> KEY_CONVERSATION
            AvatarSurface.CONTACTS -> KEY_CONTACTS
            AvatarSurface.PROFILE -> KEY_PROFILE
            AvatarSurface.MOMENTS -> KEY_MOMENTS
            AvatarSurface.OTHER -> KEY_OTHER_UI
        }
        return preferences(context).getBoolean(key, DEFAULT_SCOPE)
    }

    fun notificationsEnabled(context: Context): Boolean {
        return enabled(context) && preferences(context).getBoolean(KEY_NOTIFICATIONS, DEFAULT_SCOPE)
    }

    fun desktopShortcutEnabled(context: Context): Boolean {
        return enabled(context) && preferences(context)
            .getBoolean(KEY_DESKTOP_SHORTCUT, DEFAULT_SCOPE)
    }

    fun momentsNotificationsEnabled(context: Context): Boolean {
        return enabled(context) && preferences(context)
            .getBoolean(KEY_MOMENTS_NOTIFICATIONS, DEFAULT_SCOPE)
    }

    fun conversationMenuEnabled(context: Context): Boolean {
        return enabled(context) && preferences(context).getBoolean(KEY_CONVERSATION_MENU, DEFAULT_MENU)
    }

    internal fun preferences(context: Context) = HchatStorage.preferences(context, PREFS_NAME)
}

enum class AvatarSurface {
    CHAT,
    CONVERSATION,
    CONTACTS,
    PROFILE,
    MOMENTS,
    OTHER
}
