package h.Hchat.ui.miuix

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import h.Hchat.hooks.items.editmsg.EditableChatMessage
import java.util.concurrent.atomic.AtomicBoolean
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

object EditMessageMiuixDialog {
    interface DialogHandle {
        fun close()
        fun isShowing(): Boolean
    }

    fun show(
        activity: Activity,
        message: EditableChatMessage,
        onSave: (String, String) -> Boolean,
        onRestore: () -> Boolean
    ): DialogHandle {
        return show(activity) { close ->
            WindowDialog(
                show = true,
                title = if (message.isTransfer) "修改转账金额" else "修改聊天记录",
                onDismissRequest = close,
                content = {
                    EditMessageDialogContent(
                        message = message,
                        onSave = { reply, quoted ->
                            if (onSave(reply, quoted)) close()
                        },
                        onRestore = {
                            if (onRestore()) close()
                        },
                        onCancel = close
                    )
                }
            )
        }
    }

    @Composable
    private fun EditMessageDialogContent(
        message: EditableChatMessage,
        onSave: (String, String) -> Unit,
        onRestore: () -> Unit,
        onCancel: () -> Unit
    ) {
        var reply by remember(message.msgId) { mutableStateOf(message.replyText) }
        var quoted by remember(message.msgId) { mutableStateOf(message.quotedText) }
        var error by remember(message.msgId) { mutableStateOf("") }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = message.displayText.ifBlank { "当前内容为空" },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().heightIn(max = 96.dp)
            )
            if (message.isTransfer) {
                EditBox(
                    label = "金额",
                    value = reply,
                    singleLine = true,
                    keyboardType = KeyboardType.Decimal,
                    onValueChange = {
                        reply = it
                        error = ""
                    }
                )
            } else {
                EditBox(
                    label = if (message.isQuote) "回复内容" else "消息内容",
                    value = reply,
                    singleLine = false,
                    keyboardType = KeyboardType.Text,
                    onValueChange = {
                        reply = it
                        error = ""
                    }
                )
                if (message.isQuote) {
                    EditBox(
                        label = "引用内容",
                        value = quoted,
                        singleLine = false,
                        keyboardType = KeyboardType.Text,
                        onValueChange = {
                            quoted = it
                            error = ""
                        }
                    )
                }
            }
            if (error.isNotBlank()) {
                Text(
                    text = error,
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (message.hasBackup) {
                    TextButton(
                        text = "恢复",
                        onClick = onRestore,
                        modifier = Modifier.weight(1f)
                    )
                }
                TextButton(
                    text = "取消",
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = "保存",
                    onClick = {
                        if (message.isTransfer && reply.trim().isBlank()) {
                            error = "请输入金额"
                        } else {
                            onSave(reply, quoted)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    @Composable
    private fun EditBox(
        label: String,
        value: String,
        singleLine: Boolean,
        keyboardType: KeyboardType,
        onValueChange: (String) -> Unit
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                textStyle = TextStyle(
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 15.sp
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (singleLine) 44.dp else 92.dp, max = 170.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }

    @Composable
    private fun DialogTheme(activity: Activity, content: @Composable () -> Unit) {
        val colors = if ((activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES
        ) {
            darkColorScheme()
        } else {
            lightColorScheme()
        }
        MiuixTheme(colors = colors, content = content)
    }

    private fun show(
        activity: Activity,
        content: @Composable (() -> Unit) -> Unit
    ): DialogHandle {
        val decor = activity.window?.decorView as? ViewGroup ?: return EmptyHandle
        val owner = DialogComposeOwner()
        val closed = AtomicBoolean(false)
        val root = FrameLayout(activity).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        owner.install(decor)
        owner.install(root)
        owner.attach()
        lateinit var compose: ComposeView
        val closeDialog = {
            if (!closed.getAndSet(true)) {
                runCatching { compose.disposeComposition() }
                if (root.parent === decor) {
                    decor.removeView(root)
                }
                owner.clear(root)
                owner.clear(decor)
                owner.destroy()
            }
        }
        compose = ComposeView(activity).apply {
            owner.install(this)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                    DialogTheme(activity) {
                        content(closeDialog)
                    }
                }
            }
        }
        root.addView(
            compose,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        decor.addView(root)
        root.requestFocus()
        return object : DialogHandle {
            override fun close() = closeDialog()
            override fun isShowing(): Boolean = !closed.get()
        }
    }

    private object EmptyHandle : DialogHandle {
        override fun close() = Unit
        override fun isShowing(): Boolean = false
    }

    private class DialogComposeOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner,
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
