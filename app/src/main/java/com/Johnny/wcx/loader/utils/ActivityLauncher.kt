package com.Johnny.wcx.loader.utils

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookAfterDirectly
import de.robv.android.xposed.XposedHelpers
import dev.ujhhgtg.reflekt.reflekt

/**
 * 轻量级 Activity 启动器 — 替代 ActivityProxy 的简化方案。
 *
 * 与 ActivityProxy 的核心区别：
 * - 不 Hook Instrumentation、IActivityManager、PackageManager、Handler.Callback
 * - 仅通过 Hook WeChatSplashActivity 的 onCreate 来重定向到模块 Activity
 * - 使用简单的 Intent 替换方式，无需 token 缓存机制
 *
 * 适用场景：只需要启动模块内 Activity，不需要全局页面生命周期拦截。
 */
object ActivityLauncher {

    private const val TAG = "ActivityLauncher"
    private const val PROXY_ACTIVITY = "${PackageNames.WECHAT}.app.WeChatSplashActivity"
    private const val TRANSPARENT_PROXY = "${PackageNames.WECHAT}.plugin.appbrand.ipc.AppBrandProxyTransparentUI"

    private var initialized = false

    /**
     * 初始化 ActivityLauncher。
     * Hook 微信代理 Activity 的 onCreate，将模块 Activity 启动重定向到真实目标。
     */
    fun init() {
        if (initialized) return
        initialized = true

        try {
            hookProxyActivity()
            WeLogger.i(TAG, "ActivityLauncher initialized")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to init ActivityLauncher", e)
        }
    }

    /**
     * Hook WeChatSplashActivity.onCreate，拦截模块 Activity 的代理启动。
     * 当 WeChatSplashActivity 被启动且 Intent 中包含模块目标信息时，替换为真实 Activity。
     */
    private fun hookProxyActivity() {
        try {
            val proxyClass = XposedHelpers.findClass(PROXY_ACTIVITY, null)
            proxyClass.reflekt()
                .firstMethod { name = "onCreate"; parameters(Bundle::class) }
                .hookAfterDirectly {
                    try {
                        val activity = thisObject as? Activity ?: return@hookAfterDirectly
                        val intent = activity.intent ?: return@hookAfterDirectly

                        val targetClassName = intent.getStringExtra(EXTRA_TARGET_CLASS)
                            ?: return@hookAfterDirectly

                        WeLogger.d(TAG, "intercepted proxy activity for: $targetClassName")

                        // 构造真实 Intent 并启动目标 Activity
                        val realIntent = Intent().apply {
                            setClassName(HostInfo.packageName, targetClassName)
                            // 复制原始 flags
                            addFlags(intent.flags)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            // 复制 extras
                            intent.extras?.let { bundle ->
                                bundle.remove(EXTRA_TARGET_CLASS)
                                putExtras(bundle)
                            }
                        }

                        activity.startActivity(realIntent)
                        activity.finish()
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "proxy activity hook error", e)
                    }
                }
            WeLogger.d(TAG, "WeChatSplashActivity onCreate hooked")
        } catch (e: Throwable) {
            WeLogger.w(TAG, "Failed to hook WeChatSplashActivity, trying transparent proxy", e)
            hookTransparentProxy()
        }
    }

    /**
     * 备用方案：Hook AppBrandProxyTransparentUI.onCreate
     */
    private fun hookTransparentProxy() {
        try {
            val proxyClass = XposedHelpers.findClass(TRANSPARENT_PROXY, null)
            proxyClass.reflekt()
                .firstMethod { name = "onCreate"; parameters(Bundle::class) }
                .hookAfterDirectly {
                    try {
                        val activity = thisObject as? Activity ?: return@hookAfterDirectly
                        val intent = activity.intent ?: return@hookAfterDirectly

                        val targetClassName = intent.getStringExtra(EXTRA_TARGET_CLASS)
                            ?: return@hookAfterDirectly

                        WeLogger.d(TAG, "intercepted transparent proxy for: $targetClassName")

                        val realIntent = Intent().apply {
                            setClassName(HostInfo.packageName, targetClassName)
                            addFlags(intent.flags)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            intent.extras?.let { bundle ->
                                bundle.remove(EXTRA_TARGET_CLASS)
                                putExtras(bundle)
                            }
                        }

                        activity.startActivity(realIntent)
                        activity.finish()
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "transparent proxy hook error", e)
                    }
                }
            WeLogger.d(TAG, "AppBrandProxyTransparentUI onCreate hooked")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to hook any proxy activity", e)
        }
    }

    // ==================== 公共 API ====================

    /**
     * 启动模块 Activity（通过 Class）。
     *
     * @param activity 当前 Activity 上下文
     * @param targetClass 目标 Activity 类
     */
    fun launch(activity: Activity, targetClass: Class<*>) {
        launch(activity, targetClass.name)
    }

    /**
     * 启动模块 Activity（通过类名字符串）。
     *
     * 原理：启动 WeChatSplashActivity 作为代理，并在 Intent 中携带目标类名。
     * WeChatSplashActivity.onCreate 会被 Hook 拦截，提取目标类名并启动真实 Activity。
     *
     * @param activity 当前 Activity 上下文
     * @param targetClassName 目标 Activity 完整类名
     */
    fun launch(activity: Activity, targetClassName: String) {
        if (targetClassName.isBlank()) {
            WeLogger.w(TAG, "targetClassName is blank, skipping launch")
            return
        }

        try {
            val proxyIntent = Intent().apply {
                component = ComponentName(HostInfo.packageName, PROXY_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_TARGET_CLASS, targetClassName)
            }
            activity.startActivity(proxyIntent)
            WeLogger.d(TAG, "launched $targetClassName via proxy")
        } catch (e: Throwable) {
            // 备用方案：尝试直接启动
            WeLogger.w(TAG, "proxy launch failed, trying direct launch", e)
            try {
                val directIntent = Intent().apply {
                    setClassName(HostInfo.packageName, targetClassName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(directIntent)
            } catch (e2: Throwable) {
                WeLogger.e(TAG, "direct launch also failed for $targetClassName", e2)
            }
        }
    }

    /**
     * 从 Context 启动模块 Activity（用于非 Activity 上下文）。
     */
    fun launch(context: Context, targetClassName: String) {
        if (targetClassName.isBlank()) {
            WeLogger.w(TAG, "targetClassName is blank, skipping launch")
            return
        }

        try {
            val proxyIntent = Intent().apply {
                component = ComponentName(HostInfo.packageName, PROXY_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_TARGET_CLASS, targetClassName)
            }
            context.startActivity(proxyIntent)
            WeLogger.d(TAG, "launched $targetClassName via proxy from context")
        } catch (e: Throwable) {
            WeLogger.w(TAG, "proxy launch from context failed, trying direct", e)
            try {
                val directIntent = Intent().apply {
                    setClassName(HostInfo.packageName, targetClassName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(directIntent)
            } catch (e2: Throwable) {
                WeLogger.e(TAG, "direct launch also failed for $targetClassName", e2)
            }
        }
    }

    /**
     * 判断给定类名是否为模块 Activity。
     */
    fun isModuleActivity(className: String?): Boolean {
        return className?.startsWith(PackageNames.MODULE) == true
    }

    // ==================== 常量 ====================

    private const val EXTRA_TARGET_CLASS = "_wcx_launcher_target_class"
}