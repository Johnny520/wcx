package com.Johnny.wcx.features.items.entertain

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import com.Johnny.wcx.features.api.net.WePacketHelper
import com.Johnny.wcx.features.api.net.models.protobuf.ClearProfileListProto
import com.Johnny.wcx.features.api.net.models.protobuf.ClearProfileOpProto
import com.Johnny.wcx.features.api.net.models.protobuf.ClearProfileReqProto
import com.Johnny.wcx.features.api.net.models.protobuf.OpLogRespProto
import com.Johnny.wcx.features.api.net.models.protobuf.WeProto
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger

@Feature(name = "清空资料信息", categories = ["娱乐"], description = "清空当前用户的地区与性别等资料信息")
object ClearProfileDetails : ClickableFeature() {

    private const val TAG = "ClearProfileDetails"

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("清空资料信息") },
                text = { Text("确定清空吗？清空后你仍然可以重新选择资料信息") },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        // cmd 91 embeds the profile proto directly; all fields default to their
                        // cleared value, so an all-defaults ModProfileProto reproduces the native packet.
                        val reqBytes = WeProto.encodeWithDefaults(
                            ClearProfileReqProto(
                                ClearProfileListProto(operations = listOf(ClearProfileOpProto()))
                            )
                        )

                        WePacketHelper.sendCgiRaw(
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
                                        title = { Text("发送失败") },
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
