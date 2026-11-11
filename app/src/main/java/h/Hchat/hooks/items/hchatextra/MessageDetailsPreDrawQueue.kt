package h.Hchat.hooks.items.hchatextra

import android.view.View
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** One pending action per view. Like ViewTreeObserver itself, callers must use the UI thread. */
internal class MessageDetailsPreDrawQueue {
    private class Pending(
        val observedView: View,
        var observer: ViewTreeObserver,
        val listener: ViewTreeObserver.OnPreDrawListener
    ) : View.OnAttachStateChangeListener {
        var active = true

        override fun onViewAttachedToWindow(view: View) {
            if (!active) return
            val current = view.viewTreeObserver
            if (observer !== current) {
                if (observer.isAlive) observer.removeOnPreDrawListener(listener)
                // Attachment may already have migrated this listener to the window.
                current.removeOnPreDrawListener(listener)
                current.addOnPreDrawListener(listener)
                observer = current
            }
        }

        override fun onViewDetachedFromWindow(view: View) {
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            val current = view.viewTreeObserver
            if (current !== observer && current.isAlive) current.removeOnPreDrawListener(listener)
        }
    }

    private val pending = WeakHashMap<View, WeakReference<Pending>>()

    fun schedule(key: View, observedView: View, action: () -> Unit): Boolean {
        cancel(key)
        val observer = observedView.viewTreeObserver
        if (!observer.isAlive) return false
        lateinit var entry: Pending
        val listener = ViewTreeObserver.OnPreDrawListener {
            // Removal cannot retract a callback already in a dispatch snapshot.
            val current = pending[key]?.get() === entry
            if (current) pending.remove(key)
            removeListener(entry)
            if (current) action()
            true
        }
        entry = Pending(observedView, observer, listener)
        pending[key] = WeakReference(entry)
        observedView.addOnAttachStateChangeListener(entry)
        observer.addOnPreDrawListener(listener)
        return true
    }

    fun cancel(key: View) {
        pending.remove(key)?.get()?.let(::removeListener)
    }

    fun clear() {
        val entries = pending.values.mapNotNull { it.get() }
        pending.clear()
        entries.forEach(::removeListener)
    }

    private fun removeListener(entry: Pending) {
        entry.active = false
        entry.observedView.removeOnAttachStateChangeListener(entry)
        if (entry.observer.isAlive) entry.observer.removeOnPreDrawListener(entry.listener)
        // Attachment merges the detached view's observer into the window observer,
        // invalidating the original observer while retaining its listeners.
        val currentObserver = entry.observedView.viewTreeObserver
        if (currentObserver !== entry.observer && currentObserver.isAlive) {
            currentObserver.removeOnPreDrawListener(entry.listener)
        }
    }
}

/** Revalidate cached rows once on reattachment, including rows attached without a new bind. */
internal class MessageDetailsAttachQueue {
    /**
     * 同一条消息行反复 bind 时复用同一个监听器。
     * 原实现每次先移除再添加，View 内部要复制一次监听器数组，滚动时是纯浪费。
     */
    private class Entry(
        private val queue: MessageDetailsAttachQueue,
        var action: (View) -> Unit
    ) : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            if (queue.entries[view] !== this) return
            queue.pending.schedule(view, view) {
                if (queue.entries[view] === this) this.action(view)
            }
        }

        override fun onViewDetachedFromWindow(view: View) {
            if (queue.entries[view] === this) queue.pending.cancel(view)
        }
    }

    private val entries = WeakHashMap<View, Entry>()
    private val pending = MessageDetailsPreDrawQueue()

    // The action must not strongly capture the row or its children.
    fun observe(view: View, action: (View) -> Unit) {
        val existing = entries[view]
        if (existing != null) {
            // 回调内部每次都重新查绑定表，换掉引用即可，旧回调不会指向过期数据。
            existing.action = action
            return
        }
        val entry = Entry(this, action)
        entries[view] = entry
        view.addOnAttachStateChangeListener(entry)
    }

    fun cancel(view: View) {
        entries.remove(view)?.let(view::removeOnAttachStateChangeListener)
        pending.cancel(view)
    }

    fun clear() {
        val previous = entries.entries.map { it.key to it.value }
        entries.clear()
        previous.forEach { (view, listener) -> view.removeOnAttachStateChangeListener(listener) }
        pending.clear()
    }
}
