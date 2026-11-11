package com.highcapable.kavaref.platform

import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Type

internal class MethodAccessor(override val member: Member) : MemberAccessor(member) {
    private val method = member as Method

    val returnType: Class<*> get() = method.returnType
    val genericReturnType: Type get() = method.genericReturnType
    val isBridge: Boolean get() = method.isBridge
    val isDefault: Boolean get() = method.isDefault
    val defaultValue: Any? get() = method.defaultValue
}
