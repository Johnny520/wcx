package com.Johnny.wcx.features.api.net

import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.features.api.net.abc.IWePacketInterceptor
import com.Johnny.wcx.utils.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

object WePacketManager {

    private val listeners = CopyOnWriteArrayList<IWePacketInterceptor>()

    fun addInterceptor(interceptor: IWePacketInterceptor) = listeners.addIfAbsent(interceptor)

    fun removeInterceptor(interceptor: IWePacketInterceptor) = listeners.remove(interceptor)

    internal fun handleRequestTamper(uri: String, cgiId: Int, reqBytes: ByteArray): ByteArray? {
        if (Preferences.verboseLog) {
            val data = WeProtoData.fromBytes(reqBytes)
            WeLogger.logChunkedI(
                "WePacketInterceptor.Request",
                "Request: $uri, CGI=$cgiId, LEN=${reqBytes.size}, Data=${data.toJsonObject()}, Stack=${WeLogger.currentStackTrace}"
            )
        }

        for (listener in listeners) {
            val tampered = listener.onRequest(uri, cgiId, reqBytes)
            if (tampered != null) return tampered
        }
        return null
    }

    internal fun handleResponseTamper(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        if (Preferences.verboseLog) {
            val data = WeProtoData.fromBytes(respBytes)
            WeLogger.logChunkedI(
                "WePacketInterceptor.Response",
                "Response: $uri, CGI=$cgiId, LEN=${respBytes.size}, Data=${data.toJsonObject()}"
            )
        }
        for (listener in listeners) {
            val tampered = listener.onResponse(uri, cgiId, respBytes)
            if (tampered != null) return tampered
        }
        return null
    }
}
