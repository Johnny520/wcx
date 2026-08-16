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

    private val methodParseXml by dexMethod()    // op0.q.u(String)
    private val methodSendAppMsg by dexMethod()  // k0.J(...)

    private const val TAG = "WeAppMsgApi"

    override fun resolveDex(dexKit: DexKitBridge) {
        val classAppMsgContent = dexKit.findClass {
            matcher {
                usingStrings("<appmsg appid=\"", "parse amessage xml failed")
            }
        }.single()

        val classAppMsgLogic = dexKit.findClass {
            matcher {
                usingStrings("MicroMsg.AppMsgLogic", "summerbig sendAppMsg attachFilePath")
            }
        }.single()

        methodParseXml.find(dexKit, true) {
            matcher {
                declaredClass = classAppMsgContent.name
                modifiers = Modifier.PUBLIC or Modifier.STATIC
                paramTypes(String::class.java.name)
                returnType = classAppMsgContent.name
                usingStrings("parse msg failed")
            }
        }

        methodSendAppMsg.find(dexKit) {
            matcher {
                declaredClass = classAppMsgLogic.name
                modifiers = Modifier.STATIC
                paramCount = 6
                paramTypes(
                    classAppMsgContent.name,
                    "java.lang.String",
                    null,
                    null,
                    null,
                    null
                )
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
