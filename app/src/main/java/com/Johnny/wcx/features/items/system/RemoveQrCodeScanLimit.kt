package com.Johnny.wcx.features.items.system

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "移除二维码扫描限制", categories = ["系统与隐私"], description = "移除长按图片与相册选择的二维码扫描限制")
object RemoveQrCodeScanLimit : SwitchFeature(), IResolveDex {

    private enum class ScanScene(val source: Int, val a8KeyScene: Int) {
        CAMERA(0, 4), // 相机扫描
        ALBUM(1, 34), // 相册选择
        PICTURE_LONG_PRESS(4, 37) // 长按图片
    }

    private val methodQBarString by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.QBarStringHandler", "key_offline_scan_show_tips")
        }
    }

    override fun onEnable() {
        methodQBarString.hookBefore {
            val (sourceIndex, a8KeySceneIndex) = if (methodQBarString.method.parameterCount == 16) 3 to 4 else 2 to 3
            val source = args[sourceIndex] as Int
            val a8KeyScene = args[a8KeySceneIndex] as Int
            val matchedScene =
                ScanScene.entries.find { it.source == source && it.a8KeyScene == a8KeyScene }
            if (matchedScene == ScanScene.ALBUM || matchedScene == ScanScene.PICTURE_LONG_PRESS) {
                args[sourceIndex] = ScanScene.CAMERA.source
                args[a8KeySceneIndex] = ScanScene.CAMERA.a8KeyScene
            }
        }
    }
}
