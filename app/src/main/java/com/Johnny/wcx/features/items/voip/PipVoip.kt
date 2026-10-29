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
import com.Johnny.wcx.utils.reflection.ClassLoaders
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

    // 8.0.77 适配: 微信插件类可能被 R8 混淆改名/移除, 直接类引用前必须做存在性检查,
    // 检查失败整段 Hook 跳过(无感知, 不带来功能损失), 消除历史上未守卫直接注册导致的随机闪退。
    private const val WE_CHAT_VIDEO_ACTIVITY = "com.tencent.mm.plugin.voip.ui.VideoActivity"
    private const val WE_CHAT_MULTI_TALK_MAIN_UI = "com.tencent.mm.plugin.multitalk.ui.MultiTalkMainUI"

    /** 8.0.80+ 多人通话的应用内悬浮窗容器（VoIPMP v2 / Flutter 实现）。 */
    private const val WE_CHAT_VOIPMP_FLOAT_CONTAINER =
        "com.tencent.mm.voipmp.v2.floatcard.subfloat.VoIPMPFloatSlotContainer"

    /**
     * 8.0.80+ 多人通话是否进行中（以「微信自己的应用内悬浮窗已挂载」为判据）。
     * 只用于决定是否接管 PiP；为 false 时所有新增 Hook 立即 return，等于完全不动。
     */
    @Volatile
    private var voipMpActive = false

    /**
     * 8.0.80+ 多人通话的麦克风状态（来自 OboeAudioRecorder.g(Z)V 的实参）。
     * 只用于 PiP 面板如实显示，不做控制——Java 侧没有可靠的「设置静音」入口。
     */
    @Volatile
    private var voipMpMicMuted = false

    private const val WE_CHAT_VOIPMP_RECORDER = "com.tencent.mm.voipmp.v2.audio.backend.OboeAudioRecorder"

    /**
     * Flutter VoIP 插件的 Native 实现实例（实现 com.tencent.pigeon.flutter_voip.VoIPPluginApi）。
     *
     * 8.0.80 的通话 UI 是 Flutter 写的，静音/挂断/最小化都通过这个插件的
     * setMuted(Z) / hangUp() / minimize(...) 进 Native。拿到实例就能反向控制。
     * 拿不到（类结构又变了）时保持 null —— 此时 PiP 面板自动退回「只显示状态」。
     */
    @Volatile
    private var voipPluginInstance: Any? = null

    private fun wechatClassExists(name: String): Boolean = try {
        Class.forName(name, false, ClassLoaders.HOST) != null
    } catch (e: Throwable) {
        // 提到 W 级（会落文件日志）：8.0.80 起 MultiTalk 被 Flutter 实现整体替换，
        // 类被物理删除，用户会感知到「多人通话不再进小窗」，需要能在日志里查到原因。
        WeLogger.w(TAG, "$name 不存在（微信已移除/重构），跳过相关 Hook：多人通话侧功能在该版本不可用（单聊视频通话不受影响）")
        false
    }

    private sealed class Session(val activity: Activity) {
        var pipActive = false

        abstract val micMuted: Boolean
        open val videoEnabled: Boolean = true

        /** 是否支持主动控制（静音/挂断）。false 时 PiP 走精简模式，只显示状态。 */
        open val canControl: Boolean = false

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

        fun enterPip(limited: Boolean = false) {
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
                        putExtra(PipVoipActivity.EXTRA_LIMITED, limited && !canControl)
                        putExtra(PipVoipActivity.EXTRA_CAN_CONTROL, canControl)
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

    /**
     * 8.0.80+ 多人通话会话（VoIPMP v2 / Flutter 实现）。
     *
     * 微信 8.0.80 删除了 MultiTalkMainUI / MultiTalkUIViewModel，静音、摄像头、挂断都搬进了
     * Flutter 侧；Java 侧目前只暴露 OboeAudioRecorder 的 native 静音（句柄 + 语义都不确定）。
     * 所以这里只承载「回跳通话页」：PiP 面板走精简模式，不提供会点了无效的控制项。
     * 后续若在 voipmp.v2 找到可靠控制入口，只需补这三个实现即可。
     */
    private class VoipMpSession(activity: Activity) : Session(activity) {
        override val micMuted: Boolean get() = voipMpMicMuted

        /** 拿不到插件实例时视为不可控，PiP 面板会退回只显示状态。 */
        override val canControl: Boolean get() = voipPluginInstance != null

        override fun hangUp() {
            val inst = voipPluginInstance ?: return
            runCatching {
                inst.javaClass.getMethod("hangUp").invoke(inst)
            }.onFailure { WeLogger.e(TAG, "VoIP hangUp 调用失败", it) }
        }

        override fun toggleMic() {
            val inst = voipPluginInstance ?: return
            runCatching {
                // 接口方法 setMuted(Z)：Native 侧实现最终落到 x83/m.L0(Z)
                inst.javaClass.getMethod("setMuted", Boolean::class.javaPrimitiveType)
                    .invoke(inst, !voipMpMicMuted)
            }.onFailure { WeLogger.e(TAG, "VoIP setMuted 调用失败", it) }
        }
    }

    private val sessions = WeakHashMap<Activity, Session>()

    /** Flutter VoIP 插件实现类：靠实现的接口定位，跨版本稳定（类名会被 R8 混淆）。 */
    private val classFlutterVoipPluginImpl by dexClass(allowFailure = true) {
        matcher {
            addInterface("com.tencent.pigeon.flutter_voip.VoIPPluginApi")
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
            usingEqStrings("MicroMsg.FlutterVoipPlugin", "voip is already minimized, ignore!", "minimize, permission denied")
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
            usingEqStrings("MicroMsg.FlutterVoipPlugin", "voip is already minimized, ignore!", "minimize, permission denied")
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
        // "onMicClick/onCameraClick, cur state: " 自 8.0.76 起已是孤儿串（字符串残留但无方法引用），
        // 原三串 AND 因此恒不命中 → 8.0.76 上整个 PiP 麦克风/摄像头链路解析失败。
        // 改用 TAG + 仍被引用的 mic 日志串定位 ViewModel：8.0.76 → com.tencent.mm.plugin.multitalk.ui.b0。
        // 8.0.79 起 multitalk UI 日志体系整体消失（仅剩 MultitalkUtils/SceneSetting），此处干净降级为 placeholder。
        matcher {
            usingEqStrings(
                "MicroMsg.MT.MultiTalkUIViewModel",
                "onMicClick, need notify iLink Engine",
            )
        }
    }

    private val fieldMultiTalkViewModel by dexField(allowFailure = true) {
        matcher {
            declaredClass(runCatching { MultiTalkMainUI::class.java }.getOrDefault(Any::class.java))
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
            declaredClass(runCatching { MultiTalkMainUI::class.java }.getOrDefault(Any::class.java))
            paramCount = 0
            returnType = "void"
            usingEqStrings("onMiniMultiTalk")
        }
    }

    private val methodMultiTalkExit by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(runCatching { MultiTalkMainUI::class.java }.getOrDefault(Any::class.java))
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
            usingEqStrings("onMicClick, need notify iLink Engine")
        }
    }

    // 注意：摄像头侧日志串 "onCameraClick, cur state: " 在 8.0.76 / 8.0.79 均已成孤儿串，
    // 无替代串可定位，故此处只能保留 allowFailure 做干净降级（该版本摄像头 PiP 按钮不生效）。
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
                usingEqStrings("MicroMsg.MT.MultiTalkUIViewModel", "onMicClick, need notify iLink Engine")
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
        // 8.0.77 适配: 所有微信侧 Hook 注册前必须做类存在/委托解析守卫。
        // 解析失败整段跳过——该段 Hook 用户无感知, 跳过不带来任何功能损失,
        // 消除历史上"解析失败仍直接 hookBefore/hookAfter"导致的随机闪退。
        val videoActivityExists = wechatClassExists(WE_CHAT_VIDEO_ACTIVITY)
        val multiTalkMainUiExists = wechatClassExists(WE_CHAT_MULTI_TALK_MAIN_UI)
        WeLogger.i(TAG, "8.0.77 适配: VideoActivity=$videoActivityExists MultiTalkMainUI=$multiTalkMainUiExists")

        if (videoActivityExists) {
            ActivityInfo::class.reflekt()
                .firstConstructor()
                .hookAfter {
                    // 8.0.77 加固: as? + 空返
                    val info = thisObject as? ActivityInfo ?: return@hookAfter
                    if (info.name == VideoActivity::class.java.name) applyPipFlags(info)
                }

            // 8.0.77 适配: 类存在才直接引用, 否则整段跳过
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
        }

        if (multiTalkMainUiExists) {
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
        }

        if (!methodFlutterVoipAttachedToActivity.isPlaceholder) {
            methodFlutterVoipAttachedToActivity.hookAfter {
                registerSingleSession(thisObject)
            }
        }

        if (!methodFlutterVoipReattachedToActivity.isPlaceholder) {
            methodFlutterVoipReattachedToActivity.hookAfter {
                registerSingleSession(thisObject)
            }
        }

        if (!methodFlutterVoipMinimize.isPlaceholder) {
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
        }

        if (!methodVoipMinimize.isPlaceholder) {
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
        }

        if (!methodMultiTalkMinimize.isPlaceholder) {
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
        }

        // framework 类必然存在, 无守卫需求; 收到多聊 UI 的 onUserLeaveHint 时同样触发画中画
        // 8.0.77 适配: MultiTalkMainUI 类缺失时先短路, 避免 is 检查触发 NoClassDefFoundError
        Activity::class.reflekt().firstMethod { name = "onUserLeaveHint" }.hookBefore {
            val activity = thisObject
            if (multiTalkMainUiExists && activity is MultiTalkMainUI) {
                sessions[activity]?.enterPip() ?: WeLogger.w(TAG, "no session for $activity, leaving wechat alone")
            }
            // 8.0.80+ 多人通话：仅在 VoIPMP 悬浮窗存活（= 正在通话）时接管，否则立即返回
            if (voipMpActive && activity is Activity && activity !is PipVoipActivity) {
                enterPipForVoipMp(activity)
            }
        }

        // ── 8.0.80+ 多人通话（VoIPMP v2 / Flutter 实现）────────────────────────────
        // 微信 8.0.80 删除了 MultiTalkMainUI / MultiTalkUIViewModel，旧 GroupSession 全部失效。
        // 按「hook 到就接管、hook 不到完全不动」接入：
        //   · 悬浮窗 VoIPMPFloatSlotContainer 挂载 = 正在多人通话（微信自己弹的应用内小窗）
        //   · 用户按 Home 时若处于该状态，启动精简 PiP（显示「通话中」，点窗口回通话）
        //   · 不提供静音/摄像头/挂断：Java 侧只有 Oboe native 静音，宁可少做也不做错
        runCatching {
            wechatClassOrNull(WE_CHAT_VOIPMP_FLOAT_CONTAINER)?.let { cls ->
                // reflekt() 是 KClass 的扩展，Java Class 需先 .kotlin
                runCatching {
                    cls.kotlin.reflekt().firstMethod {
                        name = "onAttachedToWindow"
                        parameterCount = 0
                    }.hookAfter {
                        voipMpActive = true
                        WeLogger.i(TAG, "VoIPMP 悬浮窗已挂载：多人通话 PiP 接管就绪（8.0.80+ 精简模式）")
                    }
                }.onFailure { WeLogger.w(TAG, "VoIPMP onAttachedToWindow hook 跳过", it) }
                runCatching {
                    cls.kotlin.reflekt().firstMethod {
                        name = "onDetachedFromWindow"
                        parameterCount = 0
                    }.hookAfter {
                        voipMpActive = false
                        WeLogger.i(TAG, "VoIPMP 悬浮窗已移除：退出 PiP 接管")
                    }
                }.onFailure { WeLogger.w(TAG, "VoIPMP onDetachedFromWindow hook 跳过", it) }

                // Flutter VoIP 插件实例（用于反向控制静音/挂断）
                runCatching {
                    if (!classFlutterVoipPluginImpl.isPlaceholder) {
                        classFlutterVoipPluginImpl.clazz.kotlin.reflekt()
                            .firstConstructor()
                            .hookAfter {
                                voipPluginInstance = thisObject
                                WeLogger.i(TAG, "Flutter VoIP 插件实例已捕获：8.0.80 PiP 控制面板可用")
                            }
                    }
                }.onFailure { WeLogger.w(TAG, "Flutter VoIP 插件实例捕获失败（Pip 面板将只显示状态）", it) }

                // 麦克风状态：g(Z)V 的参数即「是否静音」（日志 "SDK mic state notified: "）。
                // 只读同步，供 PiP 面板如实显示；不主动调用，避免误操作。
                runCatching {
                    wechatClassOrNull(WE_CHAT_VOIPMP_RECORDER)?.kotlin
                        ?.reflekt()
                        ?.firstMethod {
                            name = "g"
                            paramTypes(Boolean::class.javaPrimitiveType)
                        }
                        ?.hookAfter {
                            val muted = args?.firstOrNull() as? Boolean ?: return@hookAfter
                            voipMpMicMuted = muted
                            WeLogger.i(TAG, "VoIPMP 麦克风状态同步：muted=$muted")
                        }
                }.onFailure { WeLogger.w(TAG, "VoIPMP 麦克风状态 hook 跳过", it) }
            }
        }.onFailure { WeLogger.w(TAG, "VoIPMP 悬浮窗 hook 跳过（不影响其他功能）", it) }
    }

    private fun wechatClassOrNull(name: String): Class<*>? = try {
        Class.forName(name, false, ClassLoaders.HOST)
    } catch (e: Throwable) {
        null
    }

    private fun enterPipForVoipMp(activity: Activity) {
        runCatching {
            val session = sessions.getOrPut(activity) { VoipMpSession(activity) }
            session.enterPip(limited = true)
        }.onFailure { WeLogger.w(TAG, "enterPipForVoipMp 失败，保持微信原样", it) }
    }

    override fun onDisable() {
        sessions.values.filter { it.pipActive }.forEach { closePipActivity(it.activity) }
        sessions.clear()
    }

    private fun registerSingleSession(plugin: Any) {
        // 8.0.77 加固: 字段可能已混淆/移除, isPlaceholder + as? + 空返, 异常不再抛进微信执行栈
        if (fieldFlutterVoipActivity.isPlaceholder || fieldFlutterVoipManager.isPlaceholder) {
            WeLogger.w(TAG, "flutter voip fields unresolved, skipping session registration")
            return
        }
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
