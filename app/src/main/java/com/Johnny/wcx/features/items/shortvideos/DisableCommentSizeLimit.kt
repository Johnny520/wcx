package com.Johnny.wcx.features.items.shortvideos

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "禁用评论长度限制", categories = ["视频号"], description = "禁用视频号发送评论的字数行数限制 (不保证有效, 云端可能有二次限制)")
object DisableCommentSizeLimit : SwitchFeature() {

    override fun onEnable() {
        "com.tencent.mm.plugin.finder.view.FinderCommentFooter".toClass()
            .reflekt().apply {
                firstMethod { name = "getCommentTextLimit" }
                    .hookBefore {
                        try {
                            // 仅当原方法返回 int 时才设置 result = 9999，避免对非 int 方法（如 getInstance）造成 ClassCastException
                            if (method is java.lang.reflect.Method) {
                                val returnType = (method as java.lang.reflect.Method).returnType
                                if (returnType == Int::class.javaPrimitiveType || returnType == Integer::class.java) {
                                    result = 9999
                                }
                            }
                        } catch (e: Throwable) {
                            // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
                        }
                    }

                runCatching {
                    firstMethod { name = "getCommentTextLimitStart" }
                        .hookBefore {
                            try {
                                // 仅当原方法返回 int 时才设置 result = 9999，避免对非 int 方法（如 getInstance）造成 ClassCastException
                                if (method is java.lang.reflect.Method) {
                                    val returnType = (method as java.lang.reflect.Method).returnType
                                    if (returnType == Int::class.javaPrimitiveType || returnType == Integer::class.java) {
                                        result = 9999
                                    }
                                }
                            } catch (e: Throwable) {
                                // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
                            }
                        }
                }

                firstMethod { name = "getCommentTextLineLimit" }
                    .hookBefore {
                        try {
                            // 仅当原方法返回 int 时才设置 result = 9999，避免对非 int 方法（如 getInstance）造成 ClassCastException
                            if (method is java.lang.reflect.Method) {
                                val returnType = (method as java.lang.reflect.Method).returnType
                                if (returnType == Int::class.javaPrimitiveType || returnType == Integer::class.java) {
                                    result = 9999
                                }
                            }
                        } catch (e: Throwable) {
                            // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
                        }
                    }
            }
    }
}
