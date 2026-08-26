package dev.ujhhgtg.reflekt.utils

import kotlin.reflect.KCallable
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod

/**
 * 与 reflekt 库同名扩展：KFunction → java.lang.reflect.Method。
 * （本模块随合并代码连带使用 `::xxx.fastJavaMethod` 形式。）
 */
val KCallable<*>.fastJavaMethod: java.lang.reflect.Method?
    get() = (this as? KFunction<*>)?.javaMethod