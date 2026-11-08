package h.Hchat.hooks.items.moments

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import java.util.Collections
import java.util.WeakHashMap

class MomentsContactFilterFeature : BaseFeature() {
    private val main = Handler(Looper.getMainLooper())
    private val timelineActivities = Collections.synchronizedMap(WeakHashMap<Activity, Unit>())
    private val installedMenus = Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())
    private var preferences: SharedPreferences? = null
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == MomentsContactFilterSettings.KEY_ENABLE) {
            refreshFilterMenus(
                prefs.getBoolean(
                    MomentsContactFilterSettings.KEY_ENABLE,
                    MomentsContactFilterSettings.DEFAULT_ENABLE
                )
            )
        }
    }

    override fun featureId(): String = ID

    override fun name(): String = "朋友圈过滤"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MomentsContactFilterSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        preferences = HchatStorage.preferences(
            context.hostContext(),
            MomentsContactFilterSettings.PREFS_NAME
        ).also { it.registerOnSharedPreferenceChangeListener(preferenceListener) }
        val installed = TIMELINE_ACTIVITY_CLASSES.count { className ->
            installTimelineMenuHook(context, className)
        } > 0
        if (!installed) {
            logError("未找到朋友圈页面快捷入口", null)
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        preferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        preferences = null
        main.removeCallbacksAndMessages(null)
        timelineActivities.clear()
        installedMenus.clear()
    }

    private fun installTimelineMenuHook(context: FeatureContext, className: String): Boolean {
        val activityClass = KavaReflector.loadClass(className, context.hostClassLoader()) ?: return false
        val onCreate = KavaReflector.findDeclaredMethod(
            activityClass,
            "onCreate",
            Bundle::class.java
        ) ?: return false
        return runCatching {
            HookRegistry.get().hook(onCreate, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    bindFilterMenu(param.thisObject as? Activity ?: return)
                }
            })
            true
        }.getOrElse {
            logError("朋友圈过滤快捷入口安装失败: $className", it)
            false
        }
    }

    private fun bindFilterMenu(activity: Activity) {
        timelineActivities[activity] = Unit
        updateFilterMenu(activity, filterEnabled())
    }

    private fun refreshFilterMenus(enabled: Boolean) {
        val update = Runnable {
            val activities = synchronized(timelineActivities) {
                timelineActivities.keys.toList()
            }
            activities.forEach { activity -> updateFilterMenu(activity, enabled) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update.run()
        } else {
            main.post(update)
        }
    }

    private fun updateFilterMenu(activity: Activity, enabled: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) {
            timelineActivities.remove(activity)
            installedMenus.remove(activity)
            return
        }
        if (enabled) {
            if (installedMenus[activity] != true) installFilterMenu(activity)
        } else if (installedMenus[activity] == true) {
            removeFilterMenu(activity)
        }
    }

    private fun filterEnabled(): Boolean {
        return preferences?.getBoolean(
            MomentsContactFilterSettings.KEY_ENABLE,
            MomentsContactFilterSettings.DEFAULT_ENABLE
        ) ?: MomentsContactFilterSettings.DEFAULT_ENABLE
    }

    private fun installFilterMenu(activity: Activity) {
        val addMenu = KavaReflector.findMethodRecursive(
            activity.javaClass,
            "addTextOptionMenu",
            Integer.TYPE,
            String::class.java,
            MenuItem.OnMenuItemClickListener::class.java
        )
        val listener = MenuItem.OnMenuItemClickListener {
            MomentsContactFilterQuickDialog.show(activity)
            true
        }
        if (!KavaReflector.invokeSuccessfully(
                addMenu,
                activity,
                MENU_ITEM_ID,
                MENU_TITLE,
                listener
            )
        ) {
            logError("朋友圈页面添加过滤入口失败: ${activity.javaClass.name}", null)
        } else {
            installedMenus[activity] = true
        }
    }

    private fun removeFilterMenu(activity: Activity) {
        val removeMenu = KavaReflector.findMethodRecursive(
            activity.javaClass,
            "removeOptionMenu",
            Integer.TYPE
        )
        if (!KavaReflector.invokeSuccessfully(removeMenu, activity, MENU_ITEM_ID)) {
            logError("朋友圈页面移除过滤入口失败: ${activity.javaClass.name}", null)
        } else {
            installedMenus.remove(activity)
        }
    }

    companion object {
        const val ID = "moments_contact_filter"

        private const val MENU_ITEM_ID = 0x48434654
        private const val MENU_TITLE = "过滤"
        private val TIMELINE_ACTIVITY_CLASSES = arrayOf(
            "com.tencent.mm.plugin.sns.ui.SnsTimeLineUI",
            "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI"
        )
    }
}
