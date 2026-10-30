package com.Johnny.wcx.features.items.scripting_js

import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "触发器：发起请求 (JS)", categories = ["脚本 (JS)"], description = "发起请求时是否执行 onRequest()")
object OnRequest : SwitchFeature()
