package com.Johnny.wcx.ui.utils

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.WeakHashMap

object LifecycleOwnerProvider {

    val lifecycleOwner by lazy { XposedLifecycleOwner.create() }

    // Keep a weak reference to activities to prevent memory leaks
    private val lifecycleMap = WeakHashMap<Activity, XposedLifecycleOwner>()

    fun getOrCreate(activity: Activity): XposedLifecycleOwner {
        return lifecycleMap.getOrPut(activity) {
            // Create a fresh lifecycle owner for this activity context
            XposedLifecycleOwner.create().also { customOwner ->

                // Register a listener to forward real activity lifecycle updates to our custom owner
                activity.application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityPaused(act: Activity) {
                        if (act === activity) customOwner.onPause()
                    }

                    override fun onActivityStopped(act: Activity) {
                        if (act === activity) customOwner.onStop()
                    }

                    override fun onActivityDestroyed(act: Activity) {
                        if (act === activity) {
                            customOwner.onDestroy()
                            // Clean up the callback to prevent leaks
                            activity.application.unregisterActivityLifecycleCallbacks(this)
                            lifecycleMap.remove(activity)
                        }
                    }

                    // Unused but required overrides
                    override fun onActivityCreated(act: Activity, savedInstanceState: Bundle?) {}
                    override fun onActivityStarted(act: Activity) {
                        if (act === activity) customOwner.onStart()
                    }

                    override fun onActivityResumed(act: Activity) {
                        if (act === activity) customOwner.onResume()
                    }

                    override fun onActivitySaveInstanceState(act: Activity, outState: Bundle) {}
                })
            }
        }
    }
}
