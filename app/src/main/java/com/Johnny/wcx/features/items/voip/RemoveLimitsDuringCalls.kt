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
                result = false
            }
        }
    }

    private val methodIsDuringCall by dexMethod {
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
    private val methodIsMultiTalking by dexMethod {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "isMultiTalking")
            paramCount = 1
        }
    }

    private val methodIsCameraUsing by dexMethod {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "isCameraUsing", "")
        }
    }
    private val methodIsCameraUsing2 by dexMethod {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "isCameraUsing", "isLiving %b isAnchor %b isAudioMicing %s isVideoMicing %s")
        }
    }
    private val methodIsVoiceUsing by dexMethod {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "isVoiceUsing")
            paramCount = 1
        }
    }
    private val methodIsVoiceUsing2 by dexMethod {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "isVoiceUsing")
            paramCount = 2
        }
    }
    private val methodCheckAppBrandVoiceUsing by dexMethod {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "checkAppBrandVoiceUsingAndShowToast isVoiceUsing:%b, isCameraUsing:%b")
            paramCount = 1
        }
    }
    private val methodCheckAppBrandVoiceUsing2 by dexMethod {
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
