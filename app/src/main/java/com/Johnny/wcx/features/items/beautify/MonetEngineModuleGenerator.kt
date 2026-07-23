//package com.Johnny.wcx.features.items.beautify
//
//import android.content.Context
//import android.os.Build
//import androidx.compose.foundation.layout.Column
//import androidx.compose.foundation.layout.Row
//import androidx.compose.foundation.layout.Spacer
//import androidx.compose.foundation.layout.height
//import androidx.compose.foundation.layout.size
//import androidx.compose.foundation.layout.width
//import androidx.compose.material3.CircularProgressIndicator
//import androidx.compose.material3.MaterialTheme
//import androidx.compose.material3.Text
//import androidx.compose.runtime.Composable
//import androidx.compose.runtime.getValue
//import androidx.compose.runtime.mutableStateOf
//import androidx.compose.runtime.remember
//import androidx.compose.runtime.setValue
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.unit.dp
//import com.Johnny.wcx.features.core.ClickableFeature
//import com.Johnny.wcx.features.core.Feature
//import com.Johnny.wcx.features.items.beautify.monet.MonetModuleGenerator
//import com.Johnny.wcx.ui.content.AlertDialogContent
//import com.Johnny.wcx.ui.content.Button
//import com.Johnny.wcx.ui.utils.showComposeDialog
//import kotlin.concurrent.thread
//
//@Feature(
//    name = "莫奈引擎 (模块)",
//    categories = ["界面美化"],
//    description = "为微信的部分组件启用动态壁纸取色 (基于 Overlay 模块的实现, 无性能开销) [需 SDK >= 31]\n安装模块后, 请同时启用「莫奈引擎 (模块)」与「莫奈引擎」"
//)
//object MonetEngineModuleGenerator : ClickableFeature() {
//
//    override fun onClick(context: Activity) {
//        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
//            showComposeDialog(context) {
//                AlertDialogContent(
//                    title = { Text("莫奈引擎 (模块)") },
//                    text = { Text("系统 Android 版本过低 (需 SDK >= 31), 无法使用动态壁纸取色。") },
//                    confirmButton = { Button(onDismiss) { Text("关闭") } }
//                )
//            }
//            return
//        }
//
//        showComposeDialog(context, directlyDismissable = false) {
//            var progress by remember {
//                mutableStateOf<MonetModuleGenerator.Progress>(MonetModuleGenerator.Progress.Preparing)
//            }
//            var running by remember { mutableStateOf(true) }
//
//            remember {
//                thread(name = "monet-module-gen") {
//                    val final = MonetModuleGenerator.generate { p ->
//                        // 回到主线程更新 UI。
//                        window.decorView.post { progress = p }
//                    }
//                    window.decorView.post {
//                        progress = final
//                        running = false
//                    }
//                }
//                true
//            }
//
//            AlertDialogContent(
//                title = { Text("莫奈引擎 (模块)") },
//                text = {
//                    when (val p = progress) {
//                        is MonetModuleGenerator.Progress.Done -> DoneContent(p)
//                        is MonetModuleGenerator.Progress.Failed -> Text(
//                            "生成失败:\n${p.error.message ?: p.error.toString()}"
//                        )
//
//                        else -> RunningContent(statusText(p))
//                    }
//                },
//                confirmButton = {
//                    if (!running) Button(onDismiss) { Text("关闭") }
//                }
//            )
//        }
//    }
//
//    private fun statusText(p: MonetModuleGenerator.Progress): String = when (p) {
//        MonetModuleGenerator.Progress.Preparing -> "正在准备模板…"
//        MonetModuleGenerator.Progress.BuildingOverlay -> "正在根据当前微信版本生成资源覆盖…"
//        MonetModuleGenerator.Progress.Signing -> "正在签名…"
//        MonetModuleGenerator.Progress.Packaging -> "正在打包模块…"
//        else -> "处理中…"
//    }
//}
//
//@Composable
//private fun RunningContent(status: String) {
//    Row(verticalAlignment = Alignment.CenterVertically) {
//        CircularProgressIndicator(modifier = Modifier.size(24.dp))
//        Spacer(Modifier.width(16.dp))
//        Text(status)
//    }
//}
//
//@Composable
//private fun DoneContent(p: MonetModuleGenerator.Progress.Done) {
//    Column {
//        Text("已生成模块:")
//        Spacer(Modifier.height(8.dp))
//        Text(
//            p.zip.absolutePath,
//            style = MaterialTheme.typography.bodySmall
//        )
//        Spacer(Modifier.height(12.dp))
//        Text(
//            "覆盖 ${p.result.kept + p.result.added} 项 " +
//                    "(保留 ${p.result.kept}, 新增 ${p.result.added}, 裁剪 ${p.result.pruned})",
//            style = MaterialTheme.typography.bodySmall
//        )
//        Spacer(Modifier.height(12.dp))
//        Text("请在你的 Root 管理器中刷入该模块并重启。若正在使用 KernelSU 或 APatch 及其衍生版, 请禁用「微信」的「App Profile」中的「卸载模块」选项。")
//    }
//}
