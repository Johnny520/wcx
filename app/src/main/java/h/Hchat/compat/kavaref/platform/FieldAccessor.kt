package com.highcapable.kavaref.platform

import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Type

internal class FieldAccessor(override val member: Member) : MemberAccessor(member) {
    private val reflectedField = member as Field

    val isEnumConstant: Boolean get() = reflectedField.isEnumConstant
    val type: Class<*> get() = reflectedField.type
    val genericType: Type get() = reflectedField.genericType
}
