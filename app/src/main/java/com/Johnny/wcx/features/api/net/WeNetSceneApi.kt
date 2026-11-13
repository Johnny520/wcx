package com.Johnny.wcx.features.api.net

import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger

@Feature(name = "NetScene 服务", categories = ["API"], description = "提供 NetScene 发送能力")
object WeNetSceneApi : ApiFeature(), IResolveDex {

    /**
     * 把 netScene 交给微信的网络队列。
     *
     * @return false 表示没能入队（微信 NetSceneQueue 对「同类型任务」有 forbid 机制，
     *         正在等待/运行时会直接拒绝）。调用方必须检查返回值，否则会以为请求已发出、
     *         一直等到超时才判失败。
     */
    fun sendNetScene(netScene: Any): Boolean {
        return runCatching {
            val queue = classMmKernel.clazz.reflekt()
                .firstMethod {
                    returnType = methodAddNetSceneToQueue.method.declaringClass
                }.invokeStatic() ?: return false
            val ret = methodAddNetSceneToQueue.method.invoke(queue, netScene, 0)
            // 方法是 boolean 时按返回值判断，非法/void 时按「已入队」处理
            ret as? Boolean ?: true
        }.getOrElse {
            WeLogger.e(TAG, "sendNetScene 失败：${netScene.javaClass.name}", it)
            false
        }
    }

    private const val TAG = "WCX:NetSceneApi"

    val classMmKernel by dexClass {
        matcher {
            usingEqStrings("MicroMsg.MMKernel", "Kernel not null, has initialized.")
        }
    }

    val methodAddNetSceneToQueue by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.NetSceneQueue", "forbid in waiting: type=", "forbid in running: type=")
        }
    }
}
