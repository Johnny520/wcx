package com.Johnny.wcx.features.items.profile

import android.graphics.Bitmap
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.reflection.int
import java.io.OutputStream

@Feature(name = "上传透明头像", categories = ["个人资料"], description = "头像上传时使用 PNG 格式保持透明")
object UploadTransparentAvatars : SwitchFeature(), IResolveDex {

    private val TRIGGER_PATTERNS = listOf(
        "com.tencent.mm.modelavatar",
        "PhotoCropActivity"
    )

    private val classMediaTailor by dexClass {
        matcher {
            usingEqStrings("Rect width or height contains zero. contentRect: ")
        }
    }

    override fun onEnable() {
        Bitmap::class.reflekt().firstMethod {
            name = "compress"
            parameters(Bitmap.CompressFormat::class, int, OutputStream::class)
        }.hookBefore {
            val stack = captureStackTrace()

            val inAvatarContext = TRIGGER_PATTERNS.any { pattern ->
                stack.contains(pattern)
            }

            val inUploadContext = classMediaTailor.clazz.name.let { stack.contains(it) }

            if (inAvatarContext || inUploadContext) {
                args[0] = Bitmap.CompressFormat.PNG
            }
        }
    }

    private fun captureStackTrace(): String {
        val frames = Thread.currentThread().stackTrace
        return frames.joinToString("\n") { frame ->
            "${frame.className}.${frame.methodName}() (${frame.fileName}:${frame.lineNumber})"
        }
    }
}
