package me.hd.wauxv.data.bean

import androidx.annotation.Keep
import com.alibaba.fastjson2.JSONObject
import com.Johnny.wcx.features.api.core.WeContactLabelApi

@Suppress("unused")
@Keep
class ContactLabelBean(
    @JvmField val origin: WeContactLabelApi.ContactLabel
) {

    fun getLabelName() = origin.labelName
    fun getDisplayName() = origin.labelName
    fun getName() = origin.labelName
    fun getLabelId() = origin.labelId
    fun getLabelID() = origin.labelId
    fun getId() = origin.labelId
    fun getOrigin(): Any = error("not implemented")

    override fun toString(): String {
        val json = JSONObject()
        json["id"] = origin.labelId
        json["name"] = origin.labelName
        return json.toString()
    }
}
