package h.Hchat.hooks.items.protobuf

object ProtobufPacketSettings {
    const val PREFS_NAME = "Hchat_protobuf_packet_config"

    const val KEY_ENABLE = "protobuf_packet_enable"
    const val KEY_CAPTURE_REQUEST = "protobuf_packet_capture_request"
    const val KEY_CAPTURE_RESPONSE = "protobuf_packet_capture_response"
    const val KEY_BLOCK_TYPES = "protobuf_packet_block_types"
    const val KEY_SEND_URI = "protobuf_packet_send_uri"
    const val KEY_SEND_TYPE = "protobuf_packet_send_type"
    const val KEY_SEND_FUNC_ID = "protobuf_packet_send_func_id"
    const val KEY_SEND_ROUTE_ID = "protobuf_packet_send_route_id"
    const val KEY_SEND_PAYLOAD = "protobuf_packet_send_payload"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_CAPTURE_REQUEST = true
    const val DEFAULT_CAPTURE_RESPONSE = true
    const val DEFAULT_BLOCK_TYPES = "25694,14186,5171,11421,389,3565,138,1948,211,29710,996,4326,4687,4768,28920,379,8674"
    const val DEFAULT_SEND_URI = "/cgi-bin/micromsg-bin/oplog"
    const val DEFAULT_SEND_TYPE = "681"
    const val DEFAULT_SEND_FUNC_ID = "0"
    const val DEFAULT_SEND_ROUTE_ID = "0"
    const val DEFAULT_SEND_PAYLOAD = ""
}
