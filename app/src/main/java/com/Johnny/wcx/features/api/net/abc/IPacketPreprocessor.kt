package com.Johnny.wcx.features.api.net.abc

import com.Johnny.wcx.features.api.net.models.PreprocessResult
import org.json.JSONObject

interface IPacketPreprocessor {
    fun matchesJson(cgiId: Int): Boolean
    fun preprocessJson(cl: ClassLoader, json: JSONObject): PreprocessResult

    fun matchesProto(value: Any): Boolean = false
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> preprocessProto(value: T): T = value
}
