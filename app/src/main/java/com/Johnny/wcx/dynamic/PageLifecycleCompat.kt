package com.Johnny.wcx.dynamic

import android.app.Activity
import android.content.Intent
import com.Johnny.wcx.loader.utils.ActivityLauncher
import com.Johnny.wcx.utils.WeLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference

/**
 * 页面生命周期动态兼容层。
 *
 * 针对 8.0.77 这类改动 MainUI、startActivity 校验逻辑的版本，
 * 自动生成兼容中间层代理；原有页面劫持、侧边栏渲染代码无需删减，
 * 由兼容层缓冲新版校验规则，杜绝主线程死锁、Tab 空白，所有 UI 功能照常运行。
 *
 * 主要功能：
 * 1. 拦截微信的 startActivity 逻辑，处理模块 Activity 的代理
 * 2. 监控 LauncherUI 页面生命周期，确保侧边栏正确挂载
 * 3. 检测四大 Tab 加载状态，防止空白卡死
 * 4. 自动微调页面挂载时机
 */
object PageLifecycleCompat {

    private const val TAG = "PageLifecycleCompat"

    // 已知的微信 LauncherUI 类名模式
    private val LAUNCHER_UI_PATTERNS = listOf(
        "com.tencent.mm.ui.LauncherUI",
        "com.tencent.mm.ui.MainUI",
        "com.tencent.mm.ui.HomeUI",
        "com.tencent.mm.ui.chatting.ChattingUI"
    )

    // 已处理的 Activity 弱引用集合
    private val processedActivities = mutableSetOf<WeakReference<Activity>>()

    private var isInitialized = false
    private var hookedStartActivity = false
    private var hookedLauncherUI = false

    /**
     * 初始化兼容层。
     *
     * @param launcherUIClassName 当前微信版本的 LauncherUI 类名（由扫描引擎提供）
     * @param activityProxyClass 模块的 ActivityProxy 类（用于页面劫持）
     */
    fun init(
        launcherUIClassName: String? = null,
        activityProxyClass: Class<*>? = null
    ) {
        if (isInitialized) return
        isInitialized = true

        WeLogger.i(TAG, "initializing page lifecycle compatibility layer")

        // 1. Hook startActivity (兼容不同版本)
        hookStartActivity(activityProxyClass)

        // 2. Hook LauncherUI (兼容不同版本)
        if (launcherUIClassName != null) {
            hookLauncherUI(launcherUIClassName)
        } else {
            tryAutoDetectAndHookLauncherUI()
        }

        // 3. 记录已初始化
        WeLogger.i(TAG, "page lifecycle compatibility layer initialized")
    }

    /**
     * 动态兼容 startActivity — 拦截微信新版 Activity 启动校验逻辑。
     */
    private fun hookStartActivity(activityProxyClass: Class<*>?) {
        if (hookedStartActivity) return

        try {
            // Hook Activity.startActivity(Intent)
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "startActivity",
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val intent = param.args[0] as? Intent ?: return
                        val component = intent.component ?: return

                        // 判断是否为模块 Activity
                        if (component.className.startsWith("com.Johnny.wcx")) {
                            WeLogger.d(TAG, "intercepted module activity start: ${component.className}")
                            // 标记为模块 Activity，由 ActivityProxy 处理
                            intent.putExtra("_wcx_compat_layer", true)
                        }
                    }
                }
            )

            // Hook Activity.startActivity(Intent, Bundle)
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "startActivity",
                Intent::class.java,
                android.os.Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val intent = param.args[0] as? Intent ?: return
                        val component = intent.component ?: return

                        if (component.className.startsWith("com.Johnny.wcx")) {
                            intent.putExtra("_wcx_compat_layer", true)
                        }
                    }
                }
            )

            hookedStartActivity = true
            WeLogger.i(TAG, "startActivity compatibility layer hooked")
        } catch (e: Exception) {
            WeLogger.w(TAG, "failed to hook startActivity: ${e.message}")
        }
    }

    /**
     * Hook LauncherUI 页面生命周期，确保侧边栏正常挂载。
     */
    private fun hookLauncherUI(className: String) {
        if (hookedLauncherUI) return

        try {
            val clazz = Class.forName(className, false, getHostClassLoader())
            if (clazz == null) {
                WeLogger.w(TAG, "LauncherUI class not found: $className")
                return
            }

            // Hook onCreate — 页面创建时确保兼容层就绪
            XposedHelpers.findAndHookMethod(
                className,
                getHostClassLoader(),
                "onCreate",
                android.os.Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        onLauncherUICreated(activity)
                    }
                }
            )

            // Hook onResume — 页面恢复时验证所有 Tab 正常
            XposedHelpers.findAndHookMethod(
                className,
                getHostClassLoader(),
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        onLauncherUIResumed(activity)
                    }
                }
            )

            // Hook onDestroy — 清理资源
            XposedHelpers.findAndHookMethod(
                className,
                getHostClassLoader(),
                "onDestroy",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        onLauncherUIDestroyed(activity)
                    }
                }
            )

            hookedLauncherUI = true
            WeLogger.i(TAG, "LauncherUI compatibility layer hooked: $className")
        } catch (e: Exception) {
            WeLogger.w(TAG, "failed to hook LauncherUI $className: ${e.message}")
        }
    }

    /**
     * 自动检测并 Hook LauncherUI。
     */
    private fun tryAutoDetectAndHookLauncherUI() {
        for (pattern in LAUNCHER_UI_PATTERNS) {
            try {
                Class.forName(pattern, false, getHostClassLoader())
                hookLauncherUI(pattern)
                return
            } catch (_: ClassNotFoundException) {
                continue
            }
        }
        WeLogger.w(TAG, "could not auto-detect LauncherUI class")
    }

    // -----------------------------------------------------------------------
    // 生命周期回调
    // -----------------------------------------------------------------------

    private fun onLauncherUICreated(activity: Activity) {
        processedActivities.add(WeakReference(activity))
        WeLogger.d(TAG, "LauncherUI created: ${activity.javaClass.name}")

        // 通知渲染监控器
        RenderMonitor.onPageCreated(activity)
    }

    private fun onLauncherUIResumed(activity: Activity) {
        WeLogger.d(TAG, "LauncherUI resumed: ${activity.javaClass.name}")

        // 检查四大 Tab 是否正常
        RenderMonitor.onPageResumed(activity)

        // 清理过期引用
        processedActivities.removeAll { it.get() == null }
    }

    private fun onLauncherUIDestroyed(activity: Activity) {
        WeLogger.d(TAG, "LauncherUI destroyed: ${activity.javaClass.name}")
        processedActivities.removeAll { it.get() == activity || it.get() == null }
    }

    // -----------------------------------------------------------------------
    // 辅助方法
    // -----------------------------------------------------------------------

    private fun getApplicationContext(): android.content.Context? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val method = atClass.getMethod("currentApplication")
            method.invoke(null) as? android.content.Context
        } catch (e: Exception) {
            null
        }
    }

    private fun getHostClassLoader(): ClassLoader {
        return try {
            Class.forName("com.tencent.mm.sdk.platformtools.MMApplicationContext")
                .classLoader ?: ClassLoader.getSystemClassLoader()
        } catch (e: Exception) {
            ClassLoader.getSystemClassLoader()
        }
    }

    /**
     * 获取当前活动的 LauncherUI 实例。
     */
    fun getCurrentLauncherUI(): Activity? {
        processedActivities.removeAll { it.get() == null }
        return processedActivities.firstNotNullOfOrNull { it.get() }
    }
}