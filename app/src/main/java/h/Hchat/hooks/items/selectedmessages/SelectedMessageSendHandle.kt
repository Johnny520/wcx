package h.Hchat.hooks.items.selectedmessages

class SelectedMessageSendHandle internal constructor(
    private val cancelAction: () -> Unit
) {
    fun cancel() {
        cancelAction()
    }
}
