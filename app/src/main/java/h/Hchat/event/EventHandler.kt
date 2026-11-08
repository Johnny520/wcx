package h.Hchat.event

fun interface EventHandler<E> {
    fun handleEvent(event: E)
}
