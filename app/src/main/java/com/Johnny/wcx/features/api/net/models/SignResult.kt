package com.Johnny.wcx.features.api.net.models

import org.json.JSONObject

data class SignResult(
    val json: JSONObject,
    val nativeNetScene: Any? = null,
    val onSendSuccess: (() -> Unit)? = null
)
