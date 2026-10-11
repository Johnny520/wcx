package h.Hchat.hooks.items.protobuf

// TODO(migration stub): 数据包监听/发送运行时（原型依赖 ProtobufPacketHook 与微信内部实现，未迁移）。
// 保留被调用成员签名，功能暂为空实现；后续应改接 WCX 的 WePacketManager。
object ProtobufPacketRuntime {
    const val DIRECTION_REQUEST = "request"
    const val DIRECTION_RESPONSE = "response"

    fun interface Listener {
        fun onPacket(packet: Packet)
    }

    fun interface Callback {
        fun onResult(success: Boolean, message: String?)
    }

    class Packet(
        val direction: String = "",
        val uri: String = "",
        val cgiId: Int = 0,
        val data: ByteArray = ByteArray(0),
        val timestamp: Long = 0L,
    )

    @JvmStatic
    fun send(uri: String, cgiId: Int, json: String?, callback: Callback?): Boolean = false

    @JvmStatic
    fun send(uri: String, cgiId: Int, funcId: Int, routeId: Int, json: String?, callback: Callback?): Boolean = false

    @JvmStatic
    fun registerListener(listener: Listener?): Boolean = false

    @JvmStatic
    fun unregisterListener(listener: Listener?): Boolean = false
}
