package com.Johnny.wcx.features.items.debug

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeUnsafeApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog

@Feature(name = "启动微信内部 URL", categories = ["调试"], description = "跳转微信 weixin:// URL")
object LaunchInternalUrls : ClickableFeature(), IResolveDex {

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var url by remember { mutableStateOf("weixin://") }
            var argsInput by remember { mutableStateOf("") }

            AlertDialogContent(
                title = { Text("启动微信内部 URL") },
                text = {
                    DefaultColumn {
                        TextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("URL") })
                        TextField(
                            value = argsInput,
                            onValueChange = { argsInput = it },
                            label = { Text("参数 (可留空)") })
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        val args = if (argsInput.isBlank()) emptyList() else argsInput.split("\n")
                        methodOpenUrl.method.invoke(
                            // FIXME: getDeclaredConstructor() says no ctor exists?? but Unsafe works????
                            WeUnsafeApi.allocateInstance(methodOpenUrl.method.declaringClass),
                            *arrayOf(context, url, args.toTypedArray())
                        )
                    }) { Text("确定") }
                })
        }
    }

    private val methodOpenUrl by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.app.plugin")
        matcher {
            // 8.0.80："openSpecificUI, context is null" 已成孤儿串（无任何方法引用）。
            // TAG "MicroMsg.MMURIJumpHandler" 仍被 com.tencent.mm.app.plugin.j 的 b/c/d 三个方法引用，
            // 用调用处实际传入的 3 参签名 (Context, String, Object[]) → boolean 收敛到 d。
            usingEqStrings("MicroMsg.MMURIJumpHandler")
            paramTypes("android.content.Context", "java.lang.String", "java.lang.Object[]")
            returnType("boolean")
        }
    }
}
