package h.Hchat.hooks.api.net

import h.Hchat.dexkit.DexFinder

/**
 * 微信网络请求 API。
 *
 * 只负责对外暴露网络队列状态和请求发送能力，具体 hook 逻辑在
 * [WeChatNetworkDispatcher] 中维护。
 */
class WeChatNetworkApi(
    private val dispatcher: WeChatNetworkDispatcher?,
    private val logger: Logger?
) {
    fun interface Logger {
        fun log(message: String)
    }

    val isAvailable: Boolean
        get() = dispatcher != null

    fun installNetworkHook(dexFinder: DexFinder?): Boolean {
        if (dispatcher == null || dexFinder == null) return false
        val hasTarget = dexFinder.netQueueClass != null || dexFinder.netQueueCandidateClasses.isNotEmpty()
        if (!hasTarget) return false
        dispatcher.hookNetworkQueue(dexFinder.netQueueClass, dexFinder.netQueueCandidateClasses)
        return true
    }

    fun sendRequest(request: Any?): Boolean {
        if (dispatcher == null) {
            log("发送请求失败: dispatcher为空")
            return false
        }
        return dispatcher.send(request)
    }

    fun isReady(): Boolean = dispatcher != null && dispatcher.isReady

    fun hasDispatcherInstance(): Boolean = dispatcher != null && dispatcher.hasDispatcherInstance()

    fun hasDispatcherMethod(): Boolean = dispatcher != null && dispatcher.hasDispatcherMethod()

    private fun log(message: String) {
        logger?.log("[WeChatNetworkApi] $message")
    }
}
