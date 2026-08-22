package me.hd.wauxv.data.bean

import androidx.annotation.Keep
import com.Johnny.wcx.features.api.core.WeContactLabelApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        val json = buildJsonObject {
            put("id", origin.labelId)
            put("name", origin.labelName)
        }
        return json.toString()
    }
}
