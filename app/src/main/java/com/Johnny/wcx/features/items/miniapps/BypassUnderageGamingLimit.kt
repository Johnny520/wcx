package com.Johnny.wcx.features.items.miniapps

import android.view.View
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import com.Johnny.wcx.features.core.Feature

import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger

@Feature(
    name = "绕过防沉迷",
    categories = ["小程序"],
    description = "绕过微信游戏小程序的未成年防沉迷\n绕过后可能没有声音, 看广告能恢复"
)
object BypassUnderageGamingLimit : SwitchFeature() {

    private const val TAG = "BypassUnderageGamingLimit"

    /**
     * 防沉迷拦截页的 URL 特征（8.0.80 实测）：
     * - 旧版人脸识别页：jiazhang.qq.com/healthy/dist/faceRecognition/game_no.html
     * - 8.0.80 家长平台：jiazhang.qq.com/wap/family/dist/main/index.html
     * - 8.0.80 小游戏年龄提示：game.weixin.qq.com/cgi-bin/minigame/static/age-tips/index.html
     *
     * 只要命中就把承载页移出可视区域，微信拿不到用户操作，等于跳过。
     */
    private val limitUrlMarkers = arrayOf(
        "jiazhang.qq.com/healthy/dist/faceRecognition/game_no.html",
        "jiazhang.qq.com/wap/family",
        "minigame/static/age-tips",
        "faceRecognition",
    )

    override val shouldLoadInCurrentProcess get() = TargetProcesses.isInMain || TargetProcesses.currentType == TargetProcesses.PROC_APPBRAND

    override fun onEnable() {
        var hooked = 0
        listOf(
            "com.tencent.xweb.pinus.PSWebview",
            "com.tencent.xweb.pinus.sdk.WebView",
            "com.tencent.xweb.WebView"
        ).forEach { className ->
            val clazz = className.toClassOrNull() ?: return@forEach
            runCatching {
                clazz.reflekt().firstMethodOrNull { name = "loadUrl" }?.hookBefore {
                    val url = args.getOrNull(0) as? String ?: return@hookBefore
                    if (limitUrlMarkers.none { url.contains(it) }) return@hookBefore
                    val webView = thisObject as? View ?: return@hookBefore
                    webView.translationX = 99999.0f
                    webView.translationY = 99999.0f
                    webView.scaleX = 0.01f
                    webView.scaleY = 0.01f
                    WeLogger.i(TAG, "已移走防沉迷页面：${url.take(120)}")
                }
                hooked++
            }.onFailure { WeLogger.e(TAG, "hook $className.loadUrl 失败", it) }
        }
        if (hooked == 0) {
            WeLogger.e(TAG, "WebView.loadUrl 一个都没 hook 上，绕过防沉迷不可用")
        } else {
            WeLogger.i(TAG, "已安装 $hooked 个 WebView 拦截点")
        }
    }
}
