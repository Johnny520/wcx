package com.Johnny.wcx.features.items.beautify

import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.utils.theme.SeedResolver
import com.Johnny.wcx.ui.utils.theme.ThemeSettings
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.isDarkMode

/**
 * Recolors the parts of WeChat that hardcode the brand green ([DEFAULT_COLOR], 0xFF07C160) so they
 * follow the user's custom color instead. Driven entirely by the module's theme settings — it is
 * NOT a user-toggleable feature (hence [ApiFeature] + the API category, so it stays out of the
 * feature list). The hooks only install when the user opted their custom color into WeChat
 * ([ThemeSettings.applyToWechat] with 自定义颜色 on); the accent comes from the same seed the injected
 * WeKit UI uses ([SeedResolver.customSeed] → the wallpaper accent or the chosen seed color, run
 * through the selected palette style + color spec). Colors are resolved once per WeChat launch
 * (restart required for a change to apply).
 */
@Feature(name = "莫奈引擎", categories = ["API"], description = "根据模块设置的自定义配色为微信原生组件上色")
object MonetEngine : ApiFeature() {

    private const val TAG = "MonetEngine"

    /** WeChat's hardcoded brand green — the pixels we replace. */
    private const val DEFAULT_COLOR = -16268960 // 0xFF07C160

    private val scheme by lazy {
        try {
            val dark = HostInfo.application.isDarkMode
            SeedResolver.materialScheme(SeedResolver.customSeed(HostInfo.application, dark), dark)
        } catch (e: Exception) {
            WeLogger.w(TAG, "failed to resolve monet scheme, device may not support dynamic colors", e)
            throw e
        }
    }

    /** Accent that replaces the brand green (M3 `primary`). */
    private val primaryColor by lazy {
        try {
            scheme.primary.toArgb()
        } catch (e: Exception) {
            WeLogger.w(TAG, "failed to get primary color from scheme", e)
            DEFAULT_COLOR // fallback to WeChat green so hooks are no-ops
        }
    }

    /** Legible foreground for content sitting on [primaryColor] (M3 `onPrimary`). */
    private val onPrimaryColor by lazy {
        try {
            scheme.onPrimary.toArgb()
        } catch (e: Exception) {
            WeLogger.w(TAG, "failed to get onPrimary color from scheme", e)
            -1 // white fallback
        }
    }

    override fun onEnable() {
        if (!(ThemeSettings.applyToWechat && ThemeSettings.customColor)) {
            WeLogger.i(TAG, "apply-to-wechat off, not recoloring")
            return
        }

        try {
            // Force resolution now so failures are caught early
            val resolvedPrimary = primaryColor
            val resolvedOnPrimary = onPrimaryColor
            WeLogger.i(TAG, "monet colors resolved: primary=#${Integer.toHexString(resolvedPrimary)}, onPrimary=#${Integer.toHexString(resolvedOnPrimary)}")
        } catch (e: Exception) {
            WeLogger.w(TAG, "monet color resolution failed, recoloring disabled", e)
            return
        }

        runCatching {
            "com.tencent.mm.ui.widget.MMSwitchBtn".toClass().constructors.forEach {
                it.hookAfter {
                    thisObject.reflekt()
                        .fields {
                            type = Int::class
                            superclass()
                        }.forEach { field ->
                            if (field.get()!! as Int == DEFAULT_COLOR)
                                field.set(primaryColor)
                        }
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook MMSwitchBtn", it)
        }

        // GradientDrawable/PaintDrawable fills (incl. WeChat's green button shapes) draw through
        // Paint.setColor, so swapping the brand green here recolors those backgrounds to primary.
        runCatching {
            Paint::class.reflekt()
                .firstMethod { name = "setColor" }
                .hookBefore {
                    val color = args[0] as Int
                    if (color != DEFAULT_COLOR) return@hookBefore
                    args[0] = primaryColor
                }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook Paint.setColor", it)
        }

        // ColorDrawable draws via Canvas.drawColor (not Paint.setColor), so it needs its own swap.
        runCatching {
            View::class.reflekt().firstMethod { name = "setBackgroundDrawable" }.hookBefore {
                val drawable = args[0] as? Drawable? ?: return@hookBefore
                if (drawable is ColorDrawable && drawable.color == DEFAULT_COLOR) {
                    drawable.color = primaryColor
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook View.setBackgroundDrawable", it)
        }

        // Green (brand) buttons already get their background recolored to primary by the Paint /
        // ColorDrawable hooks above. Neutral / cancel buttons keep their own colors —
        // we deliberately don't blanket-tint every Button.
        runCatching {
            View::class.reflekt().firstMethod { name = "onFinishInflate" }.hookAfter {
                val button = thisObject as? Button ?: return@hookAfter
                if (button.background?.hasBrandGreen() == true) {
                    button.setTextColor(onPrimaryColor)
                    button.backgroundTintList = ColorStateList.valueOf(primaryColor)
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook View.onFinishInflate", it)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }

        runCatching {
            TextView::class.reflekt().firstMethod { name = "onAttachedToWindow" }.hookAfter {
                val editText = thisObject as? EditText? ?: return@hookAfter
                editText.apply {
                    textCursorDrawable?.apply {
                        setTint(primaryColor)
                        editText.textCursorDrawable = this
                    }

                    // android views are weird
                    val handle = textSelectHandle ?: return@apply
                    handle.mutate()
                    setTextSelectHandle(handle)
                    textSelectHandle!!.setTint(primaryColor)
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook EditText cursor tinting", it)
        }
    }

    /** Whether [this] drawable's fill is WeChat's brand green (checks common state-list wrappers). */
    private fun Drawable.hasBrandGreen(): Boolean = when (this) {
        is ColorDrawable -> color == DEFAULT_COLOR
        is GradientDrawable -> color?.defaultColor == DEFAULT_COLOR
        is StateListDrawable -> current.takeIf { it !== this }?.hasBrandGreen() == true
        else -> false
    }
}
