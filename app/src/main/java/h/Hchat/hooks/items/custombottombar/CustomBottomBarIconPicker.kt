package h.Hchat.hooks.items.custombottombar

import android.app.Activity
import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object CustomBottomBarIconPicker {
    private data class Pending(
        val activity: WeakReference<Activity>,
        val tabKey: String,
        val callback: (CustomBottomBarIconPickResult) -> Unit,
        val processing: AtomicBoolean = AtomicBoolean(false)
    )

    private val nextRequestCode = AtomicInteger(REQUEST_CODE_START)
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val resultHookedClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val destroyHookedClasses = ConcurrentHashMap.newKeySet<Class<*>>()

    @JvmStatic
    fun launch(
        activity: Activity,
        tabKey: String,
        callback: (CustomBottomBarIconPickResult) -> Unit
    ) {
        val key = tabKey.trim()
        if (!CustomBottomBarIconStore.isSupportedTabKey(key)) {
            callback(CustomBottomBarIconPickResult.FAILED)
            return
        }
        hookActivityHierarchy(activity.javaClass)
        val requestCode = allocateRequestCode()
        pending[requestCode] = Pending(WeakReference(activity), key, callback)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
                        Intent.createChooser(fallback, "选择底栏图标"),
                        requestCode
                    )
                }.onFailure {
                    pending.remove(requestCode)?.callback?.invoke(
                        CustomBottomBarIconPickResult.FAILED
                    )
                }
            }
    }

    private fun hookActivityHierarchy(activityClass: Class<*>) {
        var current: Class<*>? = activityClass
        while (current != null && Activity::class.java.isAssignableFrom(current)) {
            hookActivityResult(current)
            hookActivityDestroy(current)
            current = current.superclass
        }
    }

    private fun allocateRequestCode(): Int {
        repeat(REQUEST_CODE_END - REQUEST_CODE_START + 1) {
            val candidate = nextRequestCode.updateAndGet { current ->
                if (current >= REQUEST_CODE_END) REQUEST_CODE_START else current + 1
            }
            if (!pending.containsKey(candidate)) return candidate
        }
        val reused = pending.keys.minOrNull() ?: REQUEST_CODE_START
        pending.remove(reused)?.callback?.invoke(CustomBottomBarIconPickResult.CANCELLED)
        return reused
    }

    @Synchronized
    private fun hookActivityResult(clazz: Class<*>) {
        if (!resultHookedClasses.add(clazz)) return
        runCatching {
            XposedBridge.hookAllMethods(clazz, "onActivityResult", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requestCode = param.args.getOrNull(0) as? Int ?: return
                    val request = pending[requestCode] ?: return
                    val activity = request.activity.get()
                    if (activity == null) {
                        pending.remove(requestCode, request)
                        return
                    }
                    if (param.thisObject !== activity) return
                    val resultCode = param.args.getOrNull(1) as? Int ?: return
                    val data = param.args.getOrNull(2) as? Intent
                    val uri = data?.data
                    if (resultCode != Activity.RESULT_OK || uri == null) {
                        if (pending.remove(requestCode, request)) {
                            request.callback(CustomBottomBarIconPickResult.CANCELLED)
                        }
                        return
                    }
                    if (!request.processing.compareAndSet(false, true)) return
                    val appContext = activity.applicationContext
                    Thread({
                        val path = runCatching {
                            CustomBottomBarIconStore.saveFromUri(appContext, request.tabKey, uri)
                        }.getOrNull()
                        val owner = request.activity.get()
                        if (owner == null) {
                            pending.remove(requestCode, request)
                            CustomBottomBarIconStore.delete(appContext, path)
                            return@Thread
                        }
                        owner.runOnUiThread {
                            if (!pending.remove(requestCode, request) ||
                                owner.isFinishing || owner.isDestroyed
                            ) {
                                CustomBottomBarIconStore.delete(appContext, path)
                                return@runOnUiThread
                            }
                            request.callback(
                                path?.let(CustomBottomBarIconPickResult::Saved)
                                    ?: CustomBottomBarIconPickResult.FAILED
                            )
                        }
                    }, "Hchat-CustomBottomBarIcon").start()
                }
            })
        }.onFailure { resultHookedClasses.remove(clazz) }
    }

    @Synchronized
    private fun hookActivityDestroy(clazz: Class<*>) {
        if (!destroyHookedClasses.add(clazz)) return
        runCatching {
            XposedBridge.hookAllMethods(clazz, "onDestroy", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    pending.entries.forEach { entry ->
                        val owner = entry.value.activity.get()
                        if (owner == null || owner === activity) {
                            pending.remove(entry.key, entry.value)
                        }
                    }
                }
            })
        }.onFailure { destroyHookedClasses.remove(clazz) }
    }

    private const val REQUEST_CODE_START = 0x7710
    private const val REQUEST_CODE_END = 0x77ff
}

sealed class CustomBottomBarIconPickResult {
    data class Saved(val path: String) : CustomBottomBarIconPickResult()
    object CANCELLED : CustomBottomBarIconPickResult()
    object FAILED : CustomBottomBarIconPickResult()
}
