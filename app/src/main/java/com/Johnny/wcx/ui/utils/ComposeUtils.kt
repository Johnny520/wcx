package com.Johnny.wcx.ui.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.Johnny.wcx.ui.utils.theme.ModuleTheme

// useful for showing a compose dialog in non-compose context,
// or when you don't want to manage the state for a dialog inside a composable
//
// note that you should use AlertDialogContent instead of AlertDialog inside 'content' to avoid
// creating multiple windows
fun showComposeDialog(
    context: Context,
    directlyDismissable: Boolean = true,
    content: @Composable ShowComposeDialogScope.() -> Unit
) {
    val context = CommonContextWrapper(context)

    val dialog = Dialog(
        context,
        android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar_MinWidth
    )
    val lifecycleOwner = XposedLifecycleOwner.create()

    dialog.apply {
        window!!.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            requestFeature(Window.FEATURE_NO_TITLE)
        }

        setCancelable(directlyDismissable)

        val scope = ShowComposeDialogScope(context, this, window!!, ::dismiss)

        setContentView(
            ComposeView(context).apply {
                setLifecycleOwner(lifecycleOwner)

                // Disable view state saving to prevent NotSerializableException.
                // Dialogs shown via showComposeDialog are ephemeral and don't need
                // state restoration. When the host Activity's onSaveInstanceState
                // fires, the ComposeView would try to serialize its SavedStateRegistry
                // which may contain non-serializable Compose internal objects.
                isSaveEnabled = false

                setContent {
                    CompositionLocalProvider(LocalContext provides context) {
                        ModuleTheme {
                            val fullScreen = scope.fullScreen
                            Box(
                                modifier = if (fullScreen) Modifier.fillMaxSize() else Modifier.wrapContentSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CompositionLocalProvider(LocalShowComposeDialogScope provides scope) {
                                    scope.content()
                                }
                            }
                        }
                    }
                }
            }
        )

        window!!.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        setOnDismissListener { lifecycleOwner.onDestroy() }
        show()
    }
}

class ShowComposeDialogScope(
    val context: Context,
    val dialog: Dialog,
    val window: Window,
    val onDismiss: () -> Unit
) {
    /** 是否已请求整屏显示（请求后 Box 会从居中包裹改成铺满）。 */
    var fullScreen by mutableStateOf(false)
        private set

    /**
     * 把当前对话框从「居中小窗」扩成整屏。
     *
     * Theme_DeviceDefault_Light_Dialog_NoActionBar_MinWidth 会给窗口加最小宽度与系统边距，
     * 选择联系人这类长列表放在小窗里只能看到几行（用户反馈），这里直接改成铺满并清掉边距。
     */
    fun setFullScreen() {
        if (fullScreen) return
        fullScreen = true
        runCatching {
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            window.decorView.setPadding(0, 0, 0, 0)
        }
    }
}

/** 当前弹窗内容所处的 scope；由 showComposeDialog 提供，其它场景为 null。 */
val LocalShowComposeDialogScope = staticCompositionLocalOf<ShowComposeDialogScope?> { null }

/** 在弹窗内容里调用一次即可把承载它的对话框扩成整屏（非 showComposeDialog 宿主时无副作用）。 */
@Composable
fun RequestFullScreenDialog() {
    val scope = LocalShowComposeDialogScope.current ?: return
    LaunchedEffect(scope) { scope.setFullScreen() }
}

fun View.setLifecycleOwner(lifecycleOwner: XposedLifecycleOwner) {
    apply {
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeViewModelStoreOwner(lifecycleOwner)
        setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }
}
