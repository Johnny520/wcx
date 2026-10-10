package h.Hchat.ui.miuix

import android.app.Activity
import java.util.Locale

// TODO(migration stub): WCX 切桥桩。
// 原实现依赖 miuix / compose 重量级 UI，无法整包导入。
// 这里仅保留调用方用到的成员签名，保证调用方可编译；方法体为空实现（并调用 onDismiss 避免回调悬挂）。
// 调用点：
//   h/Hchat/hooks/items/script/ScriptPluginBridge.kt
//     - showMessage / showConfirm / showTextInput / showChoices / showMultiChoices
//     - DialogPosition.from(position)
//   h/Hchat/hooks/api/sns/SnsContextMenuDispatcher.kt
//     - showListChoices
object VoiceForwardMiuixDialog {
    // 方法返回值类型，与原始实现一致
    interface DialogHandle {
        fun close()
        fun isShowing(): Boolean
    }

    enum class DialogPosition {
        TOP,
        CENTER,
        BOTTOM;

        companion object {
            fun from(value: String?): DialogPosition {
                return when (value?.trim()?.lowercase(Locale.US)) {
                    "top", "顶部", "上方" -> TOP
                    "center", "centre", "middle", "居中", "中间" -> CENTER
                    else -> BOTTOM
                }
            }
        }
    }

    private val noopHandle = object : DialogHandle {
        override fun close() {}
        override fun isShowing(): Boolean = false
    }

    // 调用点：ScriptPluginBridge.showModuleDialog -> showMessage(activity, title, message, onDismiss, position)
    fun showMessage(
        activity: Activity,
        title: String,
        message: String,
        onDismiss: () -> Unit,
        position: DialogPosition = DialogPosition.BOTTOM
    ): DialogHandle {
        // TODO(migration stub): 展示消息对话框
        onDismiss()
        return noopHandle
    }

    // 调用点：ScriptPluginBridge.showModuleConfirmDialog
    // 实参：activity, title, message, onResult = { ...(Boolean) }, onDismiss, position
    // 注意：确认回调参数名为 onResult（并非 onConfirm）。
    fun showConfirm(
        activity: Activity,
        title: String,
        message: String,
        onResult: (Boolean) -> Unit,
        onDismiss: () -> Unit,
        position: DialogPosition = DialogPosition.BOTTOM
    ): DialogHandle {
        // TODO(migration stub): 展示确认对话框
        onDismiss()
        return noopHandle
    }

    // 调用点：ScriptPluginBridge.showModuleInputDialog
    // 实参：activity, title, summary, initialValue, placeholder, maxLength=4000, allowEmpty=true,
    //       onConfirm={...(String)}, onDismiss, position  （未传 singleLine，故其须有默认值）
    fun showTextInput(
        activity: Activity,
        title: String,
        summary: String,
        initialValue: String = "",
        placeholder: String = "",
        maxLength: Int = 100,
        singleLine: Boolean = true,
        allowEmpty: Boolean = false,
        onConfirm: (String) -> Unit,
        onDismiss: () -> Unit,
        position: DialogPosition = DialogPosition.BOTTOM
    ): DialogHandle {
        // TODO(migration stub): 展示文本输入对话框
        onDismiss()
        return noopHandle
    }

    // 调用点：ScriptPluginBridge.showModuleChoiceDialog
    // 实参：activity, title, summary, choices: List<Pair<String,String>>, onSelected={...(Int)}, onDismiss, position
    fun showChoices(
        activity: Activity,
        title: String,
        summary: String,
        choices: List<Pair<String, String>>,
        onSelected: (Int) -> Unit,
        onDismiss: () -> Unit,
        position: DialogPosition = DialogPosition.BOTTOM
    ): DialogHandle {
        // TODO(migration stub): 展示单选列表
        onDismiss()
        return noopHandle
    }

    // 调用点：ScriptPluginBridge.showModuleMultiChoiceDialog
    // 实参：activity, title, summary, choices: List<Pair<String,String>>, initialSelected: Set<Int>,
    //       allowEmpty=true, onConfirm={...(Set<Int>)}, onDismiss, position
    fun showMultiChoices(
        activity: Activity,
        title: String,
        summary: String,
        choices: List<Pair<String, String>>,
        initialSelected: Set<Int> = emptySet(),
        allowEmpty: Boolean = false,
        onConfirm: (Set<Int>) -> Unit,
        onDismiss: () -> Unit,
        position: DialogPosition = DialogPosition.BOTTOM
    ): DialogHandle {
        // TODO(migration stub): 展示多选列表
        onDismiss()
        return noopHandle
    }

    // 调用点：SnsContextMenuDispatcher.showFoldPostChoices / showFoldActionChoices
    // 实参仅：activity, title, summary, choices: List<Pair<String,String>>, onSelected={...(Int)}, onDismiss
    // （未传 position / searchable / searchPlaceholder，故这些参数须有默认值）
    fun showListChoices(
        activity: Activity,
        title: String,
        summary: String,
        choices: List<Pair<String, String>>,
        onSelected: (Int) -> Unit,
        onDismiss: () -> Unit,
        position: DialogPosition = DialogPosition.BOTTOM,
        searchable: Boolean = false,
        searchPlaceholder: String = "搜索"
    ): DialogHandle {
        // TODO(migration stub): 展示可搜索列表
        onDismiss()
        return noopHandle
    }
}
