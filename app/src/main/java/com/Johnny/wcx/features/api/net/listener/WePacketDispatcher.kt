package com.Johnny.wcx.features.api.net.listener

import android.os.Handler
import android.os.Looper
import androidx.core.os.postDelayed
import com.tencent.kinda.framework.module.impl.WXPCommReqResp
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.features.api.net.WePacketHelper
import com.Johnny.wcx.features.api.net.WePacketManager
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.ClassLoaders
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

@Feature(name = "数据包拦截与篡改服务", categories = ["API"], description = "响应数据包拦截与篡改")
object WePacketDispatcher : ApiFeature(), IResolveDex {

    private const val TAG = "WePacketDispatcher"

    private val classOnGYNetEnd by dexClass {
        searchPackages("com.tencent.mm.network")
        matcher {
            methodCount(1)
            methods {
                add {
                    name = "onGYNetEnd"
                    paramCount = 6
                }
            }
        }
    }

    private val recentRequests = ConcurrentHashMap<String, Long>()

    /** 已提示过「不可序列化」的请求类型，避免同一条 cgi 反复刷日志。 */
    private val warnedNotSerializable = ConcurrentHashMap.newKeySet<String>()

    /**
     * 把 `parseFrom(bytes)` 的结果写回 wrapper 的 `a` 字段。
     *
     * protobuf 的 `parseFrom` 是静态工厂（`parseFrom(byte[]) -> XxxReq`），不会修改传入对象，
     * 旧实现调用后丢掉返回值，导致请求/响应篡改规则配了也不生效。这里把新对象塞回 wrapper，
     * 微信后续序列化用的就是篡改后的内容。失败返回 null（调用方只记日志，不影响原请求）。
     */
    private fun applyParsedPb(wrapper: Any, current: Any, tampered: ByteArray): Any? = runCatching {
        // 注意：invokeStatic 只在「类反射器」（Class.reflekt()）返回的 ReflectedMethod 上，
        // 实例反射器（instance.reflekt()）返回的 InstanceReflectedMethod 没有该方法，
        // 所以这里必须走 current.javaClass.reflekt()。
        val parsed = current.javaClass.reflekt()
            .firstMethod { name = "parseFrom"; parameterCount = 1 }
            .invokeStatic(tampered)
            ?: return@runCatching null
        wrapper.reflekt().setField("a", parsed)
        parsed
    }.onFailure {
        WeLogger.w(TAG, "apply parsed pb failed: ${it.javaClass.simpleName} ${it.message}")
    }.getOrNull()

    private fun warnNotSerializable(uri: String?, cgiId: Int, className: String) {
        if (warnedNotSerializable.size > 64) warnedNotSerializable.clear()
        val key = "$cgiId|$uri|$className"
        if (warnedNotSerializable.add(key)) {
            WeLogger.w(TAG, "request not serializable, tamper skipped: $key")
        }
    }

    override fun onEnable() {
        Handler(Looper.getMainLooper()).postDelayed(3000) {
            try {
                val netSceneBaseClass = WePacketHelper.classNetSceneBase.clazz
                val callbackInterface = classOnGYNetEnd.clazz

                netSceneBaseClass.reflekt().firstMethod { name = "dispatch" }.hookBefore {
                    val v0Var = args[1] ?: return@hookBefore
                    val originalCallback = args[2] ?: return@hookBefore

                    // 有时 getUri 返回 null
                    val v0Ref = v0Var.reflekt()
                    val uri = v0Ref.invokeMethod("getUri", superclass = true) as? String? ?: "null"
                    val cgiId = v0Ref.invokeMethod("getType", superclass = true) as Int
                    try {
                        val reqWrapper = v0Ref.invokeMethod("getReqObj", superclass = true)
                            ?: return@hookBefore
                        val reqPbObj = reqWrapper.reflekt().getField("a", superclass = true)
                            ?: return@hookBefore

                        // 模块自己用 generic 通道发的请求（WePacketHelper.sendGenericCgi 构造的 raw 包装类）
                        // 里没有完整的 protobuf 字段，toByteArray() 会对 required 字段抛
                        // NPE（com.tencent.mm.protobuf.f.computeSize() on null）——
                        // 这类请求本来就不该参与篡改，只记一次 W 日志，避免每发一条 cgi 刷一条错误。
                        val reqBytes = runCatching {
                            reqPbObj.reflekt().firstMethodOrNull { name = "toByteArray"; superclass() }
                                ?.invoke() as? ByteArray
                        }.getOrNull()
                        if (reqBytes == null) {
                            warnNotSerializable(uri, cgiId, reqPbObj.javaClass.name)
                            return@hookBefore
                        }

                        // 构造唯一标识符
                        val key =
                            "$cgiId|$uri|${reqWrapper.javaClass.name}|${reqPbObj.javaClass.name}|${reqBytes.contentToString()}"

                        // 检查是否在缓存中且时间间隔小于500毫秒
                        val currentTime = System.currentTimeMillis()
                        val lastTime = recentRequests[key]
                        if (lastTime != null && currentTime - lastTime < 500) {
                            // 直接返回，不执行任何请求处理
                            WeLogger.d(TAG, "request skipped (duplicate): $uri")
                            return@hookBefore
                        }

                        // 更新缓存
                        recentRequests[key] = currentTime
                        // 限制缓存大小为10条
                        if (recentRequests.size > 10) {
                            // 移除最旧的条目
                            val oldestEntry = recentRequests.entries.firstOrNull()
                            oldestEntry?.let {
                                recentRequests.remove(it.key)
                            }
                        }

                        WePacketManager.handleRequestTamper(uri, cgiId, reqBytes)?.let { tampered ->
                            // parseFrom 是静态工厂，返回新对象：旧实现把返回值丢掉了，
                            // 等于什么都没改（篡改规则配了也不生效）。写回 wrapper.a 才真正生效。
                            if (applyParsedPb(reqWrapper, reqPbObj, tampered) != null) {
                                WeLogger.i(TAG, "tampered request: $uri")
                            }
                        }
                    } catch (_: NoSuchElementException) {
                        // ignored; toByteArray might not exist since a might be Integer
                    } catch (e: Exception) {
                        WeLogger.e(TAG, "failed to tamper request", e)
                    }

                    if (Proxy.isProxyClass(originalCallback.javaClass)) return@hookBefore

                    args[2] = Proxy.newProxyInstance(
                        ClassLoaders.HOST,
                        arrayOf(callbackInterface)
                    ) { _, method, args ->
                        when (method.name) {
                            "hashCode" -> return@newProxyInstance originalCallback.hashCode()
                            "toString" -> return@newProxyInstance originalCallback.toString()
                            "equals" -> return@newProxyInstance originalCallback == args?.get(0)
                            "onGYNetEnd" -> {
                                runCatching {
                                    val respV0 = args!![4] ?: v0Var
                                    val respV0Ref = respV0.reflekt()

                                    // 处理 Kinda 框架的 WXPCommReqResp
                                    if (respV0 is WXPCommReqResp) {
                                        val originalRespBytes = respV0.wxpRespData
                                        if (originalRespBytes != null) {
                                            WePacketManager.handleResponseTamper(
                                                uri,
                                                cgiId,
                                                originalRespBytes
                                            )?.let { tampered ->
                                                respV0Ref.invokeMethod("setWXPRespData", tampered, superclass = true)
                                                WeLogger.i(
                                                    TAG,
                                                    "tampered response (WXP): $uri"
                                                )
                                            }
                                        }
                                    }
                                    // 处理标准混淆的 ICommReqResp 实现
                                    else {
                                        val respWrapper = runCatching {
                                            respV0Ref.getField("b", superclass = true)
                                        }.getOrElse { respV0Ref.invokeMethod("getRespObj", superclass = true) }

                                        if (respWrapper != null) {
                                            val respPbObj = runCatching {
                                                respWrapper.reflekt().getField("a", superclass = true)
                                            }.getOrNull()


                                            if (respPbObj != null) {
                                                val respPbObjRef = respPbObj.reflekt()

                                                runCatching {
                                                    val originalRespBytes = runCatching {
                                                        respPbObjRef.invokeMethod("toByteArray", superclass = true) as? ByteArray
                                                    }.getOrNull() ?: return@runCatching
                                                    WePacketManager.handleResponseTamper(
                                                        uri,
                                                        cgiId,
                                                        originalRespBytes
                                                    )?.let { tampered ->
                                                        // 同上：parseFrom 返回新对象，必须写回 wrapper.a
                                                        if (applyParsedPb(respWrapper, respPbObj, tampered) != null) {
                                                            WeLogger.i(TAG, "tampered response (PB): $uri")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }.onFailure { WeLogger.e(TAG, "failed to tamper inner logic", it) }
                            }
                        }

                        return@newProxyInstance method.invoke(
                            originalCallback,
                            *(args ?: emptyArray())
                        )
                    }
                }
            } catch (ex: IllegalStateException) {
                WeLogger.w(TAG, "exception occurred during entry", ex)
            }
        }
    }
}
