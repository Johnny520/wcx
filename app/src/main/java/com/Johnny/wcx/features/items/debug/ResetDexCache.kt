package com.Johnny.wcx.features.items.debug

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import com.Johnny.wcx.dexkit.cache.DexCacheManager
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Feature(name = "重置适配信息", categories = ["调试"], description = "清除全部 DEX 适配信息, 等待下次启动时重新适配")
object ResetDexCache : ClickableFeature() {

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("清除适配信息") },
                text = {
                    Text(
                        "这将删除所有的 DEX 适配信息，宿主重启后需要重新适配。\n" +
                                "确定清除吗？"
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            showToastSuspend("正在清除...")
                            DexCacheManager.clearAllCache()
                            showToastSuspend("清除成功!")
                            withContext(Dispatchers.Main) {
                                onDismiss()
                            }
                        }
                    }) { Text("确定") }
                })
        }
    }

    override val noSwitchWidget = true
}
