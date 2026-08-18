package com.Johnny.wcx.features.items.entertain

import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import java.util.WeakHashMap

@Feature(name = "图片旋转", categories = ["娱乐"], description = "让微信中的图片持续旋转")
object ImageRotation : SwitchFeature() {

    private data class RotationState(
        val originalRotation: Float,
        val animator: ObjectAnimator,
    )

    private val viewStateMap = WeakHashMap<View, RotationState>()

    override fun onEnable() {
        ImageView::class.reflekt()
            .firstConstructor { parameterCount = 4 }.hookAfter {
                applyRotation(thisObject as View)
            }

        "com.tencent.mm.ui.widget.QImageView".toClass().reflekt()
            .firstConstructor().hookAfter {
                applyRotation(thisObject as View)
            }
    }

    override fun onDisable() {
        viewStateMap.forEach { (view, state) ->
            state.animator.cancel()
            view.rotation = state.originalRotation
        }
        viewStateMap.clear()
    }

    private fun applyRotation(view: View) {
        view.post {
            if (!isActive || viewStateMap.containsKey(view)) return@post

            val animator = ObjectAnimator.ofFloat(view, "rotation", 0f, 360f).apply {
                duration = 1000
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
            viewStateMap[view] = RotationState(view.rotation, animator)
            animator.start()
        }
    }
}
