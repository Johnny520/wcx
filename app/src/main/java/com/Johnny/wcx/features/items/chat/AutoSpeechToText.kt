package com.Johnny.wcx.features.items.chat

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.WeServiceApi
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.collections.LruCache
import java.lang.reflect.InvocationTargetException

@Feature(name = "自动语音转文字", categories = ["聊天"], description = "自动将语音消息转为文字")
object AutoSpeechToText : SwitchFeature(),
    WeChatMessageViewApi.ICreateViewListener {

    private val processedMessages = LruCache<Long, Boolean>()

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    private const val TAG = "AutoSpeechToText"

    override fun onCreateView(
        param: XC_MethodHook.MethodHookParam,
        view: View
    ) {
        // 这里全是微信内部结构（apiManager 字段、transform 方法签名、MsgInfo 实体），
        // 任一漂移都会抛异常。原来没有兜底：一条消息失败就等于整个功能「没反应」，
        // 日志里也看不到。现在失败要留痕，自检页「最近错误」也能直接看到。
        runCatching { handleCreateView(param, view) }
            .onFailure { com.Johnny.wcx.utils.WeLogger.e(TAG, "自动语音转文字失败（本条消息跳过）：${it.message}", it) }
    }

    private fun handleCreateView(
        param: XC_MethodHook.MethodHookParam,
        view: View
    ) {
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (msgInfo.typeCode != MessageType.VOICE.code) return

        val id = msgInfo.id
        if (processedMessages[id] == true) {
            return
        }

        val chattingContext = WeChatMessageViewApi.getChattingContextFromParam(param)
        val apiMan = chattingContext.reflekt()
            .firstField {
                type = WeServiceApi.apiManagerClass
            }
            .get()!!
        val api = WeServiceApi.getApiByClass(apiMan, WeMessageApi.classTransformChattingComponent.clazz)
        val chatViewItem = api.reflekt()
            .firstMethod {
                parameters(Long::class)
                returnType { clazz ->
                    clazz.name.startsWith("com.tencent.mm.ui.chatting.viewitems")
                }
            }
            .invoke(id)

        if (chatViewItem.toString() != "NoTransform") return

        processedMessages[id] = true

        // Clear the unplayed red dot the same way WeChat does when the voice is listened to,
        // since we consume the message via transform instead of playback.
        runCatching { WeMessageApi.markVoicePlayed(msgInfo) }

        val alreadyTransformed = runCatching {
            WeMessageApi.methodGetIsTransformed.method.invoke(msgInfo.instance) as Boolean
        }.getOrDefault(false)
        if (alreadyTransformed) return

        // 触发转文字的方法签名跨版本会变：
        //   8.0.76 / 8.0.79：(MsgInfo, boolean, int, int)
        //   8.0.80：        (MsgInfo, boolean, int, int, int)   ← 末尾多了一个 int
        // 原实现把参数写死成 4 个，8.0.80 上匹配不到方法（reflekt 直接抛），
        // 于是「自动语音转文字」整个不生效。改成行为匹配：首参 MsgInfo + 第二参 boolean +
        // 其余全是 int + 返回 void，参数个数交给运行时决定，多出来的 int 按 0 补齐。
        val transformMethod = findVoiceTransformMethod(api)
        if (transformMethod == null) {
            com.Johnny.wcx.utils.WeLogger.w(TAG, "未找到语音转文字方法：${api.javaClass.name}")
            return
        }
        val callArgs = arrayOfNulls<Any?>(transformMethod.parameterCount)
        callArgs[0] = msgInfo.instance
        // 第二参 true：直接展示转文字结果（微信自己点「转文字」时也是 true）
        callArgs[1] = true
        // 第三参 -1：不限长度；之后的参数微信自己调用时都是 0
        callArgs[2] = -1
        for (i in 3 until transformMethod.parameterCount) callArgs[i] = 0
        try {
            transformMethod.invoke(api, *callArgs)
        } catch (_: InvocationTargetException) {
            // WeChat throws `java.lang.NullPointerException: getImgPath(...) must not be null`,
            // but that's not what we should care about and doesn't affect functionality
        }
    }
    /**
     * 按行为特征找「触发语音转文字」的方法，不写死参数个数。
     *
     * 8.0.80 是 `(MsgInfo, boolean, int, int, int) -> void`，8.0.79 及更早是 4 个参数。
     * 直接遍历反射拿到的 public 方法，避免 reflekt 的精确参数匹配在版本升级后直接抛异常。
     */
    private fun findVoiceTransformMethod(api: Any): java.lang.reflect.Method? = runCatching {
        val msgClass = WeMessageApi.classMsgInfo.clazz
        api.javaClass.methods.firstOrNull { method ->
            method.returnType == Void.TYPE &&
                method.parameterCount in 4..6 &&
                method.parameterTypes[0] == msgClass &&
                method.parameterTypes[1] == java.lang.Boolean.TYPE &&
                method.parameterTypes.drop(2).all { it == Integer.TYPE }
        }
    }.getOrNull()
}
