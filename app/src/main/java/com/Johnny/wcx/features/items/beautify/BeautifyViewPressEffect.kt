package com.Johnny.wcx.features.items.beautify

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import com.Johnny.reflekt.reflekt
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "美化组件按下效果", categories = ["界面美化"], description = "将 View 的背景替换为 RippleDrawable (没写完)")
object BeautifyViewPressEffect : SwitchFeature() {

    override fun onEnable() {
        View::class.reflekt()
            .firstMethod {
                name = "setBackgroundDrawable"
                parameters(Drawable::class)
            }
            .hookBefore {
                val view = thisObject as View
                val original = args[0] as? Drawable?
                if (view.javaClass.name.startsWith("android.")) return@hookBefore
                if (original != null && original is RippleDrawable) return@hookBefore

                if (view.isClickable) {
                    val rippleColor = ColorStateList.valueOf(0x1F000000)
                    val newRipple = RippleDrawable(rippleColor, original, null)
                    args[0] = newRipple
                }
            }
    }
}
