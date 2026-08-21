package com.Johnny.wcx.features.items.system

import android.util.DisplayMetrics
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.reflection.bool
import com.Johnny.wcx.utils.reflection.float
import com.Johnny.wcx.utils.reflection.int

@Feature(
    name = "DPI 修改", categories = ["界面美化", "系统与隐私"],
    description = "自定义微信屏幕密度"
)
object CustomDpi : ClickableFeature(), IResolveDex {

    private val methodGetDisplayMetrics by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.MMDensityManager", "screenResolution_target_field")
            }

            modifiers = Modifiers.PUBLIC
            returnType = DisplayMetrics::class.java.name
            paramCount = 0

            addInvoke {
                returnType = "boolean"
            }
        }
    }

    private var customDpi by prefOption("custom_dpi", 360)

    override fun onEnable() {
        methodGetDisplayMetrics.hookAfter {
            val metrics = result as? DisplayMetrics ?: return@hookAfter
            applyCustomDpi(metrics)
        }

        hookTabIconScale()
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var value by remember { mutableStateOf(customDpi.toString()) }

            AlertDialogContent(
                title = { Text("DPI 修改") },
                text = {
                    TextField(
                        value = value,
                        onValueChange = { value = it.filter { ch -> ch.isDigit() } },
                        label = { Text("显示宽度") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        val dpiInput = value.toIntOrNull()
                        if (dpiInput == null || dpiInput <= 0) {
                            showToast("数字格式不正确!")
                            return@Button
                        }
                        customDpi = dpiInput
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun applyCustomDpi(metrics: DisplayMetrics) {
        val dpi = customDpi
        val fontScale = metrics.scaledDensity / metrics.density
        metrics.density = dpi / 160.0f
        metrics.densityDpi = dpi
        metrics.scaledDensity = dpi / 160.0f * fontScale
    }

    private fun hookTabIconScale() {
        val tabIconView = "com.tencent.mm.ui.TabIconView".toClass()
        val method = tabIconView.reflekt().firstMethod {
            parameters(int, int, int, bool)
        }

        method.hookBefore {
            val view = thisObject ?: return@hookBefore
            val field = view.reflekt().firstField {
                type = float
                modifiers { !it.contains(Modifiers.STATIC) }
            }

            field.set(customDpi * 1.1666666f / 400.0f)
        }
    }
}

