package com.Johnny.wcx.features.items.debug

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.ShowComposeDialogScope
import com.Johnny.wcx.ui.utils.showComposeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Feature(name = "测试 2", categories = ["调试"], description = "执行 Nuke 客户端注册和加密报告请求，并显示完整请求与响应")
object Experiments2 : ClickableFeature() {

    private sealed interface RequestState {
        data object Idle : RequestState
        data object Running : RequestState
        data class Done(val result: String) : RequestState
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) { NukeClientDialog() }
    }

    @Composable
    private fun ShowComposeDialogScope.NukeClientDialog() {
        var state by remember { mutableStateOf<RequestState>(RequestState.Idle) }
        var requestJob by remember { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()

        AlertDialogContent(
            title = { Text("Nuke 客户端调试") },
            text = {
                DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                    when (val current = state) {
                        RequestState.Idle -> Text("将以代码内置的默认账号执行注册和加密报告请求。")
                        RequestState.Running -> {
                            Text("请求中...")
                            Spacer(Modifier.height(8.dp))
                            LinearWavyProgressIndicator()
                        }

                        is RequestState.Done -> Text(
                            text = current.result,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            },
            confirmButton = {
                when (state) {
                    RequestState.Idle -> Button(onClick = {
                        state = RequestState.Running
                        dialog.setCancelable(false)
                        requestJob = scope.launch(Dispatchers.IO) {
                            val rawResult = runCatching { submitNukeReportNative() }
                                .getOrElse { "{\"success\":false,\"error\":${JSONObject.quote(it.message ?: it.javaClass.name)}}" }
                            val result = runCatching { JSONObject(rawResult).toString(2) }.getOrDefault(rawResult)
                            withContext(Dispatchers.Main) {
                                state = RequestState.Done(result)
                                requestJob = null
                                dialog.setCancelable(true)
                            }
                        }
                    }) { Text("开始") }

                    is RequestState.Done -> Button(onDismiss) { Text("关闭") }
                    RequestState.Running -> Unit
                }
            },
            dismissButton = {
                if (state is RequestState.Running) {
                    TextButton(onClick = {
                        requestJob?.cancel()
                        requestJob = null
                        state = RequestState.Idle
                        dialog.setCancelable(true)
                    }) { Text("中断") }
                }
            },
        )
    }

    private external fun submitNukeReportNative(): String

    override val noSwitchWidget: Boolean
        get() = true
}
