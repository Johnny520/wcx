package com.Johnny.wcx.features.items.debug

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tencent.mars.xlog.Log
import com.Johnny.reflekt.reflekt
import com.Johnny.reflekt.utils.Modifiers
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.getBoolOrFalse
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger

@Feature(name = "重定向微信日志", categories = ["调试"], description = "将微信内部日志打印至模块日志")
object RedirectHostLogs : ClickableFeature() {

    private const val TAG = "RedirectHostLogs"
    private const val KEY_PREFIX = "redirect_"

    override fun onEnable() {
        Log::class.reflekt().apply {
            if (getBoolOrFalse("${KEY_PREFIX}v"))
                firstMethod {
                    name = "v"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    runCatching {
                        val tag = args[0] as String
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.v(TAG, "[V] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}d"))
                firstMethod {
                    name = "d"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    runCatching {
                        val tag = args[0] as String
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.d(TAG, "[D] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}i"))
                firstMethod {
                    name = "i"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    runCatching {
                        val tag = args[0] as String
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.i(TAG, "[I] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}w"))
                firstMethod {
                    name = "w"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    runCatching {
                        val tag = args[0] as String
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.w(TAG, "[W] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}e"))
                firstMethod {
                    name = "e"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    runCatching {
                        val tag = args[0] as String
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.e(TAG, "[E] [$tag] $formatString")
                    }
                }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("重定向微信日志") },
                text = {
                    var v by remember { mutableStateOf(getBoolOrFalse("${KEY_PREFIX}v")) }
                    var d by remember { mutableStateOf(getBoolOrFalse("${KEY_PREFIX}d")) }
                    var i by remember { mutableStateOf(getBoolOrFalse("${KEY_PREFIX}i")) }
                    var w by remember { mutableStateOf(getBoolOrFalse("${KEY_PREFIX}w")) }
                    var e by remember { mutableStateOf(getBoolOrFalse("${KEY_PREFIX}e")) }

                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable {
                                v = !v
                                WePrefs.putBool("${KEY_PREFIX}v", v)
                            },
                            trailingContent = { Switch(v, null) },
                            headlineContent = { Text("Verbose") },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                d = !d
                                WePrefs.putBool("${KEY_PREFIX}d", d)
                            },
                            trailingContent = { Switch(d, null) },
                            headlineContent = { Text("Debug") },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                i = !i
                                WePrefs.putBool("${KEY_PREFIX}i", i)
                            },
                            trailingContent = { Switch(i, null) },
                            headlineContent = { Text("Info") },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                w = !w
                                WePrefs.putBool("${KEY_PREFIX}w", w)
                            },
                            trailingContent = { Switch(w, null) },
                            headlineContent = { Text("Warning") },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                e = !e
                                WePrefs.putBool("${KEY_PREFIX}e", e)
                            },
                            trailingContent = { Switch(e, null) },
                            headlineContent = { Text("Error") },
                        )
                    }
                },
                confirmButton = {
                    Button(onDismiss) { Text("确定") }
                })
        }
    }
}
