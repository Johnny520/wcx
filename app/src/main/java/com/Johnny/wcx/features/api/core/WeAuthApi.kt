package com.Johnny.wcx.features.api.core

import com.Johnny.reflekt.reflekt
import com.Johnny.reflekt.utils.createInstance
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.features.api.net.WeNetSceneApi
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.ClassLoaders
import java.lang.reflect.Proxy
import java.util.LinkedList

@Feature(name = "授权与登录服务", categories = ["API"], description = "提供微信网页/小程序的授权登录能力")
object WeAuthApi : ApiFeature(), IResolveDex {

    private const val TAG = "WeAuthApi"

    val classNetSceneJSLogin by dexClass {
        matcher {
            usingEqStrings("MicroMsg.webview.NetSceneJSLogin", "/cgi-bin/mmbiz-bin/js-login")
        }
    }

    fun jsLogin(appId: String, onResult: (String?) -> Unit) {
        try {
            val netScene = classNetSceneJSLogin.clazz.createInstance(
                appId,
                LinkedList<String>(),
                1,
                "",
                "",
                0,
                1089,
                null
            )

            val queue = WeNetSceneApi.classMmKernel.clazz.reflekt()
                .firstMethod {
                    returnType = WeNetSceneApi.methodAddNetSceneToQueue.method.declaringClass
                }.invokeStatic()!!

            val getDispatcherMethod = queue.javaClass.methods.first { method ->
                method.parameterCount == 0 &&
                        method.returnType.isInterface &&
                        method.returnType.name.startsWith("com.tencent.mm.")
            }
            val dispatcher = getDispatcherMethod.invoke(queue)

            val doSceneMethod = netScene.javaClass.reflekt().firstMethod {
                name = "doScene"
            }
            val callbackInterface = doSceneMethod.parameterTypes[1]

            val callbackProxy = Proxy.newProxyInstance(
                ClassLoaders.HOST,
                arrayOf(callbackInterface)
            ) { _, method, args ->
                if (method.name == "onSceneEnd") {
                    try {
                        val errType = args[0] as Int
                        val errCode = args[1] as Int
                        val errMsg = args[2] as? String
                        val scene = args[3]

                        if (errType == 0 && errCode == 0 && scene != null) {
                            val reqResp = scene.reflekt().firstMethod {
                                name = "getReqResp"
                                superclass()
                            }.invoke()
                            if (reqResp != null) {
                                val respObj = reqResp.reflekt().firstMethod {
                                    name = "getRespObj"
                                    superclass()
                                }.invoke()
                                if (respObj != null) {
                                    val proto = respObj.reflekt().getField("a")
                                    if (proto != null) {
                                        val stringFields = proto.javaClass.declaredFields.filter { it.type == String::class.java }
                                        val code = if (stringFields.isNotEmpty()) {
                                            WeLogger.d(TAG, "found string fields on response: ${stringFields.map { it.name }}")
                                            stringFields[0].isAccessible = true
                                            stringFields[0].get(proto) as? String
                                        } else null

                                        WeLogger.i(TAG, "jsLogin success, code: $code")
                                        onResult(code)
                                    }
                                }
                            }
                        } else {
                            WeLogger.w(TAG, "jsLogin failed: errType=$errType, errCode=$errCode, errMsg=$errMsg")
                            onResult(null)
                        }
                    } catch (t: Throwable) {
                        WeLogger.e(TAG, "error parsing onSceneEnd", t)
                        onResult(null)
                    }
                }
                null
            }

            doSceneMethod.invoke(netScene, dispatcher, callbackProxy)
        } catch (e: Exception) {
            WeLogger.e(TAG, "jsLogin execution failed", e)
            onResult(null)
        }
    }
}
