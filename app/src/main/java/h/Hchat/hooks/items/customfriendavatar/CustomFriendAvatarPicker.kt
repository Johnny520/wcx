package h.Hchat.hooks.items.customfriendavatar

import android.app.Activity
import android.content.Intent
import android.net.Uri
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object CustomFriendAvatarPicker {
    private data class Pending(
        val activity: Activity,
        val wxid: String,
        val trackConfiguredFriend: Boolean,
        val callback: (Boolean) -> Unit
    )

    private val nextRequestCode = AtomicInteger(REQUEST_CODE_START)
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val hookedClasses = ConcurrentHashMap.newKeySet<Class<*>>()

    @JvmStatic
    fun launch(activity: Activity, wxid: String, callback: (Boolean) -> Unit = {}) {
        launchInternal(activity, wxid, trackConfiguredFriend = true, callback)
    }

    @JvmStatic
    fun launchGroup(activity: Activity, wxid: String, callback: (Boolean) -> Unit = {}) {
        launchInternal(activity, wxid, trackConfiguredFriend = false, callback)
    }

    private fun launchInternal(
        activity: Activity,
        wxid: String,
        trackConfiguredFriend: Boolean,
        callback: (Boolean) -> Unit
    ) {
        val id = wxid.trim()
        if (id.isEmpty()) {
            callback(false)
            return
        }
        hookActivityResult(activity.javaClass)
        hookActivityResult(Activity::class.java)
        val requestCode = nextRequestCode.updateAndGet { current ->
            if (current >= REQUEST_CODE_END) REQUEST_CODE_START else current + 1
        }
        pending[requestCode] = Pending(activity, id, trackConfiguredFriend, callback)
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
                        Intent.createChooser(fallback, "选择头像"),
                        requestCode
                    )
                }.onFailure {
                    pending.remove(requestCode)?.callback?.invoke(false)
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
                        return
                    }
                    takeReadPermission(result.activity, data, uri)
                    Thread({
                        val success = CustomFriendAvatarStore.saveFromUri(
                            result.activity,
                            result.wxid,
                            uri,
                            result.trackConfiguredFriend
                        )
                        result.activity.runOnUiThread { result.callback(success) }
                    }, "Hchat-CustomAvatarSave").start()
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

    private const val REQUEST_CODE_START = 0x6A10
    private const val REQUEST_CODE_END = 0x6AFF
}
