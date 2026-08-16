@file:OptIn(ExperimentalSerializationApi::class)

package com.Johnny.wcx.features.api.net.models.protobuf

import com.Johnny.wcx.features.api.net.models.protobuf.SnsCommentActionProto.Companion.decode
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * The `curActionBuf` BLOB stored in `SnsComment` — WeChat class `r45.k76`.
 *
 * Field 1 ([content]) is the plain-text body of text comments. For sticker/emoji
 * comments field 1 is absent or empty; the sticker data lives in the opaque
 * sub-messages at fields 14 and 16.
 *
 * Sub-message fields (14, 16) are held as raw [ByteArray]; repeated sub-message
 * fields (17, 25) as [List]<[ByteArray]>. Both wire-encode with wire type 2
 * (length-delimited), which is identical to the embedded-message wire type, so
 * bytes survive a [decode] → [copy] → [encode] round-trip without loss.
 *
 * Re-encode with [encode] (which uses [WeProto.encodeWithDefaults]) so that
 * zero-valued integer fields are written explicitly, matching WeChat's own encoder.
 */
@Serializable
data class SnsCommentActionProto(
    /** Display text for text comments; null/absent for sticker comments. */
    @ProtoNumber(1) val content: String? = null,
    @ProtoNumber(2) val field2: String? = null,
    @ProtoNumber(3) val field3: String? = null,
    @ProtoNumber(4) val field4: String? = null,
    @ProtoNumber(5) val field5: Int = 0,
    @ProtoNumber(6) val field6: Int = 0,
    @ProtoNumber(7) val field7: Int = 0,
    @ProtoNumber(8) val field8: String? = null,
    @ProtoNumber(9) val field9: Int = 0,
    @ProtoNumber(10) val field10: Int = 0,
    @ProtoNumber(11) val field11: Int = 0,
    @ProtoNumber(12) val field12: Long = 0,
    @ProtoNumber(13) val field13: Long = 0,
    /** cu5 sub-message — sticker / emoji metadata. Preserved as opaque bytes. */
    @ProtoNumber(14) val field14: ByteArray? = null,
    @ProtoNumber(15) val field15: Int = 0,
    /** mo5 sub-message. Preserved as opaque bytes. */
    @ProtoNumber(16) val field16: ByteArray? = null,
    /** Repeated l86 sub-messages. Preserved as opaque bytes. */
    @ProtoNumber(17) val field17: List<ByteArray> = emptyList(),
    @ProtoNumber(18) val field18: Int = 0,
    @ProtoNumber(22) val field22: Int = 0,
    @ProtoNumber(23) val field23: Int = 0,
    @ProtoNumber(24) val field24: Int = 0,
    /** Repeated d86 sub-messages. Preserved as opaque bytes. */
    @ProtoNumber(25) val field25: List<ByteArray> = emptyList(),
) {
    companion object {
        fun decode(bytes: ByteArray): SnsCommentActionProto = WeProto.decode(bytes)
    }

    /**
     * Serializes using [WeProto.encodeWithDefaults] so that zero-valued integer
     * fields are written explicitly, matching WeChat's encoder behaviour.
     */
    fun encode(): ByteArray = WeProto.encodeWithDefaults(this)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SnsCommentActionProto

        if (field5 != other.field5) return false
        if (field6 != other.field6) return false
        if (field7 != other.field7) return false
        if (field9 != other.field9) return false
        if (field10 != other.field10) return false
        if (field11 != other.field11) return false
        if (field12 != other.field12) return false
        if (field13 != other.field13) return false
        if (field15 != other.field15) return false
        if (field18 != other.field18) return false
        if (field22 != other.field22) return false
        if (field23 != other.field23) return false
        if (field24 != other.field24) return false
        if (content != other.content) return false
        if (field2 != other.field2) return false
        if (field3 != other.field3) return false
        if (field4 != other.field4) return false
        if (field8 != other.field8) return false
        if (!field14.contentEquals(other.field14)) return false
        if (!field16.contentEquals(other.field16)) return false
        if (field17 != other.field17) return false
        if (field25 != other.field25) return false

        return true
    }

    override fun hashCode(): Int {
        var result = field5
        result = 31 * result + field6
        result = 31 * result + field7
        result = 31 * result + field9
        result = 31 * result + field10
        result = 31 * result + field11
        result = 31 * result + field12.hashCode()
        result = 31 * result + field13.hashCode()
        result = 31 * result + field15
        result = 31 * result + field18
        result = 31 * result + field22
        result = 31 * result + field23
        result = 31 * result + field24
        result = 31 * result + (content?.hashCode() ?: 0)
        result = 31 * result + (field2?.hashCode() ?: 0)
        result = 31 * result + (field3?.hashCode() ?: 0)
        result = 31 * result + (field4?.hashCode() ?: 0)
        result = 31 * result + (field8?.hashCode() ?: 0)
        result = 31 * result + (field14?.contentHashCode() ?: 0)
        result = 31 * result + (field16?.contentHashCode() ?: 0)
        result = 31 * result + field17.hashCode()
        result = 31 * result + field25.hashCode()
        return result
    }
}
