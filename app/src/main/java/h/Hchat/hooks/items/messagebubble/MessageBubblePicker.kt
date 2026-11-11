package h.Hchat.hooks.items.messagebubble

import android.app.Activity
import android.content.Intent
import android.net.Uri
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object MessageBubblePicker {
    private data class Pending(
        val activity: Activity,
        val slot: MessageBubbleSlot,
        val callback: (MessageBubblePickResult) -> Unit
    )

    private val nextRequestCode = AtomicInteger(REQUEST_CODE_START)
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val hookedClasses = ConcurrentHashMap.newKeySet<Class<*>>()

    @JvmStatic
    fun launch(
        activity: Activity,
        slot: MessageBubbleSlot,
        callback: (MessageBubblePickResult) -> Unit = {}
    ) {
        hookActivityResult(activity.javaClass)
        hookActivityResult(Activity::class.java)
        val requestCode = nextRequestCode.updateAndGet { current ->
            if (current >= REQUEST_CODE_END) REQUEST_CODE_START else current + 1
        }
        pending[requestCode] = Pending(activity, slot, callback)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { activity.startActivityForResult(intent, requestCode) }
            .onFailure {
                val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    activity.startActivityForResult(
                        Intent.createChooser(fallback, "选择消息气泡图片"),
                        requestCode
                    )
                }.onFailure {
                    pending.remove(requestCode)?.callback?.invoke(MessageBubblePickResult.FAILED)
                }
            }
    }

    @Synchronized
    private fun hookActivityResult(clazz: Class<*>) {
        if (!hookedClasses.add(clazz)) return
        runCatching {
            XposedBridge.hookAllMethods(clazz, "onActivityResult", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requestCode = param.args.getOrNull(0) as? Int ?: return
                    val result = pending.remove(requestCode) ?: return
                    val resultCode = param.args.getOrNull(1) as? Int ?: return
                    val data = param.args.getOrNull(2) as? Intent
                    val uri = data?.data
                    if (resultCode != Activity.RESULT_OK || uri == null) {
                        result.callback(MessageBubblePickResult.CANCELLED)
                        return
                    }
                    takeReadPermission(result.activity, data, uri)
                    Thread({
                        val success = MessageBubbleStore.saveFromUri(result.activity, result.slot, uri)
                        result.activity.runOnUiThread {
                            result.callback(
                                if (success) MessageBubblePickResult.SAVED else MessageBubblePickResult.FAILED
                            )
                        }
                    }, "Hchat-MessageBubbleSave").start()
                }
            })
        }.onFailure { hookedClasses.remove(clazz) }
    }

    private fun takeReadPermission(activity: Activity, data: Intent, uri: Uri) {
        if (uri.scheme != "content") return
        runCatching {
            val flags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if ((flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    private const val REQUEST_CODE_START = 0x6B10
    private const val REQUEST_CODE_END = 0x6BFF
}

enum class MessageBubblePickResult {
    SAVED,
    CANCELLED,
    FAILED
}
