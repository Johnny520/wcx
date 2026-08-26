package com.Johnny.wcx.features.items.voip

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.view.View
import com.tencent.mm.plugin.multitalk.ui.MultiTalkMainUI
import com.tencent.mm.plugin.voip.ui.VideoActivity
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.activity.PipVoipActivity
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexField
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.Intent
import java.lang.reflect.Modifier
import java.util.WeakHashMap

@Feature(
    name = "音视频通话使用画中画",
    categories = ["聊天", "音视频通话"],
    description = "让微信的音视频通话使用原生的画中画模式而非悬浮窗 (没写完)"
)
object PipVoip : SwitchFeature(), IResolveDex {

    private const val TAG = "PipVoip"
    private const val HANGUP_SCENE = 4103
    private const val FLAG_SUPPORTS_PICTURE_IN_PICTURE = 0x400000
    private const val RESIZE_MODE_RESIZEABLE = 2

    private sealed class Session(val activity: Activity) {
        var pipActive = false

        abstract val micMuted: Boolean
        open val videoEnabled: Boolean = true

        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                // 8.0.77 加固: 这些回调跑在 Binder/主线程上, 行为全部 runCatching 兜底,
                // 异常只进模块日志, 不进微信执行栈。
                when (resultCode) {
                    PipVoipActivity.RESULT_HANG_UP -> {
                        runCatching { hangUp() }
                            .onFailure { WeLogger.e(TAG, "hangUp failed", it) }
                        pipActive = false
                    }

                    PipVoipActivity.RESULT_TOGGLE_MIC ->
                        runCatching { toggleMic() }
                            .onFailure { WeLogger.e(TAG, "toggleMic failed", it) }

                    PipVoipActivity.RESULT_TOGGLE_VIDEO ->
                        runCatching { toggleVideo() }
                            .onFailure { WeLogger.e(TAG, "toggleVideo failed", it) }

                    PipVoipActivity.RESULT_RESTORE ->
                        runCatching { restoreCallActivity() }
                            .onFailure { WeLogger.e(TAG, "restore failed", it) }

                    PipVoipActivity.RESULT_CLOSED -> pipActive = false
                }
            }
        }

        abstract fun hangUp()
        abstract fun toggleMic()
        open fun toggleVideo() = Unit

        fun enterPip() {
            if (pipActive) return
            // 8.0.77 加固: 取值(micMuted) 与启动画中画都 runCatching 兜底, 失败回滚 pipActive,
            // 异常只进模块日志, 不进微信执行栈。
            pipActive = true
            runCatching {
                val muted = micMuted
                activity.startActivity(
                    Intent().apply {
                        component = ComponentName(PackageNames.MODULE, PipVoipActivity::class.java.name)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(PipVoipActivity.EXTRA_GROUP_CALL, this@Session is GroupSession)
                        putExtra(PipVoipActivity.EXTRA_MIC_MUTED, muted)
                        putExtra(PipVoipActivity.EXTRA_VIDEO_ENABLED, videoEnabled)
                        putExtra(PipVoipActivity.EXTRA_RESULT_RECEIVER, receiver)
                    }
                )
                activity.moveTaskToBack(true)
            }.onFailure {
                pipActive = false
                WeLogger.e(TAG, "failed to enter pip", it)
            }
        }

        @SuppressLint("MissingPermission")
        private fun restoreCallActivity() {
            pipActive = false
            val activityManager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.moveTaskToFront(activity.taskId, 0)
        }
    }

    private class SingleSession(
        activity: VideoActivity,
        val manager: Any,
    ) : Session(activity) {
        override val micMuted: Boolean
            get() {
                val audioManager = fieldVoipAudioManager.field.get(manager)
                return fieldVoipMuted.field.getBoolean(audioManager)
            }

        override fun hangUp() {
            methodVoipHangUp.method.invoke(manager, HANGUP_SCENE)
        }

        override fun toggleMic() {
            methodSetVoipMuted.method.invoke(manager, !micMuted)
        }
    }

    private class GroupSession(
        val groupActivity: MultiTalkMainUI,
    ) : Session(groupActivity) {
        override val micMuted: Boolean
            get() {
                val state = fieldMultiTalkMicState.field.get(viewModel)
                return !(methodObservableValue.method.invoke(state) as Boolean)
            }

        override val videoEnabled: Boolean
            get() {
                val state = fieldMultiTalkCameraState.field.get(viewModel)
                return methodObservableValue.method.invoke(state) as Boolean
            }

        override fun hangUp() {
            methodMultiTalkExit.method.invoke(groupActivity)
        }

        override fun toggleMic() {
            methodMultiTalkMic.method.invoke(viewModel, true)
        }

        override fun toggleVideo() {
            methodMultiTalkCamera.method.invoke(viewModel, null)
        }

        private val viewModel: Any
            get() = fieldMultiTalkViewModel.field.get(groupActivity)!!
    }

    private val sessions = WeakHashMap<Activity, Session>()

    private val classVoipActivityProxy by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.ILinkVoipVideoActivityProxy-")
        }
    }

    private val methodVoipActivityProxyDealContentView by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classVoipActivityProxy.clazz)
            paramTypes(View::class.java)
            returnType = "void"
        }
    }

    private val classBaseVoipManager by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.Voip.NewVoipMgr", "hangupTalkingOrCancelInvite")
        }
    }

    private val classFlutterVoipManager by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.FlutterVoipMgr", "qipeng, enableMute.")
        }
    }

    private val classVoipAudioManager by dexClass(allowFailure = true) {
        matcher {
            modifiers(Modifier.FINAL)
            usingEqStrings(
                "MicroMsg.VoIP.VoIPAudioManager",
                "requestAudioFocus: gain focus",
                "requestAudioFocus: not gain focus",
            )
        }
    }

    private val classFlutterVoipPlugin by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings(
                "MicroMsg.FlutterVoipPlugin",
                "minimize: activity=",
                "voip is already minimized, ignore!",
                "minimize, permission denied",
            )
        }
    }

    private val fieldVoipAudioManager by dexField(allowFailure = true) {
        matcher {
            declaredClass(classBaseVoipManager.clazz)
            type(classVoipAudioManager.clazz.interfaces.single())
        }
    }

    private val methodVoipMinimize by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classBaseVoipManager.clazz)
            paramTypes("boolean")
            returnType = "boolean"
            usingEqStrings("onMinimizeVoip, async to minimize")
        }
    }

    private val methodSetVoipMuted by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classFlutterVoipManager.clazz)
            paramTypes("boolean")
            returnType = "void"
            usingEqStrings("qipeng, enableMute.", "qipeng, disableMute.")
        }
    }

    private val methodVoipHangUp by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classBaseVoipManager.clazz)
            paramTypes("int")
            returnType = "void"
            usingEqStrings("hangupTalkingOrCancelInvite")
        }
    }

    private val fieldVoipMuted by dexField(allowFailure = true) {
        matcher {
            declaredClass(classVoipAudioManager.clazz)
            type = "boolean"
            addWriteMethod {
                declaredClass(classFlutterVoipManager.clazz)
                paramTypes("boolean")
                usingEqStrings("qipeng, enableMute.", "qipeng, disableMute.")
            }
        }
    }

    private val methodFlutterVoipMinimize by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classFlutterVoipPlugin.clazz)
            paramCount = 4
            returnType = "void"
            usingEqStrings(
                "MicroMsg.FlutterVoipPlugin",
                "minimize: activity=",
                "voip is already minimized, ignore!",
                "minimize, permission denied",
            )
        }
    }

    private val fieldFlutterVoipActivity by dexField(allowFailure = true) {
        matcher {
            declaredClass(classFlutterVoipPlugin.clazz)
            type(Activity::class.java)
        }
    }

    private val fieldFlutterVoipManager by dexField(allowFailure = true) {
        matcher {
            declaredClass(classFlutterVoipPlugin.clazz)
            type(classFlutterVoipManager.clazz)
        }
    }

    private val methodFlutterVoipAttachedToActivity by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classFlutterVoipPlugin.clazz)
            paramCount = 1
            returnType = "void"
            usingEqStrings("onAttachedToActivity: ", "init flutter voip mgr")
        }
    }

    private val methodFlutterVoipReattachedToActivity by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classFlutterVoipPlugin.clazz)
            paramCount = 1
            returnType = "void"
            usingEqStrings("onReattachedToActivityForConfigChanges:")
        }
    }

    private val methodFlutterCallbackInvoke by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodFlutterVoipMinimize.method.parameterTypes.last())
            paramTypes(Any::class.java)
            returnType(Any::class.java)
        }
    }

    private val classMultiTalkViewModel by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings(
                "MicroMsg.MT.MultiTalkUIViewModel",
                "onCameraClick, cur state: ",
                "onMicClick, cur state: ",
            )
        }
    }

    private val fieldMultiTalkViewModel by dexField(allowFailure = true) {
        matcher {
            declaredClass(MultiTalkMainUI::class.java)
            type(classMultiTalkViewModel.clazz)
        }
    }

    private val classObservableState by dexClass(allowFailure = true) {
        searchPackages("androidx.lifecycle")
        matcher {
            modifiers(Modifier.PUBLIC or Modifier.ABSTRACT)
            methods {
                add {
                    name = "getValue"
                    paramCount = 0
                    returnType(Any::class.java)
                }
                add {
                    name = "hasObservers"
                    paramCount = 0
                    returnType = "boolean"
                }
            }
        }
    }

    private val methodMultiTalkMinimize by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(MultiTalkMainUI::class.java)
            paramCount = 0
            returnType = "void"
            usingEqStrings("onMiniMultiTalk")
        }
    }

    private val methodMultiTalkExit by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(MultiTalkMainUI::class.java)
            paramCount = 0
            returnType = "void"
            usingEqStrings("onExitMultiTalk")
        }
    }

    private val methodMultiTalkMic by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classMultiTalkViewModel.clazz)
            paramTypes("boolean")
            returnType = "void"
            usingEqStrings("onMicClick, cur state: ")
        }
    }

    private val methodMultiTalkCamera by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.MT.MultiTalkUIViewModel", "onCameraClick, cur state: ")
        }
    }

    private val fieldMultiTalkMicState by dexField(allowFailure = true) {
        matcher {
            declaredClass(classMultiTalkViewModel.clazz)
            type(classObservableState.clazz)
            addReadMethod {
                usingEqStrings("MicroMsg.MT.MultiTalkUIViewModel", "onMicClick, cur state: ")
            }
        }
    }

    private val fieldMultiTalkCameraState by dexField(allowFailure = true) {
        matcher {
            declaredClass(classMultiTalkViewModel.clazz)
            type(classObservableState.clazz)
            addReadMethod {
                usingEqStrings("MicroMsg.MT.MultiTalkUIViewModel", "onCameraClick, cur state: ")
            }
        }
    }

    private val methodObservableValue by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classObservableState.clazz)
            paramCount = 0
            returnType(Any::class.java)
        }
    }

    override fun onEnable() {
        methodVoipActivityProxyDealContentView.hookBefore {
            // 8.0.77 加固: args 取值做空安全
            WeLogger.d(TAG, "dealContentView: ${args?.getOrNull(0)?.javaClass}")
        }

        ActivityInfo::class.reflekt()
            .firstConstructor()
            .hookAfter {
                // 8.0.77 加固: as? + 空返
                val info = thisObject as? ActivityInfo ?: return@hookAfter
                if (info.name == VideoActivity::class.java.name) applyPipFlags(info)
            }

        Activity::class.reflekt()
            .firstMethod {
                name = "onPictureInPictureModeChanged"
                parameterCount = 2
            }
            .hookBefore {
                if (thisObject is VideoActivity) {
                    WeLogger.i(TAG, "VideoActivity picture-in-picture mode: ${args?.getOrNull(0)}")
                }
            }

        methodFlutterVoipAttachedToActivity.hookAfter {
            registerSingleSession(thisObject)
        }

        methodFlutterVoipReattachedToActivity.hookAfter {
            registerSingleSession(thisObject)
        }

        methodFlutterVoipMinimize.hookBefore {
            // 8.0.77 加固: as? + 空返; 取值与 Dart 回调 invoke 用 runCatching 兜底
            val activity = fieldFlutterVoipActivity.field.get(thisObject) as? VideoActivity
                ?: return@hookBefore
            sessions[activity]?.enterPip() ?: WeLogger.w(TAG, "no session for $activity, leaving wechat alone")
            runCatching {
                val callbackArg = args?.getOrNull(3) ?: return@runCatching
                methodFlutterCallbackInvoke.method.invoke(callbackArg, true)
            }.onFailure { WeLogger.e(TAG, "flutter minimize callback invoke failed", it) }
            try {
                // 仅当原方法返回 void 时才设置 result = null
                if (method is java.lang.reflect.Method) {
                    val returnType = (method as java.lang.reflect.Method).returnType
                    if (returnType == Void.TYPE) {
                        result = null
                    }
                }
            } catch (e: Throwable) {
                // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
            }
        }

        methodVoipMinimize.hookBefore {
            // 8.0.77 加固: firstOrNull + 空返, 找不到匹配 session 时静默放行
            val session = sessions.values.filterIsInstance<SingleSession>()
                .firstOrNull { it.manager === thisObject } ?: return@hookBefore
            session.enterPip()
            try {
                // 仅当原方法返回 boolean 时才设置 result = true
                if (method is java.lang.reflect.Method) {
                    val returnType = (method as java.lang.reflect.Method).returnType
                    if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
                        result = true
                    }
                }
            } catch (e: Throwable) {
                // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
            }
        }

        VideoActivity::class.reflekt().firstMethod {
            name = "onUserLeaveHint"
            parameterCount = 0
        }.hookBefore {
            // 8.0.77 加固: as? + 空返, 找不到 session 只记日志
            val activity = thisObject as? VideoActivity ?: return@hookBefore
            sessions[activity]?.enterPip() ?: WeLogger.w(TAG, "no session for $activity, leaving wechat alone")
        }
        VideoActivity::class.reflekt().firstMethod {
            name = "onDestroy"
            parameterCount = 0
        }.hookBefore {
            removeSession(thisObject as? VideoActivity ?: return@hookBefore)
        }

        MultiTalkMainUI::class.reflekt().firstMethod {
            name = "onCreate"
            parameterCount = 1
        }.hookAfter {
            // 8.0.77 加固: as? + 空返
            val activity = thisObject as? MultiTalkMainUI ?: return@hookAfter
            sessions[activity] = GroupSession(activity)
        }
        MultiTalkMainUI::class.reflekt().firstMethod {
            name = "onDestroy"
            parameterCount = 0
        }.hookBefore {
            removeSession(thisObject as? MultiTalkMainUI ?: return@hookBefore)
        }

        methodMultiTalkMinimize.hookBefore {
            // 8.0.77 加固: as? + 空返
            val activity = thisObject as? MultiTalkMainUI ?: return@hookBefore
            sessions[activity]?.enterPip() ?: WeLogger.w(TAG, "no session for $activity, leaving wechat alone")
            try {
                // 仅当原方法返回 void 时才设置 result = null
                if (method is java.lang.reflect.Method) {
                    val returnType = (method as java.lang.reflect.Method).returnType
                    if (returnType == Void.TYPE) {
                        result = null
                    }
                }
            } catch (e: Throwable) {
                // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
            }
        }

        Activity::class.reflekt().firstMethod { name = "onUserLeaveHint" }.hookBefore {
            val activity = thisObject
            if (activity is MultiTalkMainUI) {
                sessions[activity]?.enterPip() ?: WeLogger.w(TAG, "no session for $activity, leaving wechat alone")
            }
        }
    }

    override fun onDisable() {
        sessions.values.filter { it.pipActive }.forEach { closePipActivity(it.activity) }
        sessions.clear()
    }

    private fun registerSingleSession(plugin: Any) {
        // 8.0.77 加固: 字段可能已混淆/移除, as? + 空返, 异常不再抛进微信执行栈
        val activity = fieldFlutterVoipActivity.field.get(plugin) as? VideoActivity ?: run {
            WeLogger.w(TAG, "flutter voip plugin has no activity attached")
            return
        }
        val manager = fieldFlutterVoipManager.field.get(plugin) ?: run {
            WeLogger.w(TAG, "flutter voip plugin has no manager attached")
            return
        }
        WeLogger.d(TAG, "placing $activity into map")
        sessions[activity] = SingleSession(activity, manager)
    }

    private fun applyPipFlags(info: ActivityInfo) {
        // 8.0.77 加固: resizeMode 字段可能已混淆/改名, runCatching 兜底
        runCatching {
            info.flags = info.flags or FLAG_SUPPORTS_PICTURE_IN_PICTURE
            info.reflekt().firstField { name = "resizeMode" }.set(RESIZE_MODE_RESIZEABLE)
        }.onFailure { WeLogger.e(TAG, "failed to apply pip flags", it) }
    }

    private fun removeSession(activity: Activity) {
        if (sessions.remove(activity)?.pipActive == true) closePipActivity(activity)
    }

    private fun closePipActivity(context: Context) {
        // 8.0.77 加固: 启动/关闭行为 runCatching 兜底, 失败只记日志
        runCatching {
            context.startActivity(
                Intent {
                    component = ComponentName(PackageNames.WECHAT, PipVoipActivity::class.java.name)
                    action = PipVoipActivity.ACTION_CLOSE
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        }.onFailure { WeLogger.e(TAG, "failed to close pip activity", it) }
    }
}
