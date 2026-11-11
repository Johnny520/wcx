package h.Hchat.ui.miuix

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal interface FloatingGlassBarHostHandle {
    fun restore()
    fun isApplied(): Boolean
}

internal object ScriptFloatingGlassBarHost {
    private const val DEFAULT_BAR_HEIGHT_DP = 56
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hosts = WeakHashMap<ViewGroup, HandleImpl>()

    fun apply(
        activity: Activity,
        bottomBar: View,
        rawOptions: Map<*, *>?,
        restoredCallback: () -> Unit = {}
    ): FloatingGlassBarHostHandle? {
        check(Looper.myLooper() == Looper.getMainLooper()) { "悬浮底栏只能在主线程挂载" }
        if (activity.isFinishing || activity.isDestroyed) return null
        if (bottomBar is SurfaceView || bottomBar is TextureView) return null
        if (!bottomBar.isAttachedToWindow) return null
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        val originalParent = bottomBar.parent as? ViewGroup ?: return null
        if (bottomBar === contentRoot || !isDescendantOf(bottomBar, contentRoot)) return null
        if (contentRoot.childCount == 0) return null
        synchronized(hosts) {
            if (hosts[contentRoot]?.isApplied() == true) return null
        }

        val options = Options.from(rawOptions)
        val originalIndex = originalParent.indexOfChild(bottomBar)
        if (originalIndex < 0) return null
        val originalLayoutParams = bottomBar.layoutParams ?: return null
        val originalBackground = bottomBar.background
        val barHeight = resolveBarHeight(activity, bottomBar, originalLayoutParams)
        val placeholder = View(activity).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            minimumWidth = bottomBar.width.coerceAtLeast(0)
            minimumHeight = barHeight
            visibility = View.INVISIBLE
        }

        val contentChildren = ArrayList<ContentChild>()
        val nativeContentHost = FrameLayout(activity).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }
        val barHost = FrameLayout(activity).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }

        var owner: FloatingComposeOwner? = null
        var composeView: ComposeView? = null
        try {
            originalParent.removeView(bottomBar)
            originalParent.addView(placeholder, originalIndex, originalLayoutParams)

            for (index in 0 until contentRoot.childCount) {
                val child = contentRoot.getChildAt(index)
                contentChildren += ContentChild(child, index, child.layoutParams)
            }
            contentChildren.forEach { state ->
                contentRoot.removeView(state.view)
                nativeContentHost.addView(state.view, state.layoutParams)
            }

            if (options.clearBackground) bottomBar.background = null
            barHost.addView(
                bottomBar,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    barHeight
                )
            )

            val currentOwner = FloatingComposeOwner().apply { attach() }
            owner = currentOwner
            val currentComposeView = ComposeView(activity).apply {
                currentOwner.install(this)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides currentOwner) {
                        FloatingHostTheme(activity) {
                            FloatingHostContent(
                                nativeContentHost = nativeContentHost,
                                barHost = barHost,
                                barHeightPx = barHeight,
                                options = options
                            )
                        }
                    }
                }
            }
            composeView = currentComposeView
            contentRoot.addView(
                currentComposeView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            val decor = activity.window?.decorView
            lateinit var handle: HandleImpl
            handle = HandleImpl(
                bottomBar = bottomBar,
                contentRoot = contentRoot,
                originalParent = originalParent,
                originalIndex = originalIndex,
                originalLayoutParams = originalLayoutParams,
                originalBackground = originalBackground,
                placeholder = placeholder,
                contentChildren = contentChildren,
                nativeContentHost = nativeContentHost,
                barHost = barHost,
                composeView = currentComposeView,
                owner = currentOwner,
                decor = decor,
                onRestored = {
                    synchronized(hosts) {
                        if (hosts[contentRoot] === handle) hosts.remove(contentRoot)
                    }
                    restoredCallback()
                }
            )
            decor?.addOnAttachStateChangeListener(handle)
            if (originalParent !== decor) originalParent.addOnAttachStateChangeListener(handle)
            synchronized(hosts) { hosts[contentRoot] = handle }
            return handle
        } catch (throwable: Throwable) {
            runCatching { composeView?.disposeComposition() }
            (composeView?.parent as? ViewGroup)?.removeView(composeView)
            runCatching {
                restoreHierarchy(
                    bottomBar = bottomBar,
                    contentRoot = contentRoot,
                    originalParent = originalParent,
                    originalIndex = originalIndex,
                    originalLayoutParams = originalLayoutParams,
                    originalBackground = originalBackground,
                    placeholder = placeholder,
                    contentChildren = contentChildren,
                    nativeContentHost = nativeContentHost,
                    barHost = barHost
                )
            }.exceptionOrNull()?.let(throwable::addSuppressed)
            composeView?.let { owner?.clear(it) }
            owner?.destroy()
            throw throwable
        }
    }

    private fun resolveBarHeight(
        activity: Activity,
        bottomBar: View,
        layoutParams: ViewGroup.LayoutParams
    ): Int {
        return bottomBar.height.takeIf { it > 0 }
            ?: bottomBar.measuredHeight.takeIf { it > 0 }
            ?: layoutParams.height.takeIf { it > 0 }
            ?: (DEFAULT_BAR_HEIGHT_DP * activity.resources.displayMetrics.density).toInt()
    }

    private fun isDescendantOf(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun restoreHierarchy(
        bottomBar: View,
        contentRoot: ViewGroup,
        originalParent: ViewGroup,
        originalIndex: Int,
        originalLayoutParams: ViewGroup.LayoutParams,
        originalBackground: Drawable?,
        placeholder: View,
        contentChildren: List<ContentChild>,
        nativeContentHost: FrameLayout,
        barHost: FrameLayout
    ) {
        (nativeContentHost.parent as? ViewGroup)?.removeView(nativeContentHost)
        (barHost.parent as? ViewGroup)?.removeView(barHost)
        contentChildren.sortedBy { it.index }.forEach { state ->
            (state.view.parent as? ViewGroup)?.removeView(state.view)
            val index = state.index.coerceIn(0, contentRoot.childCount)
            contentRoot.addView(state.view, index, state.layoutParams)
        }

        (bottomBar.parent as? ViewGroup)?.removeView(bottomBar)
        val placeholderParent = placeholder.parent as? ViewGroup
        val restoreIndex = if (placeholderParent === originalParent) {
            originalParent.indexOfChild(placeholder).takeIf { it >= 0 } ?: originalIndex
        } else {
            originalIndex
        }.coerceIn(0, originalParent.childCount)
        placeholderParent?.removeView(placeholder)
        bottomBar.background = originalBackground
        originalParent.addView(bottomBar, restoreIndex, originalLayoutParams)
    }

    @Composable
    private fun FloatingHostContent(
        nativeContentHost: FrameLayout,
        barHost: FrameLayout,
        barHeightPx: Int,
        options: Options
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val barHeightDp = with(density) { barHeightPx.toDp() }
        val backdrop = rememberLayerBackdrop()
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { nativeContentHost },
                modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)
            )
            Box(
                modifier = Modifier.fillMaxSize().padding(
                    start = options.horizontalMarginDp.dp,
                    end = options.horizontalMarginDp.dp,
                    bottom = options.bottomMarginDp.dp
                ),
                contentAlignment = Alignment.BottomCenter
            ) {
                FloatingGlassBarSurface(
                    backdrop = backdrop,
                    isBlurEnabled = options.glass,
                    modifier = Modifier.fillMaxWidth().height(barHeightDp + 8.dp)
                ) {
                    AndroidView(
                        factory = { barHost },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    @Composable
    private fun FloatingHostTheme(activity: Activity, content: @Composable () -> Unit) {
        val dark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme(), content = content)
    }

    private data class ContentChild(
        val view: View,
        val index: Int,
        val layoutParams: ViewGroup.LayoutParams
    )

    private data class Options(
        val glass: Boolean,
        val clearBackground: Boolean,
        val horizontalMarginDp: Float,
        val bottomMarginDp: Float
    ) {
        companion object {
            fun from(source: Map<*, *>?): Options = Options(
                glass = source.boolean("glass", true),
                clearBackground = source.boolean("clearBackground", true),
                horizontalMarginDp = source.float("horizontalMarginDp", 12f).coerceIn(0f, 48f),
                bottomMarginDp = source.float("bottomMarginDp", 12f).coerceIn(0f, 48f)
            )

            private fun Map<*, *>?.boolean(key: String, defaultValue: Boolean): Boolean {
                return when (val value = this?.get(key)) {
                    is Boolean -> value
                    is Number -> value.toInt() != 0
                    is String -> when (value.trim().lowercase()) {
                        "true", "1", "yes", "on" -> true
                        "false", "0", "no", "off" -> false
                        else -> defaultValue
                    }
                    else -> defaultValue
                }
            }

            private fun Map<*, *>?.float(key: String, defaultValue: Float): Float {
                return when (val value = this?.get(key)) {
                    is Number -> value.toFloat()
                    is String -> value.toFloatOrNull() ?: defaultValue
                    else -> defaultValue
                }
            }
        }
    }

    private class HandleImpl(
        private val bottomBar: View,
        private val contentRoot: ViewGroup,
        private val originalParent: ViewGroup,
        private val originalIndex: Int,
        private val originalLayoutParams: ViewGroup.LayoutParams,
        private val originalBackground: Drawable?,
        private val placeholder: View,
        private val contentChildren: List<ContentChild>,
        private val nativeContentHost: FrameLayout,
        private val barHost: FrameLayout,
        private val composeView: ComposeView,
        private val owner: FloatingComposeOwner,
        private val decor: View?,
        private val onRestored: () -> Unit
    ) : FloatingGlassBarHostHandle, View.OnAttachStateChangeListener {
        private val applied = AtomicBoolean(true)

        override fun restore() {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                restoreOnMain()
            } else {
                mainHandler.post(::restoreOnMain)
            }
        }

        override fun isApplied(): Boolean = applied.get()

        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) {
            restoreOnMain()
        }

        private fun restoreOnMain() {
            if (!applied.compareAndSet(true, false)) return
            decor?.removeOnAttachStateChangeListener(this)
            if (originalParent !== decor) originalParent.removeOnAttachStateChangeListener(this)
            runCatching { composeView.disposeComposition() }
            (composeView.parent as? ViewGroup)?.removeView(composeView)
            runCatching {
                restoreHierarchy(
                    bottomBar = bottomBar,
                    contentRoot = contentRoot,
                    originalParent = originalParent,
                    originalIndex = originalIndex,
                    originalLayoutParams = originalLayoutParams,
                    originalBackground = originalBackground,
                    placeholder = placeholder,
                    contentChildren = contentChildren,
                    nativeContentHost = nativeContentHost,
                    barHost = barHost
                )
            }.onFailure {
                h.Hchat.utils.HLog.e("[Hchat:Script] 恢复模块悬浮底栏失败: ${it.message}", it)
            }
            owner.clear(composeView)
            owner.destroy()
            onRestored()
        }
    }

    private class FloatingComposeOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner,
        NavigationEventDispatcherOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()
        private val navigationDispatcher = NavigationEventDispatcher()
        private var restored = false

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        override val viewModelStore: ViewModelStore
            get() = store

        override val navigationEventDispatcher: NavigationEventDispatcher
            get() = navigationDispatcher

        fun install(view: View) {
            EmbeddedComposeOwnerInstaller.install(view, this, this, this, this)
        }

        fun clear(view: View) {
            EmbeddedComposeOwnerInstaller.clear(view)
        }

        fun attach() {
            if (!restored) {
                savedStateRegistryController.performRestore(Bundle.EMPTY)
                restored = true
            }
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            }
            navigationDispatcher.dispose()
            store.clear()
        }
    }
}
