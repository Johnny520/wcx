package com.Johnny.wcx.features.items.entertain

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import com.Johnny.wcx.features.api.net.WePacketHelper
import com.Johnny.wcx.features.api.net.models.protobuf.ModProfileProto
import com.Johnny.wcx.features.api.net.models.protobuf.OpLog
import com.Johnny.wcx.features.api.net.models.protobuf.OpLogRespProto
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
                text = { Text("确定清空吗? 清空后你仍然可以重新选择资料信息") },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
//                        val payload = """{"1":{"1":1,"2":{"1":1,"2":{"1":91,"2":{"1":128,"2":{"1":""},"3":{"1":""},"4":0,"5":{"1":""},"6":{"1":""},"7":0,"8":0,"9":"","10":0,"11":"","12":"","13":"","14":1,"16":0,"17":0,"19":0,"20":0,"21":0,"22":0,"23":0,"24":"","25":0,"27":"","28":"","29":0,"30":0,"31":0,"33":0,"34":0,"36":0,"38":""}}}}}"""

                        val reqBytes = OpLog.encodeSingle(
                            OpLog.CMD_MOD_PROFILE,
                            ModProfileProto()
                        )

                        WePacketHelper.sendCgi(
//                        WePacketHelper.sendCgi(
                            "/cgi-bin/micromsg-bin/oplog",
                            681, 0, 0,
                            reqBytes = reqBytes
//                            payload
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
