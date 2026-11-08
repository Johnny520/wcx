package h.Hchat.hooks.api.runtime

import h.Hchat.hooks.api.core.WeChatApis

/**
 * 微信公共 API 能力检测。
 *
 * 功能入口可以先通过这里判断能力是否可用，再决定是否显示或执行操作。
 */
class WeChatPermissionApi {
    val isAvailable: Boolean
        get() = true

    fun supportsMessages(): Boolean = WeChatApis.message().hasSender()

    fun supportsAtTextMessage(): Boolean = WeChatApis.messages() != null && WeChatApis.messages().canSendAt()

    fun supportsXmlMessage(): Boolean = WeChatApis.messages() != null && WeChatApis.messages().canSendXml()

    fun supportsDatabase(): Boolean = WeChatApis.runtime().hasDatabase()

    fun supportsContacts(): Boolean = WeChatApis.contact().hasContacts()

    fun supportsChatrooms(): Boolean = WeChatApis.contact().hasChatrooms()

    fun supportsMessageStore(): Boolean = WeChatApis.message().hasStore()

    fun supportsConversations(): Boolean = WeChatApis.message().hasConversations()

    fun supportsMessageEvents(): Boolean = WeChatApis.message().hasEvents()

    fun supportsNetwork(): Boolean = WeChatApis.runtime().hasNetwork()

    fun supportsTransfers(): Boolean = WeChatApis.payment().hasTransfers()

    fun supportsSilentMediaSend(): Boolean {
        val mediaApi = WeChatApis.interaction().media()
        return mediaApi != null &&
            (mediaApi.images().canSendSilently() ||
                mediaApi.voices().canSendSilently() ||
                mediaApi.videos().canSendSilently() ||
                mediaApi.emojis().canSendSilently() ||
                mediaApi.files().canSendSilently())
    }

    fun supportsMessageTypes(): Boolean = WeChatApis.message().hasTypes()

    fun supportsDatabaseChanges(): Boolean = WeChatApis.runtime().hasDatabaseChanges()

    fun supportsCurrentActivity(): Boolean = WeChatApis.interaction().hasCurrentActivity()

    fun supportsActivityStart(): Boolean = WeChatApis.interaction().hasActivityStart()

    fun supportsMessageChanges(): Boolean = WeChatApis.message().hasChanges()

    fun supportsConversationChanges(): Boolean = WeChatApis.runtime().hasConversationChanges()

    fun supportsContactChanges(): Boolean = WeChatApis.contact().hasChanges()

    fun supportsChatroomChanges(): Boolean = WeChatApis.contact().hasChatroomChanges()

    fun supportsLifecycle(): Boolean = WeChatApis.interaction().hasLifecycle()

    fun supportsDiagnostics(): Boolean = WeChatApis.runtime().hasDiagnostics()

    fun supportsTasks(): Boolean = WeChatApis.runtime().hasTasks()

    fun supportsMessageObserve(): Boolean = WeChatApis.message().hasObserve()

    fun supportsChatPage(): Boolean = WeChatApis.interaction().hasChatPage()
}
