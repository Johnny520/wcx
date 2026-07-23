package com.Johnny.wcx.features.items.chat

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexConstructor
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.android.showToast

@Feature(name = "拦截异常大小贴纸表情", categories = ["聊天"], description = "拦截某些异常大小表情导致的闪退现象")
object BlockAbnormalSizeStickers : SwitchFeature(), IResolveDex {

    override fun onEnable() {
        ctorMmWxgfDrawable.hookBefore {
            val inputBytes = args[0] as? ByteArray? ?: return@hookBefore
            val magicBytes = "wxgf".toByteArray()

            val isWxgf = inputBytes.size >= magicBytes.size &&
                    magicBytes.indices.all { i -> inputBytes[i] == magicBytes[i] }

            if (isWxgf && inputBytes.size >= 11) {
                // Read 16-bit Big-Endian integers for width (bytes 7-8) and height (bytes 9-10)
                val width = inputBytes[7].toInt() and 0xFF shl 8 or (inputBytes[8].toInt() and 0xFF)
                val height = inputBytes[9].toInt() and 0xFF shl 8 or (inputBytes[10].toInt() and 0xFF)

                // If raw pixel data size (width * height * 4 bytes per pixel) exceeds 50MB
                if (width.toLong() * height.toLong() * 4L > 52_428_800L) {
                    showToast("检测到异常大小贴纸表情, 已拦截")

                    // Patch the dimensions down to a safe 32x32 stub to prevent OOM/Exploits
                    inputBytes[7] = 0.toByte()
                    inputBytes[8] = 32.toByte()
                    inputBytes[9] = 0.toByte()
                    inputBytes[10] = 32.toByte()
                }
            }
        }
    }

    private val ctorMmWxgfDrawable by dexConstructor {
        searchPackages("com.tencent.mm.plugin.gif")
        matcher {
            usingEqStrings("MicroMsg.GIF.MMWXGFDrawable", "Cpan WXGF get option failed. result:%d")
        }
    }
}
