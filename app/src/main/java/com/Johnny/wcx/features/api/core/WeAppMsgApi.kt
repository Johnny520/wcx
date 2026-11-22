package com.Johnny.wcx.features.api.core

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

@Feature(name = "AppMsg 发送服务", categories = ["API"], description = "提供 XML 卡片消息发送能力")
object WeAppMsgApi : ApiFeature(), IResolveDex {

    // allowFailure = true：定位失败（微信改结构）时只让本 API 不可用，不要把整个 Dex 解析流程抛崩
    private val methodParseXml by dexMethod(allowFailure = true)    // AppMsgContent#parse(String)（8.0.80 = xx0.r.v）
    private val methodSendAppMsg by dexMethod(allowFailure = true)  // AppMsgLogic#send(AppMsgContent, ...)（8.0.80 = k0.K）

    private const val TAG = "WeAppMsgApi"

    override fun resolveDex(dexKit: DexKitBridge) {
        // 8.0.80 起 "parse msg failed" / "parse amessage xml failed" 被 3~4 个类同时引用
        // （fo4.o、r51.a、xx0.r、h71.g），按字符串找类必然多命中，
        // findClass(...).single() 会抛 "query did not return a unique result: N"，把整轮 Dex 解析带崩。
        //
        // 改用「结构反推」：AppMsgLogic 里唯一引用 isUseOriginThumb:%b 的方法（8.0.80 = k0.O）
        // 的第一个入参类型就是 AppMsgContent 类，它同时给出 AppMsgLogic 类名。天然唯一，且与发送方法签名自洽。
        val sendWithThumb = dexKit.findMethod {
            matcher {
                usingStrings("isUseOriginThumb:%b")
            }
        }.firstOrNull()
        if (sendWithThumb == null) {
            WeLogger.e(TAG, "定位 AppMsgLogic 失败: isUseOriginThumb:%b 无引用，AppMsg 发送能力不可用")
            return
        }
        val contentClassName = sendWithThumb.paramTypeNames.firstOrNull()
        val logicClassName = sendWithThumb.declaredClassName
        if (contentClassName.isNullOrBlank() || logicClassName.isNullOrBlank()) {
            WeLogger.e(TAG, "定位 AppMsgContent 失败: AppMsgLogic 方法无入参或类名缺失")
            return
        }
        WeLogger.i(TAG, "AppMsgContent=$contentClassName, AppMsgLogic=$logicClassName")

        // 解析 XML：AppMsgContent.parse(String) -> AppMsgContent
        // xx0.r 里只有 v(String):xx0.r 同时满足「参数 String / 返回自身 / 含该串」，唯一命中
        methodParseXml.find(dexKit, true) {
            matcher {
                declaredClass(contentClassName)
                modifiers = Modifier.PUBLIC or Modifier.STATIC
                paramTypes(String::class.java.name)
                returnType(contentClassName)
                usingStrings("parse msg failed")
            }
        }

        // 发送：AppMsgLogic 的 6 参版本 (AppMsgContent, String, String, String, String, byte[]) -> Pair
        // 8.0.80 = k0.K；k0 里 6 参方法有 F/I/K，靠「首参是 AppMsgContent + 返回 Pair」收窄到唯一
        methodSendAppMsg.find(dexKit) {
            matcher {
                declaredClass(logicClassName)
                modifiers = Modifier.STATIC
                paramCount = 6
                paramTypes(
                    contentClassName,
                    "java.lang.String",
                    null,
                    null,
                    null,
                    null
                )
                returnType = "android.util.Pair"
            }
        }
    }

    fun sendXmlAppMsg(
        target: String,
        title: String,
        appId: String,
        url: String?,
        data: ByteArray?,
        xmlContent: String
    ): Boolean {
        return try {
            WeLogger.i(TAG, "sending appmsg to $target")
            val contentObj = methodParseXml.method.invoke(null, xmlContent)
            if (contentObj == null) {
                WeLogger.e(TAG, "failed to parse xml")
                return false
            }

            methodSendAppMsg.method.invoke(
                null,         // static
                contentObj, // content
                appId,             // appId
                title,             // title/appName
                target,            // toUser
                url,               // url
                data               // thumbDat
            )

            true
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to send appmsg", e)
            false
        }
    }
}
