package com.Johnny.wcx.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.showToast

@Feature(name = "伪装语音时长", categories = ["聊天"], description = "预设定伪装发送语音显示的时长")
object FakeVoiceDuration : ClickableFeature(), IResolveDex {

    private val methodVoiceRecorderGetLength by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.SceneVoice.Recorder", "Stop file success: ")
            }
            returnType = "long"
        }
    }
    private const val KEY_DURATION = "fake_voice_duration_seconds"

    private const val DEFAULT_DURATION_SEC = 1
    private const val MAX_DURATION_SEC = 60

    override fun onEnable() {
        methodVoiceRecorderGetLength.hookBefore {
            val durationSec = WePrefs.getIntOrDef(KEY_DURATION, DEFAULT_DURATION_SEC)
                .coerceIn(0, MAX_DURATION_SEC)
            result = durationSec * 1000L
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var durationInput by remember {
                mutableStateOf(WePrefs.getIntOrDef(KEY_DURATION, DEFAULT_DURATION_SEC).toString())
            }
            AlertDialogContent(
                title = { Text("伪装语音时长") },
                text = {
                    TextField(
                        value = durationInput,
                        onValueChange = {
                            durationInput = it.filter(Char::isDigit).take(2)
                        },
                        label = { Text("语音时长 (秒，最大${MAX_DURATION_SEC}秒)") }
                    )
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        val durationSec = durationInput.toIntOrNull()
                        if (durationSec == null) {
                            showToast("时长格式不正确!")
                            return@Button
                        }
                        if (durationSec < 0 || durationSec > MAX_DURATION_SEC) {
                            showToast("时长范围: 0-${MAX_DURATION_SEC}秒")
                            return@Button
                        }
                        WePrefs.putInt(KEY_DURATION, durationSec)
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }
}
