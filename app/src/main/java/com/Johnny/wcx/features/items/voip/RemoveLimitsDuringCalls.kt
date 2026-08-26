package com.Johnny.wcx.features.items.voip

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import java.lang.reflect.Modifier

@Feature(name = "移除通话时聊天限制", categories = ["聊天", "音视频通话"], description = "绕过正在通话时聊天限制")
object RemoveLimitsDuringCalls : SwitchFeature(), IResolveDex {

    private const val TAG = "RemoveLimitsDuringCalls"

    override fun onEnable() {
        listOf(
            methodIsDuringCall,
            methodIsMultiTalking,
            methodIsCameraUsing,
            methodIsCameraUsing2,
            methodIsVoiceUsing,
            methodIsVoiceUsing2,
            methodCheckAppBrandVoiceUsing,
            methodCheckAppBrandVoiceUsing2,
            methodCheckDeviceUsing,
            methodCheckAudioDeviceUsing,
            methodCheckSpeakerUsing,
        ).forEach {
            it.hookBefore {
                try {
                    // 所有方法均返回 boolean，仅当返回类型匹配时才设置 result = false
                    if (method is java.lang.reflect.Method) {
                        val returnType = (method as java.lang.reflect.Method).returnType
                        if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
                            result = false
                        }
                    }
                } catch (e: Throwable) {
                    // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
                }
            }
        }
    }

    // 8.0.77 通话栈加固: 全部 dexMethod 使用 allowFailure, 类被移除/混淆时降级为 placeholder,
    // 避免 Dex 扫描阶段抛异常拖垮整个模块启动。
    private val methodIsDuringCall by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                modifiers(Modifier.ABSTRACT)
            }

            modifiers(Modifier.STATIC)
            paramCount = 0
            returnType = "boolean"

            addInvoke {
                declaredClass = "com.tencent.mm.autogen.events.MultiTalkActionEvent"
            }
        }
    }
    private val methodIsMultiTalking by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "isMultiTalking")
            paramCount = 1
        }
    }

    private val methodIsCameraUsing by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "isCameraUsing", "")
        }
    }
    private val methodIsCameraUsing2 by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "isCameraUsing", "isLiving %b isAnchor %b isAudioMicing %s isVideoMicing %s")
        }
    }
    private val methodIsVoiceUsing by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "isVoiceUsing")
            paramCount = 1
        }
    }
    private val methodIsVoiceUsing2 by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "isVoiceUsing")
            paramCount = 2
        }
    }
    private val methodCheckAppBrandVoiceUsing by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "checkAppBrandVoiceUsingAndShowToast isVoiceUsing:%b, isCameraUsing:%b")
            paramCount = 1
        }
    }
    private val methodCheckAppBrandVoiceUsing2 by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "checkAppBrandVoiceUsingAndShowToast isVoiceUsing:%b, isCameraUsing:%b")
            paramCount = 2
        }
    }

    // Additional device occupancy checks that may block voice message playback during calls.
    // These cover methods beyond the core set above that some WeChat versions use.
    private val methodCheckDeviceUsing by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "checkDeviceUsing")
            returnType = "boolean"
        }
    }
    private val methodCheckAudioDeviceUsing by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "checkAudioDeviceUsing")
            returnType = "boolean"
        }
    }
    private val methodCheckSpeakerUsing by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "checkSpeakerUsing")
            returnType = "boolean"
        }
    }
}
