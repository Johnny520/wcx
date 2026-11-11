package com.highcapable.kavaref.platform

import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable

internal class ExecutableAccessor(override val member: Member) : MemberAccessor(member) {
    private val executable = member as Executable

    val parameterTypes: Array<Class<*>> get() = executable.parameterTypes
    val parameterCount: Int get() = executable.parameterCount
    val typeParameters: Array<out TypeVariable<*>> get() = executable.typeParameters
    val exceptionTypes: Array<Class<*>> get() = executable.exceptionTypes
    val genericExceptionTypes: Array<Type> get() = executable.genericExceptionTypes
    val genericParameterTypes: Array<Type> get() = executable.genericParameterTypes
    val isVarArgs: Boolean get() = executable.isVarArgs
    val parameterAnnotations: Array<Array<Annotation>> get() = executable.parameterAnnotations
    val annotatedReturnType: AnnotatedElement get() = EmptyAnnotatedElement
    val annotatedReceiverType: AnnotatedElement get() = EmptyAnnotatedElement
    val annotatedParameterTypes: Array<AnnotatedElement> get() = emptyArray()
    val annotatedExceptionTypes: Array<AnnotatedElement> get() = emptyArray()

    private object EmptyAnnotatedElement : AnnotatedElement {
        override fun <T : Annotation?> getAnnotation(annotationClass: Class<T>?): T? = null
        override fun getAnnotations(): Array<Annotation> = emptyArray()
        override fun getDeclaredAnnotations(): Array<Annotation> = emptyArray()
    }
}
