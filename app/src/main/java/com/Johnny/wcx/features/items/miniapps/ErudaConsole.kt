package com.Johnny.wcx.features.items.miniapps

import android.webkit.ValueCallback
import android.webkit.WebView
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.R
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.loader.utils.ResourcesInjector
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.BString
import org.luckypray.dexkit.query.enums.StringMatchType

@Feature(
    name = "Eruda 调试面板",
    categories = ["小程序"],
    description = "小程序页面注入 Eruda 调试控制台"
)
object ErudaConsole : SwitchFeature(), IResolveDex {

    private val erudaScript by lazy {
        val resources = HostInfo.application.resources
        ResourcesInjector.injectModuleRes(resources)
        resources.openRawResource(R.raw.eruda)
            .bufferedReader()
            .use { it.readText() }
    }

    private val xwebOnPageFinished by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand.page")
        matcher {
            declaredClass {
                usingEqStrings(
                    "MicroMsg.AppBrandWebView",
                    "onReceivedHttpError, WebResourceRequest url = %s, ErrWebResourceResponse mimeType = %s, status = %d"
                )
                superClass {
                    className("com.tencent.xweb", StringMatchType.StartsWith)
                }
            }
            paramTypes("com.tencent.xweb.WebView", "java.lang.String", "android.graphics.Bitmap")
            returnType = "void"
        }
    }
    private val androidOnPageFinished by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand.page")
        matcher {
            declaredClass {
                superClass = "android.webkit.WebViewClient"
            }
            paramCount = 2
            returnType = "void"
        }
    }

    override val shouldLoadInCurrentProcess get() = TargetProcesses.isInMain || TargetProcesses.currentType == TargetProcesses.PROC_APPBRAND

    override fun onEnable() {
        xwebOnPageFinished.hookAfter {
            WeLogger.i(TAG, "injecting into xwebOnPageFinished: ${args[0]}")
            injectEruda(args[0]!!)
        }
        androidOnPageFinished.hookAfter {
            WeLogger.i(TAG, "injecting into androidOnPageFinished: ${args[0]}")
            injectEruda(args[0]!!)
        }
    }

    private fun injectEruda(webView: Any) {
        try {
            when (webView) {
                is WebView -> {
                    webView.evaluateJavascript(erudaScript, null)
                    webView.evaluateJavascript("eruda.init();", null)
                }

                is com.tencent.xweb.WebView -> {
                    webView.evaluateJavascript(erudaScript, null)
                    webView.evaluateJavascript("eruda.init();", null)
                }

                else -> {
                    webView.reflekt().firstMethod {
                        name = "evaluateJavascript"
                        parameters(BString, ValueCallback::class)
                        superclass()
                    }.apply {
                        invoke(erudaScript, null)
                        invoke("eruda.init();", null)
                    }
                }
            }
            WeLogger.i(TAG, "injected eruda")
        } catch (e: Throwable) {
            WeLogger.w(TAG, "failed to inject eruda", e)
        }
    }

    private const val TAG = "ErudaConsole"
}
