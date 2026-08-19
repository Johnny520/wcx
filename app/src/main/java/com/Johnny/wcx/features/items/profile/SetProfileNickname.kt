package com.Johnny.wcx.features.items.profile

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.Johnny.wcx.features.api.net.WePacketHelper
import com.Johnny.wcx.features.api.net.models.protobuf.OpLog
import com.Johnny.wcx.features.api.net.models.protobuf.OpLogRespProto
import com.Johnny.wcx.features.api.net.models.protobuf.SetNicknameProto
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger

@Feature(name = "设置微信昵称", categories = ["个人资料"], description = "通过发包来更灵活的设置微信昵称")
object SetProfileNickname : ClickableFeature() {

    private const val TAG = "SetProfileNickname"

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var nickname by remember { mutableStateOf("") }

            AlertDialogContent(
                title = { Text("设置微信昵称") },
                text = {
                    TextField(
                        label = { Text("新的昵称") },
                        value = nickname, onValueChange = { nickname = it }, singleLine = false
                    )
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        val reqBytes = OpLog.encodeSingle(
                            OpLog.CMD_SET_NICKNAME, SetNicknameProto(nickname = nickname)
                        )

                        WePacketHelper.sendCgi(
                            "/cgi-bin/micromsg-bin/oplog",
                            681, 0, 0,
                            reqBytes = reqBytes
                        ) {
                            onSuccess { bytes ->
                                val resp = bytes?.let { OpLogRespProto.decode(it) }
                                WeLogger.i(TAG, "success: ret=${resp?.ret}")
                                showComposeDialog(context) {
                                    AlertDialogContent(
                                        title = { Text("发送成功") },
                                        text = { Text("服务器返回码: ${resp?.ret ?: "未知"}") },
                                        confirmButton = {
                                            TextButton(onClick = onDismiss) { Text("关闭") }
                                        }
                                    )
                                }
                            }

                            onFailure { type, code, msg ->
                                showComposeDialog(context) {
                                    AlertDialogContent(
                                        title = { Text("发送失败, 响应结果:") },
                                        text = { Text("type: $type, code: $code, msg: $msg") },
                                        confirmButton = {
                                            TextButton(onClick = onDismiss) { Text("关闭") }
                                        }
                                    )
                                }
                            }
                        }
                        onDismiss()
                    }) { Text("确定") }
                })
        }
    }

    override val noSwitchWidget: Boolean
        get() = true
}
