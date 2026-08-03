package com.Johnny.wcx.features.items.system

import android.annotation.SuppressLint
import android.content.ComponentName
import com.tencent.tinker.loader.shareutil.ShareTinkerInternals
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.setEnabled
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively

@Feature(name = "禁用微信热更新", categories = ["系统与隐私"], description = "禁止微信热更新, 避免被强制更新到不兼容版本")
object DisableHostHotUpdates : SwitchFeature() {

    private val componentNames = listOf(
        "com.tencent.tinker.lib.service.TinkerPatchForeService",
        "com.tencent.tinker.lib.service.TinkerPatchService",
        $$"com.tencent.tinker.lib.service.TinkerPatchService$InnerService",
        "com.tencent.tinker.lib.service.DefaultTinkerResultService",
    )

    @SuppressLint("SdCardPath")
    @OptIn(ExperimentalPathApi::class)
    override fun onEnable() {
        runCatching { Path("/data/data/${HostInfo.packageName}/tinker").deleteRecursively() }

        ShareTinkerInternals::class.reflekt()
            .methods {
                name {
                    it.startsWith("isTinkerEnabled")
                }
            }
            .forEach {
                it.hookBefore {
                    try {
                        // isTinkerEnabled 系列方法返回 boolean，仅当返回类型匹配时才设置 result
                        if (method is java.lang.reflect.Method) {
                        val returnType = (method as java.lang.reflect.Method).returnType
                        if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
                            result = false
                        }
                    }
                    } catch (e: Throwable) {
                        // 兜底异常捕获
                    }
                }
            }

        batchSetEnabled(false)
    }

    override fun onDisable() {
        batchSetEnabled(true)
    }

    private fun batchSetEnabled(enabled: Boolean) {
        HostInfo.application.apply {
            componentNames.forEach {
                runCatching {
                    ComponentName(
                        this,
                        it
                    ).setEnabled(this, enabled)
                }.onFailure { WeLogger.e(TAG, "failed to set $enabled state for $it") }
            }
        }
    }

    private const val TAG = "DisableHostHotUpdates"
}
