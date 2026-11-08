package h.Hchat.ui.miuix

import android.os.Looper
import android.view.View
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.lifecycle.Lifecycle

internal class EmbeddedComposeRuntime(
    private val lifecycle: Lifecycle
) : View.OnAttachStateChangeListener {
    private var installedView: ComposeView? = null
    private var recomposer: Recomposer? = null
    private var needsReinstall = false

    fun install(view: ComposeView) {
        checkMainThread()
        if (installedView !== view) {
            installedView?.removeOnAttachStateChangeListener(this)
            installedView = view
            view.addOnAttachStateChangeListener(this)
        }
        installFresh(view)
    }

    fun destroy() {
        checkMainThread()
        installedView?.removeOnAttachStateChangeListener(this)
        installedView = null
        recomposer?.cancel()
        recomposer = null
        needsReinstall = false
    }

    override fun onViewAttachedToWindow(view: View) {
        if (needsReinstall && view === installedView) {
            installFresh(view as ComposeView)
        }
    }

    override fun onViewDetachedFromWindow(view: View) {
        if (view !== installedView) return
        (view as ComposeView).disposeComposition()
        recomposer?.cancel()
        recomposer = null
        needsReinstall = true
    }

    private fun installFresh(view: ComposeView) {
        recomposer?.cancel()
        val next = view.createLifecycleAwareWindowRecomposer(lifecycle = lifecycle)
        recomposer = next
        needsReinstall = false
        view.setParentCompositionContext(next)
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper())
    }
}
