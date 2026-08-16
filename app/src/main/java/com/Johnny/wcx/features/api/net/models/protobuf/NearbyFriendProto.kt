@file:OptIn(ExperimentalSerializationApi::class)

package com.Johnny.wcx.features.api.net.models.protobuf

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class NearbyFriendProto(
    @ProtoNumber(1) val username: String = "",
    @ProtoNumber(2) val nickname: String = "",
    @ProtoNumber(7) val sex: Int = 0,
)
