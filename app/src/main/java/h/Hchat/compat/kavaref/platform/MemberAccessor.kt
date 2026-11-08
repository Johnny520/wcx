package com.highcapable.kavaref.platform

import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method

internal open class MemberAccessor(open val member: Member) {
    val name: String get() = member.name
    val modifiers: Int get() = member.modifiers
    val isSynthetic: Boolean get() = member.isSynthetic
    val annotations: Array<Annotation>
        get() = (member as? AnnotatedElement)?.declaredAnnotations ?: emptyArray()
    val genericString: String
        get() {
            val current = member
            return when (current) {
                is Method -> current.toGenericString()
                is Constructor<*> -> current.toGenericString()
                is Field -> current.toGenericString()
                else -> current.toString()
            }
        }
}
