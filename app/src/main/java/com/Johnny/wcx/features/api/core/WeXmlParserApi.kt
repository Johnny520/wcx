package com.Johnny.wcx.features.api.core

import de.robv.android.xposed.XC_MethodHook
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

@Feature(name = "XML 解析钩子服务", categories = ["API"], description = "提供篡改 XML 解析流程的能力")
object WeXmlParserApi : ApiFeature(), IResolveDex {

    private const val TAG = "WeXmlParserApi"

    fun interface IAfterParseListener {
        fun onParse(param: XC_MethodHook.MethodHookParam, result: MutableMap<String, Any?>)
    }

    private val listeners = CopyOnWriteArrayList<IAfterParseListener>()

    fun addListener(listener: IAfterParseListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: IAfterParseListener) {
        listeners.remove(listener)
    }

    private val methodXmlParser by dexMethod {
        searchPackages("com.tencent.mm.sdk.platformtools")
        matcher {
            // 8.0.80: ja 类里两个方法共用 "MicroMsg.SDK.XmlParser" tag —
            // c(Node)String 是 nodeToString，d(String,String,String)Map 才是真正的解析入口。
            // 用返回类型 Map 锁死解析方法，避免 Multiple methods found。
            returnType(Map::class.java)
            usingEqStrings("MicroMsg.SDK.XmlParser")
        }
    }

    override fun onEnable() {
        methodXmlParser.hookAfter {
            val param = this

            @Suppress("UNCHECKED_CAST")
            val result = result as? MutableMap<String, Any?>? ?: return@hookAfter
            listeners.forEach { listener ->
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    listener.onParse(param, result)
                }.onFailure { WeLogger.e(TAG, "failed to execute listener ${listener.javaClass.name}", it) }
            }
        }
    }

    override fun onDisable() {
        listeners.clear()
    }
}
