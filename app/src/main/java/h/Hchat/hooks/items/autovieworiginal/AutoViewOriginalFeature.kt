package h.Hchat.hooks.items.autovieworiginal

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

class AutoViewOriginalFeature : BaseFeature() {
    private var runtime: AutoViewOriginalRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "自动查看原图"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(AutoViewOriginalSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = AutoViewOriginalRuntime(context, ::logFeatureError)
        runtime?.install()
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "auto_view_original"
    }
}

private class AutoViewOriginalRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = HchatStorage.preferences(
        context.hostContext(),
        AutoViewOriginalSettings.PREFS_NAME
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingTasks = WeakHashMap<Activity, Runnable>()
    private val currentPages = WeakHashMap<Activity, Int>()
    private val handledPages = WeakHashMap<Activity, MutableSet<Int>>()
    private val hookedPageListenerMethods = mutableSetOf<Method>()

    private var pageListenerFailureLogged = false

    @Volatile
    private var installed = false

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        val activityClass = KavaReflector.loadClass(
            IMAGE_GALLERY_UI,
            context.hostClassLoader()
        ) ?: run {
            logger("未找到聊天媒体查看页面: $IMAGE_GALLERY_UI", null)
            return false
        }
        val onResume = KavaReflector.findDeclaredMethod(activityClass, "onResume")
        val onDestroy = KavaReflector.findDeclaredMethod(activityClass, "onDestroy")
        if (!isHookable(onResume) || !isHookable(onDestroy)) {
            logger("聊天媒体查看页面生命周期入口不完整", null)
            return false
        }
        return runCatching {
            HookRegistry.get().hook(onResume!!, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    bindPageListener(activity)
                    scheduleCurrentPage(activity)
                }
            })
            HookRegistry.get().hook(onDestroy!!, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    clearActivity(activity)
                }
            })
            installed = true
            true
        }.getOrElse {
            logger("安装聊天媒体查看页面 Hook 失败", it)
            false
        }
    }

    fun destroy() {
        pendingTasks.values.toList().forEach(mainHandler::removeCallbacks)
        pendingTasks.clear()
        currentPages.clear()
        handledPages.clear()
    }

    private fun bindPageListener(activity: Activity) {
        val listener = readFieldByType(activity, PAGE_CHANGE_LISTENER_CLASS) ?: run {
            logPageListenerFailure("未找到聊天媒体翻页监听器", null)
            return
        }
        val callback = KavaReflector.findMethodRecursive(
            listener.javaClass,
            "onPageSelected",
            Integer.TYPE
        )
        if (!isHookable(callback)) {
            logPageListenerFailure("未找到聊天媒体翻页回调", null)
            return
        }
        if (!hookedPageListenerMethods.add(callback!!)) return
        runCatching {
            HookRegistry.get().hook(callback, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val owner = readFieldByType(param.thisObject, IMAGE_GALLERY_UI) as? Activity
                        ?: return
                    val position = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                    scheduleAutoView(owner, position, PAGE_CHANGE_INITIAL_DELAY_MS)
                }
            })
        }.onFailure {
            hookedPageListenerMethods.remove(callback)
            logPageListenerFailure("安装聊天媒体翻页 Hook 失败", it)
        }
    }

    private fun scheduleCurrentPage(activity: Activity) {
        val page = readCurrentPage(activity)
            ?: currentPages[activity]
            ?: UNKNOWN_PAGE
        scheduleAutoView(activity, page, 0L)
    }

    private fun scheduleAutoView(activity: Activity, page: Int, initialDelayMs: Long) {
        pendingTasks.remove(activity)?.let(mainHandler::removeCallbacks)
        currentPages[activity] = page
        if (!isEnabled() || handledPages[activity]?.contains(page) == true) return
        val task = object : Runnable {
            private var retryIndex = 0

            override fun run() {
                if (!isEnabled() || activity.isFinishing || activity.isDestroyed) {
                    clearActivity(activity)
                    return
                }
                if (currentPages[activity] != page || handledPages[activity]?.contains(page) == true) {
                    pendingTasks.remove(activity)
                    return
                }
                if (clickAvailableOriginalAction(activity)) {
                    handledPages.getOrPut(activity) { mutableSetOf() }.add(page)
                    pendingTasks.remove(activity)
                    return
                }
                if (retryIndex < RETRY_DELAYS_MS.size) {
                    mainHandler.postDelayed(this, RETRY_DELAYS_MS[retryIndex++])
                } else {
                    pendingTasks.remove(activity)
                }
            }
        }
        pendingTasks[activity] = task
        if (initialDelayMs > 0L) {
            mainHandler.postDelayed(task, initialDelayMs)
        } else {
            mainHandler.post(task)
        }
    }

    private fun readCurrentPage(activity: Activity): Int? {
        val pager = readFieldByType(activity, MM_VIEW_PAGER_CLASS) ?: return null
        val getter = KavaReflector.findMethodRecursive(pager.javaClass, "getCurrentItem")
            ?: return null
        return (KavaReflector.invoke(getter, pager) as? Number)?.toInt()
    }

    private fun readFieldByType(owner: Any, typeName: String): Any? {
        var current: Class<*>? = owner.javaClass
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredFields(current).firstOrNull { field ->
                !Modifier.isStatic(field.modifiers) && field.type.name == typeName
            }?.let { field ->
                KavaReflector.readField(field, owner)?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun clickAvailableOriginalAction(activity: Activity): Boolean {
        return ORIGINAL_ACTION_IDS.any { resourceName ->
            val viewId = activity.resources.getIdentifier(
                resourceName,
                "id",
                activity.packageName
            )
            if (viewId == 0) return@any false
            val action = activity.findViewById<View>(viewId) ?: return@any false
            if (!action.isShown || !action.isEnabled || !action.hasOnClickListeners()) {
                return@any false
            }
            if (action.width <= 0 || action.height <= 0 || action.alpha <= 0f) {
                return@any false
            }
            action.performClick()
        }
    }

    private fun clearActivity(activity: Activity) {
        pendingTasks.remove(activity)?.let(mainHandler::removeCallbacks)
        currentPages.remove(activity)
        handledPages.remove(activity)
    }

    private fun logPageListenerFailure(message: String, throwable: Throwable?) {
        if (pageListenerFailureLogged) return
        pageListenerFailureLogged = true
        logger(message, throwable)
    }

    private fun isEnabled(): Boolean {
        return prefs.getBoolean(
            AutoViewOriginalSettings.KEY_ENABLE,
            AutoViewOriginalSettings.DEFAULT_ENABLE
        )
    }

    private fun isHookable(method: Method?): Boolean {
        return method != null &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers)
    }

    private companion object {
        const val IMAGE_GALLERY_UI = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"
        const val MM_VIEW_PAGER_CLASS = "com.tencent.mm.ui.base.MMViewPager"
        const val PAGE_CHANGE_LISTENER_CLASS =
            "androidx.viewpager.widget.ViewPager\$OnPageChangeListener"
        const val IMAGE_ORIGINAL_VIEW_ID = "cnb"
        const val VIDEO_ORIGINAL_VIEW_ID = "p1o"
        const val UNKNOWN_PAGE = Int.MIN_VALUE
        const val PAGE_CHANGE_INITIAL_DELAY_MS = 80L
        val ORIGINAL_ACTION_IDS = listOf(IMAGE_ORIGINAL_VIEW_ID, VIDEO_ORIGINAL_VIEW_ID)
        val RETRY_DELAYS_MS = longArrayOf(80L, 160L, 320L, 640L, 1_000L, 1_500L)
    }
}
