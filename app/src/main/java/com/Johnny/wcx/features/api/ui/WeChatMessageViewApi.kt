package com.Johnny.wcx.features.api.ui

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import com.Johnny.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

@Feature(name = "消息 View 创建监听服务", categories = ["API"], description = "提供消息 View 创建监听能力")
object WeChatMessageViewApi : ApiFeature(), IResolveDex {

    fun interface ICreateViewListener {
        fun onCreateView(
            param: XC_MethodHook.MethodHookParam, view: View
        )
    }

    private val listeners = CopyOnWriteArrayList<ICreateViewListener>()

    fun addListener(listener: ICreateViewListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: ICreateViewListener) {
        val removed = listeners.remove(listener)
        WeLogger.i(
            TAG,
            "listener remove ${if (removed) "succeeded" else "failed"}, current listener count: ${listeners.size}"
        )
    }

    private const val TAG = "WeChatMessageViewApi"

    private val methodChatItemOnBindView by dexMethod {
        matcher {
            usingStrings(
                "MicroMsg.MvvmChattingItem",
                "[onBindView]"
            )
        }
    }

    override fun onEnable() {
        methodChatItemOnBindView.hookAfter {
            val holder = args[0]
            val view = holder.reflekt()
                .firstField {
                    type = View::class
                    superclass()
                }
                .get()!! as View

            for (listener in listeners) {
                try {
                    listener.onCreateView(this, view)
                } catch (ex: Exception) {
                    WeLogger.e(TAG, "listener ${listener.javaClass.name} threw", ex)
                }
            }
        }
    }

    fun getChattingContextFromParam(param: XC_MethodHook.MethodHookParam): Any {
        return param.thisObject.reflekt()
            .firstField { type = WeMessageApi.classChattingContext.clazz }
            .get()!!
    }

    fun getMsgInfoFromParam(param: XC_MethodHook.MethodHookParam): MessageInfo {
        val chattingDataAdapter = param.thisObject.reflekt()
            .firstField { type = WeMessageApi.classChattingDataAdapter.clazz }
            .get()!!
        val msgId = param.args[2] as Int
        val msgInfo = chattingDataAdapter.reflekt()
            .firstMethod { name = "getItem" }
            .invoke(msgId)!!
        return MessageInfo(msgInfo)
    }
}
