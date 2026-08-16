package com.Johnny.wcx.features.items.voip

import android.os.Bundle
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
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog

@Feature(name = "屏蔽铃声", categories = ["聊天", "音视频通话"], description = "屏蔽音视频通话铃声")
object BlockVoipRingtone : ClickableFeature(), IResolveDex {

    private var disableOutCall by prefOption("voip_disable_ringtone_out_call", true)
    private var disableInCall by prefOption("voip_disable_ringtone_in_call", false)

    private val methodPlaySound by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.BaseSceneSetting", "playSound Failed Throwable t = ")
        }
    }

    override fun onEnable() {
        methodPlaySound.hookBefore {
            val params = args[1] as? Bundle ?: return@hookBefore
            val scene = params.getString("scene") ?: return@hookBefore
            if (scene == "start") {
                val isOutCall = params.getBoolean("isOutCall")
                val disOutCall = isOutCall && disableOutCall
                val disInCall = !isOutCall && disableInCall
                if (disOutCall || disInCall) {
                    try {
                        // 仅当原方法返回 void 时才设置 result = false 阻断播放
                        if (method is java.lang.reflect.Method) {
                            val returnType = (method as java.lang.reflect.Method).returnType
                            if (returnType == Void.TYPE) {
                                result = false
                            }
                        }
                    } catch (e: Throwable) {
                        // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
                    }
                }
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var outCall by remember { mutableStateOf(disableOutCall) }
            var inCall by remember { mutableStateOf(disableInCall) }

            AlertDialogContent(
                title = { Text("屏蔽铃声") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable { outCall = !outCall },
                            trailingContent = { Switch(checked = outCall, onCheckedChange = { outCall = it }) },
                            supportingContent = { Text("屏蔽拨出音视频通话时的铃声") },
                            headlineContent = { Text("屏蔽呼出铃声") },
                        )
                        ListItem(
                            modifier = Modifier.clickable { inCall = !inCall },
                            trailingContent = { Switch(checked = inCall, onCheckedChange = { inCall = it }) },
                            supportingContent = { Text("屏蔽收到音视频通话请求时的铃声") },
                            headlineContent = { Text("屏蔽呼入铃声") },
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        disableOutCall = outCall
                        disableInCall = inCall
                        onDismiss()
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }
}
