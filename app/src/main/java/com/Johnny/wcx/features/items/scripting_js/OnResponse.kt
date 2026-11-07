package com.Johnny.wcx.features.items.scripting_js

import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "触发器：收到响应 (JS)", categories = ["脚本 (JS)"], description = "收到响应时是否执行 onResponse()")
object OnResponse : SwitchFeature()
