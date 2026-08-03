package com.Johnny.wcx.features.items.system

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import java.lang.reflect.Field

@Feature(name = "阻止微信清理模块数据", categories = ["系统与隐私"], description = "阻止微信「设置 → 存储空间 → 清理」删除模块数据")
object PreventModuleDataDeletion : SwitchFeature(), IResolveDex {

    private val methodNativeFileSystemEntryDelete by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("VFS.NativeFileSystem", "Base directory exists but is not a directory, delete and proceed.Base path: ")
            }

            paramTypes(String::class.java)
            returnType = "boolean"

            invokeMethods {
                add {
                    declaredClass = "java.io.File"
                    name = "delete"
                }
            }
        }
    }
    private lateinit var basePathField: Field

    override fun onEnable() {
        methodNativeFileSystemEntryDelete.hookBefore {
            try {
                val relPath = args[0] as String
                if (!::basePathField.isInitialized) {
                    basePathField = thisObject.reflekt()
                        .firstField {
                            type = String::class
                            modifiers(Modifiers.FINAL)
                        }.self
                }
                val basePath = basePathField.get(thisObject) as String

                val path = "$basePath/$relPath"
                if (path.contains(BuildConfig.TAG) || path.contains("Layout Inspect")) {
                    // 仅当原方法返回 boolean 时才设置 result = true，避免对非 boolean 方法（如 getInstance）造成 ClassCastException
                    if (method is java.lang.reflect.Method) {
                        val returnType = (method as java.lang.reflect.Method).returnType
                        if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
                            result = true
                        }
                    }
                }
            } catch (e: Throwable) {
                // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
            }
        }
    }
}
